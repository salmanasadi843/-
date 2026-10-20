package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "classes")
data class ClassEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val teacherName: String = "",
    /** Firebase account that owns this class. */
    @ColumnInfo(defaultValue = "''")
    val ownerUid: String = "",
    val description: String = "",
    val term: String = "",
    /** تاریخ برگزاری/شروع کلاس؛ مستقل از تاریخ ایجاد رکورد */
    val classDateMillis: Long = System.currentTimeMillis(),
    val isPublished: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis()
)
