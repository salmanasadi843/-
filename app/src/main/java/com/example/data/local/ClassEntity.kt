package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "classes")
data class ClassEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val teacherName: String = "",
    val description: String = "",
    val term: String = "",
    /** تاریخ برگزاری/شروع کلاس؛ مستقل از تاریخ ایجاد رکورد */
    val classDateMillis: Long = System.currentTimeMillis(),
    val isPublished: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis(),
    /** Stable Firebase owner; blank only for legacy rows pending safe reconciliation. */
    val ownerUid: String = "",
    /** Local folder placement; null means the teacher's root folder. */
    val folderId: Long? = null
)
