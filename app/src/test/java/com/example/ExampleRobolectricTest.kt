package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.LectureEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("استادیار", appName)
  }

  @Test
  fun `room database insert and search lecture`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val dao = db.lectureDao()

    val lecture = LectureEntity(
        title = "هوش مصنوعی - شبکه‌های عصبی",
        courseName = "هوش مصنوعی",
        professorName = "دکتر مهدوی",
        transcript = "در این جلسه ساختار پرسپترون را بررسی می‌کنیم",
        tags = "هوش مصنوعی,امتحان"
    )

    val id = dao.insertLecture(lecture)
    val fetched = dao.getLectureById(id)
    assertNotNull(fetched)
    assertEquals("هوش مصنوعی - شبکه‌های عصبی", fetched?.title)

    val searchResults = dao.searchLectures("پرسپترون").first()
    assertEquals(1, searchResults.size)
    assertEquals(id, searchResults[0].id)

    db.close()
  }
}

