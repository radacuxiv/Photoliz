package dev.panasko.photoliz

import dev.panasko.photoliz.processing.DngCropPatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DngCropPatcherTest {

    private class Tag(val tag: Int, val type: Int, val values: LongArray)

    /** Builds a tiny TIFF: each IFD is a list of tags; SubIFD offsets are filled in automatically. */
    private fun tiff(little: Boolean, ifds: List<List<Tag>>, subIfdOf: Map<Int, Int> = emptyMap()): ByteArray {
        val buf = ByteBuffer.allocate(4096).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        buf.put(if (little) 'I'.code.toByte() else 'M'.code.toByte())
        buf.put(if (little) 'I'.code.toByte() else 'M'.code.toByte())
        buf.putShort(42)
        buf.putInt(8)
        fun size(t: Int) = when (t) { 3 -> 2; 4 -> 4; 5 -> 8; else -> 1 }
        // Layout: IFDs back to back starting at 8, value area after them.
        val ifdStarts = IntArray(ifds.size)
        var pos = 8
        for ((i, ifd) in ifds.withIndex()) { ifdStarts[i] = pos; pos += 2 + ifd.size * 12 + 4 }
        var dataPos = pos
        for ((i, ifd) in ifds.withIndex()) {
            buf.position(ifdStarts[i])
            buf.putShort(ifd.size.toShort())
            for (t in ifd.sortedBy { it.tag }) {
                buf.putShort(t.tag.toShort())
                buf.putShort(t.type.toShort())
                val values = if (t.tag == 330) longArrayOf(ifdStarts[subIfdOf[i]!!].toLong()) else t.values
                buf.putInt(values.size)
                val bytes = size(t.type) * values.size
                val entryValuePos = buf.position()
                fun writeValues(at: Int) {
                    val save = buf.position()
                    buf.position(at)
                    for (v in values) when (t.type) {
                        3 -> buf.putShort(v.toShort())
                        4 -> buf.putInt(v.toInt())
                        5 -> { buf.putInt(v.toInt()); buf.putInt(1) }
                    }
                    buf.position(save)
                }
                if (bytes <= 4) {
                    writeValues(entryValuePos)
                    buf.position(entryValuePos + 4)
                } else {
                    buf.putInt(dataPos)
                    writeValues(dataPos)
                    dataPos += bytes
                }
            }
            buf.putInt(0)
        }
        return buf.array().copyOf(dataPos)
    }

    private fun readCrop(bytes: ByteArray, little: Boolean, type: Int, ifdIndexOffset: Int): IntArray {
        // Re-parse with the patcher's own reader is overkill: locate tag values directly.
        val bb = ByteBuffer.wrap(bytes).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val out = IntArray(4)
        val n = bb.getShort(ifdIndexOffset).toInt()
        for (k in 0 until n) {
            val e = ifdIndexOffset + 2 + k * 12
            val tag = bb.getShort(e).toInt() and 0xFFFF
            if (tag != 50719 && tag != 50720) continue
            val base = if (tag == 50719) 0 else 2
            val sz = when (type) { 3 -> 2; 4 -> 4; else -> 8 }
            val at = if (sz * 2 <= 4) e + 8 else bb.getInt(e + 8)
            for (i in 0 until 2) out[base + i] = when (type) {
                3 -> bb.getShort(at + i * 2).toInt() and 0xFFFF
                4 -> bb.getInt(at + i * 4)
                else -> bb.getInt(at + i * 8) / bb.getInt(at + i * 8 + 4)
            }
        }
        return out
    }

    private fun cropTags(type: Int) = listOf(
        Tag(254, 4, longArrayOf(0)),
        Tag(50719, type, longArrayOf(8, 8)),
        Tag(50720, type, longArrayOf(3984, 2984)),
    )

    @Test
    fun patchesLongTagsLittleEndian() {
        val bytes = tiff(true, listOf(cropTags(4)))
        assertTrue(DngCropPatcher.patch(DngCropPatcher.ArrayIo(bytes), 16.0 / 9.0, 1.0))
        val c = readCrop(bytes, true, 4, 8)
        assertEquals(3984, c[2])
        assertEquals(2242, c[3])
        assertEquals(8, c[0])
        assertEquals(8 + 370, c[1]) // top aligned to an even row
    }

    @Test
    fun patchesShortTagsBigEndianWithZoom() {
        val bytes = tiff(false, listOf(cropTags(3)))
        assertTrue(DngCropPatcher.patch(DngCropPatcher.ArrayIo(bytes), null, 2.0))
        val c = readCrop(bytes, false, 3, 8)
        assertEquals(1992, c[2])
        assertEquals(1492, c[3])
        assertEquals(8 + 996, c[0])
    }

    @Test
    fun patchesRationalTagsInSubIfdOnly() {
        val thumb = listOf(Tag(254, 4, longArrayOf(1)), Tag(330, 4, longArrayOf(0)), Tag(50719, 5, longArrayOf(0, 0)), Tag(50720, 5, longArrayOf(256, 192)))
        val main = cropTags(5)
        val bytes = tiff(true, listOf(thumb, main), subIfdOf = mapOf(0 to 1))
        assertTrue(DngCropPatcher.patch(DngCropPatcher.ArrayIo(bytes), 1.0, 1.0))
        val mainOffset = 8 + 2 + thumb.size * 12 + 4
        val c = readCrop(bytes, true, 5, mainOffset)
        assertEquals(2984, c[2])
        assertEquals(2984, c[3])
        // Thumbnail IFD untouched.
        val t = readCrop(bytes, true, 5, 8)
        assertEquals(256, t[2])
    }

    @Test
    fun ignoresNonTiff() {
        assertFalse(DngCropPatcher.patch(DngCropPatcher.ArrayIo(ByteArray(64)), 1.0, 1.0))
    }
}
