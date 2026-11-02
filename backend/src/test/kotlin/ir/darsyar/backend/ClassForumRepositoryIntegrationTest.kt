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
            val owner=UUID.randomUUID();val student=UUID.randomUUID();val stranger=UUID.randomUUID()
            ds.connection.use { c ->
                c.prepareStatement("INSERT INTO app_users(id,display_name) VALUES (?, 'استاد'), (?, 'دانشجو'), (?, 'بیگانه')").use {
                    it.setObject(1,owner);it.setObject(2,student);it.setObject(3,stranger);it.executeUpdate()
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
            assertEquals(announcement.id,announcements.list(student.toString(),setOf(AppRole.STUDENT),clazz.id).single().id)
            assertEquals(0,announcements.list(stranger.toString(),setOf(AppRole.STUDENT),clazz.id).size)
            val repo=ClassForumRepository(ds)
            val topic=repo.createTopic(student.toString(),setOf(AppRole.STUDENT),clazz.id,null,
                "پرسش درباره درس","لطفاً این نکته را توضیح دهید.")
            assertEquals(1,repo.listTopics(owner.toString(),setOf(AppRole.TEACHER),clazz.id).size)
            assertEquals(1,repo.listTopics(student.toString(),setOf(AppRole.STUDENT),clazz.id).size)
            assertFailsWith<SecurityException> {
                repo.listPosts(stranger.toString(),setOf(AppRole.STUDENT),topic.id)
            }
            val reply=repo.createReply(owner.toString(),setOf(AppRole.TEACHER),topic.id,null,
                "با توجه به مقدمه جلسه، پاسخ چنین است.")
            assertEquals(1,repo.listPosts(student.toString(),setOf(AppRole.STUDENT),topic.id).size)
            assertTrue(reply.topicId==topic.id)
            val studentInbox=repo.listNotifications(student.toString(),true)
            assertTrue(studentInbox.any { it.notificationType=="forum_reply" && it.topicId==topic.id })
            assertTrue(studentInbox.any { it.notificationType=="class_announcement" })
            assertFalse(repo.listNotifications(owner.toString(),true).any { it.actorUserId==owner.toString() })
            assertTrue(repo.moderateTopic(owner.toString(),setOf(AppRole.TEACHER),topic.id,true,true))
            assertFailsWith<IllegalStateException> {
                repo.createReply(student.toString(),setOf(AppRole.STUDENT),topic.id,null,"پاسخ پس از بسته شدن")
            }
            studentInbox.forEach { assertTrue(repo.markNotificationRead(student.toString(),it.id)) }
            assertEquals(0,repo.listNotifications(student.toString(),true).size)
        }
    }
}
