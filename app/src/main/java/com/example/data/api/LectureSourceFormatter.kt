package com.example.data.api

/**
 * Adds deterministic source anchors to transcript chunks before AI summarization.
 *
 * Anchors refer to chunks created from the supplied transcript; they are not claimed
 * to be original paragraph numbers or external source identifiers.
 */
internal object LectureSourceFormatter {
    private const val MAX_CHUNK_LENGTH = 1100
    private const val MIN_SENTENCE_SEARCH_LENGTH = 500

    fun withSourceAnchors(transcript: String): String {
        if (transcript.isBlank()) return transcript

        val paragraphs = transcript
            .replace("\r\n", "\n")
            .split(Regex("\\n[\\t ]*\\n+"))
            .map(String::trim)
            .filter(String::isNotBlank)

        val chunks = paragraphs.flatMap(::splitLongParagraph)
        if (chunks.isEmpty()) return transcript

        return buildString {
            appendLine("راهنمای ارجاع: متن زیر به قطعه‌های منبع شماره‌دار تقسیم شده است. شناسه هر قطعه فقط به همان متن زیر اشاره دارد؛ این شناسه‌ها شماره پاراگراف اصلی یا منبع بیرونی نیستند.")
            appendLine("برای هر ادعای محوری، در صورت امکان شناسه قطعه پشتیبان را به شکل [P001] درج کن. شناسه‌ای بیرون از فهرست زیر نساز.")
            appendLine()
            chunks.forEachIndexed { index, chunk ->
                append("[[قطعه منبع P")
                append((index + 1).toString().padStart(3, '0'))
                appendLine("]]")
                appendLine(chunk)
                appendLine()
            }
        }.trim()
    }

    private fun splitLongParagraph(paragraph: String): List<String> {
        if (paragraph.length <= MAX_CHUNK_LENGTH) return listOf(paragraph)

        val chunks = mutableListOf<String>()
        var start = 0

        while (start < paragraph.length) {
            var end = minOf(start + MAX_CHUNK_LENGTH, paragraph.length)
            if (end < paragraph.length) {
                val searchFloor = minOf(
                    end,
                    start + MIN_SENTENCE_SEARCH_LENGTH
                )
                val sentenceEnd = (searchFloor until end)
                    .lastOrNull { index ->
                        paragraph[index] in charArrayOf('.', '؟', '?', '!', '؛', '۔')
                    }
                    ?.plus(1)

                if (sentenceEnd != null && sentenceEnd > start) {
                    end = sentenceEnd
                } else {
                    val whitespace = (searchFloor until end)
                        .lastOrNull { index -> paragraph[index].isWhitespace() }
                    if (whitespace != null && whitespace > start) end = whitespace
                }
            }

            val chunk = paragraph.substring(start, end).trim()
            if (chunk.isNotBlank()) chunks += chunk
            start = end
        }

        return chunks
    }
}
