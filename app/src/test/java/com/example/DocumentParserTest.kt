package com.example

import com.example.data.parser.DocBinaryParser
import com.example.data.parser.DocxParser
import com.example.data.parser.OfficeDecryptor
import com.example.data.parser.PdfDecryptor
import com.example.data.parser.PdfTextExtractor
import com.example.data.parser.PdfTextWord
import com.example.data.parser.XlsxParser
import com.example.domain.model.DocBlock
import com.example.domain.model.DocumentFormat
import com.example.ui.viewer.buildPdfTextSegments
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

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

    val words = PdfTextExtractor.extractPageWordsFromBytes(pdf, 0)
    assertEquals(listOf("Halo", "PDF", "Baris", "Kedua"), words.map { it.text })
    words.forEach { w ->
      assertTrue(w.left in 0f..1f)
      assertTrue(w.right in 0f..1f)
      assertTrue(w.top in 0f..1f)
      assertTrue(w.bottom in 0f..1f)
      assertTrue(w.left < w.right)
      assertTrue(w.top < w.bottom)
    }
    // Line 1 ("Halo") should be above Line 2 ("Baris") and left of "PDF"
    assertTrue(words[0].right < words[1].right)
    assertTrue(words[0].top < words[2].top)
  }

  @Test
  fun `pdf selection segments merge same-line words and split wide column gaps`() {
    val words =
      listOf(
        PdfTextWord("Nama", 0.10f, 0.10f, 0.18f, 0.12f),
        PdfTextWord("Lengkap", 0.19f, 0.10f, 0.29f, 0.12f),
        PdfTextWord("Nilai", 0.65f, 0.10f, 0.72f, 0.12f),
        PdfTextWord("Budi", 0.10f, 0.15f, 0.17f, 0.17f),
      )

    val segments = buildPdfTextSegments(words)
    assertEquals(listOf("Nama Lengkap", "Nilai", "Budi"), segments.map { it.text })
    assertEquals(0.10f, segments[0].left, 0.0001f)
    assertEquals(0.29f, segments[0].right, 0.0001f)
  }

  @Test
  fun `pdf selection segments join overlapping hyphenated tokens without dropping edges`() {
    val words =
      listOf(
        PdfTextWord("5224-1200", 0.30f, 0.25f, 0.46f, 0.268f),
        PdfTextWord("-1086-2691", 0.45f, 0.25f, 0.63f, 0.268f),
      )

    val segments = buildPdfTextSegments(words)
    assertEquals(listOf("5224-1200-1086-2691"), segments.map { it.text })
    assertEquals(0.30f, segments[0].left, 0.0001f)
    assertEquals(0.63f, segments[0].right, 0.0001f)
  }

  @Test
  fun `pdf selection segments preserve staggered multi-column lines in reading order`() {
    val words =
      listOf(
        PdfTextWord("Missing", 0.08f, 0.310f, 0.14f, 0.322f),
        PdfTextWord("Data", 0.145f, 0.310f, 0.19f, 0.322f),
        PdfTextWord("Prior", 0.76f, 0.324f, 0.80f, 0.336f),
        PdfTextWord("Year", 0.805f, 0.324f, 0.84f, 0.336f),
        PdfTextWord("Data", 0.845f, 0.324f, 0.88f, 0.336f),
        PdfTextWord("Transmittal", 0.08f, 0.295f, 0.16f, 0.307f),
        PdfTextWord("Letter", 0.165f, 0.295f, 0.22f, 0.307f),
      )

    val segments = buildPdfTextSegments(words)
    assertEquals(
      listOf("Transmittal Letter", "Missing Data", "Prior Year Data"),
      segments.map { it.text },
    )
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

  @Test
  fun `pdf decryptor rejects wrong password and unlocks RC4-128 PDF`() {
    val padding =
      byteArrayOf(
        0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
        0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
        0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(),
        0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A,
      )
    val userPwd = "rahasia123".toByteArray(Charsets.ISO_8859_1)
    val paddedUser = ByteArray(32).apply {
      System.arraycopy(userPwd, 0, this, 0, userPwd.size)
      System.arraycopy(padding, 0, this, userPwd.size, 32 - userPwd.size)
    }
    val md5 = MessageDigest.getInstance("MD5")
    var oKey = md5.digest(paddedUser)
    repeat(50) { oKey = md5.digest(oKey) }
    var oBytes = paddedUser.copyOf()
    for (i in 0..19) {
      val k = ByteArray(16) { idx -> (oKey[idx].toInt() xor i).toByte() }
      oBytes = Cipher.getInstance("RC4").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "RC4")) }.doFinal(oBytes)
    }

    val id0 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
    val pValue = -4
    md5.reset()
    md5.update(paddedUser)
    md5.update(oBytes)
    md5.update(byteArrayOf(pValue.toByte(), (pValue shr 8).toByte(), (pValue shr 16).toByte(), (pValue shr 24).toByte()))
    md5.update(id0)
    var fileKey = md5.digest()
    repeat(50) {
      md5.reset()
      md5.update(fileKey, 0, 16)
      fileKey = md5.digest()
    }

    md5.reset()
    md5.update(padding)
    md5.update(id0)
    var uHash = md5.digest()
    for (i in 0..19) {
      val k = ByteArray(16) { idx -> (fileKey[idx].toInt() xor i).toByte() }
      uHash = Cipher.getInstance("RC4").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "RC4")) }.doFinal(uHash)
    }
    val uBytes = uHash + ByteArray(16)

    // Encrypt content stream for object 4 gen 0
    val plainStream = "BT\n/F1 12 Tf\n72 720 Td\n(Dokumen Rahasia) Tj\nET".toByteArray(Charsets.ISO_8859_1)
    md5.reset()
    md5.update(fileKey)
    md5.update(byteArrayOf(4, 0, 0, 0, 0))
    val obj4Key = md5.digest()
    val encStream =
      Cipher.getInstance("RC4").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(obj4Key, "RC4")) }.doFinal(plainStream)

    fun toHex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    val out = ByteArrayOutputStream()
    out.write(
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
      << /Length ${encStream.size} >>
      stream
      """.trimIndent().toByteArray(Charsets.ISO_8859_1)
    )
    out.write('\n'.code)
    out.write(encStream)
    out.write(
      """

      endstream
      endobj
      5 0 obj
      << /Filter /Standard /V 2 /R 3 /Length 128 /P $pValue /O <${toHex(oBytes)}> /U <${toHex(uBytes)}> >>
      endobj
      trailer
      << /Size 6 /Root 1 0 R /Encrypt 5 0 R /ID [<${toHex(id0)}> <${toHex(id0)}>] >>
      %%EOF
      """.trimIndent().toByteArray(Charsets.ISO_8859_1)
    )

    val encryptedPdf = out.toByteArray()
    assertTrue(PdfDecryptor.isEncryptedPdfBytes(encryptedPdf))
    assertNull(PdfDecryptor.decryptBytes(encryptedPdf, "password_salah"))

    val unlocked = PdfDecryptor.decryptBytes(encryptedPdf, "rahasia123")
    assertNotNull(unlocked)
    val extracted = PdfTextExtractor.extractFromBytes(unlocked!!, 0)
    assertTrue(extracted.contains("Dokumen Rahasia"))
  }

  @Test
  fun `office decryptor rejects wrong password and unlocks standard encrypted package`() {
    val originalZip = "PK\u0003\u0004hello-office-zip".toByteArray(Charsets.UTF_8)
    val salt = ByteArray(16) { (it + 1).toByte() }
    val password = "buka"

    val sha1 = MessageDigest.getInstance("SHA-1")
    sha1.update(salt)
    sha1.update(password.toByteArray(Charsets.UTF_16LE))
    var h = sha1.digest()
    val iterBuf = ByteArray(4)
    for (i in 0 until 50_000) {
      iterBuf[0] = (i and 0xFF).toByte()
      iterBuf[1] = ((i ushr 8) and 0xFF).toByte()
      iterBuf[2] = ((i ushr 16) and 0xFF).toByte()
      iterBuf[3] = ((i ushr 24) and 0xFF).toByte()
      sha1.reset()
      sha1.update(iterBuf)
      sha1.update(h)
      h = sha1.digest()
    }
    iterBuf.fill(0)
    sha1.reset()
    sha1.update(h)
    sha1.update(iterBuf)
    val hBlock = sha1.digest()
    val x1Buf = ByteArray(64) { 0x36 }
    for (i in hBlock.indices) x1Buf[i] = (x1Buf[i].toInt() xor (hBlock[i].toInt() and 0xFF)).toByte()
    val derivedKey = MessageDigest.getInstance("SHA-1").digest(x1Buf).copyOfRange(0, 16)

    val encCipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
      init(Cipher.ENCRYPT_MODE, SecretKeySpec(derivedKey, "AES"))
    }
    val verifier = ByteArray(16) { 0x42 }
    val encVerifier = encCipher.doFinal(verifier)
    val verifierHashPadded = MessageDigest.getInstance("SHA-1").digest(verifier).copyOf(32)
    val encVerifierHash = encCipher.doFinal(verifierHashPadded)

    val encInfo = ByteBuffer.allocate(12 + 32 + 72).order(ByteOrder.LITTLE_ENDIAN).apply {
      putShort(3) // versionMajor
      putShort(2) // versionMinor
      putInt(0) // flags
      putInt(32) // headerSize
      // header (32 bytes)
      putInt(0); putInt(0); putInt(0x660E); putInt(0x8004); putInt(128); putInt(0); putInt(0); putInt(0)
      // verifier
      putInt(16)
      put(salt)
      put(encVerifier)
      putInt(20)
      put(encVerifierHash)
    }.array()

    val paddedZip = originalZip.copyOf(((originalZip.size + 15) / 16) * 16)
    val encPayload = encCipher.doFinal(paddedZip)
    val encPkg = ByteBuffer.allocate(8 + encPayload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
      putLong(originalZip.size.toLong())
      put(encPayload)
    }.array()

    val ole2Bytes = buildMinimalOle2WithTwoStreams("EncryptionInfo", encInfo, "EncryptedPackage", encPkg)
    assertNull(OfficeDecryptor.decryptBytes(ole2Bytes, "salah"))
    val unlocked = OfficeDecryptor.decryptBytes(ole2Bytes, password)
    assertNotNull(unlocked)
    assertArrayEquals(originalZip, unlocked)
  }

  private fun buildMinimalOle2WithTwoStreams(
    name1: String,
    data1: ByteArray,
    name2: String,
    data2: ByteArray,
  ): ByteArray {
    val sectorSize = 512
    val secCount1 = ((data1.size + sectorSize - 1) / sectorSize).coerceAtLeast(1)
    val secCount2 = ((data2.size + sectorSize - 1) / sectorSize).coerceAtLeast(1)
    // Sector layout: 0 = FAT, 1 = Dir, 2..(2+secCount1-1) = stream1, next.. = stream2
    val s1Start = 2
    val s2Start = s1Start + secCount1
    val totalSectors = s2Start + secCount2
    val out = ByteArray((totalSectors + 1) * sectorSize)
    val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

    // Header
    val magic = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte())
    System.arraycopy(magic, 0, out, 0, 8)
    buf.putShort(0x18, 0x003E)
    buf.putShort(0x1A, 0x0003)
    buf.putShort(0x1C, 0xFFFE.toShort())
    buf.putShort(0x1E, 9) // 512
    buf.putShort(0x20, 6) // 64
    buf.putInt(0x2C, 1) // 1 FAT sector
    buf.putInt(0x30, 1) // firstDirSector = 1
    buf.putInt(0x38, 0) // miniStreamCutoff = 0 (always use FAT)
    buf.putInt(0x3C, -2)
    buf.putInt(0x44, -2)
    for (i in 0 until 109) buf.putInt(0x4C + i * 4, if (i == 0) 0 else -1)

    // FAT at sector 0 (offset 512)
    val fatOff = 512
    for (i in 0 until 128) buf.putInt(fatOff + i * 4, -1)
    buf.putInt(fatOff + 0 * 4, -3) // FATSECT
    buf.putInt(fatOff + 1 * 4, -2) // Dir END
    for (i in 0 until secCount1) {
      val sid = s1Start + i
      buf.putInt(fatOff + sid * 4, if (i == secCount1 - 1) -2 else sid + 1)
    }
    for (i in 0 until secCount2) {
      val sid = s2Start + i
      buf.putInt(fatOff + sid * 4, if (i == secCount2 - 1) -2 else sid + 1)
    }

    // Directory at sector 1 (offset 1024)
    fun writeDir(slot: Int, name: String, type: Int, startSec: Int, size: Int) {
      val base = 1024 + slot * 128
      val nb = name.toByteArray(Charsets.UTF_16LE)
      System.arraycopy(nb, 0, out, base, nb.size)
      buf.putShort(base + 64, (nb.size + 2).toShort())
      out[base + 66] = type.toByte()
      buf.putInt(base + 116, startSec)
      buf.putInt(base + 120, size)
    }
    writeDir(0, "Root Entry", 5, -2, 0)
    writeDir(1, name1, 2, s1Start, data1.size)
    writeDir(2, name2, 2, s2Start, data2.size)

    System.arraycopy(data1, 0, out, (s1Start + 1) * sectorSize, data1.size)
    System.arraycopy(data2, 0, out, (s2Start + 1) * sectorSize, data2.size)
    return out
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
