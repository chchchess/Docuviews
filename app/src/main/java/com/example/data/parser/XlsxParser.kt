package com.example.data.parser

import com.example.domain.model.ParsedDocument
import com.example.domain.model.SpreadsheetCell
import com.example.domain.model.SpreadsheetSheet
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

object XlsxParser {

  private data class SheetRef(val name: String, val path: String)

  private data class StyleInfo(
    val boldFontIndices: Set<Int>,
    val boldStyleIndices: Set<Int>,
    val dateStyleIndices: Set<Int>,
  )

  private fun newSaxParser() =
    SAXParserFactory.newInstance().apply { isNamespaceAware = false }.newSAXParser()

  fun parse(file: File, displayName: String = file.name): ParsedDocument.Spreadsheet {
    val sheets = mutableListOf<SpreadsheetSheet>()
    ZipFile(file).use { zip ->
      val sharedStrings = parseSharedStrings(zip)
      val styleInfo = parseStyles(zip)
      val sheetRefs = parseWorkbookSheets(zip)

      if (sheetRefs.isEmpty()) {
        val fallbackEntries =
          zip.entries().toList()
            .filter { !it.isDirectory && it.name.startsWith("xl/worksheets/") && it.name.endsWith(".xml") }
            .sortedBy { it.name }
        fallbackEntries.forEachIndexed { index, entry ->
          sheets.add(parseWorksheet(zip, entry.name, "Sheet ${index + 1}", sharedStrings, styleInfo))
        }
      } else {
        for (ref in sheetRefs) {
          if (zip.getEntry(ref.path) != null) {
            sheets.add(parseWorksheet(zip, ref.path, ref.name, sharedStrings, styleInfo))
          }
        }
      }
    }

    if (sheets.isEmpty()) {
      sheets.add(SpreadsheetSheet(name = "Sheet1", rows = emptyList(), columnCount = 0))
    }

    return ParsedDocument.Spreadsheet(
      file = file,
      fileName = displayName,
      sheets = sheets,
    )
  }

  private fun parseWorkbookSheets(zip: ZipFile): List<SheetRef> {
    val workbookEntry = zip.getEntry("xl/workbook.xml") ?: return emptyList()
    val relsMap = mutableMapOf<String, String>()
    zip.getEntry("xl/_rels/workbook.xml.rels")?.let { relsEntry ->
      zip.getInputStream(relsEntry).use { stream ->
        newSaxParser().parse(
          stream,
          object : DefaultHandler() {
            override fun startElement(uri: String?, lName: String?, qName: String, attrs: Attributes) {
              if (localName(qName) == "Relationship") {
                val id = attrs.getValue("Id")
                val target = attrs.getValue("Target")
                if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                  val cleanTarget =
                    when {
                      target.startsWith("/xl/") -> target.removePrefix("/")
                      target.startsWith("/") -> target.removePrefix("/")
                      target.startsWith("xl/") -> target
                      else -> "xl/${target.removePrefix("./")}"
                    }
                  relsMap[id] = cleanTarget
                }
              }
            }
          },
        )
      }
    }

    val result = mutableListOf<SheetRef>()
    zip.getInputStream(workbookEntry).use { stream ->
      newSaxParser().parse(
        stream,
        object : DefaultHandler() {
          override fun startElement(uri: String?, lName: String?, qName: String, attrs: Attributes) {
            if (localName(qName) == "sheet") {
              val name = getAttr(attrs, "name") ?: "Sheet"
              val rId = getAttr(attrs, "id")
              val targetPath = rId?.let { relsMap[it] }
              if (targetPath != null) {
                result.add(SheetRef(name = name, path = targetPath))
              }
            }
          }
        },
      )
    }
    return result
  }

  private fun parseSharedStrings(zip: ZipFile): List<String> {
    val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
    val strings = mutableListOf<String>()
    zip.getInputStream(entry).use { stream ->
      newSaxParser().parse(
        stream,
        object : DefaultHandler() {
          var inSi = false
          var inText = false
          val sb = StringBuilder()

          override fun startElement(uri: String?, lName: String?, qName: String, attrs: Attributes) {
            when (localName(qName)) {
              "si" -> {
                inSi = true
                sb.setLength(0)
              }
              "t" -> if (inSi) inText = true
            }
          }

          override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText && length > 0) sb.append(ch, start, length)
          }

          override fun endElement(uri: String?, lName: String?, qName: String) {
            when (localName(qName)) {
              "t" -> inText = false
              "si" -> {
                strings.add(sb.toString())
                inSi = false
              }
            }
          }
        },
      )
    }
    return strings
  }

  private fun parseStyles(zip: ZipFile): StyleInfo {
    val entry = zip.getEntry("xl/styles.xml") ?: return StyleInfo(emptySet(), emptySet(), emptySet())
    val customDateFmtIds = mutableSetOf<Int>()
    val boldFontIndices = mutableSetOf<Int>()
    val boldStyleIndices = mutableSetOf<Int>()
    val dateStyleIndices = mutableSetOf<Int>()

    zip.getInputStream(entry).use { stream ->
      newSaxParser().parse(
        stream,
        object : DefaultHandler() {
          var inFonts = false
          var fontIndex = -1
          var inCellXfs = false
          var xfIndex = -1

          override fun startElement(uri: String?, lName: String?, qName: String, attrs: Attributes) {
            when (localName(qName)) {
              "numFmt" -> {
                val id = getAttr(attrs, "numFmtId")?.toIntOrNull()
                val code = getAttr(attrs, "formatCode")?.lowercase() ?: ""
                if (id != null && isDateFormatCode(code)) {
                  customDateFmtIds.add(id)
                }
              }
              "fonts" -> {
                inFonts = true
                fontIndex = -1
              }
              "font" -> if (inFonts) fontIndex++
              "b" -> if (inFonts && fontIndex >= 0) {
                val v = getAttr(attrs, "val")
                if (v == null || (v != "0" && !v.equals("false", ignoreCase = true))) {
                  boldFontIndices.add(fontIndex)
                }
              }
              "cellXfs" -> {
                inCellXfs = true
                xfIndex = -1
              }
              "xf" -> if (inCellXfs) {
                xfIndex++
                val fId = getAttr(attrs, "fontId")?.toIntOrNull()
                val numFmtId = getAttr(attrs, "numFmtId")?.toIntOrNull()
                if (fId != null && boldFontIndices.contains(fId)) {
                  boldStyleIndices.add(xfIndex)
                }
                if (numFmtId != null && (isBuiltinDateFmt(numFmtId) || customDateFmtIds.contains(numFmtId))) {
                  dateStyleIndices.add(xfIndex)
                }
              }
            }
          }

          override fun endElement(uri: String?, lName: String?, qName: String) {
            when (localName(qName)) {
              "fonts" -> inFonts = false
              "cellXfs" -> inCellXfs = false
            }
          }
        },
      )
    }
    return StyleInfo(boldFontIndices, boldStyleIndices, dateStyleIndices)
  }

  private fun parseWorksheet(
    zip: ZipFile,
    path: String,
    sheetName: String,
    sharedStrings: List<String>,
    styleInfo: StyleInfo,
  ): SpreadsheetSheet {
    val entry = zip.getEntry(path)
      ?: return SpreadsheetSheet(sheetName, emptyList(), 0)

    val rowMap = linkedMapOf<Int, MutableMap<Int, SpreadsheetCell>>()
    var maxColIndex = -1
    var maxRowIndex = -1

    zip.getInputStream(entry).use { stream ->
      newSaxParser().parse(
        stream,
        object : DefaultHandler() {
          var currentRow = 0
          var currentCellRef = ""
          var currentCellType = ""
          var currentCellStyle = -1
          var inValue = false
          var inInlineString = false
          var inInlineText = false
          val valueBuilder = StringBuilder()

          override fun startElement(uri: String?, lName: String?, qName: String, attrs: Attributes) {
            when (localName(qName)) {
              "row" -> {
                val r = getAttr(attrs, "r")?.toIntOrNull()
                currentRow = if (r != null && r > 0) r - 1 else (maxRowIndex + 1)
              }
              "c" -> {
                currentCellRef = getAttr(attrs, "r") ?: ""
                currentCellType = getAttr(attrs, "t") ?: ""
                currentCellStyle = getAttr(attrs, "s")?.toIntOrNull() ?: -1
                valueBuilder.setLength(0)
              }
              "v" -> inValue = true
              "is" -> inInlineString = true
              "t" -> if (inInlineString) inInlineText = true
            }
          }

          override fun characters(ch: CharArray, start: Int, length: Int) {
            if ((inValue || inInlineText) && length > 0) {
              valueBuilder.append(ch, start, length)
            }
          }

          override fun endElement(uri: String?, lName: String?, qName: String) {
            when (localName(qName)) {
              "v" -> inValue = false
              "t" -> inInlineText = false
              "is" -> inInlineString = false
              "c" -> {
                val raw = valueBuilder.toString()
                if (raw.isNotEmpty() || currentCellRef.isNotEmpty()) {
                  val coords = parseCellReference(currentCellRef, currentRow)
                  val rowIdx = coords.first
                  val colIdx = coords.second
                  val formatted = formatCellValue(raw, currentCellType, currentCellStyle, sharedStrings, styleInfo)
                  if (formatted.isNotEmpty()) {
                    val isBold = styleInfo.boldStyleIndices.contains(currentCellStyle) || rowIdx == 0
                    val isNumeric =
                      currentCellType.isEmpty() &&
                        !styleInfo.dateStyleIndices.contains(currentCellStyle) &&
                        raw.toDoubleOrNull() != null
                    val cell =
                      SpreadsheetCell(
                        rowIndex = rowIdx,
                        colIndex = colIdx,
                        reference = currentCellRef.ifEmpty { "${columnIndexToName(colIdx)}${rowIdx + 1}" },
                        displayValue = formatted,
                        isBold = isBold,
                        isNumeric = isNumeric,
                      )
                    rowMap.getOrPut(rowIdx) { mutableMapOf() }[colIdx] = cell
                    if (colIdx > maxColIndex) maxColIndex = colIdx
                    if (rowIdx > maxRowIndex) maxRowIndex = rowIdx
                  }
                }
              }
            }
          }
        },
      )
    }

    if (maxRowIndex < 0 || maxColIndex < 0) {
      return SpreadsheetSheet(sheetName, emptyList(), 0)
    }

    val safeRows = (maxRowIndex + 1).coerceAtMost(5000)
    val safeCols = (maxColIndex + 1).coerceAtMost(200)
    val denseRows = ArrayList<List<SpreadsheetCell>>(safeRows)
    for (r in 0 until safeRows) {
      val sparse = rowMap[r]
      val rowCells = ArrayList<SpreadsheetCell>(safeCols)
      for (c in 0 until safeCols) {
        rowCells.add(
          sparse?.get(c)
            ?: SpreadsheetCell(
              rowIndex = r,
              colIndex = c,
              reference = "${columnIndexToName(c)}${r + 1}",
              displayValue = "",
            )
        )
      }
      denseRows.add(rowCells)
    }

    return SpreadsheetSheet(
      name = sheetName,
      rows = denseRows,
      columnCount = safeCols,
    )
  }

  private fun formatCellValue(
    raw: String,
    type: String,
    styleIdx: Int,
    sharedStrings: List<String>,
    styleInfo: StyleInfo,
  ): String {
    if (raw.isEmpty()) return ""
    return when (type) {
      "s" -> {
        val idx = raw.toIntOrNull()
        if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else raw
      }
      "inlineStr", "str", "e" -> raw
      "b" -> if (raw == "1" || raw.equals("true", ignoreCase = true)) "TRUE" else "FALSE"
      else -> {
        val num = raw.toDoubleOrNull() ?: return raw
        if (styleInfo.dateStyleIndices.contains(styleIdx) && num >= 1.0 && num < 2958466.0) {
          formatExcelDate(num)
        } else {
          formatNumber(num)
        }
      }
    }
  }

  private fun formatNumber(num: Double): String {
    val longVal = num.toLong()
    return if (num == longVal.toDouble() && num in -1e15..1e15) {
      longVal.toString()
    } else {
      DecimalFormat("#.######").format(num)
    }
  }

  private fun formatExcelDate(serial: Double): String {
    val wholeDays = serial.toInt()
    val adjustedDays = if (wholeDays > 60) wholeDays - 1 else wholeDays
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.set(1899, Calendar.DECEMBER, 31, 0, 0, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.add(Calendar.DAY_OF_YEAR, adjustedDays)
    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(cal.time)
  }

  private fun isBuiltinDateFmt(id: Int): Boolean = id in 14..22 || id in 45..47

  private fun isDateFormatCode(code: String): Boolean {
    if (code.isBlank()) return false
    val cleaned = code.replace(Regex("\"[^\"]*\"|\\[[^\\]]*\\]"), "")
    return cleaned.contains('y') || cleaned.contains('d') || (cleaned.contains('m') && !cleaned.contains("#"))
  }

  private fun parseCellReference(ref: String, fallbackRow: Int): Pair<Int, Int> {
    if (ref.isBlank()) return fallbackRow to 0
    var col = 0
    var row = 0
    for (ch in ref) {
      when {
        ch.isLetter() -> col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
        ch.isDigit() -> row = row * 10 + (ch - '0')
      }
    }
    val rowIdx = if (row > 0) row - 1 else fallbackRow
    val colIdx = if (col > 0) col - 1 else 0
    return rowIdx to colIdx
  }

  fun columnIndexToName(index: Int): String {
    var n = index + 1
    val sb = StringBuilder()
    while (n > 0) {
      val rem = (n - 1) % 26
      sb.append(('A'.code + rem).toChar())
      n = (n - 1) / 26
    }
    return sb.reverse().toString()
  }

  private fun localName(rawName: String?): String {
    if (rawName == null) return ""
    val idx = rawName.indexOf(':')
    return if (idx >= 0) rawName.substring(idx + 1) else rawName
  }

  private fun getAttr(attrs: Attributes, localAttr: String): String? {
    for (i in 0 until attrs.length) {
      if (localName(attrs.getQName(i)) == localAttr) {
        return attrs.getValue(i)
      }
    }
    return null
  }
}
