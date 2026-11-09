package com.example.data.repository

/**
 * UTF-8-safe chunking for Firestore text fields.
 *
 * Splitting by Unicode code point prevents a surrogate pair from being
 * separated across two documents. Joining validates the complete manifest
 * before any local database replacement is attempted.
 */
internal object BackupTextChunker {
    fun splitUtf8(value: String, maxBytes: Int): List<String> {
        require(maxBytes > 0) { "maxBytes must be positive" }
        if (value.isEmpty()) return listOf("")

        val chunks = mutableListOf<String>()
        var start = 0
        var index = 0
        var bytes = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val pieceBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            require(pieceBytes <= maxBytes) { "A Unicode code point exceeds the chunk byte limit" }

            if (bytes > 0 && bytes + pieceBytes > maxBytes) {
                chunks += value.substring(start, index)
                start = index
                bytes = 0
            }
            bytes += pieceBytes
            index += charCount
        }
        chunks += value.substring(start)
        return chunks
    }

    fun joinUtf8(parts: Map<Int, String>, expectedCount: Int): String {
        require(expectedCount > 0) { "Expected chunk count must be positive" }
        require(parts.size == expectedCount && (0 until expectedCount).all { it in parts }) {
            "Text chunks are missing, duplicated, or out of sequence"
        }
        return buildString {
            for (index in 0 until expectedCount) append(parts.getValue(index))
        }
    }
}
