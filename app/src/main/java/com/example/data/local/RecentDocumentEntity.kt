package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_documents")
data class RecentDocumentEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val cachedFilePath: String,
  val originalUri: String,
  val fileName: String,
  val formatName: String,
  val fileSizeBytes: Long,
  val lastOpenedAt: Long,
  val lastPageOrSheetIndex: Int = 0,
)
