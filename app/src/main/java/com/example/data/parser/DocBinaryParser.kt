package com.example.data.parser

import com.example.domain.model.DocBlock
import com.example.domain.model.DocumentFormat
import com.example.domain.model.ParsedDocument
import com.example.domain.model.StyledSpan
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import kotlin.math.min

/**
 * Native Kotlin parser for legacy Microsoft Word 97-2003 (.doc) files
 * (OLE2 Compound File Binary Format + Word Binary FIB/Piece Table).
 * Also handles RTF, HTML, or renamed DOCX files that carry a .doc extension.
 */
object DocBinaryParser {

  class EncryptedLegacyDocException : IllegalStateException(
    "File .doc (Word 97-2003) yang terenkripsi tidak didukung. Simpan ulang sebagai .docx atau .pdf untuk membukanya."
  )

  fun parse(file: File, displayName: String = file.name): ParsedDocument.RichText {
    val bytes = file.readBytes()
    if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
      // Disguised DOCX saved with .doc extension
      return DocxParser.parse(file, displayName).copy(format = DocumentFormat.DOC)
    }

    val prefixText = bytes.copyOfRange(0, min(bytes.size, 256)).toString(Charsets.ISO_8859_1).trimStart()
    if (prefixText.startsWith("{\\rtf", ignoreCase = true)) {
      val blocks = parseRtf(bytes.toString(Charsets.ISO_8859_1))
      return ParsedDocument.RichText(file, displayName, DocumentFormat.DOC, blocks, isLegacyNotice = true)
    }
    if (
      prefixText.startsWith("<html", ignoreCase = true) ||
        prefixText.startsWith("<!doctype", ignoreCase = true) ||
        prefixText.startsWith("<?xml", ignoreCase = true)
    ) {
      val blocks = parseHtmlFallback(bytes.toString(Charsets.UTF_8))
      return ParsedDocument.RichText(file, displayName, DocumentFormat.DOC, blocks, isLegacyNotice = true)
    }

    if (Ole2CompoundReader.isOle2(bytes)) {
      val rawText = extractTextFromOle2(bytes)
      val blocks = rawTextToBlocks(rawText)
      return ParsedDocument.RichText(
        file = file,
        fileName = displayName,
        format = DocumentFormat.DOC,
        blocks = blocks,
        isLegacyNotice = true,
      )
    }

    // Plain text fallback if non-OLE2 text file named .doc
    val plain = bytes.toString(Charsets.UTF_8)
    return ParsedDocument.RichText(
      file = file,
      fileName = displayName,
      format = DocumentFormat.DOC,
      blocks = rawTextToBlocks(plain),
      isLegacyNotice = true,
    )
  }

  private fun extractTextFromOle2(data: ByteArray): String {
    val streams = Ole2CompoundReader.readStreams(data)
    val wordDoc =
      streams["WordDocument"]
        ?: throw IllegalArgumentException("Stream WordDocument tidak ditemukan di file .doc")
    if (wordDoc.size < 512) {
      throw IllegalArgumentException("Header WordDocument tidak lengkap")
    }

    val wBuf = ByteBuffer.wrap(wordDoc).order(ByteOrder.LITTLE_ENDIAN)
    val flags = wBuf.getShort(0x0A).toInt() and 0xFFFF
    val isEncrypted = (flags and 0x0100) != 0
    if (isEncrypted) {
      throw EncryptedLegacyDocException()
    }
    val use1Table = (flags and 0x0200) != 0
    val tableName = if (use1Table) "1Table" else "0Table"
    val tableStream = streams[tableName] ?: streams["1Table"] ?: streams["0Table"]

    val ccpText = wBuf.getInt(0x4C)
    val ccpFtn = wBuf.getInt(0x50)
    val ccpHdd = wBuf.getInt(0x54)
    val mainCharLimit = if (ccpText > 0) ccpText else Int.MAX_VALUE

    // Try extracting via Piece Table (fcClx at offset 0x01A2, lcbClx at 0x01A6)
    if (tableStream != null && wordDoc.size >= 0x01AA) {
      val fcClx = wBuf.getInt(0x01A2)
      val lcbClx = wBuf.getInt(0x01A6)
      if (fcClx >= 0 && lcbClx > 0) {
        val extracted = extractViaPieceTable(wordDoc, tableStream, fcClx, lcbClx, mainCharLimit)
        if (extracted.isNotBlank()) return extracted
      }
    }

    // Fallback to simple fcMin..fcMac range if non-complex file
    val fcMin = wBuf.getInt(0x18).coerceAtLeast(0)
    val fcMac = wBuf.getInt(0x1C).coerceIn(fcMin, wordDoc.size)
    if (fcMac > fcMin) {
      val slice = wordDoc.copyOfRange(fcMin, fcMac)
      return slice.toString(Charset.forName("windows-1252"))
    }

    val totalSubDocs = ccpText + ccpFtn + ccpHdd
    if (totalSubDocs <= 0) return ""
    return ""
  }

  private fun extractViaPieceTable(
    wordDoc: ByteArray,
    tableStream: ByteArray,
    fcClx: Int,
    lcbClx: Int,
    mainCharLimit: Int,
  ): String {
    if (fcClx + lcbClx > tableStream.size) return ""
    var pos = fcClx
    val end = fcClx + lcbClx
    val tBuf = ByteBuffer.wrap(tableStream).order(ByteOrder.LITTLE_ENDIAN)

    // Skip any Prc blocks (clxt == 1) until Pcdt (clxt == 2)
    while (pos < end) {
      val clxt = tableStream[pos].toInt() and 0xFF
      if (clxt == 1) {
        if (pos + 3 > end) return ""
        val cbGrpprl = tBuf.getShort(pos + 1).toInt() and 0xFFFF
        pos += 3 + cbGrpprl
      } else if (clxt == 2) {
        if (pos + 5 > end) return ""
        val lcb = tBuf.getInt(pos + 1)
        val plcStart = pos + 5
        if (plcStart + lcb > tableStream.size || lcb < 12) return ""

        // PlcPcd: (n + 1) 4-byte CPs followed by n 8-byte PCDs -> lcb = 4*(n+1) + 8*n = 12*n + 4
        val n = (lcb - 4) / 12
        if (n <= 0) return ""

        val cpArray = IntArray(n + 1) { i -> tBuf.getInt(plcStart + i * 4) }
        val pcdBase = plcStart + (n + 1) * 4
        val cp1252 = Charset.forName("windows-1252")
        val sb = StringBuilder()

        for (i in 0 until n) {
          val cpStart = cpArray[i]
          val cpEnd = cpArray[i + 1]
          if (cpStart >= mainCharLimit) break
          val clampedEnd = min(cpEnd, mainCharLimit)
          val charCount = clampedEnd - cpStart
          if (charCount <= 0) continue

          val fcRaw = tBuf.getInt(pcdBase + i * 8 + 2)
          val isCompressed = (fcRaw and 0x40000000) != 0
          if (isCompressed) {
            val offset = (fcRaw and 0x3FFFFFFF) / 2
            val safeEnd = min(offset + charCount, wordDoc.size)
            if (offset in 0 until safeEnd) {
              sb.append(String(wordDoc, offset, safeEnd - offset, cp1252))
            }
          } else {
            val offset = fcRaw
            val byteLen = charCount * 2
            val safeEnd = min(offset + byteLen, wordDoc.size)
            if (offset in 0 until safeEnd) {
              sb.append(String(wordDoc, offset, safeEnd - offset, Charsets.UTF_16LE))
            }
          }
        }
        return sb.toString()
      } else {
        break
      }
    }
    return ""
  }

  private fun rawTextToBlocks(raw: String): List<DocBlock> {
    // Strip Word field codes (\u0013 ... \u0014 ... \u0015) cleanly
    val cleaned = stripWordFields(raw)
    val blocks = mutableListOf<DocBlock>()
    val tableRows = mutableListOf<List<String>>()
    var currentRow = mutableListOf<String>()
    val cellBuf = StringBuilder()

    val lines = cleaned.split('\r', '\n')
    for (line in lines) {
      if (line.contains('\u0007')) {
        val parts = line.split('\u0007')
        if (parts.size > 1) {
          for (i in 0 until parts.size - 1) {
            cellBuf.append(parts[i])
            currentRow.add(cellBuf.toString().trim())
            cellBuf.setLength(0)
          }
          if (parts.last().isEmpty() && currentRow.isNotEmpty()) {
            tableRows.add(currentRow.toList())
            currentRow = mutableListOf()
          }
        }
      } else {
        if (tableRows.isNotEmpty()) {
          blocks.add(DocBlock.Table(tableRows.toList()))
          tableRows.clear()
        }
        val trimmed = line.replace(Regex("[\\x00-\\x08\\x0B\\x0E-\\x1F]"), "").trim()
        if (trimmed.isNotEmpty()) {
          blocks.add(DocBlock.Paragraph(spans = listOf(StyledSpan(trimmed))))
        }
      }
    }
    if (tableRows.isNotEmpty()) {
      blocks.add(DocBlock.Table(tableRows.toList()))
    }
    return blocks
  }

  private fun stripWordFields(raw: String): String {
    val sb = StringBuilder(raw.length)
    var fieldDepth = 0
    var inResult = false
    for (ch in raw) {
      when (ch) {
        '\u0013' -> {
          fieldDepth++
          inResult = false
        }
        '\u0014' -> if (fieldDepth > 0) inResult = true
        '\u0015' -> if (fieldDepth > 0) {
          fieldDepth--
          inResult = fieldDepth > 0
        }
        '\u000C' -> sb.append('\n')
        else -> {
          if (fieldDepth == 0 || inResult) {
            sb.append(ch)
          }
        }
      }
    }
    return sb.toString()
  }

  private fun parseRtf(rtf: String): List<DocBlock> {
    val sb = StringBuilder()
    var i = 0
    var skipGroupDepth = 0
    var groupDepth = 0
    while (i < rtf.length) {
      val c = rtf[i]
      when (c) {
        '{' -> groupDepth++
        '}' -> {
          if (skipGroupDepth == groupDepth) skipGroupDepth = 0
          groupDepth = (groupDepth - 1).coerceAtLeast(0)
        }
        '\\' -> {
          i++
          if (i >= rtf.length) break
          val next = rtf[i]
          if (next == '\\' || next == '{' || next == '}') {
            if (skipGroupDepth == 0) sb.append(next)
          } else if (next == '\'') {
            if (i + 2 < rtf.length) {
              val hex = rtf.substring(i + 1, i + 3).toIntOrNull(16)
              if (hex != null && skipGroupDepth == 0) sb.append(hex.toChar())
              i += 2
            }
          } else if (next.isLetter()) {
            val start = i
            while (i < rtf.length && rtf[i].isLetter()) i++
            val word = rtf.substring(start, i)
            while (i < rtf.length && (rtf[i].isDigit() || rtf[i] == '-')) i++
            if (i < rtf.length && rtf[i] == ' ') i++
            i--
            when (word) {
              "par", "line" -> if (skipGroupDepth == 0) sb.append('\n')
              "tab" -> if (skipGroupDepth == 0) sb.append("  ")
              "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer" -> {
                if (skipGroupDepth == 0) skipGroupDepth = groupDepth
              }
            }
          }
        }
        '\r', '\n' -> Unit
        else -> if (skipGroupDepth == 0) sb.append(c)
      }
      i++
    }
    return sb.toString()
      .lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .map { DocBlock.Paragraph(listOf(StyledSpan(it))) }
  }

  private fun parseHtmlFallback(html: String): List<DocBlock> {
    val text =
      html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<(br|p|div|li|tr|h[1-6])[^>]*>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
    return text.lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .map { DocBlock.Paragraph(listOf(StyledSpan(it))) }
  }
}
