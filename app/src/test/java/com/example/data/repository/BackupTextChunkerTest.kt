package com.example.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupTextChunkerTest {
    @Test
    fun splitRespectsUtf8ByteLimitAndPreservesPersianText() {
        val source = "درس‌یار فارسی " + "آزمون".repeat(30) + " پایان"
        val chunks = BackupTextChunker.splitUtf8(source, 24)

        assertEquals(source, BackupTextChunker.joinUtf8(chunks.mapIndexed { i, s -> i to s }.toMap(), chunks.size))
        chunks.forEach { chunk -> assert(chunk.toByteArray(Charsets.UTF_8).size <= 24) }
    }

    @Test
    fun splitNeverBreaksEmojiSurrogatePairs() {
        val source = "الف😀ب🚀ج".repeat(12)
        val chunks = BackupTextChunker.splitUtf8(source, 8)
        assertEquals(source, chunks.joinToString(""))
        chunks.forEach { chunk ->
            assertEquals(chunk, String(chunk.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
    }

    @Test
    fun joinRejectsMissingOrOutOfSequenceChunks() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupTextChunker.joinUtf8(mapOf(0 to "الف", 2 to "ج"), 3)
        }
    }

    @Test
    fun emptyTextHasOneRestorableChunk() {
        val chunks = BackupTextChunker.splitUtf8("", 16)
        assertEquals(listOf(""), chunks)
        assertEquals("", BackupTextChunker.joinUtf8(mapOf(0 to ""), 1))
    }
}
