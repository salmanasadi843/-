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
    val createdAtMillis: Long = System.currentTimeMillis()
)
