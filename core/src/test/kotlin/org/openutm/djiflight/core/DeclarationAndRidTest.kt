package org.openutm.djiflight.core

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openutm.djiflight.core.altitude.AltitudeSource
import org.openutm.djiflight.core.declaration.DeclarationInput
import org.openutm.djiflight.core.declaration.DeclarationPlanner
import org.openutm.djiflight.core.declaration.GroundEstimate
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.model.TelemetrySample
import org.openutm.djiflight.core.rid.FlightIdentity
import org.openutm.djiflight.core.rid.RidMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeclarationAndRidTest {
    private val now = 1_792_142_000_000L
    private val hour = 3_600_000L

    private fun input(
        sigma: Double = 5.0,
        plannedMax: Double = 100.0,
        missionMax: Double? = null,
        start: Long = now + hour,
        end: Long = now + 2 * hour,
    ) = DeclarationInput(
        clientRef = "flight-1", ring = Geo.circle(TestData.NAIROBI, 500.0),
        ground = GroundEstimate(1645.0, sigma, AltitudeSource.DEVICE_GNSS),
        plannedMaxHeightM = plannedMax, missionMaxHeightM = missionMax,
        plannedStartMs = start, plannedEndMs = end, nowMs = now,
        aircraftSerial = "SN", originatingParty = "lab", submittedBy = "dev/pilot",
    )

    @Test
    fun `altitude band spans ground to max height, never a collapsed waypoint band (B3)`() {
        // A survey flown entirely at 30 m above take-off: the connector declared 210.8528-210.8552 m.
        val p = DeclarationPlanner.plan(input(missionMax = 30.0))
        val pad = 10.0 + 2 * 5.0
        assertEquals(1645.0 - pad, p.minAltW84M, 1e-9)
        assertEquals(1645.0 + 40.0 + pad, p.maxAltW84M, 1e-9) // mission max + 10 m margin
        assertTrue(p.maxAltW84M - p.minAltW84M > 70)
    }

    @Test
    fun `max height is capped by the legal limit and missions above it are refused`() {
        assertEquals(1645.0 + 120.0 + 20.0, DeclarationPlanner.plan(input(plannedMax = 400.0)).maxAltW84M, 1e-9)
        assertFailsWith<IllegalArgumentException> { DeclarationPlanner.plan(input(plannedMax = 400.0, missionMax = 150.0)) }
    }

    @Test
    fun `window starts after a lead, adds sync grace, and stays inside Blender's 48 h`() {
        val soon = DeclarationPlanner.plan(input(start = now - 60_000, end = now + hour))
        assertEquals(now + DeclarationPlanner.MIN_LEAD_MS, soon.startMs)
        assertEquals(now + hour + DeclarationPlanner.DEFAULT_SYNC_GRACE_MS, soon.endMs)

        val far = DeclarationPlanner.plan(input(start = now + 40 * hour, end = now + 47 * hour))
        assertEquals(now + DeclarationPlanner.MAX_AHEAD_MS, far.endMs)

        assertFailsWith<IllegalArgumentException> { DeclarationPlanner.plan(input(start = now - 3 * hour, end = now - 2 * hour).copy(syncGraceMs = 0)) }
    }

    @Test
    fun `rid state carries height, ground or airborne, vertical speed and accuracy (H2)`() {
        val s = TelemetrySample(seq = 7, t = now, lat = -1.29, lng = 36.82, h = 42.5, vn = 3.0, ve = 4.0, vd = -1.5, yaw = 10.0, flying = true, motors = true, gps = 5)
        val st = RidMapper.state(s, takeoffEllipsoidM = 1645.0, takeoffSigmaM = 4.0)
        val pos = st["position"]!!.jsonObject
        assertEquals("Airborne", st["operational_status"]!!.jsonPrimitive.content)
        assertEquals(1687.5, pos["alt"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(42.5, pos["height"]!!.jsonObject["distance"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("TakeoffLocation", pos["height"]!!.jsonObject["reference"]!!.jsonPrimitive.content)
        assertEquals("VA10m", pos["accuracy_v"]!!.jsonPrimitive.content)
        assertEquals("HA3m", pos["accuracy_h"]!!.jsonPrimitive.content)
        assertEquals(1.5, st["vertical_speed"]!!.jsonPrimitive.double, 1e-9) // NED down -1.5 = climbing
        assertEquals(5.0, st["speed"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(53.1, st["track"]!!.jsonPrimitive.double, 1e-9) // atan2(east 4, north 3)
        assertEquals(false, pos["extrapolated"]!!.jsonPrimitive.boolean)

        val ground = RidMapper.state(s.copy(flying = false, h = 0.0, vn = 0.0, ve = 0.0), 1645.0, 4.0)
        assertEquals("Ground", ground["operational_status"]!!.jsonPrimitive.content)
        assertEquals(10.0, ground["track"]!!.jsonPrimitive.double, 1e-9) // yaw when not moving

        val details = RidMapper.flightDetails(FlightIdentity("decl", "SN", "REG", "OPR", "desc"), -1.2925, 36.8211)
        assertEquals(-1.2925, details["operator_location"]!!.jsonObject["position"]!!.jsonObject["lat"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("SN", details["uas_id"]!!.jsonObject["serial_number"]!!.jsonPrimitive.content)
    }
}
