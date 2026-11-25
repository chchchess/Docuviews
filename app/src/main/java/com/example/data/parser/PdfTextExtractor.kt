package com.example.data.parser

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.hypot

data class PdfTextWord(
  val text: String,
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
)

/**
 * Pure-Kotlin PDF text & bounding-box extractor.
 * Used for API 24-34 and as fallback when Android 15+ PdfRenderer returns no text contents.
 */
object PdfTextExtractor {

  fun extractPageText(file: File, pageIndex: Int): String {
    if (!file.exists()) return ""
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return ""
    return extractFromBytes(bytes, pageIndex)
  }

  fun extractPageWords(file: File, pageIndex: Int): List<PdfTextWord> {
    if (!file.exists()) return emptyList()
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return emptyList()
    return extractPageWordsFromBytes(bytes, pageIndex)
  }

  fun extractFromBytes(pdfBytes: ByteArray, pageIndex: Int): String {
    val words = extractPageWordsFromBytes(pdfBytes, pageIndex)
    if (words.isEmpty()) return ""
    return joinWordsToReadableText(words)
  }

  fun joinWordsToReadableText(words: List<PdfTextWord>): String {
    if (words.isEmpty()) return ""
    val sb = StringBuilder()
    var prev: PdfTextWord? = null
    for (word in words) {
      if (prev != null) {
        val prevMidY = (prev.top + prev.bottom) * 0.5f
        val curMidY = (word.top + word.bottom) * 0.5f
        val lineHeight = ((prev.bottom - prev.top) + (word.bottom - word.top)) * 0.5f
        val sameLine = abs(curMidY - prevMidY) <= maxOf(lineHeight * 0.65f, 0.008f)
        if (sameLine) {
          sb.append(' ')
        } else {
          sb.append('\n')
        }
      }
      sb.append(word.text)
      prev = word
    }
    return sb.toString().trim()
  }

  fun extractPageWordsFromBytes(pdfBytes: ByteArray, pageIndex: Int): List<PdfTextWord> {
    if (pageIndex < 0 || pdfBytes.isEmpty()) return emptyList()
    val latin = String(pdfBytes, Charsets.ISO_8859_1)
    val objects = parseObjects(pdfBytes, latin)
    if (objects.isEmpty()) return emptyList()

    val toUnicodeMap = mutableMapOf<Int, String>()
    for (obj in objects.values) {
      val streamText = obj.decompressedStreamText() ?: continue
      if (streamText.contains("beginbfchar") || streamText.contains("beginbfrange")) {
        parseToUnicodeCMap(streamText, toUnicodeMap)
      }
    }

    val orderedPages = resolveOrderedPageEntries(objects)
    if (orderedPages.isNotEmpty() && pageIndex in orderedPages.indices) {
      val pageEntry = orderedPages[pageIndex]
      val pageObj = pageEntry.obj
      val mediaBox = resolveMediaBox(pageEntry.id, objects)
      val rotate = resolveRotate(pageEntry.id, objects)
      val contentRefs = extractContentObjectRefs(pageObj.header)
      val combinedStream = StringBuilder()
      for (refId in contentRefs) {
        val streamObj = objects[refId] ?: continue
        val content = streamObj.decompressedStreamText() ?: continue
        combinedStream.append(content).append('\n')
      }
      if (combinedStream.isNotEmpty()) {
        val words =
          extractWordsFromContentStream(
            content = combinedStream.toString(),
            toUnicodeMap = toUnicodeMap,
            mediaBox = mediaBox,
            rotate = rotate,
          )
        if (words.isNotEmpty()) return words
      }
    }

    // Fallback: collect text-bearing content streams in object order
    val fallbackStreams =
      objects.entries
        .sortedBy { it.key }
        .mapNotNull { (_, obj) ->
          val content = obj.decompressedStreamText() ?: return@mapNotNull null
          if (content.contains("BT") && (content.contains("Tj") || content.contains("TJ"))) {
            content
          } else {
            null
          }
        }
    val fallbackContent = fallbackStreams.getOrNull(pageIndex) ?: return emptyList()
    return extractWordsFromContentStream(
      content = fallbackContent,
      toUnicodeMap = toUnicodeMap,
      mediaBox = floatArrayOf(0f, 0f, 612f, 792f),
      rotate = 0,
    )
  }

  private data class PageEntry(val id: Int, val obj: PdfObject)

  private class PdfObject(
    val header: String,
    val rawBytes: ByteArray,
    val streamStart: Int,
    val streamEnd: Int,
  ) {
    private var cachedDecompressed: ByteArray? = null
    private var triedDecompress = false

    fun decompressedStreamBytes(): ByteArray? {
      if (triedDecompress) return cachedDecompressed
      triedDecompress = true
      if (streamStart < 0 || streamEnd <= streamStart || streamEnd > rawBytes.size) return null
      val slice = rawBytes.copyOfRange(streamStart, streamEnd)
      cachedDecompressed =
        if (header.contains("/FlateDecode")) {
          inflateBytes(slice)
        } else {
          slice
        }
      return cachedDecompressed
    }

    fun decompressedStreamText(): String? {
      val bytes = decompressedStreamBytes() ?: return null
      return String(bytes, Charsets.ISO_8859_1)
    }
  }

  private fun parseObjects(rawBytes: ByteArray, latin: String): Map<Int, PdfObject> {
    val result = mutableMapOf<Int, PdfObject>()
    val objRegex = Regex("(\\d+)\\s+\\d+\\s+obj\\b")
    val matches = objRegex.findAll(latin).toList()

    for (match in matches) {
      val objId = match.groupValues[1].toIntOrNull() ?: continue
      val bodyStart = match.range.last + 1
      val endObjIdx = latin.indexOf("endobj", bodyStart)
      if (endObjIdx < 0) continue

      val streamKeywordIdx = latin.indexOf("stream", bodyStart)
      if (streamKeywordIdx in bodyStart until endObjIdx) {
        val header = latin.substring(bodyStart, streamKeywordIdx)
        var dataStart = streamKeywordIdx + "stream".length
        if (dataStart < rawBytes.size && rawBytes[dataStart] == '\r'.code.toByte()) dataStart++
        if (dataStart < rawBytes.size && rawBytes[dataStart] == '\n'.code.toByte()) dataStart++
        val endStreamIdx = latin.indexOf("endstream", dataStart)
        val dataEnd =
          if (endStreamIdx in dataStart..endObjIdx) {
            var e = endStreamIdx
            while (
              e > dataStart &&
                (rawBytes[e - 1] == '\n'.code.toByte() || rawBytes[e - 1] == '\r'.code.toByte())
            ) {
              e--
            }
            e
          } else {
            -1
          }
        result[objId] = PdfObject(header, rawBytes, dataStart, dataEnd)
      } else {
        val header = latin.substring(bodyStart, endObjIdx)
        result[objId] = PdfObject(header, rawBytes, -1, -1)
      }
    }

    // Expand PDF 1.5+ /ObjStm compressed object streams so modern PDFs work reliably
    val objStms = result.values.filter { it.header.contains(Regex("/Type\\s*/ObjStm\\b")) }
    for (stm in objStms) {
      val firstOffset = Regex("/First\\s+(\\d+)").find(stm.header)?.groupValues?.get(1)?.toIntOrNull() ?: continue
      val count = Regex("/N\\s+(\\d+)").find(stm.header)?.groupValues?.get(1)?.toIntOrNull() ?: continue
      val text = stm.decompressedStreamText() ?: continue
      if (firstOffset <= 0 || firstOffset >= text.length) continue
      val indexTokens =
        text.substring(0, firstOffset).trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
      val entries = mutableListOf<Pair<Int, Int>>()
      var idx = 0
      while (idx + 1 < indexTokens.size && entries.size < count) {
        entries.add(indexTokens[idx] to (firstOffset + indexTokens[idx + 1]))
        idx += 2
      }
      for (k in entries.indices) {
        val (innerId, start) = entries[k]
        val end = if (k + 1 < entries.size) entries[k + 1].second else text.length
        if (start in 0 until end && end <= text.length && !result.containsKey(innerId)) {
          val innerHeader = text.substring(start, end)
          result[innerId] = PdfObject(innerHeader, ByteArray(0), -1, -1)
        }
      }
    }

    return result
  }

  private fun resolveOrderedPageEntries(objects: Map<Int, PdfObject>): List<PageEntry> {
    val catalog =
      objects.values.firstOrNull { it.header.contains(Regex("/Type\\s*/Catalog\\b")) }
    val rootPagesId =
      catalog?.header?.let { Regex("/Pages\\s+(\\d+)\\s+\\d+\\s+R").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    if (rootPagesId != null) {
      val collected = mutableListOf<PageEntry>()
      val visited = mutableSetOf<Int>()
      fun walk(nodeId: Int) {
        if (!visited.add(nodeId)) return
        val node = objects[nodeId] ?: return
        val isPages = node.header.contains(Regex("/Type\\s*/Pages\\b")) || node.header.contains("/Kids")
        if (isPages && !node.header.contains(Regex("/Type\\s*/Page\\b(?!s)"))) {
          val kidsMatch = Regex("/Kids\\s*\\[([^\\]]+)]", RegexOption.DOT_MATCHES_ALL).find(node.header)
          if (kidsMatch != null) {
            Regex("(\\d+)\\s+\\d+\\s+R").findAll(kidsMatch.groupValues[1]).forEach { m ->
              m.groupValues[1].toIntOrNull()?.let { walk(it) }
            }
          }
        } else if (node.header.contains(Regex("/Type\\s*/Page\\b")) && !node.header.contains(Regex("/Type\\s*/Pages\\b"))) {
          collected.add(PageEntry(nodeId, node))
        }
      }
      walk(rootPagesId)
      if (collected.isNotEmpty()) return collected
    }

    return objects.entries
      .filter { (_, obj) ->
        obj.header.contains(Regex("/Type\\s*/Page\\b")) &&
          !obj.header.contains(Regex("/Type\\s*/Pages\\b"))
      }
      .sortedBy { it.key }
      .map { PageEntry(it.key, it.value) }
  }

  private fun resolveMediaBox(pageId: Int, objects: Map<Int, PdfObject>): FloatArray {
    var curId: Int? = pageId
    val visited = mutableSetOf<Int>()
    while (curId != null && visited.add(curId)) {
      val obj = objects[curId] ?: break
      parseBox(obj.header, "/CropBox")?.let { return it }
      parseBox(obj.header, "/MediaBox")?.let { return it }
      curId = Regex("/Parent\\s+(\\d+)\\s+\\d+\\s+R").find(obj.header)?.groupValues?.get(1)?.toIntOrNull()
    }
    return floatArrayOf(0f, 0f, 612f, 792f)
  }

  private fun resolveRotate(pageId: Int, objects: Map<Int, PdfObject>): Int {
    var curId: Int? = pageId
    val visited = mutableSetOf<Int>()
    while (curId != null && visited.add(curId)) {
      val obj = objects[curId] ?: break
      val rot = Regex("/Rotate\\s+(-?\\d+)").find(obj.header)?.groupValues?.get(1)?.toIntOrNull()
      if (rot != null) return ((rot % 360) + 360) % 360
      curId = Regex("/Parent\\s+(\\d+)\\s+\\d+\\s+R").find(obj.header)?.groupValues?.get(1)?.toIntOrNull()
    }
    return 0
  }

  private fun parseBox(header: String, key: String): FloatArray? {
    val match = Regex("$key\\s*\\[([^\\]]+)]").find(header) ?: return null
    val nums =
      match.groupValues[1].trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
    if (nums.size < 4) return null
    val x0 = minOf(nums[0], nums[2])
    val y0 = minOf(nums[1], nums[3])
    val x1 = maxOf(nums[0], nums[2])
    val y1 = maxOf(nums[1], nums[3])
    return if (x1 - x0 > 1f && y1 - y0 > 1f) floatArrayOf(x0, y0, x1, y1) else null
  }

  private fun extractContentObjectRefs(pageHeader: String): List<Int> {
    val arrayMatch = Regex("/Contents\\s*\\[([^\\]]+)]", RegexOption.DOT_MATCHES_ALL).find(pageHeader)
    if (arrayMatch != null) {
      return Regex("(\\d+)\\s+\\d+\\s+R")
        .findAll(arrayMatch.groupValues[1])
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .toList()
    }
    val singleMatch = Regex("/Contents\\s+(\\d+)\\s+\\d+\\s+R").find(pageHeader)
    return listOfNotNull(singleMatch?.groupValues?.get(1)?.toIntOrNull())
  }

  private fun inflateBytes(compressed: ByteArray): ByteArray? {
    return runCatching {
      val inflater = Inflater()
      try {
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
          val count = inflater.inflate(buf)
          if (count == 0) {
            if (inflater.needsInput() || inflater.needsDictionary()) break
          } else {
            out.write(buf, 0, count)
          }
        }
        out.toByteArray()
      } finally {
        inflater.end()
      }
    }.getOrNull()
  }

  private fun parseToUnicodeCMap(cmap: String, out: MutableMap<Int, String>) {
    val bfCharRegex = Regex("beginbfchar\\s+(.*?)\\s+endbfchar", RegexOption.DOT_MATCHES_ALL)
    for (block in bfCharRegex.findAll(cmap)) {
      val pairRegex = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
      for (m in pairRegex.findAll(block.groupValues[1])) {
        val code = m.groupValues[1].toIntOrNull(16) ?: continue
        val unicode = decodeUtf16Hex(m.groupValues[2])
        if (unicode.isNotEmpty()) out[code] = unicode
      }
    }

    val bfRangeRegex = Regex("beginbfrange\\s+(.*?)\\s+endbfrange", RegexOption.DOT_MATCHES_ALL)
    for (block in bfRangeRegex.findAll(cmap)) {
      val rangeRegex = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
      for (m in rangeRegex.findAll(block.groupValues[1])) {
        val start = m.groupValues[1].toIntOrNull(16) ?: continue
        val end = m.groupValues[2].toIntOrNull(16) ?: continue
        val dest = m.groupValues[3].toIntOrNull(16) ?: continue
        val span = (end - start).coerceIn(0, 512)
        for (offset in 0..span) {
          out[start + offset] = Character.toChars(dest + offset).concatToString()
        }
      }
      val arrayRangeRegex = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*\\[([^\\]]+)]", RegexOption.DOT_MATCHES_ALL)
      for (m in arrayRangeRegex.findAll(block.groupValues[1])) {
        val start = m.groupValues[1].toIntOrNull(16) ?: continue
        val end = m.groupValues[2].toIntOrNull(16) ?: continue
        val targets = Regex("<([0-9A-Fa-f]+)>").findAll(m.groupValues[3]).toList()
        val span = minOf((end - start).coerceIn(0, 512), targets.lastIndex)
        for (offset in 0..span) {
          val unicode = decodeUtf16Hex(targets[offset].groupValues[1])
          if (unicode.isNotEmpty()) out[start + offset] = unicode
        }
      }
    }
  }

  private fun decodeUtf16Hex(hex: String): String {
    if (hex.length < 2) return ""
    val sb = StringBuilder()
    var i = 0
    while (i + 4 <= hex.length) {
      val cp = hex.substring(i, i + 4).toIntOrNull(16) ?: 0
      if (cp > 0) sb.append(cp.toChar())
      i += 4
    }
    if (sb.isEmpty() && hex.length == 2) {
      val cp = hex.toIntOrNull(16) ?: 0
      if (cp > 0) sb.append(cp.toChar())
    }
    return sb.toString()
  }

  private data class Matrix2D(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val e: Float = 0f,
    val f: Float = 0f,
  ) {
    fun mul(other: Matrix2D): Matrix2D =
      Matrix2D(
        a = a * other.a + b * other.c,
        b = a * other.b + b * other.d,
        c = c * other.a + d * other.c,
        d = c * other.b + d * other.d,
        e = e * other.a + f * other.c + other.e,
        f = e * other.b + f * other.d + other.f,
      )

    fun mapX(x: Float, y: Float): Float = a * x + c * y + e

    fun mapY(x: Float, y: Float): Float = b * x + d * y + f
  }

  private sealed interface TjElement {
    data class Text(val value: String) : TjElement
    data class Kern(val amount: Float) : TjElement
  }

  private fun extractWordsFromContentStream(
    content: String,
    toUnicodeMap: Map<Int, String>,
    mediaBox: FloatArray,
    rotate: Int,
  ): List<PdfTextWord> {
    val words = mutableListOf<PdfTextWord>()
    val ctmStack = ArrayDeque<Matrix2D>()
    var ctm = Matrix2D()
    var tm = Matrix2D()
    var tlm = Matrix2D()
    var leading = 14f
    var fontSize = 12f
    var inTextBlock = false

    val numericOperands = ArrayList<Float>(8)
    var pendingString: String? = null
    var pendingArray: List<TjElement>? = null

    val mbLeft = mediaBox[0]
    val mbBottom = mediaBox[1]
    val mbWidth = (mediaBox[2] - mediaBox[0]).coerceAtLeast(1f)
    val mbHeight = (mediaBox[3] - mediaBox[1]).coerceAtLeast(1f)

    fun emitTextChunk(chunk: String) {
      if (chunk.isEmpty()) return
      // Split chunk into whitespace-delimited words while tracking advance along tm
      var i = 0
      while (i < chunk.length) {
        if (chunk[i].isWhitespace()) {
          var spaceCount = 0
          while (i < chunk.length && chunk[i].isWhitespace()) {
            spaceCount++
            i++
          }
          val spaceAdv = spaceCount * fontSize * 0.28f
          tm = Matrix2D(e = spaceAdv).mul(tm)
          continue
        }
        val wordStart = i
        while (i < chunk.length && !chunk[i].isWhitespace()) i++
        val wordText = chunk.substring(wordStart, i)
        val wordWidthLocal = wordText.length * fontSize * 0.52f

        val trmStart = tm.mul(ctm)
        val xStart = trmStart.e
        val yBase = trmStart.f

        val tmAfter = Matrix2D(e = wordWidthLocal).mul(tm)
        val trmEnd = tmAfter.mul(ctm)
        val xEnd = trmEnd.e

        val effHeight = abs(fontSize * hypot(trmStart.c, trmStart.d)).coerceAtLeast(4f)
        val rawLeft = minOf(xStart, xEnd)
        val rawRight = maxOf(xStart, xEnd).coerceAtLeast(rawLeft + effHeight * 0.4f)
        val rawTopPdf = yBase + effHeight * 0.88f
        val rawBottomPdf = yBase - effHeight * 0.22f

        val nx0 = ((rawLeft - mbLeft) / mbWidth).coerceIn(0f, 0.992f)
        val nx1 = ((rawRight - mbLeft) / mbWidth).coerceIn(nx0 + 0.006f, 1f)
        val ny0 = (1f - (rawTopPdf - mbBottom) / mbHeight).coerceIn(0f, 0.992f)
        val ny1 = (1f - (rawBottomPdf - mbBottom) / mbHeight).coerceIn(ny0 + 0.006f, 1f)

        val mapped = applyRotation(nx0, ny0, nx1, ny1, rotate)
        words.add(
          PdfTextWord(
            text = wordText,
            left = mapped[0],
            top = mapped[1],
            right = mapped[2],
            bottom = mapped[3],
          )
        )
        tm = tmAfter
      }
    }

    fun emitTjArray(elements: List<TjElement>) {
      // Buffer consecutive characters not separated by space or big negative kerning
      val currentWord = StringBuilder()
      var wordStartTm = tm
      var currentEndTm = tm

      fun flushBufferedWord() {
        if (currentWord.isEmpty()) return
        val wordText = currentWord.toString()
        currentWord.clear()

        val trmStart = wordStartTm.mul(ctm)
        val trmEnd = currentEndTm.mul(ctm)
        val effHeight = abs(fontSize * hypot(trmStart.c, trmStart.d)).coerceAtLeast(4f)
        val rawLeft = minOf(trmStart.e, trmEnd.e)
        val rawRight = maxOf(trmStart.e, trmEnd.e).coerceAtLeast(rawLeft + effHeight * 0.4f)
        val rawTopPdf = trmStart.f + effHeight * 0.88f
        val rawBottomPdf = trmStart.f - effHeight * 0.22f

        val nx0 = ((rawLeft - mbLeft) / mbWidth).coerceIn(0f, 0.992f)
        val nx1 = ((rawRight - mbLeft) / mbWidth).coerceIn(nx0 + 0.006f, 1f)
        val ny0 = (1f - (rawTopPdf - mbBottom) / mbHeight).coerceIn(0f, 0.992f)
        val ny1 = (1f - (rawBottomPdf - mbBottom) / mbHeight).coerceIn(ny0 + 0.006f, 1f)

        val mapped = applyRotation(nx0, ny0, nx1, ny1, rotate)
        words.add(
          PdfTextWord(
            text = wordText,
            left = mapped[0],
            top = mapped[1],
            right = mapped[2],
            bottom = mapped[3],
          )
        )
      }

      for (el in elements) {
        when (el) {
          is TjElement.Kern -> {
            val dx = (-el.amount / 1000f) * fontSize
            if (el.amount <= -180f) {
              flushBufferedWord()
              tm = Matrix2D(e = dx).mul(tm)
              wordStartTm = tm
              currentEndTm = tm
            } else {
              tm = Matrix2D(e = dx).mul(tm)
              currentEndTm = tm
            }
          }
          is TjElement.Text -> {
            for (ch in el.value) {
              if (ch.isWhitespace()) {
                flushBufferedWord()
                val spaceAdv = fontSize * 0.28f
                tm = Matrix2D(e = spaceAdv).mul(tm)
                wordStartTm = tm
                currentEndTm = tm
              } else {
                if (currentWord.isEmpty()) wordStartTm = tm
                currentWord.append(ch)
                val charAdv = fontSize * 0.52f
                tm = Matrix2D(e = charAdv).mul(tm)
                currentEndTm = tm
              }
            }
          }
        }
      }
      flushBufferedWord()
    }

    var i = 0
    val len = content.length
    while (i < len) {
      val c = content[i]
      when {
        c.isWhitespace() -> i++
        c == '%' -> {
          while (i < len && content[i] != '\n' && content[i] != '\r') i++
        }
        c == '(' -> {
          val parsed = readLiteralString(content, i)
          pendingString = parsed.first
          pendingArray = null
          i = parsed.second
        }
        c == '<' -> {
          if (i + 1 < len && content[i + 1] == '<') {
            i += 2
          } else {
            val endHex = content.indexOf('>', i + 1)
            if (endHex > i) {
              pendingString = decodeHexToken(content.substring(i + 1, endHex), toUnicodeMap)
              pendingArray = null
              i = endHex + 1
            } else {
              i++
            }
          }
        }
        c == '[' -> {
          val parsed = readArrayElements(content, i, toUnicodeMap)
          pendingArray = parsed.first
          pendingString = null
          i = parsed.second
        }
        c == '/' -> {
          // PDF name token like /F1; skip name characters
          i++
          while (i < len && !content[i].isWhitespace() && content[i] !in "()<>[]{}/%") i++
        }
        c == '-' || c == '+' || c == '.' || c.isDigit() -> {
          val start = i
          i++
          while (i < len && (content[i].isDigit() || content[i] == '.')) i++
          content.substring(start, i).toFloatOrNull()?.let { numericOperands.add(it) }
        }
        c.isLetter() || c == '\'' || c == '"' || c == '*' -> {
          val start = i
          if (c == '\'' || c == '"') {
            i++
          } else {
            while (i < len && (content[i].isLetterOrDigit() || content[i] == '*')) i++
          }
          val op = content.substring(start, i)
          when (op) {
            "q" -> {
              ctmStack.addLast(ctm)
              numericOperands.clear()
            }
            "Q" -> {
              if (ctmStack.isNotEmpty()) ctm = ctmStack.removeLast()
              numericOperands.clear()
            }
            "cm" -> {
              if (numericOperands.size >= 6) {
                val n = numericOperands.size
                val m =
                  Matrix2D(
                    a = numericOperands[n - 6],
                    b = numericOperands[n - 5],
                    c = numericOperands[n - 4],
                    d = numericOperands[n - 3],
                    e = numericOperands[n - 2],
                    f = numericOperands[n - 1],
                  )
                ctm = m.mul(ctm)
              }
              numericOperands.clear()
            }
            "BT" -> {
              inTextBlock = true
              tm = Matrix2D()
              tlm = Matrix2D()
              numericOperands.clear()
            }
            "ET" -> {
              inTextBlock = false
              numericOperands.clear()
            }
            "TL" -> {
              numericOperands.lastOrNull()?.let { leading = abs(it) }
              numericOperands.clear()
            }
            "Tf" -> {
              numericOperands.lastOrNull()?.let { if (abs(it) > 0.1f) fontSize = abs(it) }
              numericOperands.clear()
            }
            "Tm" -> {
              if (inTextBlock && numericOperands.size >= 6) {
                val n = numericOperands.size
                val m =
                  Matrix2D(
                    a = numericOperands[n - 6],
                    b = numericOperands[n - 5],
                    c = numericOperands[n - 4],
                    d = numericOperands[n - 3],
                    e = numericOperands[n - 2],
                    f = numericOperands[n - 1],
                  )
                tm = m
                tlm = m
              }
              numericOperands.clear()
            }
            "Td" -> {
              if (inTextBlock && numericOperands.size >= 2) {
                val n = numericOperands.size
                val tx = numericOperands[n - 2]
                val ty = numericOperands[n - 1]
                tlm = Matrix2D(e = tx, f = ty).mul(tlm)
                tm = tlm
              }
              numericOperands.clear()
            }
            "TD" -> {
              if (inTextBlock && numericOperands.size >= 2) {
                val n = numericOperands.size
                val tx = numericOperands[n - 2]
                val ty = numericOperands[n - 1]
                leading = -ty
                tlm = Matrix2D(e = tx, f = ty).mul(tlm)
                tm = tlm
              }
              numericOperands.clear()
            }
            "T*" -> {
              if (inTextBlock) {
                tlm = Matrix2D(e = 0f, f = -leading).mul(tlm)
                tm = tlm
              }
              numericOperands.clear()
            }
            "Tj" -> {
              if (inTextBlock && pendingString != null) {
                emitTextChunk(pendingString)
                pendingString = null
              }
              numericOperands.clear()
            }
            "'", "\"" -> {
              if (inTextBlock && pendingString != null) {
                tlm = Matrix2D(e = 0f, f = -leading).mul(tlm)
                tm = tlm
                emitTextChunk(pendingString)
                pendingString = null
              }
              numericOperands.clear()
            }
            "TJ" -> {
              if (inTextBlock && pendingArray != null) {
                emitTjArray(pendingArray)
                pendingArray = null
              }
              numericOperands.clear()
            }
            else -> {
              numericOperands.clear()
            }
          }
        }
        else -> i++
      }
    }

    return words
  }

  private fun applyRotation(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    rotate: Int,
  ): FloatArray {
    return when (rotate) {
      90 -> floatArrayOf(1f - bottom, left, 1f - top, right)
      180 -> floatArrayOf(1f - right, 1f - bottom, 1f - left, 1f - top)
      270 -> floatArrayOf(top, 1f - right, bottom, 1f - left)
      else -> floatArrayOf(left, top, right, bottom)
    }
  }

  private fun readLiteralString(content: String, startIdx: Int): Pair<String, Int> {
    val sb = StringBuilder()
    var i = startIdx + 1
    var depth = 1
    while (i < content.length && depth > 0) {
      val ch = content[i]
      when (ch) {
        '\\' -> {
          i++
          if (i >= content.length) break
          when (val esc = content[i]) {
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            'b' -> sb.append('\b')
            '\\', '(', ')' -> sb.append(esc)
            in '0'..'7' -> {
              var octEnd = i + 1
              while (octEnd < content.length && octEnd < i + 3 && content[octEnd] in '0'..'7') octEnd++
              val code = content.substring(i, octEnd).toIntOrNull(8)
              if (code != null) sb.append(code.toChar())
              i = octEnd - 1
            }
            else -> sb.append(esc)
          }
        }
        '(' -> {
          depth++
          sb.append('(')
        }
        ')' -> {
          depth--
          if (depth > 0) sb.append(')')
        }
        else -> sb.append(ch)
      }
      i++
    }
    return sb.toString() to i
  }

  private fun readArrayElements(
    content: String,
    startIdx: Int,
    toUnicodeMap: Map<Int, String>,
  ): Pair<List<TjElement>, Int> {
    val parts = mutableListOf<TjElement>()
    var i = startIdx + 1
    while (i < content.length && content[i] != ']') {
      val ch = content[i]
      when {
        ch == '(' -> {
          val parsed = readLiteralString(content, i)
          parts.add(TjElement.Text(parsed.first))
          i = parsed.second
        }
        ch == '<' -> {
          val endHex = content.indexOf('>', i + 1)
          if (endHex > i) {
            parts.add(TjElement.Text(decodeHexToken(content.substring(i + 1, endHex), toUnicodeMap)))
            i = endHex + 1
          } else {
            i++
          }
        }
        ch == '-' || ch.isDigit() -> {
          val numStart = i
          i++
          while (i < content.length && (content[i].isDigit() || content[i] == '.')) i++
          val kern = content.substring(numStart, i).toFloatOrNull()
          if (kern != null) {
            parts.add(TjElement.Kern(kern))
          }
        }
        else -> i++
      }
    }
    if (i < content.length && content[i] == ']') i++
    return parts to i
  }

  private fun decodeHexToken(rawHex: String, toUnicodeMap: Map<Int, String>): String {
    val hex = rawHex.filter { !it.isWhitespace() }
    if (hex.isEmpty()) return ""
    val sb = StringBuilder()
    if (toUnicodeMap.isNotEmpty() && hex.length % 4 == 0) {
      var i = 0
      while (i + 4 <= hex.length) {
        val code = hex.substring(i, i + 4).toIntOrNull(16)
        val mapped = code?.let { toUnicodeMap[it] }
        if (mapped != null) {
          sb.append(mapped)
        } else if (code != null && code in 32..126) {
          sb.append(code.toChar())
        }
        i += 4
      }
      if (sb.isNotEmpty()) return sb.toString()
    }
    var i = 0
    while (i + 2 <= hex.length) {
      val code = hex.substring(i, i + 2).toIntOrNull(16)
      val mapped = code?.let { toUnicodeMap[it] }
      if (mapped != null) {
        sb.append(mapped)
      } else if (code != null && code in 32..255) {
        sb.append(code.toChar())
      }
      i += 2
    }
    return sb.toString()
  }
}
