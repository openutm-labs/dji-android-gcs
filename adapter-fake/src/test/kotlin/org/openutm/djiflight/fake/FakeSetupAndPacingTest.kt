package org.openutm.djiflight.fake

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.openutm.djiflight.core.adapter.Calibration
import org.openutm.djiflight.core.adapter.CalibrationProgress
import org.openutm.djiflight.core.adapter.MissionEvent
import org.openutm.djiflight.core.adapter.SettingChange
import org.openutm.djiflight.core.mission.MissionPlan
import org.openutm.djiflight.core.mission.MissionWaypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `sim` app's paced missions and simulated aircraft settings (docs/12, Testing). */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeSetupAndPacingTest {
    private val plan = MissionPlan((0 until 4).map { MissionWaypoint(-1.29 + it * 1e-4, 36.82, 50.0) }, autoSpeedMps = 8.0)

    private fun reached(events: List<MissionEvent>) = events.filterIsInstance<MissionEvent.Progress>().count { it.reached }

    @Test
    fun `reopening setup refreshes the read time without changing settings`() = runTest {
        var now = 1_000L
        val setup = FakeAircraftSetup(backgroundScope, writeDelayMs = 10, nowMs = { now })
        val original = setup.status.value.settings
        assertTrue(setup.refresh().isSuccess)
        assertEquals(1_000L, setup.status.value.readAtMs)
        now += 6 * 60_000
        assertTrue(setup.refresh().isSuccess)
        assertEquals(361_000L, setup.status.value.readAtMs)
        assertEquals(original, setup.status.value.settings)
    }

    @Test
    fun `without a pace the whole route is reported at once, as the tests expect`() = runTest {
        val op = FakeMissionOperator()
        val events = mutableListOf<MissionEvent>()
        backgroundScope.launch { op.events.toList(events) }
        runCurrent()
        op.upload(plan)
        op.start()
        runCurrent()
        assertEquals(4, reached(events))
        assertTrue(events.last() == MissionEvent.Finished(null))
    }

    @Test
    fun `a paced mission reaches one waypoint per step, holds while paused and stops on Stop`() = runTest {
        val op = FakeMissionOperator(backgroundScope, stepMs = 1_000)
        val events = mutableListOf<MissionEvent>()
        backgroundScope.launch { op.events.toList(events) }
        runCurrent()
        op.upload(plan)
        op.start()
        advanceTimeBy(2_500)
        assertEquals(2, reached(events))
        op.pause()
        advanceTimeBy(5_000)
        assertEquals(2, reached(events), "no progress while paused")
        op.resume()
        advanceTimeBy(1_100)
        assertEquals(3, reached(events))
        op.stop()
        advanceTimeBy(10_000)
        assertEquals(3, reached(events), "nothing after Stop")
        assertTrue(events.none { it is MissionEvent.Finished }, "a Stop leaves the runner to mark the mission interrupted")
    }

    @Test
    fun `simulated settings refuse values the SDKs refuse, and calibrations step through to complete`() = runTest {
        val setup = FakeAircraftSetup(backgroundScope, writeDelayMs = 10, stepMs = 100)
        assertTrue(setup.set(SettingChange.ReturnHomeHeight(60)).isSuccess)
        assertEquals(60, setup.status.value.settings.returnHomeHeightM)
        assertTrue(setup.set(SettingChange.ReturnHomeHeight(5)).isFailure)
        assertEquals(60, setup.status.value.settings.returnHomeHeightM, "a refused write leaves the aircraft's value")

        setup.calibrate(Calibration.COMPASS)
        runCurrent()
        assertEquals(CalibrationProgress.State.RUNNING, setup.status.value.calibration?.state)
        assertEquals(FakeAircraftSetup.COMPASS_LEVEL, setup.status.value.calibration?.instruction)
        advanceTimeBy(150)
        assertEquals(FakeAircraftSetup.COMPASS_NOSE_DOWN, setup.status.value.calibration?.instruction)
        advanceTimeBy(150)
        assertEquals(CalibrationProgress.State.SUCCEEDED, setup.status.value.calibration?.state)
    }
}
