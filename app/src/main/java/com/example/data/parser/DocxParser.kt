package com.example.data.parser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.text.style.TextAlign
import com.example.domain.model.DocBlock
import com.example.domain.model.DocumentFormat
import com.example.domain.model.ParsedDocument
import com.example.domain.model.StyledSpan
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

object DocxParser {

  private fun newSaxParser() =
    SAXParserFactory.newInstance().apply { isNamespaceAware = false }.newSAXParser()

  fun parse(file: File, displayName: String = file.name): ParsedDocument.RichText {
    val blocks = mutableListOf<DocBlock>()
    ZipFile(file).use { zip ->
      val relsMap = parseRelationships(zip)
      val docEntry =
        zip.getEntry("word/document.xml")
          ?: throw IllegalArgumentException("File DOCX tidak valid: word/document.xml tidak ditemukan")

      zip.getInputStream(docEntry).use { stream ->
        parseDocumentXml(stream, zip, relsMap, blocks)
      }
    }
    return ParsedDocument.RichText(
      file = file,
      fileName = displayName,
      format = DocumentFormat.DOCX,
      blocks = blocks,
    )
  }

  private fun parseRelationships(zip: ZipFile): Map<String, String> {
    val relsEntry = zip.getEntry("word/_rels/document.xml.rels") ?: return emptyMap()
    val map = mutableMapOf<String, String>()
    zip.getInputStream(relsEntry).use { stream ->
      newSaxParser().parse(
        stream,
        object : DefaultHandler() {
          override fun startElement(uri: String?, lName: String?, qName: String, attrs: Attributes) {
            if (localName(qName) == "Relationship") {
              val id = attrs.getValue("Id")
              val target = attrs.getValue("Target")
              if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                val normalized =
                  if (target.startsWith("/")) target.removePrefix("/") else "word/${target.removePrefix("./")}"
                map[id] = normalized
              }
            }
          }
        },
      )
    }
    return map
  }

  private fun parseDocumentXml(
    stream: InputStream,
    zip: ZipFile,
    relsMap: Map<String, String>,
    outBlocks: MutableList<DocBlock>,
  ) {
    newSaxParser().parse(
      stream,
      object : DefaultHandler() {
        var inBody = false
        var tableDepth = 0
        var currentTableRows = mutableListOf<List<String>>()
        var currentRowCells = mutableListOf<String>()
        var inTableCell = false
        var currentCellBuilder = StringBuilder()

        var inParagraph = false
        var paragraphStyle: String? = null
        var paragraphAlign = TextAlign.Start
        var isListItem = false
        var currentSpans = mutableListOf<StyledSpan>()

        var inRun = false
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var colorRgb: Int? = null
        var fontSizeSp: Float? = null
        var inTextTag = false
        val textBuffer = StringBuilder()

        override fun startElement(uri: String?, lName: String?, qName: String, attrs: Attributes) {
          when (localName(qName)) {
            "body" -> inBody = true
            "tbl" -> if (inBody) {
              if (tableDepth == 0) currentTableRows = mutableListOf()
              tableDepth++
            }
            "tr" -> if (tableDepth == 1) currentRowCells = mutableListOf()
            "tc" -> if (tableDepth == 1) {
              inTableCell = true
              currentCellBuilder = StringBuilder()
            }
            "p" -> if (inBody && tableDepth == 0) {
              inParagraph = true
              paragraphStyle = null
              paragraphAlign = TextAlign.Start
              isListItem = false
              currentSpans = mutableListOf()
            } else if (inTableCell && currentCellBuilder.isNotEmpty()) {
              currentCellBuilder.append('\n')
            }
            "pStyle" -> if (inParagraph) {
              paragraphStyle = getAttr(attrs, "val")
            }
            "jc" -> if (inParagraph) {
              paragraphAlign = when (getAttr(attrs, "val")?.lowercase()) {
                "center" -> TextAlign.Center
                "right", "end" -> TextAlign.End
                "both", "distribute" -> TextAlign.Justify
                else -> TextAlign.Start
              }
            }
            "numPr" -> if (inParagraph) {
              isListItem = true
            }
            "r" -> if (inParagraph) {
              inRun = true
              bold = false
              italic = false
              underline = false
              strike = false
              colorRgb = null
              fontSizeSp = null
            }
            "b" -> if (inRun) {
              val v = getAttr(attrs, "val")
              bold = v == null || (v != "0" && !v.equals("false", ignoreCase = true))
            }
            "i" -> if (inRun) {
              val v = getAttr(attrs, "val")
              italic = v == null || (v != "0" && !v.equals("false", ignoreCase = true))
            }
            "u" -> if (inRun) {
              val v = getAttr(attrs, "val")
              underline = v == null || !v.equals("none", ignoreCase = true)
            }
            "strike", "dstrike" -> if (inRun) {
              val v = getAttr(attrs, "val")
              strike = v == null || (v != "0" && !v.equals("false", ignoreCase = true))
            }
            "color" -> if (inRun) {
              val hex = getAttr(attrs, "val")
              if (!hex.isNullOrBlank() && !hex.equals("auto", ignoreCase = true) && hex.length == 6) {
                colorRgb = hex.toIntOrNull(16)?.or(0xFF000000.toInt())
              }
            }
            "sz" -> if (inRun) {
              val halfPts = getAttr(attrs, "val")?.toFloatOrNull()
              if (halfPts != null && halfPts > 0f) {
                fontSizeSp = (halfPts / 2f).coerceIn(10f, 36f)
              }
            }
            "t", "delText" -> if (inParagraph || inTableCell) {
              inTextTag = true
              textBuffer.setLength(0)
            }
            "tab" -> {
              if (inTableCell) {
                currentCellBuilder.append("  ")
              } else if (inParagraph) {
                currentSpans.add(StyledSpan("    ", bold, italic, underline, strike, colorRgb, fontSizeSp))
              }
            }
            "br" -> {
              val type = getAttr(attrs, "type")
              if (type == "page" && tableDepth == 0) {
                if (currentSpans.isNotEmpty()) {
                  flushParagraph(paragraphStyle, paragraphAlign, isListItem, currentSpans, outBlocks)
                  currentSpans = mutableListOf()
                }
                outBlocks.add(DocBlock.PageBreak)
              } else if (inTableCell) {
                currentCellBuilder.append('\n')
              } else if (inParagraph) {
                currentSpans.add(StyledSpan("\n", bold, italic, underline, strike, colorRgb, fontSizeSp))
              }
            }
            "blip" -> if (tableDepth == 0) {
              val embedId = getAttr(attrs, "embed") ?: getAttr(attrs, "link")
              val mediaPath = embedId?.let { relsMap[it] }
              if (mediaPath != null) {
                decodeZipBitmap(zip, mediaPath)?.let { bmp ->
                  if (currentSpans.isNotEmpty()) {
                    flushParagraph(paragraphStyle, paragraphAlign, isListItem, currentSpans, outBlocks)
                    currentSpans = mutableListOf()
                  }
                  val ratio = if (bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else 1.5f
                  outBlocks.add(DocBlock.ImageBlock(bmp, ratio.coerceIn(0.4f, 4f)))
                }
              }
            }
          }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
          if (inTextTag && length > 0) {
            textBuffer.append(ch, start, length)
          }
        }

        override fun endElement(uri: String?, lName: String?, qName: String) {
          when (localName(qName)) {
            "body" -> inBody = false
            "t", "delText" -> {
              if (inTextTag) {
                val text = textBuffer.toString()
                if (text.isNotEmpty()) {
                  if (inTableCell) {
                    currentCellBuilder.append(text)
                  } else if (inParagraph) {
                    currentSpans.add(
                      StyledSpan(
                        text = text,
                        bold = bold,
                        italic = italic,
                        underline = underline,
                        strikethrough = strike,
                        colorRgb = colorRgb,
                        fontSizeSp = fontSizeSp,
                      )
                    )
                  }
                }
                inTextTag = false
                textBuffer.setLength(0)
              }
            }
            "r" -> inRun = false
            "p" -> if (inParagraph && tableDepth == 0) {
              flushParagraph(paragraphStyle, paragraphAlign, isListItem, currentSpans, outBlocks)
              inParagraph = false
              currentSpans = mutableListOf()
            }
            "tc" -> if (tableDepth == 1 && inTableCell) {
              currentRowCells.add(currentCellBuilder.toString().trim())
              inTableCell = false
            }
            "tr" -> if (tableDepth == 1) {
              if (currentRowCells.isNotEmpty()) {
                currentTableRows.add(currentRowCells.toList())
              }
            }
            "tbl" -> if (inBody && tableDepth > 0) {
              tableDepth--
              if (tableDepth == 0 && currentTableRows.isNotEmpty()) {
                outBlocks.add(DocBlock.Table(currentTableRows.toList()))
                currentTableRows = mutableListOf()
              }
            }
          }
        }
      },
    )
  }

  private fun flushParagraph(
    style: String?,
    alignment: TextAlign,
    isListItem: Boolean,
    spans: List<StyledSpan>,
    outBlocks: MutableList<DocBlock>,
  ) {
    if (spans.isEmpty()) return
    val fullText = spans.joinToString("") { it.text }
    if (fullText.isBlank()) return

    val headingLevel = parseHeadingLevel(style)
    if (headingLevel != null) {
      outBlocks.add(DocBlock.Heading(level = headingLevel, spans = mergeAdjacentSpans(spans), alignment = alignment))
    } else {
      val listFlag = isListItem || style?.contains("List", ignoreCase = true) == true
      outBlocks.add(
        DocBlock.Paragraph(
          spans = mergeAdjacentSpans(spans),
          alignment = alignment,
          isListItem = listFlag,
          listMarker = if (listFlag) "•" else null,
        )
      )
    }
  }

  private fun parseHeadingLevel(style: String?): Int? {
    if (style.isNullOrBlank()) return null
    val lower = style.lowercase()
    return when {
      lower == "title" || lower == "judul" -> 1
      lower == "subtitle" || lower == "subjudul" -> 2
      lower.startsWith("heading") || lower.startsWith("judul") -> {
        lower.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 4) ?: 2
      }
      else -> null
    }
  }

  private fun mergeAdjacentSpans(spans: List<StyledSpan>): List<StyledSpan> {
    if (spans.size <= 1) return spans
    val merged = mutableListOf<StyledSpan>()
    for (span in spans) {
      val last = merged.lastOrNull()
      if (
        last != null &&
          last.bold == span.bold &&
          last.italic == span.italic &&
          last.underline == span.underline &&
          last.strikethrough == span.strikethrough &&
          last.colorRgb == span.colorRgb &&
          last.fontSizeSp == span.fontSizeSp
      ) {
        merged[merged.lastIndex] = last.copy(text = last.text + span.text)
      } else {
        merged.add(span)
      }
    }
    return merged
  }

  private fun decodeZipBitmap(zip: ZipFile, path: String): Bitmap? {
    val entry = zip.getEntry(path) ?: return null
    return runCatching {
      val bytes = zip.getInputStream(entry).use { it.readBytes() }
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      var sample = 1
      while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1600) {
        sample *= 2
      }
      val opts = BitmapFactory.Options().apply { inSampleSize = sample }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()
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
