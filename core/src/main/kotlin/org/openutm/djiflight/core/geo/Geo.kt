package org.openutm.djiflight.core.geo

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class LatLng(val lat: Double, val lng: Double) {
    val isValid: Boolean
        get() = lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0 &&
            !(lat == 0.0 && lng == 0.0)
}

data class BBox(val minLat: Double, val minLng: Double, val maxLat: Double, val maxLng: Double) {
    fun contains(p: LatLng) = p.lat in minLat..maxLat && p.lng in minLng..maxLng
    fun intersects(o: BBox) = minLat <= o.maxLat && o.minLat <= maxLat && minLng <= o.maxLng && o.minLng <= maxLng

    /** Grown by [meters] on every side (good enough away from the poles and the antimeridian). */
    fun expandedBy(meters: Double): BBox {
        val dLat = Math.toDegrees(meters / Geo.EARTH_RADIUS_M)
        val midLat = Math.toRadians((minLat + maxLat) / 2)
        val dLng = dLat / max(0.01, cos(midLat))
        return BBox(minLat - dLat, minLng - dLng, maxLat + dLat, maxLng + dLng)
    }

    /** Blender's `view` query format: `minlng,minlat,maxlng,maxlat`. */
    fun toBlenderView() = "$minLng,$minLat,$maxLng,$maxLat"
}

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(a: LatLng, b: LatLng): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lng - a.lng)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    fun destination(from: LatLng, bearingDeg: Double, distanceM: Double): LatLng {
        val d = distanceM / EARTH_RADIUS_M
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(from.lat)
        val l1 = Math.toRadians(from.lng)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return LatLng(Math.toDegrees(p2), (Math.toDegrees(l2) + 540) % 360 - 180)
    }

    /** Closed ring (first == last), counter-clockwise as GeoJSON prefers. */
    fun circle(center: LatLng, radiusM: Double, vertices: Int = 32): List<LatLng> {
        require(radiusM > 0 && vertices >= 8)
        val ring = (0 until vertices).map { i -> destination(center, 360.0 - i * 360.0 / vertices, radiusM) }
        return ring + ring.first()
    }

    fun closeRing(points: List<LatLng>): List<LatLng> {
        require(points.size >= 3) { "a polygon needs at least 3 points" }
        return if (points.first() == points.last()) points else points + points.first()
    }

    fun bbox(points: List<LatLng>): BBox = BBox(
        points.minOf { it.lat }, points.minOf { it.lng }, points.maxOf { it.lat }, points.maxOf { it.lng },
    )

    /** Ray casting; [ring] may be open or closed. */
    fun contains(ring: List<LatLng>, p: LatLng): Boolean {
        var inside = false
        val n = ring.size
        var j = n - 1
        for (i in 0 until n) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) &&
                p.lng < (b.lng - a.lng) * (p.lat - a.lat) / (b.lat - a.lat) + a.lng
            ) inside = !inside
            j = i
        }
        return inside
    }

    /** 0 inside the ring, else the distance to its nearest edge. */
    fun distanceToRingM(p: LatLng, ring: List<LatLng>): Double {
        if (contains(ring, p)) return 0.0
        val plane = LocalPlane(p)
        val pts = ring.map(plane::toXY)
        var best = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) best = min(best, segmentDistance(XY(0.0, 0.0), pts[i], pts[i + 1]))
        return best
    }

    fun ringsIntersect(a: List<LatLng>, b: List<LatLng>): Boolean {
        if (!bbox(a).intersects(bbox(b))) return false
        if (a.any { contains(b, it) } || b.any { contains(a, it) }) return true
        val plane = LocalPlane(a.first())
        val pa = a.map(plane::toXY)
        val pb = b.map(plane::toXY)
        for (i in 0 until pa.size - 1) for (j in 0 until pb.size - 1) {
            if (segmentsCross(pa[i], pa[i + 1], pb[j], pb[j + 1])) return true
        }
        return false
    }

    /**
     * Convex hull of [points] grown by [bufferM]: the declared area for a mission route.
     * Each point contributes a small circle, so the result covers every point by at least [bufferM].
     */
    fun bufferedHull(points: List<LatLng>, bufferM: Double, segments: Int = 16): List<LatLng> {
        require(points.isNotEmpty())
        val plane = LocalPlane(LatLng(points.map { it.lat }.average(), points.map { it.lng }.average()))
        val r = bufferM / cos(Math.PI / segments) // circumscribe, so the polygon contains the true circle
        val cloud = points.map(plane::toXY).flatMap { c ->
            (0 until segments).map { k ->
                val t = 2 * Math.PI * k / segments
                XY(c.x + r * cos(t), c.y + r * sin(t))
            }
        }
        val hull = convexHull(cloud).map(plane::toLatLng)
        return hull + hull.first()
    }

    private fun segmentDistance(p: XY, a: XY, b: XY): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(a.x + t * dx - p.x, a.y + t * dy - p.y)
    }

    private fun cross(o: XY, a: XY, b: XY) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    private fun segmentsCross(p1: XY, p2: XY, q1: XY, q2: XY): Boolean {
        val d1 = cross(q1, q2, p1)
        val d2 = cross(q1, q2, p2)
        val d3 = cross(p1, p2, q1)
        val d4 = cross(p1, p2, q2)
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0))
    }

    /** Andrew's monotone chain; counter-clockwise, not closed. */
    fun convexHull(points: List<XY>): List<XY> {
        val pts = points.distinct().sortedWith(compareBy<XY> { it.x }.thenBy { it.y })
        if (pts.size < 3) return pts
        val lower = ArrayList<XY>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<XY>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }
}

data class XY(val x: Double, val y: Double)

/** Equirectangular east/north metres around [origin]; accurate to well under 1 % within 10 km. */
class LocalPlane(val origin: LatLng) {
    private val mPerDegLat = Math.toRadians(1.0) * Geo.EARTH_RADIUS_M
    private val mPerDegLng = mPerDegLat * cos(Math.toRadians(origin.lat))

    fun toXY(p: LatLng) = XY((p.lng - origin.lng) * mPerDegLng, (p.lat - origin.lat) * mPerDegLat)
    fun toLatLng(p: XY) = LatLng(origin.lat + p.y / mPerDegLat, origin.lng + p.x / mPerDegLng)
}
