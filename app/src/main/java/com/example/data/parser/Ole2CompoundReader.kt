package com.example.data.parser

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Lightweight OLE2 Compound File Binary Format (CFB) reader shared across
 * DocBinaryParser, OfficeDecryptor, and DocumentRepository.
 */
object Ole2CompoundReader {

  private val OLE2_MAGIC = byteArrayOf(
    0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
    0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte(),
  )

  private const val END_OF_CHAIN = -2

  private data class DirEntry(
    val name: String,
    val type: Int,
    val startSector: Int,
    val streamSize: Long,
  )

  fun isOle2(data: ByteArray): Boolean {
    return data.size >= 512 && data.copyOfRange(0, 8).contentEquals(OLE2_MAGIC)
  }

  fun readStreams(data: ByteArray): Map<String, ByteArray> {
    if (!isOle2(data)) return emptyMap()
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

    val streams = LinkedHashMap<String, ByteArray>()
    for (entry in dirEntries) {
      if (entry.type != 2 || entry.name.isEmpty()) continue
      val bytes =
        if (entry.streamSize < miniStreamCutoff && miniStreamBytes.isNotEmpty()) {
          readMiniStream(
            miniStreamBytes,
            miniFat,
            entry.startSector,
            miniSectorSize,
            entry.streamSize.toInt(),
          )
        } else {
          readFatStream(data, fat, entry.startSector, sectorSize, entry.streamSize.toInt())
        }
      streams[entry.name] = bytes
    }
    return streams
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
}
