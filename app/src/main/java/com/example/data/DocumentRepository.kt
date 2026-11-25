package com.example.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.example.data.local.RecentDocumentDao
import com.example.data.local.RecentDocumentEntity
import com.example.data.parser.DocBinaryParser
import com.example.data.parser.DocxParser
import com.example.data.parser.OfficeDecryptor
import com.example.data.parser.Ole2CompoundReader
import com.example.data.parser.PdfDecryptor
import com.example.data.parser.XlsxParser
import com.example.domain.model.DocumentFormat
import com.example.domain.model.ParsedDocument
import com.example.domain.model.RecentDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class DocumentRepository(
  private val context: Context,
  private val dao: RecentDocumentDao,
) {
  private val cacheRoot = File(context.filesDir, "recent_docs").apply { mkdirs() }

  val recentDocuments: Flow<List<RecentDocument>> =
    dao.observeAllRecent().map { entities ->
      entities.mapNotNull { entity ->
        val fmt = DocumentFormat.entries.firstOrNull { it.name == entity.formatName } ?: return@mapNotNull null
        if (!File(entity.cachedFilePath).exists()) return@mapNotNull null
        RecentDocument(
          id = entity.id,
          cachedFilePath = entity.cachedFilePath,
          originalUri = entity.originalUri,
          fileName = entity.fileName,
          format = fmt,
          fileSizeBytes = entity.fileSizeBytes,
          lastOpenedAt = entity.lastOpenedAt,
          lastPageOrSheetIndex = entity.lastPageOrSheetIndex,
        )
      }
    }

  suspend fun importAndOpenUri(uri: Uri): Pair<RecentDocument, ParsedDocument> =
    withContext(Dispatchers.IO) {
      val resolver = context.contentResolver
      runCatching {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      val meta = queryUriMetadata(resolver, uri)
      val rawName = meta.first ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Dokumen"
      val mimeType = resolver.getType(uri)

      // Copy content stream to a temp file first so we can inspect magic bytes/ZIP structure
      val tempFile = File(cacheRoot, "tmp_${UUID.randomUUID()}")
      resolver.openInputStream(uri)?.use { input ->
        tempFile.outputStream().use { output -> input.copyTo(output) }
      } ?: throw IllegalStateException("Tidak dapat membaca file dari penyimpanan.")

      if (tempFile.length() == 0L) {
        tempFile.delete()
        throw IllegalStateException("File kosong atau tidak dapat diakses.")
      }

      val detectedFormat =
        detectFormat(
          file = tempFile,
          fileName = rawName,
          mimeType = mimeType,
        )
          ?: run {
            tempFile.delete()
            throw UnsupportedFormatException()
          }

      val normalizedName = ensureExtension(rawName, detectedFormat.extension)
      val fileSize = tempFile.length()

      val existing = dao.findByNameAndSize(normalizedName, fileSize)
      val targetFile =
        if (existing != null && File(existing.cachedFilePath).exists()) {
          tempFile.delete()
          File(existing.cachedFilePath)
        } else {
          val safeName = normalizedName.replace(Regex("[^A-Za-z0-9._-]"), "_")
          val dest = File(cacheRoot, "${System.currentTimeMillis()}_$safeName")
          if (!tempFile.renameTo(dest)) {
            tempFile.copyTo(dest, overwrite = true)
            tempFile.delete()
          }
          dest
        }

      val entity =
        RecentDocumentEntity(
          id = existing?.id ?: 0L,
          cachedFilePath = targetFile.absolutePath,
          originalUri = uri.toString(),
          fileName = normalizedName,
          formatName = detectedFormat.name,
          fileSizeBytes = fileSize,
          lastOpenedAt = System.currentTimeMillis(),
          lastPageOrSheetIndex = existing?.lastPageOrSheetIndex ?: 0,
        )
      val id = dao.upsert(entity)
      val savedId = if (entity.id > 0L) entity.id else id

      val recent =
        RecentDocument(
          id = savedId,
          cachedFilePath = targetFile.absolutePath,
          originalUri = uri.toString(),
          fileName = normalizedName,
          format = detectedFormat,
          fileSizeBytes = fileSize,
          lastOpenedAt = entity.lastOpenedAt,
          lastPageOrSheetIndex = entity.lastPageOrSheetIndex,
        )

      val parsed =
        parseLocalFile(
          docId = savedId,
          file = targetFile,
          displayName = normalizedName,
          format = detectedFormat,
          initialPageOrSheet = entity.lastPageOrSheetIndex,
          password = null,
        )
      recent to parsed
    }

  suspend fun openCachedDocument(recent: RecentDocument): ParsedDocument =
    withContext(Dispatchers.IO) {
      val file = File(recent.cachedFilePath)
      if (!file.exists()) {
        dao.deleteById(recent.id)
        throw IllegalStateException("File cache tidak lagi tersedia. Silakan buka ulang file asli.")
      }
      dao.touchLastOpened(recent.id, System.currentTimeMillis())
      parseLocalFile(
        docId = recent.id,
        file = file,
        displayName = recent.fileName,
        format = recent.format,
        initialPageOrSheet = recent.lastPageOrSheetIndex,
        password = null,
      )
    }

  suspend fun unlockDocumentWithPassword(
    docId: Long,
    file: File,
    fileName: String,
    format: DocumentFormat,
    initialPageOrSheet: Int,
    password: String,
  ): ParsedDocument =
    withContext(Dispatchers.IO) {
      parseLocalFile(
        docId = docId,
        file = file,
        displayName = fileName,
        format = format,
        initialPageOrSheet = initialPageOrSheet,
        password = password,
      )
    }

  suspend fun updateProgress(docId: Long, pageOrSheetIndex: Int) =
    withContext(Dispatchers.IO) {
      if (docId > 0L) {
        dao.updateProgress(docId, pageOrSheetIndex)
      }
    }

  suspend fun deleteRecent(doc: RecentDocument) =
    withContext(Dispatchers.IO) {
      dao.deleteById(doc.id)
      runCatching { File(doc.cachedFilePath).delete() }
    }

  suspend fun clearAllRecent() =
    withContext(Dispatchers.IO) {
      dao.clearAll()
      cacheRoot.listFiles()?.forEach { runCatching { it.delete() } }
    }

  private fun parseLocalFile(
    docId: Long,
    file: File,
    displayName: String,
    format: DocumentFormat,
    initialPageOrSheet: Int,
    password: String?,
  ): ParsedDocument {
    return when (format) {
      DocumentFormat.PDF ->
        parsePdf(
          docId = docId,
          file = file,
          displayName = displayName,
          initialPageOrSheet = initialPageOrSheet,
          password = password,
        )

      DocumentFormat.DOCX -> {
        if (OfficeDecryptor.isEncryptedOoxml(file)) {
          if (password == null) {
            throw PasswordRequiredException(
              docId = docId,
              file = file,
              fileName = displayName,
              format = format,
              initialPageOrSheet = initialPageOrSheet,
              isWrongPassword = false,
            )
          }
          val unlockedZip =
            OfficeDecryptor.decryptToTempZip(file, password, context.cacheDir)
              ?: throw PasswordRequiredException(
                docId = docId,
                file = file,
                fileName = displayName,
                format = format,
                initialPageOrSheet = initialPageOrSheet,
                isWrongPassword = true,
              )
          try {
            DocxParser.parse(unlockedZip, displayName).copy(file = file)
          } finally {
            runCatching { unlockedZip.delete() }
          }
        } else {
          DocxParser.parse(file, displayName)
        }
      }

      DocumentFormat.XLSX -> {
        if (OfficeDecryptor.isEncryptedOoxml(file)) {
          if (password == null) {
            throw PasswordRequiredException(
              docId = docId,
              file = file,
              fileName = displayName,
              format = format,
              initialPageOrSheet = initialPageOrSheet,
              isWrongPassword = false,
            )
          }
          val unlockedZip =
            OfficeDecryptor.decryptToTempZip(file, password, context.cacheDir)
              ?: throw PasswordRequiredException(
                docId = docId,
                file = file,
                fileName = displayName,
                format = format,
                initialPageOrSheet = initialPageOrSheet,
                isWrongPassword = true,
              )
          try {
            XlsxParser.parse(unlockedZip, displayName).copy(file = file)
          } finally {
            runCatching { unlockedZip.delete() }
          }
        } else {
          XlsxParser.parse(file, displayName)
        }
      }

      DocumentFormat.DOC -> DocBinaryParser.parse(file, displayName)
    }
  }

  private fun parsePdf(
    docId: Long,
    file: File,
    displayName: String,
    initialPageOrSheet: Int,
    password: String?,
  ): ParsedDocument.Pdf {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
      try {
        val count =
          ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer =
              if (password != null) {
                PdfRenderer(pfd, LoadParams.Builder().setPassword(password).build())
              } else {
                PdfRenderer(pfd)
              }
            renderer.use { it.pageCount }
          }
        return ParsedDocument.Pdf(
          file = file,
          fileName = displayName,
          pageCount = count,
          password = password,
        )
      } catch (_: SecurityException) {
        throw PasswordRequiredException(
          docId = docId,
          file = file,
          fileName = displayName,
          format = DocumentFormat.PDF,
          initialPageOrSheet = initialPageOrSheet,
          isWrongPassword = password != null,
        )
      }
    }

    // Android API 24-34: decrypt encrypted PDF via PdfDecryptor before passing to PdfRenderer
    if (PdfDecryptor.isEncryptedPdf(file)) {
      if (password == null) {
        throw PasswordRequiredException(
          docId = docId,
          file = file,
          fileName = displayName,
          format = DocumentFormat.PDF,
          initialPageOrSheet = initialPageOrSheet,
          isWrongPassword = false,
        )
      }
      val unlockedTemp =
        PdfDecryptor.decryptToTempFile(file, password, context.cacheDir)
          ?: throw PasswordRequiredException(
            docId = docId,
            file = file,
            fileName = displayName,
            format = DocumentFormat.PDF,
            initialPageOrSheet = initialPageOrSheet,
            isWrongPassword = true,
          )
      val count =
        try {
          ParcelFileDescriptor.open(unlockedTemp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
          }
        } finally {
          runCatching { unlockedTemp.delete() }
        }
      return ParsedDocument.Pdf(
        file = file,
        fileName = displayName,
        pageCount = count,
        password = password,
      )
    }

    try {
      val count =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
          PdfRenderer(pfd).use { it.pageCount }
        }
      return ParsedDocument.Pdf(
        file = file,
        fileName = displayName,
        pageCount = count,
        password = password,
      )
    } catch (_: SecurityException) {
      throw PasswordRequiredException(
        docId = docId,
        file = file,
        fileName = displayName,
        format = DocumentFormat.PDF,
        initialPageOrSheet = initialPageOrSheet,
        isWrongPassword = password != null,
      )
    }
  }

  private fun detectFormat(
    file: File,
    fileName: String,
    mimeType: String?,
  ): DocumentFormat? {
    val ext = fileName.substringAfterLast('.', "").takeIf { fileName.contains('.') }
    DocumentFormat.fromExtension(ext)?.let { return it }
    DocumentFormat.fromMimeType(mimeType)?.let { return it }

    // Inspect magic bytes / ZIP / OLE2 entries as reliable fallback
    val header = ByteArray(16)
    val read = file.inputStream().use { it.read(header) }
    if (read >= 4) {
      // %PDF
      if (
        header[0] == '%'.code.toByte() &&
          header[1] == 'P'.code.toByte() &&
          header[2] == 'D'.code.toByte() &&
          header[3] == 'F'.code.toByte()
      ) {
        return DocumentFormat.PDF
      }
      // PK.. OpenXML ZIP
      if (header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
        runCatching {
          ZipFile(file).use { zip ->
            if (zip.getEntry("word/document.xml") != null) return DocumentFormat.DOCX
            if (zip.getEntry("xl/workbook.xml") != null) return DocumentFormat.XLSX
          }
        }
      }
      // OLE2 D0 CF 11 E0: distinguish legacy .doc vs encrypted OOXML (.docx / .xlsx)
      if (
        read >= 8 &&
          header[0] == 0xD0.toByte() &&
          header[1] == 0xCF.toByte() &&
          header[2] == 0x11.toByte() &&
          header[3] == 0xE0.toByte()
      ) {
        val streams = runCatching { Ole2CompoundReader.readStreams(file.readBytes()) }.getOrNull()
        if (streams != null && streams.containsKey("EncryptionInfo") && streams.containsKey("EncryptedPackage")) {
          // Default to DOCX when neither extension nor MIME is present
          return DocumentFormat.DOCX
        }
        return DocumentFormat.DOC
      }
    }
    return null
  }

  private fun ensureExtension(name: String, ext: String): String {
    val clean = name.trim().ifEmpty { "Dokumen" }
    return if (clean.endsWith(".$ext", ignoreCase = true)) clean else "$clean.$ext"
  }

  private fun queryUriMetadata(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
    var name: String? = null
    var size: Long? = null
    var cursor: Cursor? = null
    try {
      cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
      if (cursor != null && cursor.moveToFirst()) {
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (nameIdx >= 0) name = cursor.getString(nameIdx)
        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
      }
    } catch (_: Exception) {
      // Fallback to URI segment
    } finally {
      cursor?.close()
    }
    return name to size
  }
}

class UnsupportedFormatException : IllegalArgumentException("Format file tidak didukung.")

class PasswordRequiredException(
  val docId: Long,
  val file: File,
  val fileName: String,
  val format: DocumentFormat,
  val initialPageOrSheet: Int,
  val isWrongPassword: Boolean = false,
) : Exception("Dokumen dilindungi kata sandi.")
