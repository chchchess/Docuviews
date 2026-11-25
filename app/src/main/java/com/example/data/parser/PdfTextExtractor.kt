package com.example.data.parser

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

/**
 * Lightweight pure-Kotlin PDF text extractor used as fallback on Android API 24-34
 * (on Android 15+, PdfRenderer.Page.getTextContents() is used first).
 */
object PdfTextExtractor {

  fun extractPageText(file: File, pageIndex: Int): String {
    if (!file.exists()) return ""
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return ""
    return extractFromBytes(bytes, pageIndex)
  }

  fun extractFromBytes(pdfBytes: ByteArray, pageIndex: Int): String {
    val latin = String(pdfBytes, Charsets.ISO_8859_1)
    val objects = parseObjects(pdfBytes, latin)
    if (objects.isEmpty()) return ""

    // Build global ToUnicode CMap from any CMap streams found
    val toUnicodeMap = mutableMapOf<Int, String>()
    for (obj in objects.values) {
      val streamText = obj.decompressedStreamText() ?: continue
      if (streamText.contains("beginbfchar") || streamText.contains("beginbfrange")) {
        parseToUnicodeCMap(streamText, toUnicodeMap)
      }
    }

    // Find leaf /Page objects (with /Type /Page and not /Type /Pages)
    val pageObjs =
      objects.entries
        .filter { (_, obj) ->
          obj.header.contains(Regex("/Type\\s*/Page\\b")) &&
            !obj.header.contains(Regex("/Type\\s*/Pages\\b"))
        }
        .sortedBy { it.key }
        .map { it.value }

    if (pageObjs.isNotEmpty() && pageIndex in pageObjs.indices) {
      val page = pageObjs[pageIndex]
      val contentRefs = extractContentObjectRefs(page.header)
      val sb = StringBuilder()
      for (refId in contentRefs) {
        val streamObj = objects[refId] ?: continue
        val content = streamObj.decompressedStreamText() ?: continue
        val extracted = extractTextFromContentStream(content, toUnicodeMap)
        if (extracted.isNotBlank()) {
          if (sb.isNotEmpty()) sb.append('\n')
          sb.append(extracted)
        }
      }
      if (sb.isNotBlank()) return sb.toString().trim()
    }

    // Fallback: collect text-bearing content streams in object order
    val textStreams =
      objects.entries
        .sortedBy { it.key }
        .mapNotNull { (_, obj) ->
          val content = obj.decompressedStreamText() ?: return@mapNotNull null
          if (content.contains("BT") && (content.contains("Tj") || content.contains("TJ"))) {
            extractTextFromContentStream(content, toUnicodeMap)
          } else {
            null
          }
        }
        .filter { it.isNotBlank() }

    return textStreams.getOrNull(pageIndex)?.trim() ?: ""
  }

  private class PdfObject(
    val header: String,
    val rawBytes: ByteArray,
    val streamStart: Int,
    val streamEnd: Int,
  ) {
    fun decompressedStreamText(): String? {
      if (streamStart < 0 || streamEnd <= streamStart || streamEnd > rawBytes.size) return null
      val slice = rawBytes.copyOfRange(streamStart, streamEnd)
      val bytes =
        if (header.contains("/FlateDecode")) {
          inflateBytes(slice) ?: return null
        } else {
          slice
        }
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
        val dataEnd = if (endStreamIdx in dataStart..endObjIdx) {
          var e = endStreamIdx
          while (e > dataStart && (rawBytes[e - 1] == '\n'.code.toByte() || rawBytes[e - 1] == '\r'.code.toByte())) {
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
    return result
  }

  private fun extractContentObjectRefs(pageHeader: String): List<Int> {
    val arrayMatch = Regex("/Contents\\s*\\[([^\\]]+)]").find(pageHeader)
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
        var dest = m.groupValues[3].toIntOrNull(16) ?: continue
        val span = (end - start).coerceIn(0, 512)
        for (offset in 0..span) {
          out[start + offset] = Character.toChars(dest + offset).concatToString()
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

  private fun extractTextFromContentStream(
    content: String,
    toUnicodeMap: Map<Int, String>,
  ): String {
    val out = StringBuilder()
    var i = 0
    val len = content.length
    var inTextBlock = false
    var pendingString: String? = null
    var pendingArray: List<String>? = null

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
          val parsed = readArrayTokens(content, i, toUnicodeMap)
          pendingArray = parsed.first
          pendingString = null
          i = parsed.second
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
            "BT" -> inTextBlock = true
            "ET" -> {
              if (inTextBlock && out.isNotEmpty() && out.last() != '\n') out.append('\n')
              inTextBlock = false
            }
            "T*", "TD", "Td" -> {
              if (inTextBlock && out.isNotEmpty() && out.last() != '\n' && out.last() != ' ') {
                out.append('\n')
              }
            }
            "Tj" -> {
              if (inTextBlock && pendingString != null) {
                out.append(pendingString)
                pendingString = null
              }
            }
            "'", "\"" -> {
              if (inTextBlock && pendingString != null) {
                if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                out.append(pendingString)
                pendingString = null
              }
            }
            "TJ" -> {
              if (inTextBlock && pendingArray != null) {
                pendingArray.forEach { out.append(it) }
                pendingArray = null
              }
            }
          }
        }
        else -> i++
      }
    }

    return out.toString()
      .replace(Regex("[ \\t]+\n"), "\n")
      .replace(Regex("\n{3,}"), "\n\n")
      .trim()
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

  private fun readArrayTokens(
    content: String,
    startIdx: Int,
    toUnicodeMap: Map<Int, String>,
  ): Pair<List<String>, Int> {
    val parts = mutableListOf<String>()
    var i = startIdx + 1
    while (i < content.length && content[i] != ']') {
      val ch = content[i]
      when {
        ch == '(' -> {
          val parsed = readLiteralString(content, i)
          parts.add(parsed.first)
          i = parsed.second
        }
        ch == '<' -> {
          val endHex = content.indexOf('>', i + 1)
          if (endHex > i) {
            parts.add(decodeHexToken(content.substring(i + 1, endHex), toUnicodeMap))
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
          if (kern != null && kern <= -180f) {
            parts.add(" ")
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
