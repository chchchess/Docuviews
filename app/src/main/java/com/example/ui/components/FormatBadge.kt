package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.DocumentFormat
import com.example.ui.theme.ExcelEmerald
import com.example.ui.theme.ExcelEmeraldContainer
import com.example.ui.theme.ExcelEmeraldDarkContainer
import com.example.ui.theme.PdfCrimson
import com.example.ui.theme.PdfCrimsonContainer
import com.example.ui.theme.PdfCrimsonDarkContainer
import com.example.ui.theme.WordCobalt
import com.example.ui.theme.WordCobaltContainer
import com.example.ui.theme.WordCobaltDarkContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FormatVisualStyle(
  val accent: Color,
  val container: Color,
  val icon: ImageVector,
)

@Composable
fun rememberFormatStyle(format: DocumentFormat): FormatVisualStyle {
  val isDark = isSystemInDarkTheme()
  return when (format) {
    DocumentFormat.PDF ->
      FormatVisualStyle(
        accent = PdfCrimson,
        container = if (isDark) PdfCrimsonDarkContainer else PdfCrimsonContainer,
        icon = Icons.Rounded.PictureAsPdf,
      )
    DocumentFormat.DOC, DocumentFormat.DOCX ->
      FormatVisualStyle(
        accent = WordCobalt,
        container = if (isDark) WordCobaltDarkContainer else WordCobaltContainer,
        icon = Icons.Rounded.Description,
      )
    DocumentFormat.XLSX ->
      FormatVisualStyle(
        accent = ExcelEmerald,
        container = if (isDark) ExcelEmeraldDarkContainer else ExcelEmeraldContainer,
        icon = Icons.Rounded.TableChart,
      )
  }
}

@Composable
fun FormatIconBadge(
  format: DocumentFormat,
  modifier: Modifier = Modifier,
) {
  val style = rememberFormatStyle(format)
  Box(
    modifier =
      modifier
        .size(48.dp)
        .clip(RoundedCornerShape(14.dp))
        .background(style.container),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = style.icon,
      contentDescription = format.label,
      tint = style.accent,
      modifier = Modifier.size(26.dp),
    )
  }
}

@Composable
fun FormatPill(
  format: DocumentFormat,
  modifier: Modifier = Modifier,
) {
  val style = rememberFormatStyle(format)
  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(6.dp))
        .background(style.container)
        .padding(horizontal = 7.dp, vertical = 2.dp),
  ) {
    Text(
      text = format.label,
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
      color = style.accent,
    )
  }
}

fun formatFileSize(bytes: Long): String {
  if (bytes < 1024L) return "$bytes B"
  val kb = bytes / 1024.0
  if (kb < 1024.0) return String.format(Locale.getDefault(), "%.0f KB", kb)
  val mb = kb / 1024.0
  return String.format(Locale.getDefault(), "%.1f MB", mb)
}

fun formatRelativeDate(timestampMs: Long): String {
  val diff = (System.currentTimeMillis() - timestampMs).coerceAtLeast(0L)
  val mins = diff / 60_000L
  val hours = diff / 3_600_000L
  return when {
    mins < 1 -> "Baru saja"
    mins < 60 -> "$mins mnt lalu"
    hours < 24 -> "$hours jam lalu"
    else -> SimpleDateFormat("dd MMM yyyy", Locale("id", "ID")).format(Date(timestampMs))
  }
}
