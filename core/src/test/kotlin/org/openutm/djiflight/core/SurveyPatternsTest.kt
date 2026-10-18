package org.openutm.djiflight.core

import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LocalPlane
import org.openutm.djiflight.core.geo.XY
import org.openutm.djiflight.core.mission.CameraModel
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.core.mission.MissionValidator
import org.openutm.djiflight.core.mission.SurveyPattern
import org.openutm.djiflight.core.mission.SurveyPlanner
import org.openutm.djiflight.core.mission.SurveySpec
import org.openutm.djiflight.core.mission.TravelLegs
import org.openutm.djiflight.core.mission.WpmlTarget
import org.openutm.djiflight.core.mission.WpmlWriter
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurveyPatternsTest {
    private val plane = LocalPlane(TestData.NAIROBI)
    private fun square(sideM: Double) = listOf(XY(0.0, 0.0), XY(sideM, 0.0), XY(sideM, sideM), XY(0.0, sideM)).map(plane::toLatLng)
    private fun spec(pattern: SurveyPattern) = SurveySpec(
        square(200.0), CameraModel.MAVIC_3E, heightM = 80.0, frontOverlap = 0.75, sideOverlap = 0.65, headingDeg = 0.0, speedMps = 8.0,
        gimbalPitchDeg = pattern.defaultGimbalDeg, pattern = pattern,
    )
    private fun angleDiff(a: Double, b: Double) = abs(((a - b) % 360 + 540) % 360 - 180)

    @Test
    fun `crosshatch flies the grid, then the same area at right angles, joined at the nearest corner`() {
        val grid = SurveyPlanner.plan(spec(SurveyPattern.GRID))
        val r = SurveyPlanner.plan(spec(SurveyPattern.CROSSHATCH))
        assertEquals(2 * grid.lineCount, r.lineCount)
        assertEquals(-70.0, r.plan.gimbalPitchDeg)
        val xy = r.plan.waypoints.map { plane.toXY(it.position) }
        val n = grid.plan.waypoints.size
        // First pass: north-south lines, as the grid; second pass: east-west.
        assertEquals(grid.plan.waypoints, r.plan.waypoints.take(n))
        assertEquals(xy[n].y, xy[n + 1].y, 0.01)
        // The join is no longer than one line spacing, and every point stays over the area.
        assertTrue(hypot(xy[n].x - xy[n - 1].x, xy[n].y - xy[n - 1].y) <= r.lineSpacingM + 1, "join ${xy[n - 1]} -> ${xy[n]}")
        xy.forEach { assertTrue(it.x in -0.5..200.5 && it.y in -0.5..200.5, "$it") }
        assertTrue(MissionValidator.validate(r.plan, MissionLimits.WAYPOINT_V1, 120.0).isEmpty())
    }

    @Test
    fun `perimeter follows the edge back to the start, facing the middle, turning gently`() {
        val r = SurveyPlanner.plan(spec(SurveyPattern.PERIMETER))
        val w = r.plan.waypoints
        val xy = w.map { plane.toXY(it.position) }
        assertEquals(1, r.lineCount)
        assertEquals(0.0, r.lineSpacingM)
        assertEquals(-45.0, r.plan.gimbalPitchDeg)
        assertEquals(xy.first().x, xy.last().x, 0.01)
        assertEquals(xy.first().y, xy.last().y, 0.01)
        for ((p, wp) in xy.zip(w)) {
            // On the square's edge...
            assertTrue(minOf(abs(p.x), abs(p.x - 200), abs(p.y), abs(p.y - 200)) < 0.5, "$p")
            // ...facing its centre (100, 100), as a compass heading.
            val toCentre = Math.toDegrees(atan2(100 - p.x, 100 - p.y))
            assertTrue(angleDiff(wp.headingDeg!!, toCentre) < 0.5, "${wp.headingDeg} vs $toCentre at $p")
        }
        for (i in 0 until w.size - 1) {
            assertTrue(angleDiff(w[i].headingDeg!!, w[i + 1].headingDeg!!) <= SurveyPlanner.PERIMETER_MAX_TURN_DEG + 0.01)
            assertTrue(Geo.distanceM(w[i].position, w[i + 1].position) <= SurveyPlanner.PERIMETER_MAX_STEP_M + 0.01)
        }
        assertTrue(MissionValidator.validate(r.plan, MissionLimits.WAYPOINT_V1, 120.0).isEmpty())
        // Photos all the way round, by distance; the WPML file keeps each heading.
        assertEquals(r.photoSpacingM, r.plan.photoDistanceIntervalM)
        val wpml = WpmlWriter.waylines(r.plan, WpmlTarget.MAVIC_3E)
        assertEquals(w.size, Regex("<wpml:waypointHeadingAngleEnable>1<").findAll(wpml).count())
    }

    @Test
    fun `travel legs keep the heading of the waypoint they lead to and from`() {
        val r = SurveyPlanner.plan(spec(SurveyPattern.PERIMETER).copy(travelHeightM = 100.0))
        val e = TravelLegs.expand(r.plan, plane.toLatLng(XY(-50.0, -50.0)), intro = true, outro = true)
        val w = e.plan.waypoints
        assertTrue(w.first().transit && w.last().transit)
        assertEquals(r.plan.waypoints.first().headingDeg, w.first().headingDeg)
        assertEquals(r.plan.waypoints.last().headingDeg, w.last().headingDeg)
    }

    @Test
    fun `new survey cameras reproduce DJI's published fields of view`() {
        fun fov(sizeMm: Double, f: Double) = 2 * Math.toDegrees(atan(sizeMm / 2 / f))
        fun dfov(c: CameraModel) = fov(hypot(c.sensorWidthMm, c.sensorHeightMm), c.focalLengthMm)
        assertEquals(84.0, dfov(CameraModel.MAVIC_3T), 0.3)
        assertEquals(82.1, dfov(CameraModel.ZENMUSE_H30_WIDE), 0.3)
        assertEquals(61.2, fov(CameraModel.MAVIC_3M.sensorWidthMm, CameraModel.MAVIC_3M.focalLengthMm), 0.3)
        assertEquals(48.1, fov(CameraModel.MAVIC_3M.sensorHeightMm, CameraModel.MAVIC_3M.focalLengthMm), 0.3)
        // The M3M plans on its multispectral cameras: narrower than its RGB (M3E) camera, so closer lines.
        val m3m = SurveyPlanner.plan(spec(SurveyPattern.GRID).copy(camera = CameraModel.MAVIC_3M))
        assertTrue(m3m.lineSpacingM < SurveyPlanner.plan(spec(SurveyPattern.GRID)).lineSpacingM)
        assertEquals(1.80, CameraModel.MAVIC_3T.gsdM(100.0) * 100, 0.01)
        assertEquals(1.73, CameraModel.ZENMUSE_H30_WIDE.gsdM(100.0) * 100, 0.01)
    }

    @Test
    fun `M3T, M3M and H30 missions name their aircraft, payload and lenses for DJI`() {
        val plan = SurveyPlanner.plan(spec(SurveyPattern.GRID)).plan
        fun cfg(t: WpmlTarget) = WpmlWriter.waylines(plan, t)
        cfg(WpmlTarget.MAVIC_3T).let { assertTrue("<wpml:droneSubEnumValue>1<" in it && "<wpml:payloadEnumValue>67<" in it) }
        cfg(WpmlTarget.MAVIC_3M).let { assertTrue("<wpml:droneSubEnumValue>2<" in it && "<wpml:payloadEnumValue>68<" in it) }
        assertTrue("<wpml:imageFormat>visible,narrow_band</wpml:imageFormat>" in WpmlWriter.template(plan, WpmlTarget.MAVIC_3M, 0))
        val h30 = WpmlTarget(WpmlTarget.MATRICE_400, 0, WpmlTarget.PAYLOAD_H30, imageFormat = "wide")
        cfg(h30).let { assertTrue("<wpml:droneEnumValue>103<" in it && "<wpml:payloadEnumValue>82<" in it) }
    }
}
