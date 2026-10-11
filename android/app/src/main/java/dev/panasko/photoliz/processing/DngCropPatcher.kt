package dev.panasko.photoliz.processing

import dev.panasko.photoliz.camera.CropMath
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/**
 * Applies the aspect / zoom crop to a DNG *without touching the raw data*: the existing DefaultCropOrigin and
 * DefaultCropSize tags (written by DngCreator) are rewritten in place. Raw converters show the crop by default
 * and can always expand back to the full sensor.
 */
object DngCropPatcher {
    private const val TAG_NEW_SUBFILE_TYPE = 254
    private const val TAG_SUB_IFDS = 330
    private const val TAG_DEFAULT_CROP_ORIGIN = 50719
    private const val TAG_DEFAULT_CROP_SIZE = 50720

    interface Io {
        val length: Long
        fun read(pos: Long, dst: ByteArray, len: Int)
        fun write(pos: Long, src: ByteArray, len: Int)
    }

    class FileIo(private val raf: RandomAccessFile) : Io {
        override val length: Long get() = raf.length()
        override fun read(pos: Long, dst: ByteArray, len: Int) { raf.seek(pos); raf.readFully(dst, 0, len) }
        override fun write(pos: Long, src: ByteArray, len: Int) { raf.seek(pos); raf.write(src, 0, len) }
    }

    class ArrayIo(val bytes: ByteArray) : Io {
        override val length: Long get() = bytes.size.toLong()
        override fun read(pos: Long, dst: ByteArray, len: Int) { System.arraycopy(bytes, pos.toInt(), dst, 0, len) }
        override fun write(pos: Long, src: ByteArray, len: Int) { System.arraycopy(src, 0, bytes, pos.toInt(), len) }
    }

    fun patchFile(file: File, ratio: Double?, zoom: Double): Boolean {
        if (ratio == null && zoom <= 1.0001) return false
        return try {
            RandomAccessFile(file, "rw").use { patch(FileIo(it), ratio, zoom) }
        } catch (t: Throwable) {
            false
        }
    }

    private class Entry(val type: Int, val count: Long, val valuePos: Long)

    /** @return true when at least one IFD was patched. */
    fun patch(io: Io, ratio: Double?, zoom: Double): Boolean {
        val buf = ByteArray(16)
        if (io.length < 16) return false
        io.read(0, buf, 4)
        val little = when {
            buf[0] == 'I'.code.toByte() && buf[1] == 'I'.code.toByte() -> true
            buf[0] == 'M'.code.toByte() && buf[1] == 'M'.code.toByte() -> false
            else -> return false
        }

        fun u16(pos: Long): Int {
            io.read(pos, buf, 2)
            return if (little) (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
            else ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        }

        fun u32(pos: Long): Long {
            io.read(pos, buf, 4)
            val b0 = buf[0].toLong() and 0xFF
            val b1 = buf[1].toLong() and 0xFF
            val b2 = buf[2].toLong() and 0xFF
            val b3 = buf[3].toLong() and 0xFF
            return if (little) b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24) else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }

        fun w16(pos: Long, v: Int) {
            if (little) { buf[0] = (v and 0xFF).toByte(); buf[1] = ((v shr 8) and 0xFF).toByte() }
            else { buf[0] = ((v shr 8) and 0xFF).toByte(); buf[1] = (v and 0xFF).toByte() }
            io.write(pos, buf, 2)
        }

        fun w32(pos: Long, v: Long) {
            for (i in 0 until 4) {
                val shift = if (little) 8 * i else 8 * (3 - i)
                buf[i] = ((v shr shift) and 0xFF).toByte()
            }
            io.write(pos, buf, 4)
        }

        fun typeSize(t: Int) = when (t) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11, 13 -> 4; 5, 10, 12 -> 8; else -> 0 }

        fun readValue(e: Entry, i: Int): Double = when (e.type) {
            3 -> u16(e.valuePos + 2L * i).toDouble()
            4, 13 -> u32(e.valuePos + 4L * i).toDouble()
            5 -> { val num = u32(e.valuePos + 8L * i); val den = u32(e.valuePos + 8L * i + 4); if (den == 0L) 0.0 else num.toDouble() / den }
            else -> Double.NaN
        }

        fun writeValue(e: Entry, i: Int, v: Int) {
            when (e.type) {
                3 -> w16(e.valuePos + 2L * i, v.coerceIn(0, 0xFFFF))
                4, 13 -> w32(e.valuePos + 4L * i, v.toLong())
                5 -> { w32(e.valuePos + 8L * i, v.toLong()); w32(e.valuePos + 8L * i + 4, 1L) }
            }
        }

        val queue = ArrayDeque<Long>()
        val visited = HashSet<Long>()
        queue.add(u32(4))
        var patched = false
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 64) {
            val ifd = queue.removeFirst()
            if (ifd <= 0 || ifd + 2 > io.length || !visited.add(ifd)) continue
            val n = u16(ifd)
            if (n <= 0 || n > 4096 || ifd + 2 + n * 12L + 4 > io.length) continue
            var origin: Entry? = null
            var size: Entry? = null
            var subfileType = 0L
            for (k in 0 until n) {
                val ePos = ifd + 2 + k * 12L
                val tag = u16(ePos)
                val type = u16(ePos + 2)
                val count = u32(ePos + 4)
                val bytes = typeSize(type) * count
                val valuePos = if (bytes in 1..4) ePos + 8 else u32(ePos + 8)
                if (bytes <= 0 || valuePos + bytes > io.length) continue
                val e = Entry(type, count, valuePos)
                when (tag) {
                    TAG_DEFAULT_CROP_ORIGIN -> if (count >= 2) origin = e
                    TAG_DEFAULT_CROP_SIZE -> if (count >= 2) size = e
                    TAG_NEW_SUBFILE_TYPE -> subfileType = if (type == 3) u16(valuePos).toLong() else u32(valuePos)
                    TAG_SUB_IFDS -> for (s in 0 until count.toInt().coerceAtMost(16)) {
                        queue.add(if (type == 3) u16(valuePos + 2L * s).toLong() else u32(valuePos + 4L * s))
                    }
                }
            }
            val next = u32(ifd + 2 + n * 12L)
            if (next != 0L) queue.add(next)

            val o = origin
            val s = size
            // Only the main (full resolution) image carries a meaningful default crop.
            if (o != null && s != null && (subfileType and 1L) == 0L) {
                val ox = readValue(o, 0)
                val oy = readValue(o, 1)
                val sw = readValue(s, 0)
                val sh = readValue(s, 1)
                if (!sw.isFinite() || !sh.isFinite() || sw < 8 || sh < 8) continue
                val crop = CropMath.centered(sw.roundToInt(), sh.roundToInt(), ratio, zoom, align = 2)
                writeValue(o, 0, (ox + crop.left).roundToInt())
                writeValue(o, 1, (oy + crop.top).roundToInt())
                writeValue(s, 0, crop.width)
                writeValue(s, 1, crop.height)
                patched = true
            }
        }
        return patched
    }
}
