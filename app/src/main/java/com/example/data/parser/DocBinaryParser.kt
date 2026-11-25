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

  private val OLE2_MAGIC = byteArrayOf(
    0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
    0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte(),
  )

  private const val FREE_SECT = -1
  private const val END_OF_CHAIN = -2
  private const val FAT_SECT = -3
  private const val DIF_SECT = -4
  private const val NO_STREAM = -1

  private data class DirEntry(
    val name: String,
    val type: Int,
    val startSector: Int,
    val streamSize: Long,
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

    if (bytes.size >= 512 && bytes.copyOfRange(0, 8).contentEquals(OLE2_MAGIC)) {
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
    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val sectorShift = buf.getShort(0x1E).toInt() and 0xFFFF
    val miniSectorShift = buf.getShort(0x20).toInt() and 0xFFFF
    val sectorSize = 1 shl sectorShift.coerceIn(9, 12)
    val miniSectorSize = 1 shl miniSectorShift.coerceIn(6, 9)

    val firstDirSector = buf.getInt(0x30)
    val miniStreamCutoff = buf.getInt(0x38)
    val firstMiniFatSector = buf.getInt(0x3C)
    val firstDifatSector = buf.getInt(0x44)
    val difatSectorCount = buf.getInt(0x48)

    // Build FAT sector index list (first 109 in header at offset 0x4C)
    val fatSectorIds = mutableListOf<Int>()
    for (i in 0 until 109) {
      val sid = buf.getInt(0x4C + i * 4)
      if (sid >= 0) fatSectorIds.add(sid)
    }
    var difatSec = firstDifatSector
    var difatLoops = 0
    while (difatSec >= 0 && difatLoops < difatSectorCount + 16) {
      difatLoops++
      val off = sectorOffset(difatSec, sectorSize)
      if (off + sectorSize > data.size) break
      val entriesPerSec = (sectorSize / 4) - 1
      for (i in 0 until entriesPerSec) {
        val sid = buf.getInt(off + i * 4)
        if (sid >= 0) fatSectorIds.add(sid)
      }
      difatSec = buf.getInt(off + entriesPerSec * 4)
    }

    val intsPerFat = sectorSize / 4
    val fat = IntArray(fatSectorIds.size * intsPerFat) { END_OF_CHAIN }
    fatSectorIds.forEachIndexed { idx, sid ->
      val off = sectorOffset(sid, sectorSize)
      if (off + sectorSize <= data.size) {
        for (j in 0 until intsPerFat) {
          fat[idx * intsPerFat + j] = buf.getInt(off + j * 4)
        }
      }
    }

    // Read Directory Stream
    val dirBytes = readFatStream(data, fat, firstDirSector, sectorSize, -1)
    val dirEntries = parseDirectoryEntries(dirBytes)
    val rootEntry = dirEntries.firstOrNull { it.type == 5 }

    val miniStreamBytes =
      if (rootEntry != null && rootEntry.startSector >= 0 && rootEntry.streamSize > 0) {
        readFatStream(data, fat, rootEntry.startSector, sectorSize, rootEntry.streamSize.toInt())
      } else {
        ByteArray(0)
      }

    val miniFatBytes =
      if (firstMiniFatSector >= 0) {
        readFatStream(data, fat, firstMiniFatSector, sectorSize, -1)
      } else {
        ByteArray(0)
      }
    val miniFat = IntArray(miniFatBytes.size / 4)
    if (miniFat.isNotEmpty()) {
      ByteBuffer.wrap(miniFatBytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(miniFat)
    }

    fun readEntryStream(entry: DirEntry): ByteArray {
      return if (entry.type != 5 && entry.streamSize < miniStreamCutoff && miniStreamBytes.isNotEmpty()) {
        readMiniStream(miniStreamBytes, miniFat, entry.startSector, miniSectorSize, entry.streamSize.toInt())
      } else {
        readFatStream(data, fat, entry.startSector, sectorSize, entry.streamSize.toInt())
      }
    }

    val wordDocEntry =
      dirEntries.firstOrNull { it.name == "WordDocument" }
        ?: throw IllegalArgumentException("Stream WordDocument tidak ditemukan di file .doc")
    val wordDoc = readEntryStream(wordDocEntry)
    if (wordDoc.size < 512) {
      throw IllegalArgumentException("Header WordDocument tidak lengkap")
    }

    val wBuf = ByteBuffer.wrap(wordDoc).order(ByteOrder.LITTLE_ENDIAN)
    val flags = wBuf.getShort(0x0A).toInt() and 0xFFFF
    val use1Table = (flags and 0x0200) != 0
    val tableName = if (use1Table) "1Table" else "0Table"
    val tableEntry =
      dirEntries.firstOrNull { it.name == tableName }
        ?: dirEntries.firstOrNull { it.name == "1Table" || it.name == "0Table" }

    val ccpText = wBuf.getInt(0x4C)
    val ccpFtn = wBuf.getInt(0x50)
    val ccpHdd = wBuf.getInt(0x54)
    val mainCharLimit = if (ccpText > 0) ccpText else Int.MAX_VALUE

    // Try extracting via Piece Table (fcClx at offset 0x01A2, lcbClx at 0x01A6)
    if (tableEntry != null && wordDoc.size >= 0x01AA) {
      val fcClx = wBuf.getInt(0x01A2)
      val lcbClx = wBuf.getInt(0x01A6)
      if (fcClx >= 0 && lcbClx > 0) {
        val tableStream = readEntryStream(tableEntry)
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

  private fun sectorOffset(sectorId: Int, sectorSize: Int): Int = (sectorId + 1) * sectorSize

  private fun readFatStream(
    data: ByteArray,
    fat: IntArray,
    startSector: Int,
    sectorSize: Int,
    expectedSize: Int,
  ): ByteArray {
    if (startSector < 0) return ByteArray(0)
    val chunks = mutableListOf<ByteArray>()
    var current = startSector
    var totalRead = 0
    val visited = HashSet<Int>()
    while (current >= 0 && current < fat.size && visited.add(current)) {
      val off = sectorOffset(current, sectorSize)
      if (off >= data.size) break
      val len = min(sectorSize, data.size - off)
      chunks.add(data.copyOfRange(off, off + len))
      totalRead += len
      if (expectedSize in 1..totalRead) break
      current = fat[current]
    }
    val outSize = if (expectedSize > 0) min(expectedSize, totalRead) else totalRead
    val out = ByteArray(outSize)
    var pos = 0
    for (chunk in chunks) {
      val toCopy = min(chunk.size, outSize - pos)
      if (toCopy <= 0) break
      System.arraycopy(chunk, 0, out, pos, toCopy)
      pos += toCopy
    }
    return out
  }

  private fun readMiniStream(
    miniStream: ByteArray,
    miniFat: IntArray,
    startSector: Int,
    miniSectorSize: Int,
    expectedSize: Int,
  ): ByteArray {
    if (startSector < 0 || expectedSize <= 0) return ByteArray(0)
    val out = ByteArray(expectedSize)
    var current = startSector
    var pos = 0
    val visited = HashSet<Int>()
    while (current >= 0 && current < miniFat.size && pos < expectedSize && visited.add(current)) {
      val off = current * miniSectorSize
      if (off >= miniStream.size) break
      val toCopy = min(miniSectorSize, min(expectedSize - pos, miniStream.size - off))
      System.arraycopy(miniStream, off, out, pos, toCopy)
      pos += toCopy
      current = miniFat[current]
    }
    return out
  }

  private fun parseDirectoryEntries(dirBytes: ByteArray): List<DirEntry> {
    val entries = mutableListOf<DirEntry>()
    val count = dirBytes.size / 128
    val buf = ByteBuffer.wrap(dirBytes).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until count) {
      val base = i * 128
      val type = dirBytes[base + 66].toInt() and 0xFF
      if (type == 0) continue
      val nameLen = (buf.getShort(base + 64).toInt() and 0xFFFF).coerceIn(0, 64)
      val nameBytes = if (nameLen >= 2) nameLen - 2 else 0
      val name = String(dirBytes, base, nameBytes, Charsets.UTF_16LE)
      val startSector = buf.getInt(base + 116)
      val sizeLow = buf.getInt(base + 120).toLong() and 0xFFFFFFFFL
      entries.add(DirEntry(name, type, startSector, sizeLow))
    }
    return entries
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
