package com.example.data.parser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

data class PdfSearchHit(
  val pageIndex: Int,
  val snippet: String,
)

class PdfRendererHelper(private val file: File) : Closeable {
  private val mutex = Mutex()
  private val pfd: ParcelFileDescriptor =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
  private val renderer: PdfRenderer = PdfRenderer(pfd)

  val pageCount: Int
    get() = renderer.pageCount

  // Keep rendered pages cached in memory (up to ~36 MB)
  private val cache: LruCache<Int, Bitmap> =
    object : LruCache<Int, Bitmap>(36 * 1024 * 1024) {
      override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

  private val pageTextCache = ConcurrentHashMap<Int, String>()

  suspend fun renderPage(pageIndex: Int, targetWidthPx: Int = 1440): Bitmap? =
    withContext(Dispatchers.IO) {
      if (pageIndex < 0 || pageIndex >= pageCount) return@withContext null
      cache.get(pageIndex)?.let { cached ->
        if (!cached.isRecycled) return@withContext cached
      }

      mutex.withLock {
        cache.get(pageIndex)?.let { cached ->
          if (!cached.isRecycled) return@withLock cached
        }

        val page = renderer.openPage(pageIndex)
        try {
          val safeWidth = targetWidthPx.coerceIn(480, 2048)
          val scale = safeWidth.toFloat() / page.width.coerceAtLeast(1)
          val height = (page.height * scale).roundToInt().coerceIn(200, 4096)
          val bitmap = Bitmap.createBitmap(safeWidth, height, Bitmap.Config.ARGB_8888)
          val canvas = Canvas(bitmap)
          canvas.drawColor(Color.WHITE)
          page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          cache.put(pageIndex, bitmap)
          bitmap
        } finally {
          page.close()
        }
      }
    }

  suspend fun extractPageText(pageIndex: Int): String =
    withContext(Dispatchers.IO) {
      if (pageIndex < 0 || pageIndex >= pageCount) return@withContext ""
      pageTextCache[pageIndex]?.let { return@withContext it }

      val extracted =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
          val nativeText =
            mutex.withLock {
              val page = renderer.openPage(pageIndex)
              try {
                page.getTextContents().joinToString("\n") { it.text }.trim()
              } finally {
                page.close()
              }
            }
          nativeText.ifEmpty { PdfTextExtractor.extractPageText(file, pageIndex) }
        } else {
          PdfTextExtractor.extractPageText(file, pageIndex)
        }

      pageTextCache[pageIndex] = extracted
      extracted
    }

  suspend fun searchAllPages(query: String): List<PdfSearchHit> =
    withContext(Dispatchers.IO) {
      val trimmed = query.trim()
      if (trimmed.isEmpty()) return@withContext emptyList()
      val hits = mutableListOf<PdfSearchHit>()
      for (pageIdx in 0 until pageCount) {
        val text = extractPageText(pageIdx)
        if (text.isBlank()) continue
        var start = text.indexOf(trimmed, ignoreCase = true)
        while (start >= 0) {
          val snippetStart = (start - 36).coerceAtLeast(0)
          val snippetEnd = (start + trimmed.length + 54).coerceAtMost(text.length)
          val prefix = if (snippetStart > 0) "…" else ""
          val suffix = if (snippetEnd < text.length) "…" else ""
          val rawSnippet =
            text.substring(snippetStart, snippetEnd).replace(Regex("\\s+"), " ").trim()
          hits.add(PdfSearchHit(pageIndex = pageIdx, snippet = "$prefix$rawSnippet$suffix"))
          start = text.indexOf(trimmed, startIndex = start + trimmed.length, ignoreCase = true)
        }
      }
      hits
    }

  override fun close() {
    cache.evictAll()
    pageTextCache.clear()
    runCatching { renderer.close() }
    runCatching { pfd.close() }
  }
}
