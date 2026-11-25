package com.example.data.parser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt

data class PdfSearchHit(
  val pageIndex: Int,
  val snippet: String,
  val rects: List<PdfTextWord> = emptyList(),
)

class PdfRendererHelper(
  private val file: File,
  private val password: String? = null,
  cacheDir: File? = null,
) : Closeable {
  private val mutex = Mutex()

  private val decryptedTempFile: File? =
    if (
      Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM &&
        password != null &&
        PdfDecryptor.isEncryptedPdf(file)
    ) {
      val dir = cacheDir ?: file.parentFile ?: File(".")
      PdfDecryptor.decryptToTempFile(file, password, dir)
    } else {
      null
    }

  private val effectiveFile: File = decryptedTempFile ?: file

  private val pfd: ParcelFileDescriptor =
    ParcelFileDescriptor.open(effectiveFile, ParcelFileDescriptor.MODE_READ_ONLY)

  private val renderer: PdfRenderer =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && password != null && decryptedTempFile == null) {
      PdfRenderer(pfd, LoadParams.Builder().setPassword(password).build())
    } else {
      PdfRenderer(pfd)
    }

  val pageCount: Int
    get() = renderer.pageCount

  // Keep rendered pages cached in memory (up to 1/8 heap, 32-96 MB) for silky-smooth flinging
  private val cache: LruCache<Int, Bitmap> =
    object :
      LruCache<Int, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8L).toInt().coerceIn(32 * 1024 * 1024, 96 * 1024 * 1024)
      ) {
      override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

  private val pageTextCache = ConcurrentHashMap<Int, String>()
  private val pageWordsCache = ConcurrentHashMap<Int, List<PdfTextWord>>()
  private val pageAspectRatioCache = ConcurrentHashMap<Int, Float>()

  fun getCachedAspectRatio(pageIndex: Int): Float? = pageAspectRatioCache[pageIndex]

  fun getEstimatedAspectRatio(pageIndex: Int): Float {
    pageAspectRatioCache[pageIndex]?.let { return it }
    return pageAspectRatioCache[0]
      ?: pageAspectRatioCache.values.firstOrNull()
      ?: 0.71f
  }

  fun getCachedPageBitmap(pageIndex: Int): Bitmap? {
    val bmp = cache.get(pageIndex) ?: return null
    return if (bmp.isRecycled) null else bmp
  }

  fun getCachedPageWords(pageIndex: Int): List<PdfTextWord>? = pageWordsCache[pageIndex]

  suspend fun warmupPageMetrics(centerPage: Int, radius: Int = 6) {
    withContext(Dispatchers.IO) {
      if (pageCount <= 1) return@withContext
      val start = (centerPage - radius).coerceAtLeast(0)
      val end = (centerPage + radius).coerceAtMost(pageCount - 1)
      for (idx in start..end) {
        coroutineContext.ensureActive()
        if (pageAspectRatioCache.containsKey(idx)) continue
        mutex.withLock {
          coroutineContext.ensureActive()
          if (pageAspectRatioCache.containsKey(idx)) return@withLock
          val page = renderer.openPage(idx)
          try {
            val ratio = (page.width.toFloat() / page.height.coerceAtLeast(1)).coerceIn(0.4f, 2.5f)
            pageAspectRatioCache[idx] = ratio
          } finally {
            page.close()
          }
        }
      }
    }
  }

  suspend fun renderPage(pageIndex: Int, targetWidthPx: Int = 1080): Bitmap? =
    withContext(Dispatchers.IO) {
      if (pageIndex < 0 || pageIndex >= pageCount) return@withContext null
      getCachedPageBitmap(pageIndex)?.let { return@withContext it }
      coroutineContext.ensureActive()

      mutex.withLock {
        coroutineContext.ensureActive()
        getCachedPageBitmap(pageIndex)?.let { return@withLock it }

        val page = renderer.openPage(pageIndex)
        try {
          val ratio = (page.width.toFloat() / page.height.coerceAtLeast(1)).coerceIn(0.4f, 2.5f)
          pageAspectRatioCache[pageIndex] = ratio

          val safeWidth = targetWidthPx.coerceIn(540, 1600)
          val scale = safeWidth.toFloat() / page.width.coerceAtLeast(1)
          val height = (page.height * scale).roundToInt().coerceIn(200, 4096)
          val bitmap = Bitmap.createBitmap(safeWidth, height, Bitmap.Config.ARGB_8888)
          val canvas = Canvas(bitmap)
          canvas.drawColor(Color.WHITE)
          page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          bitmap.prepareToDraw()
          cache.put(pageIndex, bitmap)
          bitmap
        } finally {
          page.close()
        }
      }
    }

  suspend fun prefetchPages(centerPage: Int, targetWidthPx: Int = 1080) {
    val candidates = listOf(centerPage + 1, centerPage + 2, centerPage - 1)
    for (idx in candidates) {
      coroutineContext.ensureActive()
      if (idx in 0 until pageCount && getCachedPageBitmap(idx) == null) {
        renderPage(idx, targetWidthPx)
      }
    }
  }

  suspend fun extractPageWords(pageIndex: Int): List<PdfTextWord> =
    withContext(Dispatchers.IO) {
      if (pageIndex < 0 || pageIndex >= pageCount) return@withContext emptyList()
      pageWordsCache[pageIndex]?.let { return@withContext it }
      coroutineContext.ensureActive()

      val extracted =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
          val nativeWords =
            mutex.withLock {
              coroutineContext.ensureActive()
              val page = renderer.openPage(pageIndex)
              try {
                val pw = page.width.toFloat().coerceAtLeast(1f)
                val ph = page.height.toFloat().coerceAtLeast(1f)
                val out = mutableListOf<PdfTextWord>()
                for (content in page.getTextContents()) {
                  appendNormalizedWordsFromContent(
                    rawText = content.text,
                    bounds = content.bounds,
                    pageWidth = pw,
                    pageHeight = ph,
                    out = out,
                  )
                }
                out.toList()
              } finally {
                page.close()
              }
            }
          nativeWords.ifEmpty { PdfTextExtractor.extractPageWords(effectiveFile, pageIndex) }
        } else {
          PdfTextExtractor.extractPageWords(effectiveFile, pageIndex)
        }

      pageWordsCache[pageIndex] = extracted
      extracted
    }

  suspend fun extractPageText(pageIndex: Int): String =
    withContext(Dispatchers.IO) {
      if (pageIndex < 0 || pageIndex >= pageCount) return@withContext ""
      pageTextCache[pageIndex]?.let { return@withContext it }

      val words = extractPageWords(pageIndex)
      val extracted =
        if (words.isNotEmpty()) {
          PdfTextExtractor.joinWordsToReadableText(words)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
          mutex.withLock {
            val page = renderer.openPage(pageIndex)
            try {
              page.getTextContents().joinToString("\n") { it.text }.trim()
            } finally {
              page.close()
            }
          }.ifEmpty { PdfTextExtractor.extractPageText(effectiveFile, pageIndex) }
        } else {
          PdfTextExtractor.extractPageText(effectiveFile, pageIndex)
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
        val words = extractPageWords(pageIdx)
        if (words.isNotEmpty()) {
          val joinedBuilder = StringBuilder()
          val wordSpans = ArrayList<IntRange>(words.size)
          for (wIdx in words.indices) {
            if (wIdx > 0) joinedBuilder.append(' ')
            val startOffset = joinedBuilder.length
            joinedBuilder.append(words[wIdx].text)
            val endOffset = joinedBuilder.length
            wordSpans.add(startOffset until endOffset)
          }
          val joinedText = joinedBuilder.toString()
          var matchStart = joinedText.indexOf(trimmed, ignoreCase = true)
          while (matchStart >= 0) {
            val matchEnd = matchStart + trimmed.length
            val snippetStart = (matchStart - 36).coerceAtLeast(0)
            val snippetEnd = (matchEnd + 54).coerceAtMost(joinedText.length)
            val prefix = if (snippetStart > 0) "…" else ""
            val suffix = if (snippetEnd < joinedText.length) "…" else ""
            val rawSnippet = joinedText.substring(snippetStart, snippetEnd).trim()

            val hitRects = mutableListOf<PdfTextWord>()
            for (wIdx in words.indices) {
              val span = wordSpans[wIdx]
              if (span.last < matchStart || span.first >= matchEnd) continue
              val word = words[wIdx]
              val wordLen = (span.last - span.first + 1).coerceAtLeast(1)
              val overlapStart = (maxOf(matchStart, span.first) - span.first).toFloat() / wordLen
              val overlapEnd = (minOf(matchEnd, span.last + 1) - span.first).toFloat() / wordLen
              val wWidth = (word.right - word.left).coerceAtLeast(0.004f)
              hitRects.add(
                word.copy(
                  left = word.left + wWidth * overlapStart,
                  right = word.left + wWidth * overlapEnd,
                )
              )
            }
            hits.add(
              PdfSearchHit(
                pageIndex = pageIdx,
                snippet = "$prefix$rawSnippet$suffix",
                rects = hitRects,
              )
            )
            matchStart = joinedText.indexOf(trimmed, startIndex = matchEnd, ignoreCase = true)
          }
        } else {
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
      }
      hits
    }

  private fun appendNormalizedWordsFromContent(
    rawText: String,
    bounds: List<RectF>,
    pageWidth: Float,
    pageHeight: Float,
    out: MutableList<PdfTextWord>,
  ) {
    if (rawText.isBlank() || bounds.isEmpty()) return

    val lines = rawText.replace("\r\n", "\n").split('\n').filter { it.isNotBlank() }
    val validBounds = bounds.filter { it.width() > 0.5f && it.height() > 0.5f }.ifEmpty { bounds }
    val lineMergedBounds = mergeSameLineRects(validBounds)

    if (lineMergedBounds.size == 1) {
      val rect = lineMergedBounds[0]
      if (lines.size <= 1) {
        appendLineWordsInRect(rawText.trim(), rect, pageWidth, pageHeight, out)
      } else {
        val lineHeight = (rect.height() / lines.size).coerceAtLeast(1f)
        lines.forEachIndexed { idx, line ->
          val subRect =
            RectF(
              rect.left,
              rect.top + idx * lineHeight,
              rect.right,
              rect.top + (idx + 1) * lineHeight,
            )
          appendLineWordsInRect(line.trim(), subRect, pageWidth, pageHeight, out)
        }
      }
      return
    }

    if (lines.size == lineMergedBounds.size) {
      for (i in lines.indices) {
        appendLineWordsInRect(lines[i].trim(), lineMergedBounds[i], pageWidth, pageHeight, out)
      }
      return
    }

    if (lines.size == bounds.size) {
      for (i in lines.indices) {
        appendLineWordsInRect(lines[i].trim(), bounds[i], pageWidth, pageHeight, out)
      }
      return
    }

    if (lines.isEmpty()) return

    // Align lineMergedBounds 1-to-1 with \n lines without global token smearing:
    // - If too many rects, merge the closest vertical pairs first
    // - If too few rects, split the tallest multi-line rects proportionally
    val rowRects = lineMergedBounds.mapTo(mutableListOf()) { RectF(it) }

    while (rowRects.size > lines.size && rowRects.size > 1) {
      var bestIdx = 0
      var bestDist = Float.MAX_VALUE
      for (i in 0 until rowRects.lastIndex) {
        val aMid = (rowRects[i].top + rowRects[i].bottom) * 0.5f
        val bMid = (rowRects[i + 1].top + rowRects[i + 1].bottom) * 0.5f
        val d = abs(aMid - bMid)
        if (d < bestDist) {
          bestDist = d
          bestIdx = i
        }
      }
      rowRects[bestIdx].union(rowRects[bestIdx + 1])
      rowRects.removeAt(bestIdx + 1)
    }

    while (rowRects.size < lines.size) {
      var tallestIdx = 0
      var tallestH = -1f
      for (i in rowRects.indices) {
        val h = rowRects[i].height()
        if (h > tallestH) {
          tallestH = h
          tallestIdx = i
        }
      }
      val rect = rowRects[tallestIdx]
      val midY = (rect.top + rect.bottom) * 0.5f
      val topHalf = RectF(rect.left, rect.top, rect.right, midY)
      val bottomHalf = RectF(rect.left, midY, rect.right, rect.bottom)
      rowRects[tallestIdx] = topHalf
      rowRects.add(tallestIdx + 1, bottomHalf)
    }

    for (i in lines.indices) {
      appendLineWordsInRect(lines[i].trim(), rowRects[i], pageWidth, pageHeight, out)
    }
  }

  private fun mergeSameLineRects(bounds: List<RectF>): List<RectF> {
    if (bounds.size <= 1) return bounds
    val result = mutableListOf<RectF>()
    var cur = RectF(bounds[0])
    for (i in 1 until bounds.size) {
      val next = bounds[i]
      val curMidY = (cur.top + cur.bottom) * 0.5f
      val nextMidY = (next.top + next.bottom) * 0.5f
      val lineH = maxOf(cur.height(), next.height(), 4f)
      val sameRow = abs(curMidY - nextMidY) <= lineH * 0.55f
      val hGap = next.left - cur.right
      val contiguous = hGap <= maxOf(lineH * 2.5f, 24f) && next.right >= cur.left - lineH * 0.5f
      if (sameRow && contiguous) {
        cur.union(next)
      } else {
        result.add(cur)
        cur = RectF(next)
      }
    }
    result.add(cur)
    return result
  }

  private fun appendLineWordsInRect(
    line: String,
    rect: RectF,
    pageWidth: Float,
    pageHeight: Float,
    out: MutableList<PdfTextWord>,
  ) {
    if (line.isBlank()) return
    val cumWeights = FloatArray(line.length + 1)
    var totalWeight = 0f
    for (idx in line.indices) {
      val ch = line[idx]
      val w = if (ch.isWhitespace()) 0.28f else PdfTextExtractor.glyphAdvanceFactor(ch)
      totalWeight += w
      cumWeights[idx + 1] = totalWeight
    }
    if (totalWeight <= 0f) totalWeight = 1f

    val rw = rect.width().coerceAtLeast(1f)
    val ny0 = (rect.top / pageHeight).coerceIn(0f, 0.992f)
    val ny1 = (rect.bottom / pageHeight).coerceIn(ny0 + 0.005f, 1f)

    val wordRegex = Regex("\\S+")
    for (match in wordRegex.findAll(line)) {
      val startFrac = cumWeights[match.range.first] / totalWeight
      val endFrac = cumWeights[match.range.last + 1] / totalWeight
      val nx0 = ((rect.left + rw * startFrac) / pageWidth).coerceIn(0f, 0.992f)
      val nx1 = ((rect.left + rw * endFrac) / pageWidth).coerceIn(nx0 + 0.005f, 1f)
      out.add(PdfTextWord(match.value, nx0, ny0, nx1, ny1))
    }
  }

  override fun close() {
    cache.evictAll()
    pageTextCache.clear()
    pageWordsCache.clear()
    pageAspectRatioCache.clear()
    runCatching { renderer.close() }
    runCatching { pfd.close() }
    runCatching { decryptedTempFile?.delete() }
  }
}
