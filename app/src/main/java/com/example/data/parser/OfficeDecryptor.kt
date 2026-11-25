package com.example.data.parser

import android.util.Base64
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.min

/**
 * Decrypts password-protected OOXML (.docx / .xlsx) documents saved as
 * OLE2 EncryptedPackage containers (both Standard 2007 and Agile 2010+ encryption).
 */
object OfficeDecryptor {

  private val BLK_VERIFIER_HASH_INPUT =
    byteArrayOf(
      0xFE.toByte(), 0xA7.toByte(), 0xD2.toByte(), 0x76.toByte(),
      0x3B.toByte(), 0x4B.toByte(), 0x9E.toByte(), 0x79.toByte(),
    )
  private val BLK_VERIFIER_HASH_VALUE =
    byteArrayOf(
      0xD7.toByte(), 0xAA.toByte(), 0x0F.toByte(), 0x6D.toByte(),
      0x30.toByte(), 0x61.toByte(), 0x34.toByte(), 0x4E.toByte(),
    )
  private val BLK_ENCRYPTED_KEY_VALUE =
    byteArrayOf(
      0x14.toByte(), 0x6E.toByte(), 0x0B.toByte(), 0xE7.toByte(),
      0xAB.toByte(), 0xAC.toByte(), 0xD0.toByte(), 0xD6.toByte(),
    )

  fun isEncryptedOoxml(file: File): Boolean {
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
    if (!Ole2CompoundReader.isOle2(bytes)) return false
    val streams = runCatching { Ole2CompoundReader.readStreams(bytes) }.getOrNull() ?: return false
    return streams.containsKey("EncryptionInfo") && streams.containsKey("EncryptedPackage")
  }

  /**
   * Attempts to decrypt an encrypted OOXML file into a temporary ZIP file in [cacheDir].
   * Returns null if the password is incorrect, or throws if the file structure is unsupported.
   */
  fun decryptToTempZip(file: File, password: String, cacheDir: File): File? {
    val bytes = file.readBytes()
    val decryptedBytes = decryptBytes(bytes, password) ?: return null
    val outFile = File.createTempFile("unlocked_", ".zip", cacheDir)
    outFile.writeBytes(decryptedBytes)
    return outFile
  }

  internal fun decryptBytes(ole2Bytes: ByteArray, password: String): ByteArray? {
    if (!Ole2CompoundReader.isOle2(ole2Bytes)) {
      throw IllegalArgumentException("File bukan kontainer OLE2 terenkripsi.")
    }
    val streams = Ole2CompoundReader.readStreams(ole2Bytes)
    val encInfo = streams["EncryptionInfo"]
      ?: throw IllegalArgumentException("Stream EncryptionInfo tidak ditemukan.")
    val encPkg = streams["EncryptedPackage"]
      ?: throw IllegalArgumentException("Stream EncryptedPackage tidak ditemukan.")

    if (encInfo.size < 8 || encPkg.size < 8) {
      throw IllegalArgumentException("Header enkripsi tidak lengkap.")
    }

    val buf = ByteBuffer.wrap(encInfo).order(ByteOrder.LITTLE_ENDIAN)
    val versionMajor = buf.getShort(0).toInt() and 0xFFFF
    val versionMinor = buf.getShort(2).toInt() and 0xFFFF

    return when {
      versionMajor == 4 && versionMinor == 4 -> decryptAgile(encInfo, encPkg, password)
      versionMajor in 2..4 && versionMinor == 2 -> decryptStandard(encInfo, encPkg, password)
      else -> throw IllegalStateException("Metode enkripsi Office v$versionMajor.$versionMinor belum didukung.")
    }
  }

  private fun decryptStandard(
    encInfo: ByteArray,
    encPkg: ByteArray,
    password: String,
  ): ByteArray? {
    val buf = ByteBuffer.wrap(encInfo).order(ByteOrder.LITTLE_ENDIAN)
    val headerSize = buf.getInt(8)
    val headerOff = 12
    val verifierOff = headerOff + headerSize
    if (verifierOff + 40 > encInfo.size) {
      throw IllegalArgumentException("Header enkripsi standar tidak valid.")
    }

    val rawKeyBits = buf.getInt(headerOff + 16)
    val keyBytesLen = (if (rawKeyBits > 0) rawKeyBits else 128) / 8

    val saltSize = buf.getInt(verifierOff).coerceIn(1, 32)
    val salt = encInfo.copyOfRange(verifierOff + 4, verifierOff + 4 + saltSize)
    val encVerifier = encInfo.copyOfRange(verifierOff + 20, verifierOff + 36)
    val verifierHashSize = buf.getInt(verifierOff + 36).coerceIn(16, 32)
    val encVerifierHashStart = verifierOff + 40
    val encVerifierHashEnd = min(encVerifierHashStart + 32, encInfo.size)
    val encVerifierHash = encInfo.copyOfRange(encVerifierHashStart, encVerifierHashEnd)

    val sha1 = MessageDigest.getInstance("SHA-1")
    val pwdBytes = password.toByteArray(Charsets.UTF_16LE)
    sha1.update(salt)
    sha1.update(pwdBytes)
    var h = sha1.digest()

    val iterBuf = ByteArray(4)
    for (i in 0 until 50_000) {
      writeIntLE(i, iterBuf)
      sha1.reset()
      sha1.update(iterBuf)
      sha1.update(h)
      h = sha1.digest()
    }

    writeIntLE(0, iterBuf)
    sha1.reset()
    sha1.update(h)
    sha1.update(iterBuf)
    val hBlock = sha1.digest()

    val x1Buf = ByteArray(64) { 0x36 }
    val x2Buf = ByteArray(64) { 0x5C }
    for (i in hBlock.indices) {
      x1Buf[i] = (x1Buf[i].toInt() xor (hBlock[i].toInt() and 0xFF)).toByte()
      x2Buf[i] = (x2Buf[i].toInt() xor (hBlock[i].toInt() and 0xFF)).toByte()
    }
    val x1 = MessageDigest.getInstance("SHA-1").digest(x1Buf)
    val x2 = MessageDigest.getInstance("SHA-1").digest(x2Buf)
    val keyMaterial = x1 + x2
    val derivedKey = keyMaterial.copyOfRange(0, keyBytesLen)

    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    val keySpec = SecretKeySpec(derivedKey, "AES")
    cipher.init(Cipher.DECRYPT_MODE, keySpec)

    val decVerifier = cipher.doFinal(encVerifier)
    val decVerifierHash = cipher.doFinal(encVerifierHash)
    val actualHash = MessageDigest.getInstance("SHA-1").digest(decVerifier)
    val compareLen = min(verifierHashSize, min(actualHash.size, decVerifierHash.size))
    for (i in 0 until compareLen) {
      if (actualHash[i] != decVerifierHash[i]) return null
    }

    val uncompressedSize = ByteBuffer.wrap(encPkg, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
    val encryptedPayload = encPkg.copyOfRange(8, encPkg.size)
    val paddedLen = (encryptedPayload.size / 16) * 16
    val decryptedPayload = cipher.doFinal(encryptedPayload, 0, paddedLen)
    val outSize = uncompressedSize.coerceIn(0L, decryptedPayload.size.toLong()).toInt()
    return decryptedPayload.copyOfRange(0, outSize)
  }

  private fun decryptAgile(
    encInfo: ByteArray,
    encPkg: ByteArray,
    password: String,
  ): ByteArray? {
    val xmlBytes = encInfo.copyOfRange(8, encInfo.size)
    val doc =
      DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(xmlBytes))

    val keyDataEl =
      findFirstElementByLocalName(doc.documentElement, "keyData")
        ?: throw IllegalArgumentException("Elemen keyData Agile tidak ditemukan.")
    val passwordKeyEl =
      findFirstElementByLocalName(doc.documentElement, "encryptedKey")
        ?: throw IllegalArgumentException("Elemen encryptedKey Agile tidak ditemukan.")

    val dataSalt = decodeBase64(keyDataEl.getAttribute("saltValue"))
    val dataBlockSize = keyDataEl.getAttribute("blockSize").toIntOrNull() ?: 16
    val dataKeyBits = keyDataEl.getAttribute("keyBits").toIntOrNull() ?: 256
    val dataHashAlg = mapHashAlgorithm(keyDataEl.getAttribute("hashAlgorithm"))
    val dataCipherAlg = keyDataEl.getAttribute("cipherAlgorithm").ifEmpty { "AES" }

    val spinCount = passwordKeyEl.getAttribute("spinCount").toIntOrNull() ?: 100_000
    val keySalt = decodeBase64(passwordKeyEl.getAttribute("saltValue"))
    val keyBlockSize = passwordKeyEl.getAttribute("blockSize").toIntOrNull() ?: 16
    val keyBits = passwordKeyEl.getAttribute("keyBits").toIntOrNull() ?: 256
    val keyHashSize = passwordKeyEl.getAttribute("hashSize").toIntOrNull() ?: 64
    val keyHashAlg = mapHashAlgorithm(passwordKeyEl.getAttribute("hashAlgorithm"))
    val encVerifierInput = decodeBase64(passwordKeyEl.getAttribute("encryptedVerifierHashInput"))
    val encVerifierValue = decodeBase64(passwordKeyEl.getAttribute("encryptedVerifierHashValue"))
    val encSecretKeyValue = decodeBase64(passwordKeyEl.getAttribute("encryptedKeyValue"))

    val ivKey = padOrTrim(keySalt, keyBlockSize, 0x00)
    val kVerifierInput =
      deriveAgileKey(password, keySalt, keyHashAlg, spinCount, BLK_VERIFIER_HASH_INPUT, keyBits)
    val kVerifierValue =
      deriveAgileKey(password, keySalt, keyHashAlg, spinCount, BLK_VERIFIER_HASH_VALUE, keyBits)
    val kSecretKey =
      deriveAgileKey(password, keySalt, keyHashAlg, spinCount, BLK_ENCRYPTED_KEY_VALUE, keyBits)

    val decVerifierInput = aesCbcNoPaddingDecrypt(kVerifierInput, ivKey, encVerifierInput)
    val decVerifierValue = aesCbcNoPaddingDecrypt(kVerifierValue, ivKey, encVerifierValue)
    val computedHash = MessageDigest.getInstance(keyHashAlg).digest(decVerifierInput)

    val checkLen = min(keyHashSize, min(computedHash.size, decVerifierValue.size))
    for (i in 0 until checkLen) {
      if (computedHash[i] != decVerifierValue[i]) return null
    }

    val rawSecretKey = aesCbcNoPaddingDecrypt(kSecretKey, ivKey, encSecretKeyValue)
    val secretKey = rawSecretKey.copyOfRange(0, min(dataKeyBits / 8, rawSecretKey.size))

    val uncompressedSize = ByteBuffer.wrap(encPkg, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
    val out = ByteArrayOutputStream(uncompressedSize.coerceIn(0L, 64L * 1024L * 1024L).toInt())
    val md = MessageDigest.getInstance(dataHashAlg)
    val cipher = Cipher.getInstance("$dataCipherAlg/CBC/NoPadding")
    val secretSpec = SecretKeySpec(secretKey, dataCipherAlg)
    val segIdxBuf = ByteArray(4)

    var pos = 8
    var segIdx = 0
    while (pos < encPkg.size && out.size() < uncompressedSize) {
      val end = min(pos + 4096, encPkg.size)
      val segLen = ((end - pos) / dataBlockSize) * dataBlockSize
      if (segLen <= 0) break

      writeIntLE(segIdx, segIdxBuf)
      md.reset()
      md.update(dataSalt)
      md.update(segIdxBuf)
      val segIv = padOrTrim(md.digest(), dataBlockSize, 0x36)

      cipher.init(Cipher.DECRYPT_MODE, secretSpec, IvParameterSpec(segIv))
      val plainSeg = cipher.doFinal(encPkg, pos, segLen)
      val remaining = (uncompressedSize - out.size()).toInt()
      out.write(plainSeg, 0, min(plainSeg.size, remaining))

      pos += 4096
      segIdx++
    }

    return out.toByteArray()
  }

  private fun deriveAgileKey(
    password: String,
    salt: ByteArray,
    hashAlg: String,
    spinCount: Int,
    blockKey: ByteArray,
    keyBits: Int,
  ): ByteArray {
    val md = MessageDigest.getInstance(hashAlg)
    md.update(salt)
    md.update(password.toByteArray(Charsets.UTF_16LE))
    var h = md.digest()

    val iterBuf = ByteArray(4)
    for (i in 0 until spinCount) {
      writeIntLE(i, iterBuf)
      md.reset()
      md.update(iterBuf)
      md.update(h)
      h = md.digest()
    }

    md.reset()
    md.update(h)
    md.update(blockKey)
    val hFinal = md.digest()
    return padOrTrim(hFinal, keyBits / 8, 0x36)
  }

  private fun aesCbcNoPaddingDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(data)
  }

  private fun padOrTrim(bytes: ByteArray, targetLen: Int, padByte: Int): ByteArray {
    if (bytes.size >= targetLen) return bytes.copyOfRange(0, targetLen)
    val out = ByteArray(targetLen) { padByte.toByte() }
    System.arraycopy(bytes, 0, out, 0, bytes.size)
    return out
  }

  private fun writeIntLE(value: Int, out: ByteArray) {
    out[0] = (value and 0xFF).toByte()
    out[1] = ((value ushr 8) and 0xFF).toByte()
    out[2] = ((value ushr 16) and 0xFF).toByte()
    out[3] = ((value ushr 24) and 0xFF).toByte()
  }

  private fun mapHashAlgorithm(name: String): String {
    return when (name.uppercase()) {
      "SHA512", "SHA-512" -> "SHA-512"
      "SHA256", "SHA-256" -> "SHA-256"
      "SHA384", "SHA-384" -> "SHA-384"
      else -> "SHA-1"
    }
  }

  private fun decodeBase64(b64: String): ByteArray {
    val clean = b64.trim()
    return runCatching { Base64.decode(clean, Base64.DEFAULT) }
      .getOrElse { java.util.Base64.getMimeDecoder().decode(clean) }
  }

  private fun findFirstElementByLocalName(root: Element, localName: String): Element? {
    if (root.localName == localName || root.tagName.substringAfter(':') == localName) {
      return root
    }
    val children = root.childNodes
    for (i in 0 until children.length) {
      val node = children.item(i)
      if (node is Element) {
        findFirstElementByLocalName(node, localName)?.let { return it }
      }
    }
    return null
  }
}
