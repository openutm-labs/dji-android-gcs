package org.openutm.djiflight.core

import org.openutm.djiflight.core.altitude.AltitudeCandidate
import org.openutm.djiflight.core.altitude.AltitudeSource
import org.openutm.djiflight.core.altitude.Egm96Grid
import org.openutm.djiflight.core.altitude.GnssAverager
import org.openutm.djiflight.core.altitude.GnssFix
import org.openutm.djiflight.core.altitude.TakeoffReferenceResolver
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.time.Rfc3339
import java.time.Instant
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeoTimeAltitudeTest {
    private val grid = Egm96Grid.bundled()

    @Test
    fun `rfc3339 matches java time`() {
        val rnd = Random(3339)
        repeat(2000) {
            val ms = rnd.nextLong(-2_000_000_000_000L, 4_000_000_000_000L)
            val expected = Instant.ofEpochMilli(ms).toString().let { s ->
                // java.time drops zero millis; normalise to always-3-digit form
                val noZ = s.removeSuffix("Z")
                if ('.' in noZ) noZ.padEnd(23, '0').take(23) + "Z" else "$noZ.000Z"
            }
            assertEquals(expected, Rfc3339.format(ms), "epoch ms $ms")
        }
        assertEquals("2026-10-16", Rfc3339.date(1_792_142_000_000L))
    }

    @Test
    fun `egm96 matches PROJ on the same grid within 2 cm`() {
        val lines = javaClass.getResource("/egm96-fixture.csv")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(lines.size >= 50)
        var worst = 0.0
        for (l in lines) {
            val (lat, lon, n) = l.split(",").map(String::toDouble)
            val err = abs(grid.undulationM(lat, lon) - n)
            worst = maxOf(worst, err)
            assertTrue(err < 0.02, "N at $lat,$lon: got ${grid.undulationM(lat, lon)}, PROJ $n")
        }
        println("EGM96 worst |app - PROJ| = ${"%.4f".format(worst)} m over ${lines.size} points")
        // Longitude wraps; the poles clamp.
        assertEquals(grid.undulationM(10.0, -180.0), grid.undulationM(10.0, 180.0), 1e-9)
        assertEquals(grid.undulationM(5.0, 190.0), grid.undulationM(5.0, -170.0), 1e-9)
    }

    @Test
    fun `circle, containment and distance`() {
        val c = TestData.NAIROBI
        val ring = Geo.circle(c, 500.0)
        assertEquals(ring.first(), ring.last())
        assertTrue(Geo.contains(ring, c))
        assertFalse(Geo.contains(ring, Geo.destination(c, 45.0, 520.0)))
        ring.dropLast(1).forEach { assertEquals(500.0, Geo.distanceM(c, it), 0.5) }
        assertEquals(0.0, Geo.distanceToRingM(c, ring))
        assertEquals(100.0, Geo.distanceToRingM(Geo.destination(c, 0.0, 600.0), ring), 2.0)
    }

    @Test
    fun `buffered hull covers every point by the buffer`() {
        val pts = listOf(LatLng(-1.29, 36.82), LatLng(-1.289, 36.823), LatLng(-1.292, 36.824))
        val hull = Geo.bufferedHull(pts, 30.0)
        for (p in pts) for (b in 0 until 360 step 15) {
            assertTrue(Geo.contains(hull, Geo.destination(p, b.toDouble(), 29.5)), "point $p bearing $b")
        }
    }

    @Test
    fun `takeoff reference prefers RTK, then device GNSS, and flags disagreement`() {
        val device = AltitudeCandidate(AltitudeSource.DEVICE_GNSS, 1650.0, 4.0)
        val djiClose = AltitudeCandidate(AltitudeSource.DJI_TAKEOFF_MSL, 1655.0, 10.0)
        val djiFar = AltitudeCandidate(AltitudeSource.DJI_TAKEOFF_MSL, 1670.0, 10.0)
        val rtk = AltitudeCandidate(AltitudeSource.RTK, 1652.31, 0.05)

        assertEquals(AltitudeSource.DEVICE_GNSS, TakeoffReferenceResolver.resolve(null, device, djiClose)!!.chosen.source)
        assertFalse(TakeoffReferenceResolver.resolve(null, device, djiClose)!!.pilotCheckNeeded)
        assertTrue(TakeoffReferenceResolver.resolve(null, device, djiFar)!!.pilotCheckNeeded)
        assertEquals(AltitudeSource.RTK, TakeoffReferenceResolver.resolve(rtk, device, djiFar)!!.chosen.source)
        assertFalse(TakeoffReferenceResolver.resolve(rtk, device, djiFar)!!.pilotCheckNeeded)
        assertEquals(AltitudeSource.DJI_TAKEOFF_MSL, TakeoffReferenceResolver.resolve(null, null, djiFar)!!.chosen.source)

        // DJI's sea-level value becomes an ellipsoid height through EGM96 (Nairobi N is about -16 m).
        val dji = TakeoffReferenceResolver.djiCandidate(1661.0, TestData.NAIROBI, grid)
        assertNotNull(dji)
        assertEquals(1661.0 - 16.06, dji.ellipsoidM, 0.05)
        assertEquals(null, TakeoffReferenceResolver.djiCandidate(Double.NaN, TestData.NAIROBI, grid))
    }

    @Test
    fun `gnss averager waits for enough fixes and keeps a conservative sigma`() {
        val avg = GnssAverager(minFixes = 5)
        repeat(4) { avg.add(GnssFix(it * 1000L, it * 1000L, -1.29, 36.82, 1645.0 + it, 3.0)) }
        assertEquals(null, avg.candidate())
        avg.add(GnssFix(4000, 4000, -1.29, 36.82, 1649.0, 3.0))
        val c = avg.candidate()!!
        assertEquals(1647.0, c.ellipsoidM, 1e-9)
        assertEquals(3.0, c.sigmaM, 1e-9) // not 3/sqrt(5): phone errors are correlated
    }
}
