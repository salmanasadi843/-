package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "teacher_folders",
    indices = [Index(value = ["ownerUid", "parentFolderId", "name"], unique = true)]
)
data class TeacherFolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ownerUid: String,
    val name: String,
    val parentFolderId: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
)
