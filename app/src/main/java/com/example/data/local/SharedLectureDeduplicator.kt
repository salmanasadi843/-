package com.example.data.local

/**
 * Reconciles shared copies against locally owned sessions without treating an
 * absent cloud row as a deletion. Positive IDs are owned rows; negative IDs are
 * imported rows.
 */
internal object SharedLectureDeduplicator {
    data class Result(
        val importedToInsert: List<LectureEntity>,
        val localRecordsToUpdate: List<LectureEntity>,
        val duplicateImportedIdsToDelete: List<Long>,
        val skippedDuplicateCount: Int
    )

    fun mergeSameIdCopies(items: List<LectureEntity>): List<LectureEntity> =
        items.groupBy { it.id }.values.map { copies ->
            copies.drop(1).fold(copies.first()) { current, next ->
                mergeWithoutChangingLocalIdentity(current, next)
            }
        }

    fun reconcile(
        existingLocal: List<LectureEntity>,
        imported: List<LectureEntity>
    ): Result {
        val localByKey = existingLocal
            .filter { it.id > 0L && it.classId != null && it.transcript.isNotBlank() }
            .groupBy(::duplicateKey)
        val localUpdates = linkedMapOf<Long, LectureEntity>()
        val retainedImports = linkedMapOf<String, LectureEntity>()
        val duplicateIds = linkedSetOf<Long>()
        var skipped = 0

        imported.forEach { incoming ->
            val key = duplicateKey(incoming)
            val ownedMatch = localByKey[key]?.firstOrNull()
            if (ownedMatch != null) {
                skipped++
                if (incoming.id < 0L) duplicateIds += incoming.id
                val current = localUpdates[ownedMatch.id] ?: ownedMatch
                localUpdates[ownedMatch.id] = mergeWithoutChangingLocalIdentity(current, incoming)
                return@forEach
            }

            val priorImport = retainedImports[key]
            if (priorImport == null) {
                retainedImports[key] = incoming
            } else {
                skipped++
                val merged = mergeWithoutChangingLocalIdentity(priorImport, incoming)
                retainedImports[key] = merged
                if (incoming.id < 0L && incoming.id != priorImport.id) {
                    duplicateIds += incoming.id
                }
            }
        }

        return Result(
            importedToInsert = retainedImports.values.toList(),
            localRecordsToUpdate = localUpdates.values.filter { updated ->
                existingLocal.firstOrNull { original -> original.id == updated.id } != updated
            },
            duplicateImportedIdsToDelete = duplicateIds.toList(),
            skippedDuplicateCount = skipped
        )
    }

    private fun duplicateKey(item: LectureEntity): String {
        val base = listOf(
            item.classId?.toString().orEmpty(),
            normalize(item.courseName),
            normalizeTitle(item.title),
            item.dateMillis.toString()
        ).joinToString("|")
        val transcript = normalize(item.transcript)
        // Without a full transcript there is not enough evidence to collapse rows.
        return if (transcript.isBlank()) "$base|blank-id:${item.id}" else "$base|$transcript"
    }

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
        audioUrl = local.audioUrl?.takeIf { it.isNotBlank() } ?: incoming.audioUrl,
        audioFilePath = local.audioFilePath?.takeIf { it.isNotBlank() } ?: incoming.audioFilePath,
        audioDurationMs = maxOf(local.audioDurationMs, incoming.audioDurationMs),
        aiSummary = richer(local.aiSummary, incoming.aiSummary),
        aiKeyPoints = richer(local.aiKeyPoints, incoming.aiKeyPoints),
        aiQuizJson = richer(local.aiQuizJson, incoming.aiQuizJson),
        tags = richer(local.tags, incoming.tags).orEmpty(),
        isFavorite = local.isFavorite || incoming.isFavorite,
        lastEditedMillis = maxOf(local.lastEditedMillis, incoming.lastEditedMillis)
    )
}
