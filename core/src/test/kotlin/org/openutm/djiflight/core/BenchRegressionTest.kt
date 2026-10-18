package org.openutm.djiflight.core

import org.openutm.djiflight.core.adapter.CompassHealth
import org.openutm.djiflight.core.adapter.Health
import org.openutm.djiflight.core.adapter.ObstacleSensing
import org.openutm.djiflight.core.export.FlightExport
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.session.FlightSnapshot
import org.openutm.djiflight.core.zones.Zone
import org.openutm.djiflight.core.zones.ZoneIndex
import org.openutm.djiflight.core.zones.ZoneSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchRegressionTest {
    @Test
    fun `compass interference overrides a false hasError on either SDK`() {
        assertEquals(Health.WARNING, CompassHealth.status(listOf("NORMAL_MODULUS", "WEAK_MODULUS"), false).health)
        assertEquals(Health.WARNING, CompassHealth.status(listOf("SERIOUS_MODULUS"), false).health)
        assertEquals(Health.ERROR, CompassHealth.status(listOf("DIRECTION_EXCEPTION", "NORMAL_MODULUS"), false).health)
        assertEquals(Health.ERROR, CompassHealth.status(listOf("NORMAL_MODULUS"), true).health)
        assertEquals(Health.OK, CompassHealth.status(listOf("NORMAL_MODULUS"), false).health)
        assertEquals(Health.UNKNOWN, CompassHealth.status(emptyList(), false).health)
        assertEquals(Health.WARNING, CompassHealth.status(listOf("NORMAL_MODULUS"), false, true).health)
    }

    @Test
    fun `positioning cameras do not imply obstacle sensing`() {
        listOf("DJI_MINI_3", "UNKNOWN", null).forEach { assertFalse(ObstacleSensing.supportsV5(it), it) }
        listOf("DJI_MINI_3_PRO", "DJI_MAVIC_3_ENTERPRISE_SERIES").forEach { assertTrue(ObstacleSensing.supportsV5(it), it) }
        listOf("DJI_MINI_2", "DJI_MINI_SE", "MAVIC_MINI", "PHANTOM_3_STANDARD", "INSPIRE_1", "MATRICE_600", "UNKNOWN_AIRCRAFT", null)
            .forEach { assertFalse(ObstacleSensing.supportsV4(it), it) }
        listOf("P_4_MULTISPECTRAL", "PHANTOM_4_PRO_V2", "MAVIC_2_PRO", "MAVIC_AIR_2", "Spark", "MATRICE_300_RTK")
            .forEach { assertTrue(ObstacleSensing.supportsV4(it), it) }
    }

    @Test
    fun `export without a site uses the flight date once and the id once`() {
        val f = FlightSnapshot("12345678-other", plan = TestData.plan(0), lastEventAt = 0)
        assertEquals("flight-1970-01-01-12345678.geojson", FlightExport.fileName(f, "geojson"))
        assertEquals("flight-1970-01-01-12345678.csv", FlightExport.fileName(f.copy(plan = null), "csv"))
        assertEquals("flight-Test_site-12345678.csv", FlightExport.fileName(f.copy(plan = f.plan!!.copy(siteName = "Test site")), "csv"))
        assertEquals(FlightExport.fileName(f, "csv"), FlightExport.fileName(f.copy(plan = f.plan!!.copy(siteName = "  ")), "csv"))
    }

    @Test
    fun `zone summaries merge names but preserve all categories and spatial checks`() {
        val ring = Geo.circle(TestData.NAIROBI, 100.0)
        fun zone(id: String, kind: String?, source: String = "DJI GEO", upper: Double? = null) = Zone(id, "Test airport", listOf(ring), null, upper, "AGL", source, kind)
        val zones = listOf(zone("a", "AUTHORIZATION"), zone("b", "AUTHORIZATION"), zone("c", "WARNING", upper = 60.0), zone("d", null, "blender"))
        val hits = ZoneIndex(zones, 0).intersecting(ring)
        assertEquals(4, hits.size)
        val summary = ZoneSummary.of(hits)
        assertEquals(2, summary.size)
        assertEquals(setOf("AUTHORIZATION", "WARNING", "ALTITUDE"), summary.first().categories)
        assertEquals("blender", summary.last().source)
        assertTrue(ZoneSummary.of(emptyList()).isEmpty())
    }
}
