package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TeacherFolderDao {
    @Query("SELECT * FROM teacher_folders WHERE ownerUid = :ownerUid ORDER BY name COLLATE NOCASE")
    fun observeFolders(ownerUid: String): Flow<List<TeacherFolderEntity>>

    @Query("SELECT * FROM teacher_folders WHERE id = :id AND ownerUid = :ownerUid")
    suspend fun getOwnedFolder(id: Long, ownerUid: String): TeacherFolderEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: TeacherFolderEntity): Long

    @Update
    suspend fun update(folder: TeacherFolderEntity)

    @Delete
    suspend fun delete(folder: TeacherFolderEntity)

    @Query("SELECT COUNT(*) FROM classes WHERE folderId = :folderId")
    suspend fun countClasses(folderId: Long): Int

    @Query("UPDATE classes SET folderId = :targetFolderId WHERE folderId = :sourceFolderId AND id > 0")
    suspend fun moveClasses(sourceFolderId: Long, targetFolderId: Long?)
}
