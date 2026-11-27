package ir.darsyar.backend

import java.util.UUID
import javax.sql.DataSource

data class ClassAnnouncementRecord(
    val id:String,val classId:String,val authorUserId:String,val authorName:String,
    val title:String,val body:String,val isPinned:Boolean,val publishedAtMs:Long
)

class ClassAnnouncementRepository(private val dataSource:DataSource) {
    fun list(actor:String,roles:Set<AppRole>,classId:String):List<ClassAnnouncementRecord> {
        val user=UUID.fromString(actor);val clazz=UUID.fromString(classId)
        val sql="""
            SELECT a.id::text,a.class_id::text,a.author_user_id::text,
              COALESCE(NULLIF(u.display_name,''),'استاد') author_name,
              a.title,a.body,a.is_pinned,(extract(epoch from a.published_at)*1000)::bigint published_ms
            FROM class_announcements a
            JOIN teaching_classes c ON c.id=a.class_id
            JOIN app_users u ON u.id=a.author_user_id
            WHERE a.class_id=? AND a.deleted_at IS NULL AND c.deleted_at IS NULL
              AND ((? OR c.owner_user_id=?) OR
                (c.is_published AND c.is_hidden=false AND
                  (c.visibility='public' OR (? AND EXISTS(
                    SELECT 1 FROM class_memberships m WHERE m.class_id=c.id
                    AND m.student_user_id=? AND m.status='active'
                  )))))
            ORDER BY a.is_pinned DESC,a.published_at DESC
        """.trimIndent()
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { st ->
                st.setObject(1,clazz);st.setBoolean(2,AppRole.SUPER_ADMIN in roles);st.setObject(3,user)
                st.setBoolean(4,AppRole.STUDENT in roles);st.setObject(5,user)
                st.executeQuery().use { rs -> return buildList { while(rs.next()) add(rs.announcement()) } }
            }
        }
    }

    fun delete(actor:String,roles:Set<AppRole>,classId:String,announcementId:String):Boolean {
        val user=UUID.fromString(actor);val clazz=UUID.fromString(classId);val announcement=UUID.fromString(announcementId)
        dataSource.connection.use { c ->
            c.autoCommit=false
            try {
                val owner=c.prepareStatement("SELECT owner_user_id FROM teaching_classes WHERE id=? AND deleted_at IS NULL FOR UPDATE").use { st ->
                    st.setObject(1,clazz);st.executeQuery().use { rs -> if(rs.next()) rs.getObject(1,UUID::class.java) else null }
                } ?: throw IllegalArgumentException("Class not found.")
                if(AppRole.SUPER_ADMIN !in roles && !(AppRole.TEACHER in roles && owner==user))
                    throw SecurityException("Only the class owner or super admin can delete announcements.")
                val deleted=c.prepareStatement("""
                    UPDATE class_announcements SET deleted_at=now()
                    WHERE id=? AND class_id=? AND deleted_at IS NULL
                """.trimIndent()).use { st ->
                    st.setObject(1,announcement);st.setObject(2,clazz);st.executeUpdate()>0
                }
                c.commit();return deleted
            } catch(e:Throwable){c.rollback();throw e} finally {c.autoCommit=true}
        }
    }

    fun create(actor:String,roles:Set<AppRole>,classId:String,title:String,body:String):ClassAnnouncementRecord {
        require(title.trim().length in 3..200 && body.isNotBlank() && body.length<=20000) { "Invalid class announcement." }
        val user=UUID.fromString(actor);val clazz=UUID.fromString(classId)
        dataSource.connection.use { c ->
            c.autoCommit=false
            try {
                val owner=c.prepareStatement("SELECT owner_user_id FROM teaching_classes WHERE id=? AND deleted_at IS NULL FOR UPDATE").use { st ->
                    st.setObject(1,clazz);st.executeQuery().use { rs -> if(rs.next()) rs.getObject(1,UUID::class.java) else null }
                } ?: throw IllegalArgumentException("Class not found.")
                if(AppRole.SUPER_ADMIN !in roles && !(AppRole.TEACHER in roles && owner==user))
                    throw SecurityException("Only the class owner or super admin can publish announcements.")
                val created=c.prepareStatement("""
                    INSERT INTO class_announcements(class_id,author_user_id,title,body)
                    VALUES(?,?,?,?)
                    RETURNING id::text,class_id::text,author_user_id::text,'استاد'::text author_name,
                      title,body,is_pinned,(extract(epoch from published_at)*1000)::bigint published_ms
                """.trimIndent()).use { st ->
                    st.setObject(1,clazz);st.setObject(2,user);st.setString(3,title.trim());st.setString(4,body.trim())
                    st.executeQuery().use { rs -> check(rs.next());rs.announcement() }
                }
                val recipients=mutableSetOf<UUID>()
                c.prepareStatement("SELECT student_user_id FROM class_memberships WHERE class_id=? AND status='active'").use { st ->
                    st.setObject(1,clazz);st.executeQuery().use { rs -> while(rs.next()) recipients+=rs.getObject(1,UUID::class.java) }
                }
                recipients.remove(user)
                recipients.forEach { recipient: UUID ->
                    c.prepareStatement("""
                        INSERT INTO user_notifications(recipient_user_id,actor_user_id,class_id,notification_type,message)
                        VALUES(?,?,?,'class_announcement',?)
                    """.trimIndent()).use { st ->
                        st.setObject(1,recipient);st.setObject(2,user);st.setObject(3,clazz)
                        st.setString(4,"اعلان تازه‌ای از استاد در کلاس منتشر شد: ${created.title}")
                        st.executeUpdate()
                    }
                }
                c.commit();return created
            } catch(e:Throwable){c.rollback();throw e} finally {c.autoCommit=true}
        }
    }
}

private fun java.sql.ResultSet.announcement()=ClassAnnouncementRecord(
    getString("id"),getString("class_id"),getString("author_user_id"),getString("author_name"),
    getString("title"),getString("body"),getBoolean("is_pinned"),getLong("published_ms")
)
