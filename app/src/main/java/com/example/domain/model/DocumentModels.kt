package com.example.domain.model

import android.graphics.Bitmap
import androidx.compose.ui.text.style.TextAlign
import java.io.File

enum class DocumentFormat(
  val extension: String,
  val label: String,
  val primaryMimeType: String,
  val mimeTypes: List<String>,
) {
  PDF(
    extension = "pdf",
    label = "PDF",
    primaryMimeType = "application/pdf",
    mimeTypes = listOf("application/pdf", "application/x-pdf", "application/acrobat"),
  ),
  DOC(
    extension = "doc",
    label = "DOC",
    primaryMimeType = "application/msword",
    mimeTypes = listOf("application/msword", "application/vnd.ms-word"),
  ),
  DOCX(
    extension = "docx",
    label = "DOCX",
    primaryMimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    mimeTypes = listOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
  ),
  XLSX(
    extension = "xlsx",
    label = "XLSX",
    primaryMimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    mimeTypes = listOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
  );

  companion object {
    val allSupportedMimeTypes: Array<String> =
      entries.flatMap { it.mimeTypes }.distinct().toTypedArray()

    fun fromExtension(ext: String?): DocumentFormat? {
      val clean = ext?.trim()?.lowercase()?.removePrefix(".") ?: return null
      return entries.firstOrNull { it.extension == clean }
    }

    fun fromMimeType(mime: String?): DocumentFormat? {
      val clean = mime?.trim()?.lowercase() ?: return null
      return entries.firstOrNull { format ->
        format.mimeTypes.any { it.equals(clean, ignoreCase = true) }
      }
    }
  }
}

data class RecentDocument(
  val id: Long,
  val cachedFilePath: String,
  val originalUri: String,
  val fileName: String,
  val format: DocumentFormat,
  val fileSizeBytes: Long,
  val lastOpenedAt: Long,
  val lastPageOrSheetIndex: Int,
)

data class StyledSpan(
  val text: String,
  val bold: Boolean = false,
  val italic: Boolean = false,
  val underline: Boolean = false,
  val strikethrough: Boolean = false,
  val colorRgb: Int? = null,
  val fontSizeSp: Float? = null,
)

sealed interface DocBlock {
  data class Heading(
    val level: Int,
    val spans: List<StyledSpan>,
    val alignment: TextAlign = TextAlign.Start,
  ) : DocBlock

  data class Paragraph(
    val spans: List<StyledSpan>,
    val alignment: TextAlign = TextAlign.Start,
    val isListItem: Boolean = false,
    val listMarker: String? = null,
  ) : DocBlock

  data class Table(
    val rows: List<List<String>>,
  ) : DocBlock

  data class ImageBlock(
    val bitmap: Bitmap,
    val aspectRatio: Float,
  ) : DocBlock

  data object PageBreak : DocBlock
}

data class SpreadsheetCell(
  val rowIndex: Int,
  val colIndex: Int,
  val reference: String,
  val displayValue: String,
  val isBold: Boolean = false,
  val isNumeric: Boolean = false,
)

data class SpreadsheetSheet(
  val name: String,
  val rows: List<List<SpreadsheetCell>>,
  val columnCount: Int,
)

sealed interface ParsedDocument {
  val fileName: String
  val format: DocumentFormat
  val file: File

  data class Pdf(
    override val file: File,
    override val fileName: String,
    val pageCount: Int,
  ) : ParsedDocument {
    override val format: DocumentFormat = DocumentFormat.PDF
  }

  data class RichText(
    override val file: File,
    override val fileName: String,
    override val format: DocumentFormat,
    val blocks: List<DocBlock>,
    val isLegacyNotice: Boolean = false,
  ) : ParsedDocument

  data class Spreadsheet(
    override val file: File,
    override val fileName: String,
    val sheets: List<SpreadsheetSheet>,
  ) : ParsedDocument {
    override val format: DocumentFormat = DocumentFormat.XLSX
  }
}
