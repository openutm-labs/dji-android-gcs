package org.openutm.djiflight.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.openutm.djiflight.core.terrain.FileByteSource
import org.openutm.djiflight.core.terrain.PmTiles
import org.openutm.djiflight.core.terrain.Raster
import org.openutm.djiflight.core.terrain.TerrainModel
import org.openutm.djiflight.core.terrain.TerrariumTerrain
import org.openutm.djiflight.core.terrain.TileDecoder
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date

/**
 * The lab's offline map files, each one local PMTiles archive (docs/12, Zones and maps):
 *   basemap.pmtiles  vector, Protomaps schema (an OSM extract cut with `pmtiles extract`)
 *   imagery.pmtiles  raster, e.g. the lab's own orthomosaics
 *   terrain.pmtiles  Terrarium heights, e.g. cut from Mapterhorn: ground heights, clearance, hillshade
 * The app never fetches map tiles online.
 */
class RegionPacks(private val context: Context) {
    val dir = File(context.filesDir, "maps").apply { mkdirs() }
    val basemap = File(dir, "basemap.pmtiles")
    val imagery = File(dir, "imagery.pmtiles")
    val terrain = File(dir, "terrain.pmtiles")
    private val resolver = context.contentResolver

    /** Bumped whenever a file changes, so maps reload their style. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun describe(): String = listOf(context.getString(R.string.maps_basemap) to basemap, context.getString(R.string.maps_imagery) to imagery, context.getString(R.string.maps_terrain) to terrain).joinToString("\n") { (label, f) ->
        if (f.isFile) context.getString(R.string.maps_file_line, label, f.length() / 1e6, DateFormat.getDateInstance().format(Date(f.lastModified())))
        else context.getString(R.string.maps_file_none, label)
    }

    suspend fun import(uri: Uri, target: File) = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri) ?: error(context.getString(R.string.maps_error_open))
        install(input, target) { _, _ -> }
    }

    suspend fun download(url: String, target: File, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            check(conn.responseCode == 200) { context.getString(R.string.maps_error_http, conn.responseCode, url) }
            val total = contentLength(conn)
            install(conn.inputStream, target) { done, _ -> progress(done, total) }
        } finally {
            conn.disconnect()
        }
    }

    /** Copies to a temp file, checks the PMTiles v3 magic, then swaps it in. */
    private fun install(input: InputStream, target: File, progress: (Long, Long) -> Unit) {
        val tmp = File(target.path + ".part")
        var done = 0L
        input.use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    progress(done, -1)
                }
            }
        }
        val magic = tmp.inputStream().use { s -> ByteArray(8).also { s.read(it) } }
        if (String(magic, 0, 7, Charsets.US_ASCII) != "PMTiles" || magic[7].toInt() != 3) {
            tmp.delete()
            error(context.getString(R.string.maps_error_not_pmtiles))
        }
        check(tmp.renameTo(target)) { context.getString(R.string.maps_error_replace, target) }
        _version.value++
    }

    /**
     * Credits the map files ask for (OpenStreetMap's ODbL requires it), from each file's own
     * metadata, HTML tags removed: e.g. "© OpenStreetMap", "© Mapterhorn".
     */
    fun attributions(): List<String> = listOf(basemap, imagery, terrain).filter { it.isFile }.mapNotNull { f ->
        runCatching {
            val meta = FileByteSource(f).use { PmTiles(it).metadataJson() }
            org.json.JSONObject(meta).optString("attribution").replace(Regex("<[^>]*>"), "").replace("&copy;", "©").trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }.distinct()

    fun remove(target: File) {
        if (target.delete()) _version.value++
    }
}

/**
 * The terrain model over [RegionPacks.terrain], reopened whenever the file changes. Null without a
 * file, or if it isn't Terrarium PNG/WebP tiles.
 */
class TerrainFiles(private val packs: RegionPacks) {
    private var key: Long = 0
    private var model: TerrainModel? = null
    private var source: FileByteSource? = null

    @Synchronized
    fun model(): TerrainModel? {
        val f = packs.terrain
        if (!f.isFile) { close(); return null }
        val k = f.lastModified() * 31 + f.length()
        if (k != key) {
            close()
            key = k
            model = runCatching {
                val src = FileByteSource(f).also { source = it }
                val tiles = PmTiles(src)
                if (tiles.header.tileType != PmTiles.TYPE_PNG && tiles.header.tileType != PmTiles.TYPE_WEBP) null
                else TerrariumTerrain(tiles, BitmapTileDecoder)
            }.getOrNull()
        }
        return model
    }

    private fun close() {
        runCatching { source?.close() }
        source = null
        model = null
        key = 0
    }
}

/** PNG and WebP tiles through Android's own decoder (WebP lossless since API 18, so on every controller). */
object BitmapTileDecoder : TileDecoder {
    override fun decode(bytes: ByteArray): Raster? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        return try {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            Raster(bmp.width, bmp.height, px)
        } finally {
            bmp.recycle()
        }
    }
}

/** `Content-Length`, or -1. `URLConnection.contentLengthLong` is API 24, one above the minimum (Android 6 phones). */
internal fun contentLength(conn: java.net.URLConnection): Long =
    if (android.os.Build.VERSION.SDK_INT >= 24) conn.contentLengthLong else conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
