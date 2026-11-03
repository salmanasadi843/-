package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClassForumRepositoryIntegrationTest {
    @Test
    fun `class discussions enforce access and create reply notifications`() {
        val jdbcUrl=System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(),"PostgreSQL integration database is not configured")
        val username=System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password=System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema="forum_repo_${UUID.randomUUID().toString().replace("-","")}"
        Flyway.configure().dataSource(jdbcUrl,username,password).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").load().migrate()
        val config=HikariConfig().apply {
            this.jdbcUrl=jdbcUrl;this.username=username;this.password=password
            maximumPoolSize=3;connectionInitSql="SET search_path TO $schema"
        }
        HikariDataSource(config).use { ds ->
            val admin=UUID.randomUUID();val owner=UUID.randomUUID();val otherTeacher=UUID.randomUUID()
            val student=UUID.randomUUID();val stranger=UUID.randomUUID()
            ds.connection.use { c ->
                c.prepareStatement("INSERT INTO app_users(id,display_name) VALUES (?, 'مدیر'), (?, 'استاد'), (?, 'استاد دیگر'), (?, 'دانشجو'), (?, 'بیگانه')").use {
                    it.setObject(1,admin);it.setObject(2,owner);it.setObject(3,otherTeacher)
                    it.setObject(4,student);it.setObject(5,stranger);it.executeUpdate()
                }
            }
            ds.connection.use { c ->
                c.prepareStatement("INSERT INTO app_roles(user_id,role_name) VALUES (?,'teacher'), (?,'teacher')").use {
                    it.setObject(1,owner);it.setObject(2,otherTeacher);it.executeUpdate()
                }
            }
            val clazz=TeachingClassRepository(ds).create(owner.toString(),
                CreateTeachingClass(owner.toString(),"تالار آزمایشی","","","private",true))
            ds.connection.use { c ->
                c.prepareStatement("INSERT INTO class_memberships(class_id,student_user_id,status,joined_at) VALUES (?,?,'active',now())").use {
                    it.setObject(1,UUID.fromString(clazz.id));it.setObject(2,student);it.executeUpdate()
                }
            }
            val announcements=ClassAnnouncementRepository(ds)
            val announcement=announcements.create(owner.toString(),setOf(AppRole.TEACHER),clazz.id,
                "تغییر برنامه کلاس","جلسه آینده نیم ساعت دیرتر برگزار می‌شود.")
            assertFailsWith<SecurityException> {
                announcements.create(stranger.toString(),setOf(AppRole.STUDENT),clazz.id,
                    "اعلان غیرمجاز","کاربر خارج از کلاس نباید بتواند اعلان منتشر کند.")
            }
            assertEquals(announcement.id,announcements.list(student.toString(),setOf(AppRole.STUDENT),clazz.id).single().id)
            assertEquals(0,announcements.list(stranger.toString(),setOf(AppRole.STUDENT),clazz.id).size)
            val repo=ClassForumRepository(ds)
            val topic=repo.createTopic(student.toString(),setOf(AppRole.STUDENT),clazz.id,null,
                "پرسش درباره درس","لطفاً این نکته را توضیح دهید.")
            assertEquals(1,repo.listTopics(owner.toString(),setOf(AppRole.TEACHER),clazz.id).size)
            assertEquals(1,repo.listTopics(student.toString(),setOf(AppRole.STUDENT),clazz.id).size)
            // Mentions are delivered only to users who belong to this class.
            val studentReply=repo.createReply(student.toString(),setOf(AppRole.STUDENT),topic.id,null,
                "سلام @["+owner+"] و @["+stranger+"]")
            val ownerInbox=repo.listNotifications(owner.toString(),true)
            assertTrue(ownerInbox.any { it.notificationType=="forum_reply" && it.postId==studentReply.id })
            assertFalse(ownerInbox.any { it.notificationType=="forum_mention" && it.postId==studentReply.id },
                "A mention must not create a second notification for a user already notified about the same reply.")
            assertEquals(1,ownerInbox.count { it.postId==studentReply.id },
                "The class owner should receive exactly one notification for this reply.")
            assertFalse(repo.listNotifications(stranger.toString(),true).any { it.notificationType=="forum_mention" })
            assertFalse(repo.markNotificationRead(stranger.toString(),ownerInbox.first().id))
            assertFailsWith<SecurityException> {
                repo.listPosts(stranger.toString(),setOf(AppRole.STUDENT),topic.id)
            }
            val reply=repo.createReply(owner.toString(),setOf(AppRole.TEACHER),topic.id,null,
                "با توجه به مقدمه جلسه، پاسخ چنین است.")
            assertEquals(2,repo.listPosts(student.toString(),setOf(AppRole.STUDENT),topic.id).size)
            assertTrue(reply.topicId==topic.id)
            val studentInbox=repo.listNotifications(student.toString(),true)
            assertTrue(studentInbox.any { it.notificationType=="forum_reply" && it.topicId==topic.id })
            assertTrue(studentInbox.any { it.notificationType=="class_announcement" && it.classId==clazz.id })
            assertFalse(studentInbox.any { it.actorUserId==student.toString() },
                "A user must never receive their own notification.")
            assertFalse(repo.listNotifications(owner.toString(),true).any { it.actorUserId==owner.toString() })
            assertFailsWith<SecurityException> {
                announcements.delete(otherTeacher.toString(),setOf(AppRole.TEACHER),clazz.id,announcement.id)
            }
            assertTrue(announcements.delete(admin.toString(),setOf(AppRole.SUPER_ADMIN),clazz.id,announcement.id))
            assertEquals(0,announcements.list(student.toString(),setOf(AppRole.STUDENT),clazz.id).size)
            assertFalse(announcements.delete(admin.toString(),setOf(AppRole.SUPER_ADMIN),clazz.id,announcement.id))
            assertFailsWith<SecurityException> {
                repo.moderateTopic(otherTeacher.toString(),setOf(AppRole.TEACHER),topic.id,true,false)
            }
            assertTrue(repo.moderateTopic(admin.toString(),setOf(AppRole.SUPER_ADMIN),topic.id,true,false))
            assertTrue(repo.moderateTopic(owner.toString(),setOf(AppRole.TEACHER),topic.id,true,true))
            assertFailsWith<IllegalStateException> {
                repo.createReply(student.toString(),setOf(AppRole.STUDENT),topic.id,null,"پاسخ پس از بسته شدن")
            }
            studentInbox.forEach { assertTrue(repo.markNotificationRead(student.toString(),it.id)) }
            assertEquals(0,repo.listNotifications(student.toString(),true).size)

            // Authors may edit their own topic and replies; the owning teacher may moderate content.
            val editedTopic=repo.updateTopic(student.toString(),setOf(AppRole.STUDENT),topic.id,
                "پرسش ویرایش‌شده","متن اصلاح‌شده پرسش")
            assertEquals("پرسش ویرایش‌شده",editedTopic.title)
            val editedReply=repo.updatePost(owner.toString(),setOf(AppRole.TEACHER),reply.id,
                "پاسخ ویرایش‌شده توسط استاد")
            assertEquals("پاسخ ویرایش‌شده توسط استاد",editedReply.body)
            assertFailsWith<SecurityException> {
                repo.updatePost(stranger.toString(),setOf(AppRole.STUDENT),reply.id,"تغییر غیرمجاز")
            }
            assertTrue(repo.deletePost(owner.toString(),setOf(AppRole.TEACHER),reply.id))
            assertEquals(0,repo.listPosts(student.toString(),setOf(AppRole.STUDENT),topic.id).size)
            assertTrue(repo.deleteTopic(student.toString(),setOf(AppRole.STUDENT),topic.id))
            assertEquals(0,repo.listTopics(owner.toString(),setOf(AppRole.TEACHER),clazz.id).size)

        }
    }
}
