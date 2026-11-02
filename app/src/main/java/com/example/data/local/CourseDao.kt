package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses WHERE classId = :classId ORDER BY createdAtMillis DESC")
    fun getByClass(classId: Long): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses ORDER BY createdAtMillis DESC")
    fun getAll(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getById(id: Long): CourseEntity?

    @Insert
    suspend fun insert(item: CourseEntity): Long

    @Update
    suspend fun update(item: CourseEntity)

    @Delete
    suspend fun delete(item: CourseEntity)
    @Query("SELECT * FROM courses ORDER BY createdAtMillis ASC")
    suspend fun getAllForBackup(): List<CourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllForRestore(items: List<CourseEntity>)

    @Query("DELETE FROM courses")
    suspend fun clearForRestore()

    @Query("DELETE FROM courses WHERE id < 0")
    suspend fun clearCloudSharedData()

}
