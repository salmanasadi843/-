package ir.darsyar.backend

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

data class ForumTopicRecord(
    val id: String, val classId: String, val sessionId: String?, val authorUserId: String,
    val authorName: String, val title: String, val body: String, val isPinned: Boolean,
    val isLocked: Boolean, val replyCount: Int, val createdAtMs: Long, val updatedAtMs: Long
)
data class ForumPostRecord(
    val id: String, val topicId: String, val parentPostId: String?, val authorUserId: String,
    val authorName: String, val body: String, val createdAtMs: Long, val updatedAtMs: Long
)
data class UserNotificationRecord(
    val id: String, val notificationType: String, val message: String, val classId: String?,
    val topicId: String?, val postId: String?, val actorUserId: String?, val isRead: Boolean,
    val createdAtMs: Long
)

class ClassForumRepository(private val dataSource: DataSource) {
    fun listTopics(actor: String, roles: Set<AppRole>, classId: String): List<ForumTopicRecord> {
        val sql = """
            SELECT t.id::text,t.class_id::text,t.session_id::text,t.author_user_id::text,
                   COALESCE(NULLIF(u.display_name,''),'کاربر') author_name,t.title,t.body,
                   t.is_pinned,t.is_locked,
                   (SELECT count(*)::int FROM forum_posts p WHERE p.topic_id=t.id AND p.deleted_at IS NULL) reply_count,
                   (extract(epoch from t.created_at)*1000)::bigint created_ms,
                   (extract(epoch from t.updated_at)*1000)::bigint updated_ms
            FROM forum_topics t
            JOIN teaching_classes c ON c.id=t.class_id
            JOIN app_users u ON u.id=t.author_user_id
            WHERE t.class_id=? AND t.deleted_at IS NULL AND c.deleted_at IS NULL
              AND ((? OR c.owner_user_id=? OR
                (c.is_published AND c.is_hidden=false AND
                  (c.visibility='public' OR (? AND EXISTS(
                    SELECT 1 FROM class_memberships m WHERE m.class_id=c.id
                    AND m.student_user_id=? AND m.status='active'
                  ))))))
            ORDER BY t.is_pinned DESC,t.updated_at DESC
        """.trimIndent()
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { st ->
                st.setObject(1, UUID.fromString(classId))
                st.setBoolean(2, AppRole.SUPER_ADMIN in roles)
                st.setObject(3, UUID.fromString(actor))
                st.setBoolean(4, AppRole.STUDENT in roles)
                st.setObject(5, UUID.fromString(actor))
                st.executeQuery().use { rs -> return buildList { while(rs.next()) add(rs.topic()) } }
            }
        }
    }

    fun createTopic(actor: String, roles: Set<AppRole>, classId: String, sessionId: String?, title: String, body: String): ForumTopicRecord {
        require(title.trim().length in 3..200 && body.trim().isNotEmpty() && body.length <= 20000) { "Invalid forum topic." }
        val user=UUID.fromString(actor); val clazz=UUID.fromString(classId)
        val session=sessionId?.takeIf{it.isNotBlank()}?.let(UUID::fromString)
        dataSource.connection.use { c ->
            c.autoCommit=false
            try {
                val owner=c.requireClassAccess(clazz,user,roles)
                if(session!=null) {
                    c.prepareStatement("SELECT 1 FROM teaching_sessions WHERE id=? AND class_id=? AND deleted_at IS NULL").use {
                        it.setObject(1,session);it.setObject(2,clazz);it.executeQuery().use { rs -> require(rs.next()) { "Session does not belong to this class." } }
                    }
                }
                val result=c.prepareStatement("""
                    INSERT INTO forum_topics(class_id,session_id,author_user_id,title,body)
                    VALUES(?,?,?,?,?)
                    RETURNING id::text,class_id::text,session_id::text,author_user_id::text,
                    'کاربر'::text author_name,
                    title,body,is_pinned,is_locked,0::int reply_count,
                    (extract(epoch from created_at)*1000)::bigint created_ms,
                    (extract(epoch from updated_at)*1000)::bigint updated_ms
                """.trimIndent()).use { st ->
                    st.setObject(1,clazz);st.setObject(2,session);st.setObject(3,user);st.setString(4,title.trim());st.setString(5,body.trim())
                    st.executeQuery().use { rs -> check(rs.next());rs.topic() }
                }
                val recipients=linkedSetOf<UUID>()
                recipients+=owner
                c.prepareStatement("SELECT student_user_id FROM class_memberships WHERE class_id=? AND status='active'").use {
                    it.setObject(1,clazz);it.executeQuery().use { rs -> while(rs.next()) recipients+=rs.getObject(1,UUID::class.java) }
                }
                val notifiedRecipients=recipients-user
                notifyUsers(c,notifiedRecipients,user,clazz,UUID.fromString(result.id),null,"forum_topic","بحث تازه‌ای در تالار کلاس ایجاد شد: ${result.title}")
                notifyMentions(c,body,user,clazz,UUID.fromString(result.id),null,notifiedRecipients)
                c.commit();return result
            } catch(e:Throwable){c.rollback();throw e} finally {c.autoCommit=true}
        }
    }

    fun listPosts(actor: String, roles: Set<AppRole>, topicId: String): List<ForumPostRecord> {
        val topic=UUID.fromString(topicId)
        dataSource.connection.use { c ->
            c.requireTopicAccess(topic,UUID.fromString(actor),roles)
            c.prepareStatement("""
                SELECT p.id::text,p.topic_id::text,p.parent_post_id::text,p.author_user_id::text,
                COALESCE(NULLIF(u.display_name,''),'کاربر') author_name,p.body,
                (extract(epoch from p.created_at)*1000)::bigint created_ms,
                (extract(epoch from p.updated_at)*1000)::bigint updated_ms
                FROM forum_posts p JOIN app_users u ON u.id=p.author_user_id
                WHERE p.topic_id=? AND p.deleted_at IS NULL ORDER BY p.created_at,p.id
            """.trimIndent()).use { st ->
                st.setObject(1,topic);st.executeQuery().use { rs -> return buildList { while(rs.next()) add(rs.post()) } }
            }
        }
    }

    fun createReply(actor: String, roles: Set<AppRole>, topicId: String, parentPostId: String?, body: String): ForumPostRecord {
        require(body.isNotBlank() && body.length<=20000) { "Reply is empty or too long." }
        val user=UUID.fromString(actor);val topic=UUID.fromString(topicId);val parent=parentPostId?.takeIf{it.isNotBlank()}?.let(UUID::fromString)
        dataSource.connection.use { c ->
            c.autoCommit=false
            try {
                val context=c.requireTopicAccess(topic,user,roles,lock=true)
                if(context.locked) throw IllegalStateException("This discussion is locked.")
                if(parent!=null) c.prepareStatement("SELECT 1 FROM forum_posts WHERE id=? AND topic_id=? AND deleted_at IS NULL").use {
                    it.setObject(1,parent);it.setObject(2,topic);it.executeQuery().use { rs -> require(rs.next()) { "Parent reply not found." } }
                }
                val result=c.prepareStatement("""
                    INSERT INTO forum_posts(topic_id,parent_post_id,author_user_id,body)
                    VALUES(?,?,?,?)
                    RETURNING id::text,topic_id::text,parent_post_id::text,author_user_id::text,
                    (SELECT COALESCE(NULLIF(display_name,''),'کاربر') FROM app_users WHERE id=author_user_id) author_name,
                    body,(extract(epoch from created_at)*1000)::bigint created_ms,
                    (extract(epoch from updated_at)*1000)::bigint updated_ms
                """.trimIndent()).use { st ->
                    st.setObject(1,topic);st.setObject(2,parent);st.setObject(3,user);st.setString(4,body.trim())
                    st.executeQuery().use { rs -> check(rs.next());rs.post() }
                }
                c.prepareStatement("UPDATE forum_topics SET updated_at=now() WHERE id=?").use { it.setObject(1,topic);it.executeUpdate() }
                val recipients=linkedSetOf<UUID>()
                recipients+=context.owner
                recipients+=context.author
                c.prepareStatement("SELECT author_user_id FROM forum_posts WHERE topic_id=? AND deleted_at IS NULL").use {
                    it.setObject(1,topic);it.executeQuery().use { rs -> while(rs.next()) recipients+=rs.getObject(1,UUID::class.java) }
                }
                val notifiedRecipients=recipients-user
                notifyUsers(c,notifiedRecipients,user,UUID.fromString(context.classId),topic,UUID.fromString(result.id),"forum_reply","پاسخ تازه‌ای در بحث «${context.title}» ثبت شد.")
                notifyMentions(c,body,user,UUID.fromString(context.classId),topic,UUID.fromString(result.id),notifiedRecipients)
                c.commit();return result
            } catch(e:Throwable){c.rollback();throw e} finally {c.autoCommit=true}
        }
    }


    fun updateTopic(actor:String,roles:Set<AppRole>,topicId:String,title:String,body:String):ForumTopicRecord {
        require(title.trim().length in 3..200 && body.trim().isNotEmpty() && body.length<=20000) { "Invalid forum topic." }
        val user=UUID.fromString(actor);val topic=UUID.fromString(topicId)
        dataSource.connection.use { c ->
            c.autoCommit=false
            try {
                val context=c.requireTopicAccess(topic,user,roles,lock=true)
                if(AppRole.SUPER_ADMIN !in roles && user!=context.owner && user!=context.author)
                    throw SecurityException("Forum topic editing is not allowed.")
                val updated=c.prepareStatement("""
                    UPDATE forum_topics SET title=?,body=?,updated_at=now()
                    WHERE id=? AND deleted_at IS NULL
                    RETURNING id::text,class_id::text,session_id::text,author_user_id::text,
                    (SELECT COALESCE(NULLIF(display_name,''),'کاربر') FROM app_users WHERE id=author_user_id) author_name,
                    title,body,is_pinned,is_locked,
                    (SELECT count(*)::int FROM forum_posts p WHERE p.topic_id=forum_topics.id AND p.deleted_at IS NULL) reply_count,
                    (extract(epoch from created_at)*1000)::bigint created_ms,
                    (extract(epoch from updated_at)*1000)::bigint updated_ms
                """.trimIndent()).use { st ->
                    st.setString(1,title.trim());st.setString(2,body.trim());st.setObject(3,topic)
                    st.executeQuery().use { rs -> check(rs.next());rs.topic() }
                }
                c.commit();return updated
            } catch(e:Throwable){c.rollback();throw e} finally {c.autoCommit=true}
        }
    }

    fun deleteTopic(actor:String,roles:Set<AppRole>,topicId:String):Boolean {
        val user=UUID.fromString(actor);val topic=UUID.fromString(topicId)
        dataSource.connection.use { c ->
            c.autoCommit=false
            try {
                val context=try { c.requireTopicAccess(topic,user,roles,lock=true) }
                    catch(_:SecurityException){ c.rollback();return false }
                if(AppRole.SUPER_ADMIN !in roles && user!=context.owner && user!=context.author)
                    throw SecurityException("Forum topic deletion is not allowed.")
                val changed=c.prepareStatement("UPDATE forum_topics SET deleted_at=now(),updated_at=now() WHERE id=? AND deleted_at IS NULL").use {
                    it.setObject(1,topic);it.executeUpdate()==1
                }
                c.commit();return changed
            } catch(e:Throwable){c.rollback();throw e} finally {c.autoCommit=true}
        }
    }

    fun updatePost(actor:String,roles:Set<AppRole>,postId:String,body:String):ForumPostRecord {
        require(body.trim().isNotEmpty() && body.length<=20000) { "Reply is empty or too long." }
        val user=UUID.fromString(actor);val post=UUID.fromString(postId)
        dataSource.connection.use { c ->
            c.autoCommit=false
            try {
                val topicId=c.prepareStatement("SELECT topic_id FROM forum_posts WHERE id=? AND deleted_at IS NULL").use {
                    it.setObject(1,post);it.executeQuery().use { rs -> if(rs.next()) rs.getObject(1,UUID::class.java) else throw IllegalArgumentException("Reply not found.") }
                }
                val context=c.requireTopicAccess(topicId,user,roles,lock=true)
                val updated=c.prepareStatement("""
                    UPDATE forum_posts p SET body=?,updated_at=now()
                    WHERE p.id=? AND p.topic_id=? AND p.deleted_at IS NULL
                      AND (p.author_user_id=? OR ? OR ?)
                    RETURNING p.id::text,p.topic_id::text,p.parent_post_id::text,p.author_user_id::text,
                    (SELECT COALESCE(NULLIF(display_name,''),'کاربر') FROM app_users WHERE id=p.author_user_id) author_name,
                    p.body,(extract(epoch from p.created_at)*1000)::bigint created_ms,
                    (extract(epoch from p.updated_at)*1000)::bigint updated_ms
                """.trimIndent()).use { st ->
                    st.setString(1,body.trim());st.setObject(2,post);st.setObject(3,topicId)
                    st.setObject(4,user);st.setBoolean(5,user==context.owner);st.setBoolean(6,AppRole.SUPER_ADMIN in roles)
                    st.executeQuery().use { rs -> if(rs.next()) rs.post() else throw SecurityException("Reply editing is not allowed.") }
                }
                c.prepareStatement("UPDATE forum_topics SET updated_at=now() WHERE id=?").use { it.setObject(1,topicId);it.executeUpdate() }
                c.commit();return updated
            } catch(e:Throwable){c.rollback();throw e} finally {c.autoCommit=true}
        }
    }

    fun deletePost(actor:String,roles:Set<AppRole>,postId:String):Boolean {
        val user=UUID.fromString(actor);val post=UUID.fromString(postId)
        dataSource.connection.use { c ->
            c.autoCommit=false
            try {
                val topicId=c.prepareStatement("SELECT topic_id FROM forum_posts WHERE id=? AND deleted_at IS NULL").use {
                    it.setObject(1,post);it.executeQuery().use { rs -> if(rs.next()) rs.getObject(1,UUID::class.java) else {c.rollback();return false} }
                }
                val context=c.requireTopicAccess(topicId,user,roles,lock=true)
                val changed=c.prepareStatement("""
                    UPDATE forum_posts SET deleted_at=now(),updated_at=now()
                    WHERE id=? AND topic_id=? AND deleted_at IS NULL
                      AND (author_user_id=? OR ? OR ?)
                """.trimIndent()).use { st ->
                    st.setObject(1,post);st.setObject(2,topicId);st.setObject(3,user)
                    st.setBoolean(4,user==context.owner);st.setBoolean(5,AppRole.SUPER_ADMIN in roles)
                    st.executeUpdate()==1
                }
                if(!changed) throw SecurityException("Reply deletion is not allowed.")
                c.prepareStatement("UPDATE forum_topics SET updated_at=now() WHERE id=?").use { it.setObject(1,topicId);it.executeUpdate() }
                c.commit();return true
            } catch(e:Throwable){c.rollback();throw e} finally {c.autoCommit=true}
        }
    }

    fun moderateTopic(actor:String,roles:Set<AppRole>,topicId:String,pinned:Boolean,locked:Boolean):Boolean {
        val user=UUID.fromString(actor);val topic=UUID.fromString(topicId)
        dataSource.connection.use { c ->
            c.autoCommit=false
            try {
                val owner=c.prepareStatement("""
                    SELECT c.owner_user_id FROM forum_topics t JOIN teaching_classes c ON c.id=t.class_id
                    WHERE t.id=? AND t.deleted_at IS NULL FOR UPDATE OF t
                """.trimIndent()).use { st -> st.setObject(1,topic);st.executeQuery().use { rs -> if(rs.next()) rs.getObject(1,UUID::class.java) else null } } ?: run {c.rollback();return false}
                if(AppRole.SUPER_ADMIN !in roles && !(AppRole.TEACHER in roles && owner==user)) throw SecurityException("Forum moderation is not allowed.")
                c.prepareStatement("UPDATE forum_topics SET is_pinned=?,is_locked=?,updated_at=now() WHERE id=?").use { st -> st.setBoolean(1,pinned);st.setBoolean(2,locked);st.setObject(3,topic);check(st.executeUpdate()==1) }
                c.commit();return true
            } catch(e:Throwable){c.rollback();throw e} finally {c.autoCommit=true}
        }
    }

    fun listNotifications(actor:String,unreadOnly:Boolean,limit:Int=50):List<UserNotificationRecord> {
        val user=UUID.fromString(actor)
        dataSource.connection.use { c -> c.prepareStatement("""
            SELECT id::text,notification_type,message,class_id::text,topic_id::text,post_id::text,
            actor_user_id::text,is_read,(extract(epoch from created_at)*1000)::bigint created_ms
            FROM user_notifications WHERE recipient_user_id=? AND (?=false OR is_read=false)
            ORDER BY created_at DESC LIMIT ?
        """.trimIndent()).use { st ->
            st.setObject(1,user);st.setBoolean(2,unreadOnly);st.setInt(3,limit.coerceIn(1,100));st.executeQuery().use { rs -> return buildList { while(rs.next()) add(rs.notification()) } }
        } }
    }

    fun markNotificationRead(actor:String,notificationId:String):Boolean {
        dataSource.connection.use { c -> c.prepareStatement("UPDATE user_notifications SET is_read=true WHERE id=? AND recipient_user_id=?").use {
            it.setObject(1,UUID.fromString(notificationId));it.setObject(2,UUID.fromString(actor));return it.executeUpdate()==1
        } }
    }

    private fun notifyUsers(c:Connection,recipients:Set<UUID>,actor:UUID,classId:UUID,topicId:UUID,postId:UUID?,type:String,message:String) {
        recipients.forEach { recipient -> c.prepareStatement("""
            INSERT INTO user_notifications(recipient_user_id,actor_user_id,class_id,topic_id,post_id,notification_type,message)
            VALUES(?,?,?,?,?,?,?)
        """.trimIndent()).use { st ->
            st.setObject(1,recipient);st.setObject(2,actor);st.setObject(3,classId);st.setObject(4,topicId);st.setObject(5,postId);st.setString(6,type);st.setString(7,message);st.executeUpdate()
        } }
    }

    private fun notifyMentions(c:Connection,body:String,actor:UUID,classId:UUID,topic:UUID,post:UUID?,alreadyNotified:Set<UUID> = emptySet()) {
        val ids=Regex("@\\[([0-9a-fA-F-]{36})]").findAll(body).mapNotNull { runCatching { UUID.fromString(it.groupValues[1]) }.getOrNull() }.toSet()-actor-alreadyNotified
        if(ids.isEmpty()) return
        val allowed=mutableSetOf<UUID>()
        c.prepareStatement("""
            SELECT c.owner_user_id FROM teaching_classes c WHERE c.id=?
            UNION SELECT student_user_id FROM class_memberships WHERE class_id=? AND status='active'
        """.trimIndent()).use { st ->
            st.setObject(1,classId);st.setObject(2,classId);st.executeQuery().use { rs -> while(rs.next()) allowed+=rs.getObject(1,UUID::class.java) }
        }
        notifyUsers(c,ids intersect allowed,actor,classId,topic,post,"forum_mention","در یک گفت‌وگوی کلاسی شما را نام برده‌اند.")
    }
}

private data class ForumTopicContext(val owner:UUID,val author:UUID,val locked:Boolean,val classId:String,val title:String)

private fun Connection.requireClassAccess(clazz:UUID,user:UUID,roles:Set<AppRole>):UUID {
    val sql="""
        SELECT c.owner_user_id FROM teaching_classes c WHERE c.id=? AND c.deleted_at IS NULL
        AND ((? OR c.owner_user_id=?) OR (c.is_published AND c.is_hidden=false AND
          (c.visibility='public' OR (? AND EXISTS(SELECT 1 FROM class_memberships m
           WHERE m.class_id=c.id AND m.student_user_id=? AND m.status='active')))))
    """.trimIndent()
    return prepareStatement(sql).use { st ->
        st.setObject(1,clazz);st.setBoolean(2,AppRole.SUPER_ADMIN in roles);st.setObject(3,user)
        st.setBoolean(4,AppRole.STUDENT in roles);st.setObject(5,user)
        st.executeQuery().use { rs -> if(rs.next()) rs.getObject(1,UUID::class.java) else throw SecurityException("Class forum access denied.") }
    }
}

private fun Connection.requireTopicAccess(topic:UUID,user:UUID,roles:Set<AppRole>,lock:Boolean=false):ForumTopicContext {
    val sql="""
        SELECT c.owner_user_id,t.author_user_id,t.is_locked,c.id::text,t.title FROM forum_topics t
        JOIN teaching_classes c ON c.id=t.class_id
        WHERE t.id=? AND t.deleted_at IS NULL AND c.deleted_at IS NULL
        AND ((? OR c.owner_user_id=?) OR (c.is_published AND c.is_hidden=false AND
          (c.visibility='public' OR (? AND EXISTS(SELECT 1 FROM class_memberships m
           WHERE m.class_id=c.id AND m.student_user_id=? AND m.status='active')))))
        ${if(lock) "FOR UPDATE OF t" else ""}
    """.trimIndent()
    return prepareStatement(sql).use { st ->
        st.setObject(1,topic);st.setBoolean(2,AppRole.SUPER_ADMIN in roles);st.setObject(3,user)
        st.setBoolean(4,AppRole.STUDENT in roles);st.setObject(5,user)
        st.executeQuery().use { rs -> if(rs.next()) ForumTopicContext(rs.getObject(1,UUID::class.java),rs.getObject(2,UUID::class.java),rs.getBoolean(3),rs.getString(4),rs.getString(5)) else throw SecurityException("Forum topic access denied.") }
    }
}

private fun java.sql.ResultSet.topic()=ForumTopicRecord(getString("id"),getString("class_id"),getString("session_id"),getString("author_user_id"),getString("author_name"),getString("title"),getString("body"),getBoolean("is_pinned"),getBoolean("is_locked"),getInt("reply_count"),getLong("created_ms"),getLong("updated_ms"))
private fun java.sql.ResultSet.post()=ForumPostRecord(getString("id"),getString("topic_id"),getString("parent_post_id"),getString("author_user_id"),getString("author_name"),getString("body"),getLong("created_ms"),getLong("updated_ms"))
private fun java.sql.ResultSet.notification()=UserNotificationRecord(getString("id"),getString("notification_type"),getString("message"),getString("class_id"),getString("topic_id"),getString("post_id"),getString("actor_user_id"),getBoolean("is_read"),getLong("created_ms"))
