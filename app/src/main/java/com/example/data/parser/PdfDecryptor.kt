package com.example.data.parser

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Pure-Kotlin PDF Standard Security Handler decryptor (RC4-40/128, AES-128, AES-256 R5/R6).
 * Used on Android API 24-34 (without PdfRenderer LoadParams) and for text extraction.
 */
object PdfDecryptor {

  private val PDF_PADDING =
    byteArrayOf(
      0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
      0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
      0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(),
      0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A,
    )

  private enum class CryptMethod { NONE, RC4, AES128, AES256 }

  fun isEncryptedPdf(file: File): Boolean {
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
    return isEncryptedPdfBytes(bytes)
  }

  fun isEncryptedPdfBytes(pdfBytes: ByteArray): Boolean {
    if (pdfBytes.size < 32) return false
    val latin = String(pdfBytes, Charsets.ISO_8859_1)
    return findEncryptRefId(latin) != null
  }

  /**
   * Decrypts an encrypted PDF using [password] into a temporary unencrypted PDF file in [cacheDir].
   * Returns null if [password] is incorrect, or throws if the encryption scheme is unsupported.
   */
  fun decryptToTempFile(file: File, password: String, cacheDir: File): File? {
    val bytes = file.readBytes()
    val decrypted = decryptBytes(bytes, password) ?: return null
    val outFile = File.createTempFile("unlocked_pdf_", ".pdf", cacheDir)
    outFile.writeBytes(decrypted)
    return outFile
  }

  internal fun decryptBytes(pdfBytes: ByteArray, password: String): ByteArray? {
    val latin = String(pdfBytes, Charsets.ISO_8859_1)
    val encryptObjId = findEncryptRefId(latin) ?: return pdfBytes
    val rawObjects = parseRawObjects(pdfBytes, latin)
    val encryptObj =
      rawObjects[encryptObjId]
        ?: throw IllegalArgumentException("Objek /Encrypt tidak ditemukan di PDF.")

    val encDict = encryptObj.header
    val v = extractIntValue(encDict, "/V") ?: 0
    val r = extractIntValue(encDict, "/R") ?: 2
    val bitLength =
      extractIntValue(encDict, "/Length")
        ?: when (v) {
          1 -> 40
          5 -> 256
          else -> 128
        }
    val keyLenBytes = (bitLength / 8).coerceIn(5, 32)
    val pValue = extractIntValue(encDict, "/P") ?: -4
    val encryptMetadata = !Regex("/EncryptMetadata\\s+false\\b").containsMatchIn(encDict)

    val oBytes = extractPdfBytesField(encDict, "/O")
      ?: throw IllegalArgumentException("Field /O enkripsi PDF tidak ditemukan.")
    val uBytes = extractPdfBytesField(encDict, "/U")
      ?: throw IllegalArgumentException("Field /U enkripsi PDF tidak ditemukan.")
    val oeBytes = extractPdfBytesField(encDict, "/OE")
    val ueBytes = extractPdfBytesField(encDict, "/UE")
    val id0 = extractFirstIdBytes(latin) ?: ByteArray(0)

    val strMethod = resolveCryptMethod(encDict, "/StrF", v)
    val stmMethod = resolveCryptMethod(encDict, "/StmF", v)

    val fileKey =
      when {
        r <= 4 ->
          deriveAndVerifyKeyR2to4(
            password = password,
            r = r,
            keyLen = keyLenBytes,
            oBytes = oBytes,
            uBytes = uBytes,
            pValue = pValue,
            id0 = id0,
            encryptMetadata = encryptMetadata,
          )
        r == 5 || r == 6 ->
          deriveAndVerifyKeyR5to6(
            password = password,
            r = r,
            oBytes = oBytes,
            uBytes = uBytes,
            oeBytes = oeBytes,
            ueBytes = ueBytes,
          )
        else -> throw IllegalStateException("Enkripsi PDF revisi $r belum didukung.")
      } ?: return null

    // Decrypt all non-encrypt, non-XRef objects
    val decryptedObjects = LinkedHashMap<Int, Pair<String, ByteArray?>>()
    for ((objId, obj) in rawObjects) {
      if (objId == encryptObjId) continue
      if (obj.header.contains(Regex("/Type\\s*/XRef\\b"))) continue

      val isMetadata = obj.header.contains(Regex("/Type\\s*/Metadata\\b"))
      val skipStreamCrypt = isMetadata && !encryptMetadata

      val decryptedHeader =
        if (strMethod == CryptMethod.NONE) obj.header
        else decryptStringsInDict(obj.header, objId, obj.gen, fileKey, strMethod)

      val rawStream =
        if (obj.streamStart >= 0 && obj.streamEnd >= obj.streamStart) {
          pdfBytes.copyOfRange(obj.streamStart, obj.streamEnd)
        } else {
          null
        }

      val decryptedStream =
        if (rawStream == null) {
          null
        } else if (skipStreamCrypt || stmMethod == CryptMethod.NONE) {
          rawStream
        } else {
          decryptPayload(rawStream, objId, obj.gen, fileKey, stmMethod)
        }

      // If this was a compressed /ObjStm, expand its inner objects as top-level objects
      if (decryptedHeader.contains(Regex("/Type\\s*/ObjStm\\b")) && decryptedStream != null) {
        val expanded = expandObjStm(decryptedHeader, decryptedStream)
        if (expanded.isNotEmpty()) {
          for ((innerId, innerBody) in expanded) {
            decryptedObjects[innerId] = innerBody to null
          }
          continue
        }
      }

      val cleanedHeader =
        if (decryptedStream != null) {
          updateStreamLengthInHeader(decryptedHeader, decryptedStream.size)
        } else {
          decryptedHeader
        }
      decryptedObjects[objId] = cleanedHeader to decryptedStream
    }

    val rootId =
      findRootRefId(latin)
        ?: decryptedObjects.entries.firstOrNull { (_, pair) ->
          pair.first.contains(Regex("/Type\\s*/Catalog\\b"))
        }?.key
        ?: throw IllegalStateException("Objek /Catalog tidak ditemukan.")

    return rebuildCleanPdf(decryptedObjects, rootId)
  }

  private data class RawPdfObj(
    val id: Int,
    val gen: Int,
    val header: String,
    val streamStart: Int,
    val streamEnd: Int,
  )

  private fun parseRawObjects(rawBytes: ByteArray, latin: String): Map<Int, RawPdfObj> {
    val result = LinkedHashMap<Int, RawPdfObj>()
    val objRegex = Regex("(\\d+)\\s+(\\d+)\\s+obj\\b")
    for (match in objRegex.findAll(latin)) {
      val objId = match.groupValues[1].toIntOrNull() ?: continue
      val gen = match.groupValues[2].toIntOrNull() ?: 0
      val bodyStart = match.range.last + 1
      val endObjIdx = latin.indexOf("endobj", bodyStart)
      if (endObjIdx < 0) continue

      val streamKeywordIdx = latin.indexOf("stream", bodyStart)
      if (streamKeywordIdx in bodyStart until endObjIdx) {
        val header = latin.substring(bodyStart, streamKeywordIdx).trim()
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
            dataStart
          }
        result[objId] = RawPdfObj(objId, gen, header, dataStart, dataEnd)
      } else {
        val header = latin.substring(bodyStart, endObjIdx).trim()
        result[objId] = RawPdfObj(objId, gen, header, -1, -1)
      }
    }
    return result
  }

  private fun findEncryptRefId(latin: String): Int? {
    val match = Regex("/Encrypt\\s+(\\d+)\\s+\\d+\\s+R").find(latin)
    return match?.groupValues?.get(1)?.toIntOrNull()
  }

  private fun findRootRefId(latin: String): Int? {
    val match = Regex("/Root\\s+(\\d+)\\s+\\d+\\s+R").find(latin)
    return match?.groupValues?.get(1)?.toIntOrNull()
  }

  private fun extractIntValue(dict: String, key: String): Int? {
    val match = Regex("$key\\s+(-?\\d+)").find(dict) ?: return null
    return match.groupValues[1].toIntOrNull()
  }

  private fun resolveCryptMethod(encDict: String, key: String, v: Int): CryptMethod {
    if (v == 5) return CryptMethod.AES256
    if (v in 1..2) return CryptMethod.RC4
    if (v == 4) {
      val cfNameMatch = Regex("$key\\s*/(\\S+)").find(encDict)
      val cfName = cfNameMatch?.groupValues?.get(1) ?: "StdCF"
      if (cfName == "Identity") return CryptMethod.NONE
      val cfm = Regex("/CFM\\s*/(\\S+)").find(encDict)?.groupValues?.get(1)
      return when (cfm) {
        "AESV2" -> CryptMethod.AES128
        "AESV3" -> CryptMethod.AES256
        "None" -> CryptMethod.NONE
        else -> CryptMethod.RC4
      }
    }
    return CryptMethod.RC4
  }

  private fun extractFirstIdBytes(latin: String): ByteArray? {
    val idMatch = Regex("/ID\\s*\\[\\s*([^\\]]+)]").find(latin) ?: return null
    val inner = idMatch.groupValues[1].trim()
    return if (inner.startsWith("<")) {
      val end = inner.indexOf('>')
      if (end > 0) hexToBytes(inner.substring(1, end)) else null
    } else if (inner.startsWith("(")) {
      readLiteralBytes(inner, 0).first
    } else {
      null
    }
  }

  private fun extractPdfBytesField(dict: String, key: String): ByteArray? {
    val keyIdx = Regex("$key\\s*([(<])").find(dict) ?: return null
    val valStart = keyIdx.range.last
    return if (dict[valStart] == '<') {
      val endHex = dict.indexOf('>', valStart + 1)
      if (endHex > valStart) hexToBytes(dict.substring(valStart + 1, endHex)) else null
    } else {
      readLiteralBytes(dict, valStart).first
    }
  }

  private fun readLiteralBytes(text: String, startIdx: Int): Pair<ByteArray, Int> {
    val out = ByteArrayOutputStream()
    var i = startIdx + 1
    var depth = 1
    while (i < text.length && depth > 0) {
      val ch = text[i]
      when (ch) {
        '\\' -> {
          i++
          if (i >= text.length) break
          when (val esc = text[i]) {
            'n' -> out.write('\n'.code)
            'r' -> out.write('\r'.code)
            't' -> out.write('\t'.code)
            'b' -> out.write('\b'.code)
            'f' -> out.write(0x0C)
            '\\', '(', ')' -> out.write(esc.code)
            in '0'..'7' -> {
              var octEnd = i + 1
              while (octEnd < text.length && octEnd < i + 3 && text[octEnd] in '0'..'7') octEnd++
              val code = text.substring(i, octEnd).toIntOrNull(8) ?: 0
              out.write(code and 0xFF)
              i = octEnd - 1
            }
            '\r' -> {
              if (i + 1 < text.length && text[i + 1] == '\n') i++
            }
            '\n' -> Unit
            else -> out.write(esc.code and 0xFF)
          }
        }
        '(' -> {
          depth++
          out.write('('.code)
        }
        ')' -> {
          depth--
          if (depth > 0) out.write(')'.code)
        }
        else -> out.write(ch.code and 0xFF)
      }
      i++
    }
    return out.toByteArray() to i
  }

  private fun deriveAndVerifyKeyR2to4(
    password: String,
    r: Int,
    keyLen: Int,
    oBytes: ByteArray,
    uBytes: ByteArray,
    pValue: Int,
    id0: ByteArray,
    encryptMetadata: Boolean,
  ): ByteArray? {
    // 1. Try as user password
    val userPadded = padPasswordR2to4(password.toByteArray(Charsets.ISO_8859_1))
    val candidateUserKey = computeKeyR2to4(userPadded, r, keyLen, oBytes, pValue, id0, encryptMetadata)
    if (verifyUserKeyR2to4(candidateUserKey, r, uBytes, id0)) {
      return candidateUserKey
    }

    // 2. Try as owner password (Algorithm 3 + Algorithm 7)
    if (oBytes.size >= 32) {
      val ownerMd5 = MessageDigest.getInstance("MD5")
      var ownerKey = ownerMd5.digest(userPadded)
      if (r >= 3) {
        repeat(50) { ownerKey = ownerMd5.digest(ownerKey) }
      }
      val ownerRc4Key = ownerKey.copyOfRange(0, keyLen)
      var decryptedUserPwd = oBytes.copyOfRange(0, 32)
      if (r == 2) {
        decryptedUserPwd = rc4(ownerRc4Key, decryptedUserPwd)
      } else {
        for (iter in 19 downTo 0) {
          val iterKey = ByteArray(ownerRc4Key.size) { k -> (ownerRc4Key[k].toInt() xor iter).toByte() }
          decryptedUserPwd = rc4(iterKey, decryptedUserPwd)
        }
      }
      val keyFromOwner =
        computeKeyR2to4(decryptedUserPwd, r, keyLen, oBytes, pValue, id0, encryptMetadata)
      if (verifyUserKeyR2to4(keyFromOwner, r, uBytes, id0)) {
        return keyFromOwner
      }
    }

    return null
  }

  private fun padPasswordR2to4(raw: ByteArray): ByteArray {
    val out = ByteArray(32)
    val copyLen = min(raw.size, 32)
    System.arraycopy(raw, 0, out, 0, copyLen)
    if (copyLen < 32) {
      System.arraycopy(PDF_PADDING, 0, out, copyLen, 32 - copyLen)
    }
    return out
  }

  private fun computeKeyR2to4(
    paddedPwd: ByteArray,
    r: Int,
    keyLen: Int,
    oBytes: ByteArray,
    pValue: Int,
    id0: ByteArray,
    encryptMetadata: Boolean,
  ): ByteArray {
    val md5 = MessageDigest.getInstance("MD5")
    md5.update(paddedPwd)
    md5.update(oBytes, 0, min(32, oBytes.size))
    val pBytes =
      byteArrayOf(
        (pValue and 0xFF).toByte(),
        ((pValue ushr 8) and 0xFF).toByte(),
        ((pValue ushr 16) and 0xFF).toByte(),
        ((pValue ushr 24) and 0xFF).toByte(),
      )
    md5.update(pBytes)
    md5.update(id0)
    if (r >= 4 && !encryptMetadata) {
      md5.update(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
    }
    var digest = md5.digest()
    if (r >= 3) {
      repeat(50) {
        md5.reset()
        md5.update(digest, 0, keyLen)
        digest = md5.digest()
      }
    }
    return digest.copyOfRange(0, keyLen)
  }

  private fun verifyUserKeyR2to4(
    key: ByteArray,
    r: Int,
    uBytes: ByteArray,
    id0: ByteArray,
  ): Boolean {
    if (uBytes.size < 16) return false
    if (r == 2) {
      val expected = rc4(key, PDF_PADDING)
      return uBytes.size >= 32 && expected.contentEquals(uBytes.copyOfRange(0, 32))
    }
    val md5 = MessageDigest.getInstance("MD5")
    md5.update(PDF_PADDING)
    md5.update(id0)
    var hash = md5.digest()
    for (iter in 0..19) {
      val iterKey = ByteArray(key.size) { k -> (key[k].toInt() xor iter).toByte() }
      hash = rc4(iterKey, hash)
    }
    for (i in 0 until 16) {
      if (hash[i] != uBytes[i]) return false
    }
    return true
  }

  private fun deriveAndVerifyKeyR5to6(
    password: String,
    r: Int,
    oBytes: ByteArray,
    uBytes: ByteArray,
    oeBytes: ByteArray?,
    ueBytes: ByteArray?,
  ): ByteArray? {
    val pwdUtf8 = password.toByteArray(Charsets.UTF_8).let {
      if (it.size > 127) it.copyOfRange(0, 127) else it
    }
    val zeroIv = ByteArray(16)

    // 1. Check user password
    if (uBytes.size >= 48 && ueBytes != null && ueBytes.size >= 32) {
      val uValidationSalt = uBytes.copyOfRange(32, 40)
      val uKeySalt = uBytes.copyOfRange(40, 48)
      val uHash =
        if (r == 5) {
          MessageDigest.getInstance("SHA-256").apply {
            update(pwdUtf8)
            update(uValidationSalt)
          }.digest()
        } else {
          computeHashR6(pwdUtf8, uValidationSalt, null)
        }
      if (uHash.contentEquals(uBytes.copyOfRange(0, 32))) {
        val unwrapKey =
          if (r == 5) {
            MessageDigest.getInstance("SHA-256").apply {
              update(pwdUtf8)
              update(uKeySalt)
            }.digest()
          } else {
            computeHashR6(pwdUtf8, uKeySalt, null)
          }
        return aesCbcNoPadding(Cipher.DECRYPT_MODE, unwrapKey, zeroIv, ueBytes.copyOfRange(0, 32))
      }
    }

    // 2. Check owner password
    if (oBytes.size >= 48 && uBytes.size >= 48 && oeBytes != null && oeBytes.size >= 32) {
      val u48 = uBytes.copyOfRange(0, 48)
      val oValidationSalt = oBytes.copyOfRange(32, 40)
      val oKeySalt = oBytes.copyOfRange(40, 48)
      val oHash =
        if (r == 5) {
          MessageDigest.getInstance("SHA-256").apply {
            update(pwdUtf8)
            update(oValidationSalt)
            update(u48)
          }.digest()
        } else {
          computeHashR6(pwdUtf8, oValidationSalt, u48)
        }
      if (oHash.contentEquals(oBytes.copyOfRange(0, 32))) {
        val unwrapKey =
          if (r == 5) {
            MessageDigest.getInstance("SHA-256").apply {
              update(pwdUtf8)
              update(oKeySalt)
              update(u48)
            }.digest()
          } else {
            computeHashR6(pwdUtf8, oKeySalt, u48)
          }
        return aesCbcNoPadding(Cipher.DECRYPT_MODE, unwrapKey, zeroIv, oeBytes.copyOfRange(0, 32))
      }
    }

    return null
  }

  // PDF 2.0 Algorithm 2.B (hardened hash for R=6)
  private fun computeHashR6(pwd: ByteArray, salt8: ByteArray, u48: ByteArray?): ByteArray {
    var k =
      MessageDigest.getInstance("SHA-256").run {
        update(pwd)
        update(salt8)
        if (u48 != null) update(u48)
        digest()
      }
    var round = 0
    while (round < 64 || (k.last().toInt() and 0xFF) > round - 32) {
      val unitLen = pwd.size + k.size + (u48?.size ?: 0)
      val k1 = ByteArray(unitLen * 64)
      var pos = 0
      repeat(64) {
        System.arraycopy(pwd, 0, k1, pos, pwd.size)
        pos += pwd.size
        System.arraycopy(k, 0, k1, pos, k.size)
        pos += k.size
        if (u48 != null) {
          System.arraycopy(u48, 0, k1, pos, u48.size)
          pos += u48.size
        }
      }
      val e =
        aesCbcNoPadding(
          mode = Cipher.ENCRYPT_MODE,
          key = k.copyOfRange(0, 16),
          iv = k.copyOfRange(16, 32),
          data = k1,
        )
      var sumMod3 = 0
      for (j in 0 until 16) {
        sumMod3 = (sumMod3 + (e[j].toInt() and 0xFF)) % 3
      }
      val hashAlg =
        when (sumMod3) {
          0 -> "SHA-256"
          1 -> "SHA-384"
          else -> "SHA-512"
        }
      k = MessageDigest.getInstance(hashAlg).digest(e)
      round++
    }
    return k.copyOfRange(0, 32)
  }

  private fun decryptPayload(
    data: ByteArray,
    objId: Int,
    gen: Int,
    fileKey: ByteArray,
    method: CryptMethod,
  ): ByteArray {
    if (data.isEmpty() || method == CryptMethod.NONE) return data
    if (method == CryptMethod.AES256) {
      if (data.size <= 16) return ByteArray(0)
      val iv = data.copyOfRange(0, 16)
      val cipherBytes = data.copyOfRange(16, data.size)
      val paddedLen = (cipherBytes.size / 16) * 16
      if (paddedLen == 0) return ByteArray(0)
      val dec = aesCbcNoPadding(Cipher.DECRYPT_MODE, fileKey, iv, cipherBytes.copyOfRange(0, paddedLen))
      return stripPkcs5Padding(dec)
    }

    val md5 = MessageDigest.getInstance("MD5")
    md5.update(fileKey)
    md5.update(byteArrayOf((objId and 0xFF).toByte(), ((objId ushr 8) and 0xFF).toByte(), ((objId ushr 16) and 0xFF).toByte()))
    md5.update(byteArrayOf((gen and 0xFF).toByte(), ((gen ushr 8) and 0xFF).toByte()))
    if (method == CryptMethod.AES128) {
      md5.update(byteArrayOf(0x73, 0x41, 0x6C, 0x54)) // "sAlT"
    }
    val objKey = md5.digest().copyOfRange(0, min(fileKey.size + 5, 16))

    return if (method == CryptMethod.RC4) {
      rc4(objKey, data)
    } else {
      if (data.size <= 16) return ByteArray(0)
      val iv = data.copyOfRange(0, 16)
      val cipherBytes = data.copyOfRange(16, data.size)
      val paddedLen = (cipherBytes.size / 16) * 16
      if (paddedLen == 0) return ByteArray(0)
      val dec = aesCbcNoPadding(Cipher.DECRYPT_MODE, objKey, iv, cipherBytes.copyOfRange(0, paddedLen))
      stripPkcs5Padding(dec)
    }
  }

  private fun decryptStringsInDict(
    dict: String,
    objId: Int,
    gen: Int,
    fileKey: ByteArray,
    method: CryptMethod,
  ): String {
    val sb = StringBuilder(dict.length)
    var i = 0
    val len = dict.length
    while (i < len) {
      val ch = dict[i]
      when {
        ch == '(' -> {
          val (rawBytes, nextIdx) = readLiteralBytes(dict, i)
          val plain = decryptPayload(rawBytes, objId, gen, fileKey, method)
          sb.append('<').append(bytesToHex(plain)).append('>')
          i = nextIdx
        }
        ch == '<' && i + 1 < len && dict[i + 1] == '<' -> {
          sb.append("<<")
          i += 2
        }
        ch == '<' -> {
          val endHex = dict.indexOf('>', i + 1)
          if (endHex > i) {
            val rawBytes = hexToBytes(dict.substring(i + 1, endHex))
            val plain = decryptPayload(rawBytes, objId, gen, fileKey, method)
            sb.append('<').append(bytesToHex(plain)).append('>')
            i = endHex + 1
          } else {
            sb.append(ch)
            i++
          }
        }
        else -> {
          sb.append(ch)
          i++
        }
      }
    }
    return sb.toString()
  }

  private fun expandObjStm(header: String, rawStream: ByteArray): List<Pair<Int, String>> {
    val firstOffset = Regex("/First\\s+(\\d+)").find(header)?.groupValues?.get(1)?.toIntOrNull() ?: return emptyList()
    val count = Regex("/N\\s+(\\d+)").find(header)?.groupValues?.get(1)?.toIntOrNull() ?: return emptyList()
    val decompressed =
      if (header.contains("/FlateDecode")) {
        inflateBytes(rawStream) ?: return emptyList()
      } else {
        rawStream
      }
    val text = String(decompressed, Charsets.ISO_8859_1)
    if (firstOffset <= 0 || firstOffset >= text.length) return emptyList()
    val tokens = text.substring(0, firstOffset).trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
    val entries = mutableListOf<Pair<Int, Int>>()
    var idx = 0
    while (idx + 1 < tokens.size && entries.size < count) {
      entries.add(tokens[idx] to (firstOffset + tokens[idx + 1]))
      idx += 2
    }
    return entries.mapIndexedNotNull { k, (innerId, start) ->
      val end = if (k + 1 < entries.size) entries[k + 1].second else text.length
      if (start in 0 until end && end <= text.length) {
        innerId to text.substring(start, end).trim()
      } else {
        null
      }
    }
  }

  private fun inflateBytes(compressed: ByteArray): ByteArray? {
    return runCatching {
      val inflater = java.util.zip.Inflater()
      try {
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
          val n = inflater.inflate(buf)
          if (n == 0) {
            if (inflater.needsInput() || inflater.needsDictionary()) break
          } else {
            out.write(buf, 0, n)
          }
        }
        out.toByteArray()
      } finally {
        inflater.end()
      }
    }.getOrNull()
  }

  private fun updateStreamLengthInHeader(header: String, newLen: Int): String {
    return if (Regex("/Length\\s+\\d+(?:\\s+\\d+\\s+R)?").containsMatchIn(header)) {
      header.replaceFirst(Regex("/Length\\s+\\d+(?:\\s+\\d+\\s+R)?"), "/Length $newLen")
    } else {
      header
    }
  }

  private fun rebuildCleanPdf(
    objects: Map<Int, Pair<String, ByteArray?>>,
    rootId: Int,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("%PDF-1.5\n".toByteArray(Charsets.ISO_8859_1))

    val sortedIds = objects.keys.sorted()
    val maxId = (sortedIds.maxOrNull() ?: 0).coerceAtLeast(rootId)
    val offsets = LongArray(maxId + 1) { -1L }

    for (id in sortedIds) {
      val (header, streamBytes) = objects[id] ?: continue
      offsets[id] = out.size().toLong()
      out.write("$id 0 obj\n$header\n".toByteArray(Charsets.ISO_8859_1))
      if (streamBytes != null) {
        out.write("stream\n".toByteArray(Charsets.ISO_8859_1))
        out.write(streamBytes)
        out.write("\nendstream\n".toByteArray(Charsets.ISO_8859_1))
      }
      out.write("endobj\n".toByteArray(Charsets.ISO_8859_1))
    }

    val xrefOffset = out.size().toLong()
    val size = maxId + 1
    out.write("xref\n0 $size\n".toByteArray(Charsets.ISO_8859_1))
    out.write("0000000000 65535 f \n".toByteArray(Charsets.ISO_8859_1))
    for (id in 1 until size) {
      val off = offsets[id]
      if (off >= 0L) {
        out.write(String.format("%010d 00000 n \n", off).toByteArray(Charsets.ISO_8859_1))
      } else {
        out.write("0000000000 65535 f \n".toByteArray(Charsets.ISO_8859_1))
      }
    }
    out.write(
      "trailer\n<< /Size $size /Root $rootId 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n"
        .toByteArray(Charsets.ISO_8859_1)
    )
    return out.toByteArray()
  }

  private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("RC4")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "RC4"))
    return cipher.doFinal(data)
  }

  private fun aesCbcNoPadding(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(data)
  }

  private fun stripPkcs5Padding(data: ByteArray): ByteArray {
    if (data.isEmpty()) return data
    val pad = data.last().toInt() and 0xFF
    if (pad in 1..16 && pad <= data.size) {
      var valid = true
      for (i in data.size - pad until data.size) {
        if ((data[i].toInt() and 0xFF) != pad) {
          valid = false
          break
        }
      }
      if (valid) return data.copyOfRange(0, data.size - pad)
    }
    return data
  }

  private fun hexToBytes(raw: String): ByteArray {
    val clean = raw.filter { !it.isWhitespace() }
    val even = if (clean.length % 2 == 1) "${clean}0" else clean
    return ByteArray(even.length / 2) { idx ->
      even.substring(idx * 2, idx * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
    }
  }

  private fun bytesToHex(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
      val v = b.toInt() and 0xFF
      sb.append("0123456789ABCDEF"[v ushr 4])
      sb.append("0123456789ABCDEF"[v and 0x0F])
    }
    return sb.toString()
  }
}
