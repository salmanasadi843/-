package com.example.ui.screens

import com.example.data.local.LectureEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirroredSessionDisplayTest {
    private fun lecture(
        id: Long,
        title: String = "جلسه اول",
        courseName: String = "خارج فقه",
        professorName: String = "استاد",
        dateMillis: Long = 1_000L,
        transcript: String = ""
    ) = LectureEntity(
        id = id,
        title = title,
        courseName = courseName,
        professorName = professorName,
        dateMillis = dateMillis,
        transcript = transcript
    )

    @Test
    fun collapsesOnlyOnePositiveNegativeMirrorAndKeepsRicherCopy() {
        val local = lecture(id = 10L, transcript = "متن کوتاه")
        val shared = lecture(id = -10L, transcript = "متن کامل‌تر و طولانی‌تر")

        val result = deduplicateMirroredSessions(listOf(local, shared))

        assertEquals(1, result.size)
        assertEquals(-10L, result.single().id)
    }

    @Test
    fun preservesTwoLocalSessionsWithSameTitleAndDate() {
        val result = deduplicateMirroredSessions(
            listOf(lecture(id = 10L), lecture(id = 11L))
        )

        assertEquals(2, result.size)
    }

    @Test
    fun pairsCopiesOneToOneAndPreservesUnmatchedRecords() {
        val result = deduplicateMirroredSessions(
            listOf(
                lecture(id = 10L),
                lecture(id = 11L),
                lecture(id = -10L)
            )
        )

        assertEquals(2, result.size)
        assertTrue(result.any { it.id == 11L })
    }

    @Test
    fun doesNotPairSessionsWithDifferentDates() {
        val result = deduplicateMirroredSessions(
            listOf(lecture(id = 10L), lecture(id = -10L, dateMillis = 2_000L))
        )

        assertEquals(2, result.size)
    }
}
