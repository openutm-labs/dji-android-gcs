package org.openutm.djiflight.core

import org.openutm.djiflight.core.adapter.AircraftSettings
import org.openutm.djiflight.core.adapter.AircraftStatus
import org.openutm.djiflight.core.adapter.Health
import org.openutm.djiflight.core.adapter.RcLostAction
import org.openutm.djiflight.core.adapter.SensorStatus
import org.openutm.djiflight.core.advisory.FlightAdvisor
import org.openutm.djiflight.core.advisory.Level
import org.openutm.djiflight.core.mission.MissionPlan
import org.openutm.djiflight.core.mission.MissionWaypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Pre-flight panel's checks of the aircraft's own settings against the declaration (docs/12). */
class AircraftChecksTest {
    private val manual = TestData.plan(nowMs = 0) // declared up to 100 m, legal 120 m
    private val mission = manual.copy(
        mission = MissionPlan(listOf(MissionWaypoint(-1.29, 36.82, 80.0), MissionWaypoint(-1.291, 36.821, 80.0)), autoSpeedMps = 8.0),
    )

    private fun status(settings: AircraftSettings = AircraftSettings(), compass: Health = Health.OK) =
        AircraftStatus(settings = settings, compass = SensorStatus(compass), imu = SensorStatus(Health.OK))

    private fun codes(s: AircraftStatus, plan: org.openutm.djiflight.core.model.FlightPlan = manual, flying: Boolean = false) =
        FlightAdvisor.aircraftChecks(s, plan, flying).map { it.code }

    @Test
    fun `a return-to-home height above the declared ceiling is flagged, at or below it is not`() {
        val above = FlightAdvisor.aircraftChecks(status(AircraftSettings(returnHomeHeightM = 120)), manual)
        assertEquals(listOf("RTH_ABOVE_DECLARED"), above.map { it.code })
        assertEquals(Level.WARNING, above.single().level)
        assertTrue("120 m" in above.single().message && "100 m" in above.single().message, above.single().message)
        assertEquals(emptyList(), codes(status(AircraftSettings(returnHomeHeightM = 100))))
        assertEquals(emptyList(), codes(status(AircraftSettings(returnHomeHeightM = 30))))
    }

    @Test
    fun `an aircraft height limit above the legal limit is a note, not a warning`() {
        val a = FlightAdvisor.aircraftChecks(status(AircraftSettings(maxAltitudeM = 500)), manual)
        assertEquals(listOf("HEIGHT_LIMIT_ABOVE_LEGAL"), a.map { it.code })
        assertEquals(Level.INFO, a.single().level)
        assertEquals(emptyList(), codes(status(AircraftSettings(maxAltitudeM = 120))))
    }

    @Test
    fun `missions expect return to home on RC loss - manual flights don't`() {
        assertEquals(listOf("RC_LOST_NOT_RTH"), codes(status(AircraftSettings(rcLostAction = RcLostAction.HOVER)), mission))
        assertEquals(emptyList(), codes(status(AircraftSettings(rcLostAction = RcLostAction.RETURN_HOME)), mission))
        assertEquals(emptyList(), codes(status(AircraftSettings(rcLostAction = RcLostAction.HOVER)), manual))
    }

    @Test
    fun `a compass error is a warning on the ground and critical in the air`() {
        assertEquals(Level.WARNING, FlightAdvisor.aircraftChecks(status(compass = Health.ERROR), manual, flying = false).single().level)
        assertEquals(Level.CRITICAL, FlightAdvisor.aircraftChecks(status(compass = Health.ERROR), manual, flying = true).single().level)
    }

    @Test
    fun `nothing is checked when the adapter can't read the aircraft`() {
        assertEquals(emptyList(), FlightAdvisor.aircraftChecks(null, manual).map { it.code })
        assertEquals(emptyList(), codes(AircraftStatus()))
    }
}
