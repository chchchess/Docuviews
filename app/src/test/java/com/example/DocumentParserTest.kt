package com.example

import com.example.data.parser.DocBinaryParser
import com.example.data.parser.DocxParser
import com.example.data.parser.PdfTextExtractor
import com.example.data.parser.XlsxParser
import com.example.domain.model.DocBlock
import com.example.domain.model.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentParserTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun `pdf text extractor extracts literal and TJ text from page stream`() {
    val contentStream = "BT\n/F1 12 Tf\n72 720 Td\n(Halo PDF) Tj\n0 -20 Td\n[(Baris) -250 (Kedua)] TJ\nET"
    val pdf =
      """
      %PDF-1.4
      1 0 obj
      << /Type /Catalog /Pages 2 0 R >>
      endobj
      2 0 obj
      << /Type /Pages /Kids [3 0 R] /Count 1 >>
      endobj
      3 0 obj
      << /Type /Page /Parent 2 0 R /Contents 4 0 R >>
      endobj
      4 0 obj
      << /Length ${contentStream.length} >>
      stream
      $contentStream
      endstream
      endobj
      %%EOF
      """.trimIndent().toByteArray(Charsets.ISO_8859_1)

    val extracted = PdfTextExtractor.extractFromBytes(pdf, 0)
    assertTrue(extracted.contains("Halo PDF"))
    assertTrue(extracted.contains("Baris Kedua"))
  }

  @Test
  fun `docx parser extracts headings, styled paragraphs, and tables`() {
    val file = tempFolder.newFile("sample.docx")
    writeZip(
      file,
      mapOf(
        "word/document.xml" to
          """
          <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
          <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
            <w:body>
              <w:p>
                <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                <w:r><w:t>Laporan Bulanan</w:t></w:r>
              </w:p>
              <w:p>
                <w:r><w:rPr><w:b/></w:rPr><w:t>Penting: </w:t></w:r>
                <w:r><w:t>Semua target tercapai.</w:t></w:r>
              </w:p>
              <w:tbl>
                <w:tr>
                  <w:tc><w:p><w:r><w:t>Item</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>Jumlah</w:t></w:r></w:p></w:tc>
                </w:tr>
                <w:tr>
                  <w:tc><w:p><w:r><w:t>Buku</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>25</w:t></w:r></w:p></w:tc>
                </w:tr>
              </w:tbl>
            </w:body>
          </w:document>
          """.trimIndent(),
      ),
    )

    val parsed = DocxParser.parse(file)
    assertEquals(DocumentFormat.DOCX, parsed.format)
    assertEquals(3, parsed.blocks.size)

    val heading = parsed.blocks[0] as DocBlock.Heading
    assertEquals(1, heading.level)
    assertEquals("Laporan Bulanan", heading.spans.joinToString("") { it.text })

    val para = parsed.blocks[1] as DocBlock.Paragraph
    assertTrue(para.spans[0].bold)
    assertEquals("Penting: ", para.spans[0].text)

    val table = parsed.blocks[2] as DocBlock.Table
    assertEquals(listOf(listOf("Item", "Jumlah"), listOf("Buku", "25")), table.rows)
  }

  @Test
  fun `xlsx parser extracts sheets, shared strings, numbers, and dates`() {
    val file = tempFolder.newFile("rekap.xlsx")
    writeZip(
      file,
      mapOf(
        "xl/workbook.xml" to
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
            <sheets>
              <sheet name="Penjualan" sheetId="1" r:id="rId1"/>
            </sheets>
          </workbook>
          """.trimIndent(),
        "xl/_rels/workbook.xml.rels" to
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
          </Relationships>
          """.trimIndent(),
        "xl/sharedStrings.xml" to
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <si><t>Produk</t></si>
            <si><t>Total</t></si>
            <si><t>Kertas A4</t></si>
          </sst>
          """.trimIndent(),
        "xl/styles.xml" to
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <fonts count="2"><font/><font><b/></font></fonts>
            <cellXfs count="3">
              <xf numFmtId="0" fontId="0"/>
              <xf numFmtId="0" fontId="1"/>
              <xf numFmtId="14" fontId="0"/>
            </cellXfs>
          </styleSheet>
          """.trimIndent(),
        "xl/worksheets/sheet1.xml" to
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <sheetData>
              <row r="1">
                <c r="A1" t="s" s="1"><v>0</v></c>
                <c r="B1" t="s" s="1"><v>1</v></c>
                <c r="C1" t="inlineStr" s="1"><is><t>Tanggal</t></is></c>
              </row>
              <row r="2">
                <c r="A2" t="s"><v>2</v></c>
                <c r="B2"><v>1500</v></c>
                <c r="C2" s="2"><v>45658</v></c>
              </row>
            </sheetData>
          </worksheet>
          """.trimIndent(),
      ),
    )

    val parsed = XlsxParser.parse(file)
    assertEquals(1, parsed.sheets.size)
    val sheet = parsed.sheets[0]
    assertEquals("Penjualan", sheet.name)
    assertEquals(2, sheet.rows.size)
    assertEquals(3, sheet.columnCount)
    assertEquals("Produk", sheet.rows[0][0].displayValue)
    assertTrue(sheet.rows[0][0].isBold)
    assertEquals("Tanggal", sheet.rows[0][2].displayValue)
    assertEquals("Kertas A4", sheet.rows[1][0].displayValue)
    assertEquals("1500", sheet.rows[1][1].displayValue)
    assertTrue(sheet.rows[1][1].isNumeric)
    assertEquals("01/01/2025", sheet.rows[1][2].displayValue)
  }

  @Test
  fun `doc binary parser handles rtf disguised as doc`() {
    val file = tempFolder.newFile("surat.doc")
    file.writeText("{\\rtf1\\ansi{\\fonttbl{\\f0 Arial;}}Halo Dunia\\par Baris Kedua}")

    val parsed = DocBinaryParser.parse(file)
    assertEquals(DocumentFormat.DOC, parsed.format)
    assertTrue(parsed.isLegacyNotice)
    assertEquals(2, parsed.blocks.size)
    val p1 = parsed.blocks[0] as DocBlock.Paragraph
    val p2 = parsed.blocks[1] as DocBlock.Paragraph
    assertEquals("Halo Dunia", p1.spans.first().text)
    assertEquals("Baris Kedua", p2.spans.first().text)
  }

  private fun writeZip(file: File, entries: Map<String, String>) {
    ZipOutputStream(file.outputStream()).use { zos ->
      entries.forEach { (name, content) ->
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
      }
    }
  }
}
