package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentDocumentDao {
  @Query("SELECT * FROM recent_documents ORDER BY lastOpenedAt DESC")
  fun observeAllRecent(): Flow<List<RecentDocumentEntity>>

  @Query(
    "SELECT * FROM recent_documents WHERE fileName = :fileName AND fileSizeBytes = :fileSizeBytes LIMIT 1"
  )
  suspend fun findByNameAndSize(fileName: String, fileSizeBytes: Long): RecentDocumentEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: RecentDocumentEntity): Long

  @Query("UPDATE recent_documents SET lastPageOrSheetIndex = :index WHERE id = :id")
  suspend fun updateProgress(id: Long, index: Int)

  @Query("UPDATE recent_documents SET lastOpenedAt = :timestamp WHERE id = :id")
  suspend fun touchLastOpened(id: Long, timestamp: Long)

  @Query("DELETE FROM recent_documents WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM recent_documents")
  suspend fun clearAll()
}
