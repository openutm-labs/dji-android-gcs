package org.openutm.djiflight.core

import org.openutm.djiflight.core.altitude.AltitudeCandidate
import org.openutm.djiflight.core.altitude.AltitudeSource
import org.openutm.djiflight.core.altitude.GnssDatumCheck
import org.openutm.djiflight.core.altitude.TakeoffReferenceResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AltitudeChoiceTest {
    private val device = AltitudeCandidate(AltitudeSource.DEVICE_GNSS, 312.0, 8.0)
    private val dji = AltitudeCandidate(AltitudeSource.DJI_TAKEOFF_MSL, 268.0, 10.0)

    @Test
    fun `the pilot's pick settles a disagreement and survives the next resolution`() {
        val ref = TakeoffReferenceResolver.resolve(null, device, dji)!!
        assertTrue(ref.pilotCheckNeeded)
        assertEquals(AltitudeSource.DEVICE_GNSS, ref.chosen.source)
        val picked = TakeoffReferenceResolver.choose(ref, AltitudeSource.DJI_TAKEOFF_MSL)!!
        assertFalse(picked.pilotCheckNeeded)
        assertEquals(268.0, picked.chosen.ellipsoidM)
        // At take-off the heights are read again: the pilot's source stays chosen, with the newer value.
        val again = TakeoffReferenceResolver.resolve(null, device.copy(ellipsoidM = 314.0), dji.copy(ellipsoidM = 267.5))!!
        val kept = TakeoffReferenceResolver.keepChoice(picked, again)
        assertEquals(AltitudeSource.DJI_TAKEOFF_MSL, kept.chosen.source)
        assertEquals(267.5, kept.chosen.ellipsoidM)
        assertFalse(kept.pilotCheckNeeded)
        // Nothing to pick from a source that isn't a candidate; no pick, no change.
        assertNull(TakeoffReferenceResolver.choose(ref, AltitudeSource.RTK))
        assertEquals(again, TakeoffReferenceResolver.keepChoice(ref, again))
    }

    @Test
    fun `a controller reporting height above sea level is caught where the geoid is far from the ellipsoid`() {
        // Budapest: geoid about +44 m. Ground 110 m above sea level is 154 m above the ellipsoid.
        assertEquals(GnssDatumCheck.Verdict.ELLIPSOID, GnssDatumCheck.check(156.0, 110.0, 44.0, 15.0))
        assertEquals(GnssDatumCheck.Verdict.LIKELY_SEA_LEVEL, GnssDatumCheck.check(112.0, 110.0, 44.0, 15.0))
        // Nairobi: about -16 m, within the readings' error: can't tell.
        assertEquals(GnssDatumCheck.Verdict.UNCLEAR, GnssDatumCheck.check(1645.0, 1661.0, -16.0, 15.0))
        // Bengaluru: about -88 m.
        assertEquals(GnssDatumCheck.Verdict.LIKELY_SEA_LEVEL, GnssDatumCheck.check(918.0, 920.0, -88.0, 15.0))
    }
}
