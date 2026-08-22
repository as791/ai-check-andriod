package com.aicheck.app.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnalysisDao {
    @Insert
    suspend fun insert(entity: AnalysisEntity): Long

    @Query("SELECT * FROM analyses ORDER BY timestampMillis DESC")
    fun observeAll(): Flow<List<AnalysisEntity>>

    @Query("SELECT * FROM analyses ORDER BY timestampMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AnalysisEntity>>

    @Query("SELECT * FROM analyses WHERE id = :id")
    fun observeById(id: Long): Flow<AnalysisEntity?>

    @Query("SELECT * FROM analyses WHERE id = :id")
    suspend fun getById(id: Long): AnalysisEntity?

    @Query("DELETE FROM analyses WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM analyses")
    suspend fun clearAll()
}
