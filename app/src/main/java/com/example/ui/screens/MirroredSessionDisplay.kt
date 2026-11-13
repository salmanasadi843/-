package com.example.ui.screens

import com.example.data.local.LectureEntity

/**
 * Returns one visible item for each mirrored local/shared pair without collapsing
 * separate sessions that happen to share a title and date.
 *
 * Positive IDs are local Room records; negative IDs are shared-feed records.
 * This function is display-only and never mutates or deletes Room entities.
 */
internal fun deduplicateMirroredSessions(lectures: List<LectureEntity>): List<LectureEntity> {
    fun normalize(value: String): String = value.trim().lowercase()
        .replace('ي', 'ی')
        .replace('ك', 'ک')
        .replace(Regex("\\s+"), " ")

    fun key(lecture: LectureEntity): String = listOf(
        normalize(lecture.courseName),
        normalize(lecture.professorName),
        normalize(lecture.title),
        lecture.dateMillis.toString()
    ).joinToString("|")

    fun richness(lecture: LectureEntity): Int =
        lecture.transcript.length +
            lecture.originalTranscript.length +
            (lecture.aiSummary?.length ?: 0) +
            (lecture.aiKeyPoints?.length ?: 0) +
            (lecture.aiQuizJson?.length ?: 0) +
            lecture.tags.length +
            (if (!lecture.audioUrl.isNullOrBlank()) 1000 else 0) +
            (if (!lecture.audioFilePath.isNullOrBlank()) 1000 else 0)

    return lectures.groupBy(::key).values.flatMap { sameContent ->
        val local = sameContent.filter { it.id > 0L }
        val shared = sameContent.filter { it.id < 0L }
        val unclassified = sameContent.filter { it.id == 0L }
        val pairedCount = minOf(local.size, shared.size)

        buildList {
            for (index in 0 until pairedCount) {
                add(maxOf(local[index], shared[index], compareBy(::richness)))
            }
            addAll(local.drop(pairedCount))
            addAll(shared.drop(pairedCount))
            addAll(unclassified)
        }
    }
}
