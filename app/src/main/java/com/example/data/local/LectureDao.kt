package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LectureDao {
    @Query("SELECT * FROM lectures ORDER BY dateMillis DESC")
    fun getAllLectures(): Flow<List<LectureEntity>>

    @Query("SELECT * FROM lectures WHERE id = :id")
    suspend fun getLectureById(id: Long): LectureEntity?

    @Query("SELECT * FROM lectures WHERE id = :id")
    fun observeLectureById(id: Long): Flow<LectureEntity?>

    @Query("SELECT * FROM lectures WHERE courseId = :courseId ORDER BY dateMillis DESC")
    fun getLecturesByCourse(courseId: Long): Flow<List<LectureEntity>>

    @Query("""
        SELECT * FROM lectures 
        WHERE title LIKE '%' || :query || '%' 
           OR courseName LIKE '%' || :query || '%'
           OR professorName LIKE '%' || :query || '%'
           OR tags LIKE '%' || :query || '%'
           OR transcript LIKE '%' || :query || '%'
           OR aiSummary LIKE '%' || :query || '%'
        ORDER BY dateMillis DESC
    """)
    fun searchLectures(query: String): Flow<List<LectureEntity>>

    @Query("SELECT DISTINCT courseName FROM lectures WHERE courseName != '' ORDER BY courseName ASC")
    fun getAllCourseNames(): Flow<List<String>>

    @Query("SELECT DISTINCT professorName FROM lectures WHERE professorName != '' ORDER BY professorName ASC")
    fun getAllProfessorNames(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLecture(lecture: LectureEntity): Long

    @Update
    suspend fun updateLecture(lecture: LectureEntity)

    @Delete
    suspend fun deleteLecture(lecture: LectureEntity)

    @Query("DELETE FROM lectures WHERE id = :id")
    suspend fun deleteLectureById(id: Long)

    @Query("SELECT COUNT(*) FROM lectures")
    suspend fun getLectureCount(): Int
}
