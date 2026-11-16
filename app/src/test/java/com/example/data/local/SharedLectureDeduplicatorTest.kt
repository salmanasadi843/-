package com.example.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedLectureDeduplicatorTest {
    @Test
    fun removesImportedCopyWhenOwnedSessionHasSameFullContent() {
        val local = lecture(
            id = 11L,
            originalTranscript = "متن خام اصلی",
            audioUrl = "https://example.test/audio.mp3"
        )
        val imported = lecture(id = -11L, originalTranscript = "")

        val result = SharedLectureDeduplicator.reconcile(listOf(local), listOf(imported))

        assertTrue(result.importedToInsert.isEmpty())
        assertEquals(1, result.skippedDuplicateCount)
        assertTrue(result.localRecordsToUpdate.isEmpty())
    }

    @Test
    fun matchesTitleWithTrailingPunctuationAndPreservesLocalId() {
        val local = lecture(id = 57L, title = "جایگاه عرف در معنا و تطبیق مفهوم بر مصداق.")
        val imported = lecture(id = -57L, title = "جایگاه عرف در معنا و تطبیق مفهوم بر مصداق")

        val result = SharedLectureDeduplicator.reconcile(listOf(local), listOf(imported))

        assertTrue(result.importedToInsert.isEmpty())
        assertEquals(1, result.skippedDuplicateCount)
    }

    @Test
    fun keepsSessionsWithDifferentClassDateOrTranscript() {
        val local = lecture(id = 1L)
        val differentClass = lecture(id = -2L, classId = 9L)
        val differentDate = lecture(id = -3L, dateMillis = 200L)
        val differentText = lecture(id = -4L, transcript = "متن متفاوت")

        val result = SharedLectureDeduplicator.reconcile(
            listOf(local),
            listOf(differentClass, differentDate, differentText)
        )

        assertEquals(3, result.importedToInsert.size)
        assertEquals(0, result.skippedDuplicateCount)
    }

    @Test
    fun duplicateEnrichmentUpdatesExistingRecordWithoutChangingItsId() {
        val local = lecture(id = 11L, originalTranscript = "")
        val imported = lecture(
            id = -11L,
            originalTranscript = "نسخه خام کامل‌تر",
            audioUrl = "https://example.test/audio.mp3"
        )

        val result = SharedLectureDeduplicator.reconcile(listOf(local), listOf(imported))

        assertTrue(result.importedToInsert.isEmpty())
        assertEquals(11L, result.localRecordsToUpdate.single().id)
        assertEquals("نسخه خام کامل‌تر", result.localRecordsToUpdate.single().originalTranscript)
        assertEquals("https://example.test/audio.mp3", result.localRecordsToUpdate.single().audioUrl)
    }

    private fun lecture(
        id: Long,
        title: String = "جلسه نمونه",
        classId: Long = 1L,
        dateMillis: Long = 100L,
        transcript: String = "متن کامل جلسه",
        originalTranscript: String = "متن خام",
        audioUrl: String? = null
    ) = LectureEntity(
        id = id,
        title = title,
        courseName = "فقه",
        professorName = "استاد",
        classId = classId,
        dateMillis = dateMillis,
        transcript = transcript,
        originalTranscript = originalTranscript,
        audioUrl = audioUrl
    )
}
