package org.openutm.djiflight.core.terrain

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Random access to an archive's bytes. */
fun interface ByteSource {
    fun read(offset: Long, length: Int): ByteArray
}

class FileByteSource(file: File) : ByteSource, Closeable {
    private val raf = RandomAccessFile(file, "r")

    @Synchronized
    override fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        raf.seek(offset)
        raf.readFully(out)
        return out
    }

    override fun close() = raf.close()
}

/**
 * Reads tiles from a PMTiles v3 archive (Protomaps' single-file format, the one MapLibre reads for the
 * basemap): the 127-byte header, then the root and leaf directories down to the tile. Written from
 * the published spec (github.com/protomaps/PMTiles, spec/v3) so the core can read the lab's terrain
 * file without Android. Gzip or no compression only, which is what `pmtiles extract` keeps for
 * Protomaps and Mapterhorn archives.
 */
class PmTiles(private val src: ByteSource) {
    data class Header(
        val rootOffset: Long,
        val rootLength: Long,
        val metadataOffset: Long,
        val metadataLength: Long,
        val leafOffset: Long,
        val tileDataOffset: Long,
        val internalCompression: Int,
        val tileCompression: Int,
        val tileType: Int,
        val minZoom: Int,
        val maxZoom: Int,
        val minLon: Double,
        val minLat: Double,
        val maxLon: Double,
        val maxLat: Double,
        val centerZoom: Int,
        val centerLon: Double,
        val centerLat: Double,
    )

    private class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    val header: Header = parseHeader(src.read(0, HEADER_BYTES))
    private val root: List<Entry> = directory(header.rootOffset, header.rootLength.toInt())
    private val leaves = object : LinkedHashMap<Long, List<Entry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Entry>>?) = size > 16
    }

    /** The archive's JSON metadata (name, attribution, layers...), or "{}". */
    fun metadataJson(): String {
        if (header.metadataLength <= 0 || header.metadataLength > 4_000_000) return "{}"
        return decompress(src.read(header.metadataOffset, header.metadataLength.toInt()), header.internalCompression).toString(Charsets.UTF_8)
    }

    /** Some tile near the archive's centre, at its most detailed zoom: to learn a raster's tile size. */
    fun sampleTile(): ByteArray? {
        val z = header.maxZoom
        val n = 1 shl z
        val lat = Math.toRadians(header.centerLat.coerceIn(header.minLat, header.maxLat))
        val x = ((header.centerLon.coerceIn(header.minLon, header.maxLon) + 180) / 360 * n).toInt().coerceIn(0, n - 1)
        val y = ((1 - kotlin.math.ln(kotlin.math.tan(lat) + 1 / kotlin.math.cos(lat)) / Math.PI) / 2 * n).toInt().coerceIn(0, n - 1)
        return tile(z, x, y)
    }

    /** The tile's bytes as stored (a PNG, WebP, MVT...), decompressed if the archive compressed it; null if absent. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < header.minZoom || z > header.maxZoom) return null
        val id = tileId(z, x, y)
        var dir = root
        repeat(4) { // the spec allows at most three levels below the root
            val e = find(dir, id) ?: return null
            if (e.runLength > 0) {
                val raw = src.read(header.tileDataOffset + e.offset, e.length)
                return decompress(raw, header.tileCompression)
            }
            val at = header.leafOffset + e.offset
            dir = synchronized(leaves) { leaves[at] } ?: directory(at, e.length).also { synchronized(leaves) { leaves[at] = it } }
        }
        return null
    }

    private fun find(dir: List<Entry>, id: Long): Entry? {
        var lo = 0
        var hi = dir.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = dir[mid].tileId
            when {
                c < id -> lo = mid + 1
                c > id -> hi = mid - 1
                else -> return dir[mid]
            }
        }
        // hi is now the last entry before id: a run that covers it, or a leaf directory to look in.
        if (hi < 0) return null
        val e = dir[hi]
        return when {
            e.runLength == 0 -> e
            id - e.tileId < e.runLength -> e
            else -> null
        }
    }

    private fun directory(offset: Long, length: Int): List<Entry> {
        val bytes = decompress(src.read(offset, length), header.internalCompression)
        val r = VarintReader(bytes)
        val n = r.next().toInt()
        val ids = LongArray(n)
        var last = 0L
        for (i in 0 until n) { last += r.next(); ids[i] = last }
        val runs = IntArray(n) { r.next().toInt() }
        val lengths = IntArray(n) { r.next().toInt() }
        val offsets = LongArray(n)
        for (i in 0 until n) {
            val v = r.next()
            offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
    }

    private class VarintReader(private val b: ByteArray) {
        private var pos = 0
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = b[pos++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "bad varint in a PMTiles directory" }
            }
        }
    }

    companion object {
        const val HEADER_BYTES = 127
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_GZIP = 2
        const val TYPE_MVT = 1
        const val TYPE_PNG = 2
        const val TYPE_WEBP = 4

        fun open(file: File): PmTiles = PmTiles(FileByteSource(file))

        private fun parseHeader(h: ByteArray): Header {
            require(String(h, 0, 7, Charsets.US_ASCII) == "PMTiles" && h[7].toInt() == 3) { "not a PMTiles v3 archive" }
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            fun e7(at: Int) = b.getInt(at) / 1e7
            return Header(
                rootOffset = b.getLong(8), rootLength = b.getLong(16), metadataOffset = b.getLong(24), metadataLength = b.getLong(32), leafOffset = b.getLong(40), tileDataOffset = b.getLong(56),
                internalCompression = h[97].toInt(), tileCompression = h[98].toInt(), tileType = h[99].toInt(),
                minZoom = h[100].toInt() and 0xFF, maxZoom = h[101].toInt() and 0xFF,
                minLon = e7(102), minLat = e7(106), maxLon = e7(110), maxLat = e7(114),
                centerZoom = h[118].toInt() and 0xFF, centerLon = e7(119), centerLat = e7(123),
            )
        }

        private fun decompress(b: ByteArray, compression: Int): ByteArray = when (compression) {
            COMPRESSION_GZIP -> GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
            0, COMPRESSION_NONE -> b
            else -> error("PMTiles compression $compression is not supported (gzip or none only)")
        }

        /** Tile id on the archive's Hilbert curve: all tiles of lower zooms first, then the curve at [z]. */
        fun tileId(z: Int, x: Int, y: Int): Long {
            require(z in 0..31) { "zoom $z" }
            var acc = 0L
            for (t in 0 until z) acc += 1L shl (2 * t)
            var tx = x.toLong()
            var ty = y.toLong()
            var d = 0L
            var s = (1L shl z) / 2
            while (s > 0) {
                val rx = if (tx and s > 0) 1L else 0L
                val ry = if (ty and s > 0) 1L else 0L
                d += s * s * ((3 * rx) xor ry)
                if (ry == 0L) {
                    if (rx == 1L) { tx = s - 1 - tx; ty = s - 1 - ty }
                    val t = tx; tx = ty; ty = t
                }
                s /= 2
            }
            return acc + d
        }
    }
}
