package org.openutm.djiflight.core.mission

import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.geo.LocalPlane
import org.openutm.djiflight.core.geo.XY
import kotlin.math.*

/** Capture geometry only. The ordinary validator, declaration and execution checks still apply. */
object AdvancedSurveys {
    fun corridor(s: SurveySpec): SurveyResult {
        require(s.polygon.size in 2..256) { "a corridor needs at least two centreline points" }
        require(s.corridorWidthM.isFinite() && s.corridorWidthM in 5.0..500.0) { "corridor width must be 5–500 m" }
        val plane = LocalPlane(s.polygon.first())
        val pts = s.polygon.map(plane::toXY)
        val directions = pts.zipWithNext { a, b ->
            val length = hypot(b.x - a.x, b.y - a.y)
            require(length >= 2 && length <= 20_000) { "centreline segments must be 2 m–20 km" }
            XY((b.x - a.x) / length, (b.y - a.y) / length)
        }
        val normals = directions.map { XY(-it.y, it.x) }
        val gsd = s.camera.gsdM(s.heightM)
        val gap = gsd * s.camera.imageWidthPx * (1 - s.sideOverlap)
        val photo = gsd * s.camera.imageHeightPx * (1 - s.frontOverlap)
        val strips = ceil(s.corridorWidthM / gap).toInt().coerceAtLeast(1)
        require(strips <= 500) { "corridor needs too many strips; increase height or reduce overlap" }
        val points = (0 until strips).flatMap { strip ->
            val offset = -s.corridorWidthM / 2 + s.corridorWidthM * (strip + 0.5) / strips
            val line = pts.mapIndexed { i, p ->
                val n = when (i) {
                    0 -> normals.first()
                    pts.lastIndex -> normals.last()
                    else -> {
                        val a = normals[i - 1]; val b = normals[i]
                        val denominator = 1 + a.x * b.x + a.y * b.y
                        require(denominator >= 0.25) { "corridor has a turn too sharp for safe offset planning; split it at the bend" }
                        XY((a.x + b.x) / denominator, (a.y + b.y) / denominator)
                    }
                }
                val q = plane.toLatLng(XY(p.x + n.x * offset, p.y + n.y * offset))
                MissionWaypoint(q.lat, q.lng, s.heightM)
            }
            if (strip % 2 == 0) line else line.reversed()
        }
        return SurveyResult(MissionPlan(points, s.speedMps, photoDistanceIntervalM = photo, gimbalPitchDeg = s.gimbalPitchDeg,
            travelHeightM = s.travelHeightM, cameraName = s.camera.name), s.corridorWidthM / strips, photo, gsd * 100, strips)
    }

    fun surround(s: SurveySpec): SurveyResult {
        require(s.polygon.size >= 3) { "draw the building footprint first" }
        require(s.orbitStandoffM.isFinite() && s.orbitStandoffM in 5.0..500.0)
        val top = s.orbitTopHeightM ?: s.heightM
        require(top.isFinite() && top >= s.heightM && top <= 500)
        require(s.orbitLayerSpacingM.isFinite() && s.orbitLayerSpacingM >= 5)
        val layers = ceil((top - s.heightM) / s.orbitLayerSpacingM).toInt().coerceAtLeast(0) + 1
        require(layers <= 50) { "use at most 50 capture layers" }
        val center = LatLng(s.polygon.map { it.lat }.average(), s.polygon.map { it.lng }.average())
        val radius = s.polygon.maxOf { Geo.distanceM(center, it) } + s.orbitStandoffM
        require(radius <= 2_000) { "surround radius must be at most 2 km" }
        val segments = max(36, ceil(2 * PI * radius / 30).toInt())
        val points = (0 until layers).flatMap { layer ->
            val h = if (layers == 1) s.heightM else s.heightM + (top - s.heightM) * layer / (layers - 1)
            // Consecutive layers start one angular step further on: no vertically stacked waypoints.
            (0..segments).map { i ->
                val bearing = s.headingDeg + (layer + i) * 360.0 / segments
                val p = Geo.destination(center, bearing, radius)
                MissionWaypoint(p.lat, p.lng, h, headingDeg = (bearing + 180) % 360)
            }
        }
        val gsd = s.camera.gsdM(s.heightM)
        val photo = gsd * s.camera.imageHeightPx * (1 - s.frontOverlap)
        return SurveyResult(MissionPlan(points, s.speedMps, photoDistanceIntervalM = photo, gimbalPitchDeg = s.gimbalPitchDeg,
            travelHeightM = s.travelHeightM, cameraName = s.camera.name), 0.0, photo, gsd * 100, layers)
    }
}
