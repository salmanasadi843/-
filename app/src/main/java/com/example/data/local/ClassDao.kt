package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ClassDao {
    @Query("SELECT * FROM classes ORDER BY createdAtMillis DESC")
    fun getAllClasses(): Flow<List<ClassEntity>>

    @Query("SELECT * FROM classes WHERE id = :id")
    suspend fun getById(id: Long): ClassEntity?

    @Insert
    suspend fun insert(item: ClassEntity): Long

    @Update
    suspend fun update(item: ClassEntity)

    @Delete
    suspend fun delete(item: ClassEntity)
    @Query("SELECT * FROM classes ORDER BY createdAtMillis ASC")
    suspend fun getAllForBackup(): List<ClassEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllForRestore(items: List<ClassEntity>)

    @Query("DELETE FROM classes")
    suspend fun clearForRestore()

}
