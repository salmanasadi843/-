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
    fun backupShapeOfEightyRowsCollapsesThirtySixImportedDuplicatesToFortyFourUniqueSessions() {
        val local = mutableListOf<LectureEntity>()
        val importedDuplicates = mutableListOf<LectureEntity>()
        var id = 1L
        val classUniqueCounts = listOf(20, 2, 16, 6)

        classUniqueCounts.forEachIndexed { classIndex, uniqueCount ->
            repeat(uniqueCount) { sessionIndex ->
                val title = "جلسه نمونه ${classIndex + 1}-${sessionIndex + 1}"
                val transcript = "متن ناشناس جلسه ${classIndex + 1}-${sessionIndex + 1}"
                val date = 1_000L + sessionIndex
                local += lecture(
                    id = id++,
                    title = title,
                    classId = classIndex + 1L,
                    dateMillis = date,
                    transcript = transcript,
                    originalTranscript = "نسخه خام مستقل"
                )
                if (classIndex != 1) {
                    // The supplied backup has 16 + 0 + 14 + 5 = 35 exact
                    // title/date/content pairs; one additional pair differs only
                    // by trailing title punctuation.
                    val duplicateCount = when (classIndex) {
                        0 -> if (sessionIndex < 16) 1 else 0
                        2 -> if (sessionIndex < 14) 1 else 0
                        else -> if (sessionIndex < 5) 1 else 0
                    }
                    if (duplicateCount == 1) {
                        importedDuplicates += lecture(
                            id = -id,
                            title = if (classIndex == 2 && sessionIndex == 0) "$title." else title,
                            classId = classIndex + 1L,
                            dateMillis = date,
                            transcript = transcript,
                            originalTranscript = ""
                        )
                    }
                }
            }
        }

        // Add the 36th pair: the real backup's single punctuation-only title variant.
        importedDuplicates += lecture(
            id = -999L,
            title = "جلسه نمونه 1-17.",
            classId = 1L,
            dateMillis = 1_016L,
            transcript = "متن ناشناس جلسه 1-17",
            originalTranscript = ""
        )

        val result = SharedLectureDeduplicator.reconcile(local, importedDuplicates)

        assertEquals(44, local.size)
        assertEquals(36, importedDuplicates.size)
        assertTrue(result.importedToInsert.isEmpty())
        assertEquals(36, result.skippedDuplicateCount)
        assertEquals(36, result.duplicateImportedIdsToDelete.size)
    }

    @Test
    fun keepsAnImportedSessionWhenItIsMissingFromTheLatestCloudFeed() {
        val oldImported = lecture(id = -501L, transcript = "قدیمی اما یکتا")

        val result = SharedLectureDeduplicator.reconcile(
            existingLocal = emptyList(),
            imported = listOf(oldImported)
        )

        assertEquals(listOf(oldImported), result.importedToInsert)
        assertTrue(result.duplicateImportedIdsToDelete.isEmpty())
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
