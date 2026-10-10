package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LectureEntity::class, ClassEntity::class, CourseEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lectureDao(): LectureDao
    abstract fun classDao(): ClassDao
    abstract fun courseDao(): CourseDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lectures ADD COLUMN audioUrl TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lectures ADD COLUMN classId INTEGER")
                db.execSQL("ALTER TABLE lectures ADD COLUMN courseId INTEGER")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS classes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        teacherName TEXT NOT NULL,
                        description TEXT NOT NULL,
                        term TEXT NOT NULL,
                        createdAtMillis INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS courses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        classId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        teacherName TEXT NOT NULL,
                        description TEXT NOT NULL,
                        createdAtMillis INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_courses_classId ON courses(classId)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE classes ADD COLUMN classDateMillis INTEGER NOT NULL DEFAULT 0"
                )
                // برای کلاس‌های قدیمی، تاریخ کلاس را فعلاً برابر تاریخ ایجاد رکورد قرار می‌دهیم
                // تا داده قبلی از بین نرود و تاریخ خالی نمایش داده نشود.
                db.execSQL(
                    "UPDATE classes SET classDateMillis = createdAtMillis WHERE classDateMillis = 0"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lectures ADD COLUMN rawTranscript TEXT")
                db.execSQL("ALTER TABLE lectures ADD COLUMN correctedTranscript TEXT")
                db.execSQL("UPDATE lectures SET rawTranscript = transcript WHERE transcript IS NOT NULL")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ostadyar_lecture_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
