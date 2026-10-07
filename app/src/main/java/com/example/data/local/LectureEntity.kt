package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lectures")
data class LectureEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val courseName: String,
    val professorName: String,
    val classId: Long? = null,
    val courseId: Long? = null,
    val dateMillis: Long = System.currentTimeMillis(),
    val audioFilePath: String? = null,
    val audioUrl: String? = null,
    val audioDurationMs: Long = 0L,
    val transcript: String = "",
    val aiSummary: String? = null,
    val aiKeyPoints: String? = null,
    val aiQuizJson: String? = null,
    val tags: String = "", // Comma-separated tags e.g. "فصل اول,امتحان,هوش"
    val isFavorite: Boolean = false,
    val lastEditedMillis: Long = System.currentTimeMillis()
)
