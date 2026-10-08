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
    val createdAtMillis: Long = System.currentTimeMillis()
)
