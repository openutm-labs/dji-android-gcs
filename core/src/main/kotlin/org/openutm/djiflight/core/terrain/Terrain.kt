package org.openutm.djiflight.core.terrain

import org.openutm.djiflight.core.advisory.Advisory
import org.openutm.djiflight.core.advisory.Level
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.mission.MissionWaypoint
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Ground heights from the lab's terrain file, for planning away from the site and checking routes over hills. */
interface TerrainModel {
    /** Height above mean sea level at [p], or null where the model has no data. */
    fun elevationM(p: LatLng): Double?

    /** One-sigma error of a single height, for the declaration band. */
    val sigmaM: Double
}

/** A decoded raster tile: ARGB pixels, row by row. */
class Raster(val width: Int, val height: Int, val argb: IntArray)

/** Image bytes → pixels. Android supplies BitmapFactory (PNG and WebP); tests use ImageIO. */
fun interface TileDecoder {
    fun decode(bytes: ByteArray): Raster?
}

/**
 * Terrain-RGB tiles in Mapterhorn's / Mapzen's "Terrarium" encoding (height = R·256 + G + B/256 − 32768 m)
 * inside a PMTiles archive, read at its most detailed zoom and interpolated bilinearly.
 *
 * Mapterhorn's planet file is Copernicus GLO-30 (and finer national models where it has them) up to
 * zoom 12: about 19 m pixels at the equator. GLO-30 is a surface model: roofs and tree canopy count as
 * ground, which errs towards less clearance, the safe side. Its heights are above EGM2008, within a
 * metre or two of the EGM96 the app converts with; [sigmaM] covers that.
 */
class TerrariumTerrain(
    private val tiles: PmTiles,
    private val decoder: TileDecoder,
    override val sigmaM: Double = 8.0,
) : TerrainModel {
    private val zoom = tiles.header.maxZoom
    private val cache = object : LinkedHashMap<Long, FloatArray?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, FloatArray?>?) = size > CACHED_TILES
    }
    private var tileSize = 0

    override fun elevationM(p: LatLng): Double? {
        if (!p.isValid || p.lat !in tiles.header.minLat..tiles.header.maxLat || p.lng !in tiles.header.minLon..tiles.header.maxLon) return null
        val n = (1L shl zoom).toDouble()
        val fx = (p.lng + 180) / 360 * n
        val latR = Math.toRadians(p.lat)
        val fy = (1 - ln(tan(latR) + 1 / kotlin.math.cos(latR)) / PI) / 2 * n
        heights(floor(fx).toInt(), floor(fy).toInt()) ?: return null // also learns the tile size
        val size = tileSize
        // Pixel centres sit at +0.5: interpolate between the four around the point, across tile edges.
        val gx = fx * size - 0.5
        val gy = fy * size - 0.5
        val x0 = floor(gx).toLong()
        val y0 = floor(gy).toLong()
        val tx = gx - x0
        val ty = gy - y0
        fun at(x: Long, y: Long): Float? {
            val max = (n * size).toLong() - 1
            val cx = x.coerceIn(0, max)
            val cy = y.coerceIn(0, max)
            val t = heights((cx / size).toInt(), (cy / size).toInt()) ?: return null
            return t[((cy % size) * size + cx % size).toInt()]
        }
        val h00 = at(x0, y0) ?: return null
        val h10 = at(x0 + 1, y0) ?: h00
        val h01 = at(x0, y0 + 1) ?: h00
        val h11 = at(x0 + 1, y0 + 1) ?: h00
        return (h00 * (1 - tx) * (1 - ty) + h10 * tx * (1 - ty) + h01 * (1 - tx) * ty + h11 * tx * ty)
    }

    private fun heights(x: Int, y: Int): FloatArray? {
        val key = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val decoded = tiles.tile(zoom, x, y)?.let(decoder::decode)?.let { r ->
            require(r.width == r.height) { "terrain tiles must be square" }
            tileSize = r.width
            FloatArray(r.argb.size) { i ->
                val c = r.argb[i]
                (((c shr 16) and 0xFF) * 256 + ((c shr 8) and 0xFF) + (c and 0xFF) / 256f - 32768f)
            }
        }
        synchronized(cache) { cache[key] = decoded }
        return decoded
    }

    companion object {
        /** A 512 px tile is 1 MB as floats: a few cover a survey and its surroundings. */
        const val CACHED_TILES = 6
    }
}

/** What the terrain model says about a planned flight, relative to its take-off point. */
data class TerrainReport(
    val takeoffElevationM: Double,
    /** Ground under the route or area, relative to the take-off point (negative = lower). */
    val lowestM: Double,
    val highestM: Double,
    /** Least height above the terrain model along the route; null for a manual flight. */
    val minClearanceM: Double?,
    val minClearanceAt: LatLng?,
    /** Most height above the terrain model: legal limits count from the ground below. */
    val maxAboveGroundM: Double,
    val maxAboveGroundAt: LatLng,
    val samples: Int,
)

object TerrainCheck {
    /** Along a route: one height every this many metres, plus each waypoint. */
    const val ROUTE_STEP_M = 20.0
    /** Below this over the terrain model (a surface model, but not every tree or mast), the route is too low. */
    const val LOW_CLEARANCE_M = 30.0
    private const val AREA_SAMPLES = 400

    /** A mission's route, heights above take-off; null if the model has no height at take-off. */
    fun route(terrain: TerrainModel, takeoff: LatLng, waypoints: List<MissionWaypoint>): TerrainReport? {
        val t0 = terrain.elevationM(takeoff) ?: return null
        if (waypoints.isEmpty()) return null
        var lowest = Double.POSITIVE_INFINITY
        var highest = Double.NEGATIVE_INFINITY
        var minClear = Double.POSITIVE_INFINITY
        var minAt = waypoints.first().position
        var maxAbove = Double.NEGATIVE_INFINITY
        var maxAt = waypoints.first().position
        var n = 0
        fun sample(p: LatLng, heightM: Double) {
            val g = (terrain.elevationM(p) ?: return) - t0
            n++
            lowest = min(lowest, g)
            highest = max(highest, g)
            val above = heightM - g
            if (above < minClear) { minClear = above; minAt = p }
            if (above > maxAbove) { maxAbove = above; maxAt = p }
        }
        sample(waypoints.first().position, waypoints.first().heightM)
        for ((a, b) in waypoints.zipWithNext()) {
            val d = Geo.distanceM(a.position, b.position)
            val steps = max(1, ceil(d / ROUTE_STEP_M).toInt())
            for (k in 1..steps) {
                val f = k.toDouble() / steps
                sample(LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f), a.heightM + (b.heightM - a.heightM) * f)
            }
        }
        if (n == 0) return null
        return TerrainReport(t0, lowest, highest, minClear, minAt, maxAbove, maxAt, n)
    }

    /** A manual flight's area, flown at up to [maxHeightM] above take-off. */
    fun area(terrain: TerrainModel, takeoff: LatLng, ring: List<LatLng>, maxHeightM: Double): TerrainReport? {
        val t0 = terrain.elevationM(takeoff) ?: return null
        val box = Geo.bbox(ring)
        val side = kotlin.math.sqrt(AREA_SAMPLES.toDouble()).toInt()
        var lowest = Double.POSITIVE_INFINITY
        var highest = Double.NEGATIVE_INFINITY
        var lowAt = takeoff
        var n = 0
        for (i in 0..side) for (j in 0..side) {
            val p = LatLng(box.minLat + (box.maxLat - box.minLat) * i / side, box.minLng + (box.maxLng - box.minLng) * j / side)
            if (!Geo.contains(ring, p)) continue
            val g = (terrain.elevationM(p) ?: continue) - t0
            n++
            highest = max(highest, g)
            if (g < lowest) { lowest = g; lowAt = p }
        }
        if (n == 0) return null
        return TerrainReport(t0, lowest, highest, null, null, maxHeightM - lowest, lowAt, n)
    }

    /** Warnings for the Plan screen. A route into the ground is CRITICAL (it blocks declaring); the rest warn. */
    fun advisories(r: TerrainReport, legalMaxHeightM: Double): List<Advisory> = buildList {
        r.minClearanceM?.let { c ->
            when {
                c < 0 -> add(Advisory.of("TERRAIN_INTO_GROUND", Level.CRITICAL, "The route goes %1${'$'}s m into rising ground (terrain model): raise the altitude", "%.0f".format(-c)))
                c < LOW_CLEARANCE_M -> add(Advisory.of("TERRAIN_LOW_CLEARANCE", Level.WARNING, "Only %1${'$'}s m above the terrain model at its closest; trees and masts may be higher", "%.0f".format(c)))
            }
        }
        if (r.maxAboveGroundM > legalMaxHeightM + 0.5) {
            add(Advisory.of("TERRAIN_ABOVE_LEGAL", Level.WARNING, "Up to %1${'$'}s m above the ground where it drops away: legal limit %2${'$'}s m above ground", "%.0f".format(r.maxAboveGroundM), "%.0f".format(legalMaxHeightM)))
        }
    }
}
