package org.openutm.djiflight.core

import org.openutm.djiflight.core.adapter.AircraftSettings
import org.openutm.djiflight.core.adapter.AircraftStatus
import org.openutm.djiflight.core.adapter.Health
import org.openutm.djiflight.core.adapter.SensorStatus
import org.openutm.djiflight.core.advisory.AdvisorInput
import org.openutm.djiflight.core.advisory.FlightAdvisor
import org.openutm.djiflight.core.altitude.AltitudeSource
import org.openutm.djiflight.core.declaration.DeclarationInput
import org.openutm.djiflight.core.declaration.DeclarationPlanner
import org.openutm.djiflight.core.declaration.GroundEstimate
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.geo.LocalPlane
import org.openutm.djiflight.core.geo.XY
import org.openutm.djiflight.core.mission.CameraModel
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.core.mission.MissionPlan
import org.openutm.djiflight.core.mission.MissionValidator
import org.openutm.djiflight.core.mission.MissionWaypoint
import org.openutm.djiflight.core.mission.SurveyPattern
import org.openutm.djiflight.core.mission.SurveyPlanner
import org.openutm.djiflight.core.mission.SurveySpec
import org.openutm.djiflight.core.mission.WpmlTarget
import org.openutm.djiflight.core.mission.WpmlWriter
import org.openutm.djiflight.core.model.OperationKind
import org.openutm.djiflight.core.session.FlightSnapshot
import org.openutm.djiflight.core.terrain.TerrainCheck
import org.openutm.djiflight.core.terrain.TerrainFollow
import org.openutm.djiflight.core.terrain.TerrainModel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Terrain following over synthetic ground: an even slope, a ridge, a cliff, a drop and rolling hills. */
class TerrainFollowTest {
    private val plane = LocalPlane(LatLng(-1.28, 36.82))
    private val takeoff = plane.toLatLng(XY(0.0, 0.0))
    private val v1 = MissionLimits.WAYPOINT_V1
    private val tol = TerrainFollow.TOLERANCE_M

    /** Heights above sea level from x, y in metres east and north of the take-off point; 200 m there. */
    private fun ground(f: (XY) -> Double?) = object : TerrainModel {
        override fun elevationM(p: LatLng) = f(plane.toXY(p))?.let { 200.0 + it }
        override val sigmaM = 8.0
    }

    /** Rises 1 m every 10 m eastwards. */
    private val slope = ground { it.x / 10 }
    /** Flat, then 60 m up and down again over 300 m (0.4: steeper than 3 m/s at 8 m/s). */
    private val ridge = ground { 60 * max(0.0, 1 - abs(it.x - 300) / 150) }
    /** 60 m up within 20 m at x = 300. */
    private val cliff = ground { (it.x - 300).coerceIn(0.0, 20.0) * 3 }
    /** 100 m down within 10 m at x = 300. */
    private val drop = ground { -(it.x - 300).coerceIn(0.0, 10.0) * 10 }

    private fun line(y: Double = 150.0) =
        MissionPlan(listOf(XY(0.0, y), XY(600.0, y)).map(plane::toLatLng).map { MissionWaypoint(it.lat, it.lng, 80.0) }, autoSpeedMps = 8.0, photoDistanceIntervalM = 20.0)

    private fun survey(pattern: SurveyPattern = SurveyPattern.GRID, w: Double = 600.0, h: Double = 300.0, heading: Double = 90.0, travel: Double? = null) =
        SurveyPlanner.plan(SurveySpec(listOf(XY(0.0, 0.0), XY(w, 0.0), XY(w, h), XY(0.0, h)).map(plane::toLatLng), CameraModel.MAVIC_3E, 80.0, 0.75, 0.65, heading, 8.0, travelHeightM = travel, pattern = pattern)).plan

    /** Height above [t] along the route, every 5 m: what the aircraft flies between waypoints. */
    private fun aboveGround(plan: MissionPlan, t: TerrainModel): List<Double> = plan.waypoints.zipWithNext().flatMap { (a, b) ->
        val n = max(1, ceil(Geo.distanceM(a.position, b.position) / 5).toInt())
        (0..n).map { k ->
            val f = k.toDouble() / n
            a.heightM + (b.heightM - a.heightM) * f - (t.elevationM(LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f))!! - 200)
        }
    }

    /** Vertical speed on each leg at the plan's speed, m/s (positive = climbing). */
    private fun verticalSpeeds(plan: MissionPlan) = plan.waypoints.zipWithNext { a, b -> (b.heightM - a.heightM) / Geo.distanceM(a.position, b.position) * plan.autoSpeedMps }

    @Test
    fun `on an even slope the heights follow the ground and no waypoint is added`() {
        val base = survey()
        val r = assertNotNull(TerrainFollow.follow(base, takeoff, 80.0, slope, v1))
        assertEquals(base.waypoints.size, r.waypoints.size)
        r.waypoints.forEach { w ->
            val x = plane.toXY(w.position).x
            assertEquals(x / 10, w.groundM!!, 0.1)
            assertEquals(80 + x / 10, w.heightM, 0.2)
        }
        aboveGround(r, slope).forEach { assertEquals(80.0, it, 0.5) }
        assertEquals(80.0, r.terrainFollowM)
        assertEquals(140.0, r.maxHeightM, 0.5) // the top of the slope: also every part's RTH height
        assertEquals(r.maxHeightM, r.returnHomeHeightM)
        assertEquals(60.0, r.groundRiseM, 0.5)
        assertEquals(base.photoDistanceIntervalM, r.photoDistanceIntervalM)
        // The flat plan cleared the top of the slope by 20 m; this one by 80.
        assertEquals(20.0, TerrainCheck.route(slope, takeoff, base.waypoints)!!.minClearanceM!!, 1.0)
        val rep = TerrainCheck.route(slope, takeoff, r.waypoints)!!
        assertEquals(80.0, rep.minClearanceM!!, 1.0)
        assertEquals(80.0, rep.maxAboveGroundM, 1.0)
        assertEquals(emptyList(), TerrainCheck.advisories(rep, 120.0))
    }

    @Test
    fun `a ridge gets waypoints where the slope changes, and the climb and descent stay within the caps`() {
        val r = assertNotNull(TerrainFollow.follow(line(), takeoff, 80.0, ridge, v1))
        assertTrue(r.waypoints.size in 4..12, "${r.waypoints.size} waypoints")
        val agl = aboveGround(r, ridge)
        assertTrue(agl.min() >= 80 - tol - 0.2, "lowest ${agl.min()}")
        // Coming down at 2 m/s while the ground falls at 3.2 m/s: up to 0.15 x 150 m = 22.5 m higher at the foot.
        assertEquals(80 + 22.5, agl.max(), tol + 0.5)
        verticalSpeeds(r).forEach { assertTrue(it <= TerrainFollow.MAX_CLIMB_MPS + 0.02 && it >= -TerrainFollow.MAX_DESCENT_MPS - 0.02, "$it m/s") }
        // Added waypoints take no photo and leave the distance trigger alone.
        assertTrue(r.waypoints.none { it.takePhoto || it.transit })
        assertEquals(20.0, r.photoDistanceIntervalM)
        // Round the edge of a ridge (closer, to add some): added waypoints face between their neighbours, so the turn limit holds.
        val p = assertNotNull(TerrainFollow.follow(survey(SurveyPattern.PERIMETER), takeoff, 80.0, ridge, v1, toleranceM = 1.0))
        assertTrue(p.waypoints.size > survey(SurveyPattern.PERIMETER).waypoints.size)
        assertTrue(p.waypoints.all { it.headingDeg != null })
        p.waypoints.zipWithNext { a, b -> assertTrue(abs(((b.headingDeg!! - a.headingDeg!!) % 360 + 540) % 360 - 180) <= SurveyPlanner.PERIMETER_MAX_TURN_DEG + 0.01) }
    }

    @Test
    fun `before a cliff the route starts climbing early, so it never dips below the height`() {
        val r = assertNotNull(TerrainFollow.follow(line(), takeoff, 80.0, cliff, v1))
        val agl = aboveGround(r, cliff)
        assertTrue(agl.min() >= 80 - tol - 0.2, "lowest ${agl.min()}")
        verticalSpeeds(r).forEach { assertTrue(it <= TerrainFollow.MAX_CLIMB_MPS + 0.02, "$it m/s") }
        // 60 m at 3 m/s and 8 m/s takes 160 m: by 200 m east, 40 m short of the cliff, it is well on its way up.
        val at200 = r.waypoints.zipWithNext().first { (a, b) -> plane.toXY(a.position).x <= 200 && plane.toXY(b.position).x >= 200 }
            .let { (a, b) -> val xa = plane.toXY(a.position).x; a.heightM + (b.heightM - a.heightM) * (200 - xa) / (plane.toXY(b.position).x - xa) }
        assertEquals(80 + 60 - 0.375 * 120, at200, tol + 0.5)
    }

    @Test
    fun `where the ground drops away faster than the descent cap the route stays high, and the legal limit counts from the ground`() {
        val r = assertNotNull(TerrainFollow.follow(line(), takeoff, 80.0, drop, v1))
        verticalSpeeds(r).forEach { assertTrue(it >= -TerrainFollow.MAX_DESCENT_MPS - 0.02, "$it m/s") }
        // Off the edge: 100 m lower, coming down at 0.25 m per metre, so about 177 m above the ground below it.
        val rep = TerrainCheck.route(drop, takeoff, r.waypoints)!!
        assertEquals(177.5, rep.maxAboveGroundM, tol + 1)
        // Between waypoints, so the Plan screen warns (as for a flat route off a ridge).
        assertTrue(TerrainCheck.advisories(rep, 120.0).any { it.code == "TERRAIN_ABOVE_LEGAL" })

        // Up the slope, 140 m above take-off is 80 m above the ground: legal. The same heights without the ground are not,
        // and nor is a waypoint 140 m above the ground.
        val up = TerrainFollow.follow(survey(), takeoff, 80.0, slope, v1)!!
        assertEquals(emptyList(), MissionValidator.validate(up, v1, 120.0))
        val blind = up.copy(waypoints = up.waypoints.map { it.copy(groundM = null) })
        assertTrue(MissionValidator.validate(blind, v1, 120.0).any { "exceeds the legal limit" in it })
        val over = up.copy(waypoints = up.waypoints.mapIndexed { i, w -> if (i == 1) w.copy(groundM = w.groundM!! - 60) else w })
        assertEquals(listOf("waypoint 1 is 140 m above the ground, over the legal limit 120.0 m"), MissionValidator.validate(over, v1, 120.0))
    }

    @Test
    fun `a long survey over rolling ground uploads in parts that each return home above the whole route`() {
        val hills = ground { 25 * sin(it.x / 70) * cos(it.y / 90) + it.x / 25 }
        val base = survey(w = 900.0, h = 700.0, heading = 0.0)
        val r = assertNotNull(TerrainFollow.follow(base, takeoff, 80.0, hills, v1))
        assertTrue(r.waypoints.size > v1.maxWaypoints, "${r.waypoints.size} waypoints")
        aboveGround(r, hills).let { assertTrue(it.min() >= 80 - tol - 0.2, "lowest ${it.min()}") }

        val parts = MissionValidator.split(r, v1)
        assertTrue(parts.size >= 2)
        parts.forEach { part ->
            assertTrue(part.waypoints.size <= v1.maxWaypoints)
            assertEquals(emptyList(), MissionValidator.validate(part, v1, 120.0))
            assertEquals(r.maxHeightM, part.maxHeightM, 1e-9)
            assertEquals(base.photoDistanceIntervalM, part.photoDistanceIntervalM)
        }
        parts.zipWithNext { a, b -> assertEquals(a.waypoints.last(), b.waypoints.first()) }
        assertEquals(r.waypoints, parts.flatMapIndexed { i, p -> if (i == 0) p.waypoints else p.waypoints.drop(1) })

        // WPML: still relative to take-off, the followed heights, and RTH at the route's top even in a part low on the slope.
        val low = parts.minBy { p -> p.waypoints.maxOf { it.heightM } }
        val xml = WpmlWriter.waylines(low, WpmlTarget.MAVIC_3E)
        assertTrue("<wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>" in xml)
        val rth = Regex("<wpml:globalRTHHeight>([0-9.]+)<").find(xml)!!.groupValues[1].toDouble()
        assertEquals(r.maxHeightM, rth, 1e-3)
        assertTrue(rth > low.waypoints.maxOf { it.heightM })
        val heights = Regex("<wpml:executeHeight>([0-9.-]+)<").findAll(xml).map { it.groupValues[1].toDouble() }.toList()
        assertEquals(low.waypoints.map { it.heightM }, heights)
        assertEquals(1, Regex("multipleDistance").findAll(xml).count())
    }

    @Test
    fun `the declared volume and the aircraft's settings cover the route's top`() {
        val r = TerrainFollow.follow(survey(), takeoff, 80.0, slope, v1)!!
        val now = 1_792_142_000_000L
        fun declare(rise: Double) = DeclarationPlanner.plan(
            DeclarationInput(
                clientRef = "f", ring = Geo.circle(takeoff, 800.0), ground = GroundEstimate(1645.0, 5.0, AltitudeSource.DEVICE_GNSS),
                plannedMaxHeightM = r.maxHeightM + 10, legalMaxHeightM = 120.0, missionMaxHeightM = r.maxHeightM, groundRiseM = rise,
                plannedStartMs = now + 3_600_000, plannedEndMs = now + 7_200_000, nowMs = now, aircraftSerial = "SN", originatingParty = "lab", submittedBy = "p",
            ),
        )
        // 140 m above take-off is over the legal 120 m only if the ground were flat.
        assertFailsWith<IllegalArgumentException> { declare(0.0) }
        assertEquals(1645.0 + r.maxHeightM + 10 + 20, declare(r.groundRiseM).maxAltW84M, 1e-6)

        val plan = TestData.plan(now).copy(kind = OperationKind.MISSION, mission = r, plannedMaxHeightM = r.maxHeightM + 10, missionMaxHeightM = r.maxHeightM)
        assertEquals(180.0, plan.legalCeilingM, 0.5)
        fun inFlight(h: Double, p: org.openutm.djiflight.core.model.FlightPlan) =
            FlightAdvisor.advise(AdvisorInput(FlightSnapshot("f", plan = p), TestData.state(0, h = h, flying = true), telemetryStale = false, zones = null, nowMs = 0)).map { it.code }
        assertTrue("ABOVE_LEGAL_HEIGHT" !in inFlight(140.0, plan) && "ABOVE_PLANNED_HEIGHT" !in inFlight(140.0, plan))
        assertTrue("ABOVE_LEGAL_HEIGHT" in inFlight(140.0, plan.copy(mission = survey())))

        fun checks(s: AircraftSettings, p: org.openutm.djiflight.core.model.FlightPlan = plan) =
            FlightAdvisor.aircraftChecks(AircraftStatus(settings = s, compass = SensorStatus(Health.OK), imu = SensorStatus(Health.OK)), p).map { it.code }
        assertEquals(listOf("RTH_BELOW_TERRAIN_ROUTE"), checks(AircraftSettings(returnHomeHeightM = 60)))
        assertEquals(emptyList(), checks(AircraftSettings(returnHomeHeightM = 145)))
        // A flat mission flown at its own height goes home above it anyway: no RTH check.
        assertEquals(emptyList(), checks(AircraftSettings(returnHomeHeightM = 60), TestData.plan(now).copy(mission = survey())))
        assertEquals(listOf("HEIGHT_LIMIT_BELOW_MISSION"), checks(AircraftSettings(maxAltitudeM = 120)))
        assertEquals(emptyList(), checks(AircraftSettings(maxAltitudeM = 150)))
        assertEquals(listOf("HEIGHT_LIMIT_ABOVE_LEGAL"), checks(AircraftSettings(maxAltitudeM = 200)))
    }

    @Test
    fun `a travel altitude below the new top is dropped, and ground the file doesn't cover gives no plan`() {
        assertNull(TerrainFollow.follow(survey(travel = 100.0), takeoff, 80.0, slope, v1)!!.travelHeightM)
        val high = TerrainFollow.follow(survey(travel = 160.0), takeoff, 80.0, slope, v1)!!
        assertEquals(160.0, high.travelHeightM)
        assertEquals(160.0, high.returnHomeHeightM)
        assertNull(TerrainFollow.follow(survey(), takeoff, 80.0, ground { if (it.x > 500) null else 0.0 }, v1))
        assertNull(TerrainFollow.follow(survey(), plane.toLatLng(XY(-100.0, 0.0)), 80.0, ground { if (it.x < -50) null else 0.0 }, v1))
    }
}
