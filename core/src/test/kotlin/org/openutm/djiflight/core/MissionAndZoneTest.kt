package org.openutm.djiflight.core

import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.geo.LocalPlane
import org.openutm.djiflight.core.geo.XY
import org.openutm.djiflight.core.mission.CameraModel
import org.openutm.djiflight.core.mission.FinishAction
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.core.mission.MissionPlan
import org.openutm.djiflight.core.mission.MissionValidator
import org.openutm.djiflight.core.mission.MissionWaypoint
import org.openutm.djiflight.core.mission.SurveyPlanner
import org.openutm.djiflight.core.mission.SurveySpec
import org.openutm.djiflight.core.zones.ZoneIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MissionAndZoneTest {
    private val plane = LocalPlane(TestData.NAIROBI)
    private fun square(sideM: Double) = listOf(XY(0.0, 0.0), XY(sideM, 0.0), XY(sideM, sideM), XY(0.0, sideM)).map(plane::toLatLng)

    @Test
    fun `P4P V2 survey over a 200 m square at 80 m with 75-65 overlap`() {
        val r = SurveyPlanner.plan(SurveySpec(square(200.0), CameraModel.P4P_V2, heightM = 80.0, frontOverlap = 0.75, sideOverlap = 0.65, headingDeg = 0.0, speedMps = 8.0))
        assertEquals(2.193, r.gsdCm, 0.001)          // 80 * 13.2 / (8.8 * 5472) m
        assertEquals(42.0, r.lineSpacingM, 1e-6)     // 120 m footprint * 0.35
        assertEquals(20.0, r.photoSpacingM, 1e-6)    // 80 m footprint * 0.25
        assertEquals(5, r.lineCount)
        assertEquals(10, r.plan.waypoints.size)
        assertEquals(20.0, r.plan.photoDistanceIntervalM)
        assertTrue(MissionValidator.validate(r.plan, MissionLimits.WAYPOINT_V1, 120.0).isEmpty())
        // Lines run north-south (heading 0) and alternate direction.
        val xy = r.plan.waypoints.map { plane.toXY(it.position) }
        assertEquals(xy[0].x, xy[1].x, 0.01)
        assertTrue(xy[1].y > xy[0].y && xy[3].y < xy[2].y)
        // Every waypoint is inside the declared area the planner would use.
        val area = Geo.bufferedHull(r.plan.waypoints.map { it.position }, 30.0)
        r.plan.waypoints.forEach { assertTrue(Geo.contains(area, it.position)) }
    }

    @Test
    fun `heading rotates the grid`() {
        val r = SurveyPlanner.plan(SurveySpec(square(200.0), CameraModel.P4P_V2, 80.0, 0.75, 0.65, headingDeg = 90.0, speedMps = 8.0))
        val xy = r.plan.waypoints.map { plane.toXY(it.position) }
        assertEquals(xy[0].y, xy[1].y, 0.01) // east-west lines
        assertEquals(5, r.lineCount)
    }

    @Test
    fun `long plans split into connected missions under the 99-waypoint limit`() {
        val wps = (0 until 150).map { i -> plane.toLatLng(XY(i * 5.0, 0.0)).let { MissionWaypoint(it.lat, it.lng, 60.0) } }
        val plan = MissionPlan(wps, autoSpeedMps = 8.0)
        assertTrue(MissionValidator.validate(plan, MissionLimits.WAYPOINT_V1, 120.0).any { "at most 99" in it })
        val parts = MissionValidator.split(plan, MissionLimits.WAYPOINT_V1)
        assertEquals(listOf(99, 52), parts.map { it.waypoints.size })
        assertEquals(parts[0].waypoints.last(), parts[1].waypoints.first())
        assertEquals(FinishAction.HOVER, parts[0].finishAction)
        assertEquals(FinishAction.GO_HOME, parts[1].finishAction)
        parts.forEach { assertTrue(MissionValidator.validate(it, MissionLimits.WAYPOINT_V1, 120.0).isEmpty()) }
    }

    @Test
    fun `validator refuses waypoints above the legal limit`() {
        val plan = MissionPlan(listOf(MissionWaypoint(-1.29, 36.82, 50.0), MissionWaypoint(-1.291, 36.82, 130.0)), autoSpeedMps = 8.0)
        assertTrue(MissionValidator.validate(plan, MissionLimits.WAYPOINT_V1, 120.0).any { "legal limit" in it })
    }

    @Test
    fun `zones parsed from Blender's geo_fence list warn on area overlap and proximity`() {
        val c = TestData.NAIROBI
        val zone = Geo.circle(Geo.destination(c, 90.0, 1_000.0), 300.0)
        val coords = zone.joinToString(",") { "[${it.lng},${it.lat}]" }
        val body = """{"count":1,"results":[{"id":"z1","name":"Wilson Airport ATZ (test)","upper_limit":"120.00","lower_limit":"0.00",
            "altitude_ref":"AGL","raw_geo_fence":{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},
            "geometry":{"type":"Polygon","coordinates":[[$coords]]}}]}}]}"""
        val idx = ZoneIndex.parseBlender(body, fetchedAtMs = 1)
        assertEquals(1, idx.zones.size)
        assertEquals(120.0, idx.zones.single().upperM)
        assertTrue(idx.intersecting(Geo.circle(c, 500.0)).isEmpty())
        assertEquals(listOf("z1"), idx.intersecting(Geo.circle(c, 800.0)).map { it.id })
        val edge = Geo.destination(c, 90.0, 650.0) // 50 m short of the zone
        assertEquals(50.0, idx.near(edge).single().distanceM, 2.0)
        assertEquals(0.0, idx.near(Geo.destination(c, 90.0, 1_000.0)).single().distanceM)
        assertTrue(idx.near(c).isEmpty())
        assertEquals(LatLng(c.lat, c.lng), c)
    }
}
