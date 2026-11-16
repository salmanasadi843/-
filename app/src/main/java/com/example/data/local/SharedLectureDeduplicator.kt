package com.example.data.local

/**
 * Prevents a published/imported copy from being stored beside an already-owned
 * copy of the same session. Positive IDs are local records; imported records use
 * negative IDs. A match is deliberately strict: class, course, date, title and
 * normalized full transcript must all match.
 */
internal object SharedLectureDeduplicator {
    data class Result(
        val importedToInsert: List<LectureEntity>,
        val localRecordsToUpdate: List<LectureEntity>,
        val skippedDuplicateCount: Int
    )

    fun reconcile(
        existingLocal: List<LectureEntity>,
        imported: List<LectureEntity>
    ): Result {
        val localByKey = existingLocal
            .filter { it.id > 0L && it.classId != null && it.transcript.isNotBlank() }
            .groupBy(::duplicateKey)
        val updates = linkedMapOf<Long, LectureEntity>()
        val insert = mutableListOf<LectureEntity>()
        var skipped = 0

        imported.forEach { incoming ->
            val key = duplicateKey(incoming)
            val match = localByKey[key]?.firstOrNull()
            if (match == null) {
                insert += incoming
            } else {
                skipped++
                val current = updates[match.id] ?: match
                updates[match.id] = mergeWithoutChangingLocalIdentity(current, incoming)
            }
        }

        return Result(
            importedToInsert = insert,
            localRecordsToUpdate = updates.values.filter { it != existingLocal.firstOrNull { row -> row.id == it.id } },
            skippedDuplicateCount = skipped
        )
    }

    private fun duplicateKey(item: LectureEntity): String = listOf(
        item.classId?.toString().orEmpty(),
        normalize(item.courseName),
        normalizeTitle(item.title),
        item.dateMillis.toString(),
        normalize(item.transcript)
    ).joinToString("|")

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace('ي', 'ی')
        .replace('ك', 'ک')
        .replace('ى', 'ی')
        .replace(Regex("[\\u200c\\u200d]"), "")
        .replace(Regex("\\s+"), " ")

    private fun normalizeTitle(value: String): String = normalize(value)
        .trimEnd('.', '。', '．', '!', '؟', '?', '،', ',')

    private fun richer(first: String?, second: String?): String? {
        val a = first.orEmpty()
        val b = second.orEmpty()
        return when {
            a.isBlank() -> second
            b.isBlank() -> first
            b.length > a.length -> second
            else -> first
        }
    }

    private fun mergeWithoutChangingLocalIdentity(
        local: LectureEntity,
        incoming: LectureEntity
    ): LectureEntity = local.copy(
        transcript = richer(local.transcript, incoming.transcript).orEmpty(),
        originalTranscript = richer(local.originalTranscript, incoming.originalTranscript).orEmpty(),
        audioUrl = richer(local.audioUrl, incoming.audioUrl),
        audioFilePath = richer(local.audioFilePath, incoming.audioFilePath),
        audioDurationMs = maxOf(local.audioDurationMs, incoming.audioDurationMs),
        aiSummary = richer(local.aiSummary, incoming.aiSummary),
        aiKeyPoints = richer(local.aiKeyPoints, incoming.aiKeyPoints),
        aiQuizJson = richer(local.aiQuizJson, incoming.aiQuizJson),
        tags = richer(local.tags, incoming.tags).orEmpty(),
        isFavorite = local.isFavorite || incoming.isFavorite,
        lastEditedMillis = maxOf(local.lastEditedMillis, incoming.lastEditedMillis)
    )
}
