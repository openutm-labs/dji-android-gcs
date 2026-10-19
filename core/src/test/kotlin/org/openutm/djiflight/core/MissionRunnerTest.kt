package org.openutm.djiflight.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.openutm.djiflight.core.adapter.AircraftIdentity
import org.openutm.djiflight.core.adapter.DroneAdapter
import org.openutm.djiflight.core.adapter.DroneCapabilities
import org.openutm.djiflight.core.adapter.DroneState
import org.openutm.djiflight.core.adapter.LinkState
import org.openutm.djiflight.core.adapter.MissionEvent
import org.openutm.djiflight.core.adapter.MissionOperator
import org.openutm.djiflight.core.adapter.MissionSupport
import org.openutm.djiflight.core.mission.FileMissionProgressStore
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.core.mission.MissionPhase
import org.openutm.djiflight.core.mission.MissionPlan
import org.openutm.djiflight.core.mission.MissionRunner
import org.openutm.djiflight.core.mission.MissionWaypoint
import org.openutm.djiflight.core.mission.stableHash
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class MissionRunnerTest {
    @TempDir lateinit var dir: File

    /** Records uploads; the test plays the aircraft's events. */
    private class ScriptedOperator : MissionOperator {
        val flow = MutableSharedFlow<MissionEvent>(extraBufferCapacity = 256)
        override val events: SharedFlow<MissionEvent> = flow
        val uploads = mutableListOf<MissionPlan>()
        var starts = 0
        override suspend fun upload(plan: MissionPlan): Result<Unit> {
            uploads += plan
            flow.emit(MissionEvent.Uploaded)
            return Result.success(Unit)
        }
        override suspend fun start(): Result<Unit> {
            starts++
            flow.emit(MissionEvent.Started)
            return Result.success(Unit)
        }
        override suspend fun pause() = Result.success(Unit)
        override suspend fun resume() = Result.success(Unit)
        override suspend fun stop() = Result.success(Unit)

        /** v4 style: heading to each waypoint, then reached it. */
        fun fly(range: IntRange, total: Int) = range.forEach {
            flow.tryEmit(MissionEvent.Progress(it, total, reached = false))
            flow.tryEmit(MissionEvent.Progress(it, total, reached = true))
        }
    }

    private class TestAdapter(limits: MissionLimits, support: MissionSupport, camera: org.openutm.djiflight.core.mission.CameraModel? = null) : DroneAdapter {
        val op = ScriptedOperator()
        override val link = MutableStateFlow<LinkState>(LinkState.Connected(AircraftIdentity("SN1", "Test aircraft")))
        override val capabilities: StateFlow<DroneCapabilities?> = MutableStateFlow(DroneCapabilities("Test aircraft", support, limits, hasRtk = false, nativeRateHz = 10, camera = camera))
        override val states: SharedFlow<DroneState> = MutableSharedFlow()
        override fun missionOperator(): MissionOperator = op
        override fun start() = Unit
        override fun stop() = Unit
        override fun resubscribe() = Unit
    }

    private fun plan(n: Int) = MissionPlan(
        waypoints = (0 until n).map { MissionWaypoint(-1.28 + it * 0.0005, 36.82 + (it % 2) * 0.0005, 80.0) },
        autoSpeedMps = 8.0, photoDistanceIntervalM = 20.0,
    )

    private fun TestScope.runner(adapter: TestAdapter, limits: MissionLimits = MissionLimits.WPML) =
        MissionRunner(backgroundScope, adapter, limits, FileMissionProgressStore(File(dir, "progress.json"))) { 120.0 }

    @Test
    fun `a changed or unknown survey camera blocks upload until the route is reviewed`() = runTest(UnconfinedTestDispatcher()) {
        val expected = org.openutm.djiflight.core.mission.CameraModel.MAVIC_3E
        for (camera in listOf(null, org.openutm.djiflight.core.mission.CameraModel.P4P_V2, expected)) {
            val adapter = TestAdapter(MissionLimits.WPML, MissionSupport.WPML, camera)
            val r = runner(adapter)
            r.load("camera-check", plan(4).copy(cameraName = expected.name))
            r.upload(); advanceUntilIdle()
            assertEquals(if (camera == expected) 1 else 0, adapter.op.uploads.size)
            assertEquals(if (camera == expected) MissionPhase.READY else MissionPhase.FAILED, r.ui.value.phase)
        }
    }

    @Test
    fun `a mission flown to the end is done`() = runTest(UnconfinedTestDispatcher()) {
        val a = TestAdapter(MissionLimits.WPML, MissionSupport.WPML)
        val r = runner(a)
        val p = plan(10)
        r.load("f1", p)
        r.upload(); r.start(); advanceUntilIdle()
        a.op.fly(0..9, 10)
        a.op.flow.tryEmit(MissionEvent.Finished(null)); advanceUntilIdle()
        assertEquals(MissionPhase.DONE, r.ui.value.phase)
        assertEquals(p, a.op.uploads.single())
    }

    @Test
    fun `RTH after waypoint 6 then battery swap - resume uploads the rest from waypoint 6 and finishes`() = runTest(UnconfinedTestDispatcher()) {
        val a = TestAdapter(MissionLimits.WPML, MissionSupport.WPML)
        val r = runner(a)
        val p = plan(10)
        r.load("f1", p)
        r.upload(); r.start(); advanceUntilIdle()
        a.op.fly(0..5, 10)                                       // reached index 5 = waypoint 6
        a.op.flow.tryEmit(MissionEvent.Progress(6, 10, reached = false)) // heading to 7 when the battery runs low
        a.link.value = LinkState.Disconnected; advanceUntilIdle() // RTH, land, swap
        assertEquals(MissionPhase.INTERRUPTED, r.ui.value.phase)
        assertEquals(6, r.ui.value.resumeFrom)

        a.link.value = LinkState.Connected(AircraftIdentity("SN1", "Test aircraft"))
        r.resume(); advanceUntilIdle()
        val rest = a.op.uploads.last()
        assertEquals(p.waypoints.subList(5, 10), rest.waypoints)   // flies back to waypoint 6, re-flies the cut segment
        assertEquals(p.photoDistanceIntervalM, rest.photoDistanceIntervalM)
        assertEquals(MissionPhase.READY, r.ui.value.phase)
        r.start(); advanceUntilIdle()
        a.op.fly(0..4, 5)
        assertEquals(10, r.ui.value.waypoint)                      // whole-mission numbering
        a.op.flow.tryEmit(MissionEvent.Finished(null)); advanceUntilIdle()
        assertEquals(MissionPhase.DONE, r.ui.value.phase)
    }

    @Test
    fun `a clean finish short of the last waypoint (v4 RTH) is an interruption`() = runTest(UnconfinedTestDispatcher()) {
        val a = TestAdapter(MissionLimits.WAYPOINT_V1, MissionSupport.WAYPOINT_V1)
        val r = runner(a, MissionLimits.WAYPOINT_V1)
        r.load("f1", plan(10))
        r.upload(); r.start(); advanceUntilIdle()
        a.op.fly(0..3, 10)
        a.op.flow.tryEmit(MissionEvent.Finished(null)); advanceUntilIdle()
        assertEquals(MissionPhase.INTERRUPTED, r.ui.value.phase)
        assertEquals(4, r.ui.value.resumeFrom)
    }

    @Test
    fun `an operator without reached flags (v5) - a clean finish is complete, an error is an interruption`() = runTest(UnconfinedTestDispatcher()) {
        val a = TestAdapter(MissionLimits.WPML, MissionSupport.WPML)
        val r = runner(a)
        r.load("f1", plan(10))
        r.upload(); r.start(); advanceUntilIdle()
        (0..9).forEach { a.op.flow.tryEmit(MissionEvent.Progress(it, 10, reached = false)) }
        a.op.flow.tryEmit(MissionEvent.Finished(null)); advanceUntilIdle()
        assertEquals(MissionPhase.DONE, r.ui.value.phase)

        r.load("f2", plan(12))
        r.upload(); r.start(); advanceUntilIdle()
        (0..7).forEach { a.op.flow.tryEmit(MissionEvent.Progress(it, 12, reached = false)) } // executing index 7 → reached 6
        a.op.flow.tryEmit(MissionEvent.Finished("mission ended before the last waypoint")); advanceUntilIdle()
        assertEquals(MissionPhase.INTERRUPTED, r.ui.value.phase)
        assertEquals(7, r.ui.value.resumeFrom)
        r.resume(); advanceUntilIdle()
        assertEquals(plan(12).waypoints.subList(6, 12), a.op.uploads.last().waypoints)
    }

    @Test
    fun `Waypoint 1 point 0 - interrupted in part 2 of a 150-waypoint survey, the rest is re-split from the resume point`() = runTest(UnconfinedTestDispatcher()) {
        val a = TestAdapter(MissionLimits.WAYPOINT_V1, MissionSupport.WAYPOINT_V1)
        val r = runner(a, MissionLimits.WAYPOINT_V1)
        val p = plan(150)
        r.load("f1", p)
        assertEquals(2, r.ui.value.parts)                          // 0..98, 98..149
        r.upload(); r.start(); advanceUntilIdle()
        a.op.fly(0..98, 99)
        a.op.flow.tryEmit(MissionEvent.Finished(null)); advanceUntilIdle()
        assertEquals(MissionPhase.PART_DONE, r.ui.value.phase)
        r.upload(); advanceUntilIdle()
        assertEquals(p.waypoints.subList(98, 150), a.op.uploads.last().waypoints)
        r.start(); advanceUntilIdle()
        a.op.fly(0..20, 52)                                        // reached whole-mission index 118
        a.op.flow.tryEmit(MissionEvent.Finished("RC signal lost")); advanceUntilIdle()
        assertEquals(MissionPhase.INTERRUPTED, r.ui.value.phase)
        assertEquals(119, r.ui.value.resumeFrom)
        r.resume(); advanceUntilIdle()
        assertEquals(p.waypoints.subList(118, 150), a.op.uploads.last().waypoints)
        assertEquals(1, r.ui.value.parts)
    }

    @Test
    fun `progress survives an app restart, but not a different plan or flight`() = runTest(UnconfinedTestDispatcher()) {
        val a = TestAdapter(MissionLimits.WPML, MissionSupport.WPML)
        val p = plan(10)
        val first = runner(a)
        first.load("f1", p)
        first.upload(); first.start(); advanceUntilIdle()
        a.op.fly(0..4, 10)

        val restarted = runner(a)
        restarted.load("f1", p)
        assertEquals(MissionPhase.INTERRUPTED, restarted.ui.value.phase)
        assertEquals(5, restarted.ui.value.resumeFrom)

        val other = runner(a)
        other.load("f1", plan(11))
        assertEquals(MissionPhase.READY_TO_UPLOAD, other.ui.value.phase)
        other.load("f2", p)
        assertEquals(MissionPhase.READY_TO_UPLOAD, other.ui.value.phase)
    }

    @Test
    fun `a plan's saved identity is the same in every run, and for the plan read back from the journal`() {
        val p = plan(10)
        val back = org.openutm.djiflight.core.journal.JournalJson.run { decodeFromString(MissionPlan.serializer(), encodeToString(MissionPlan.serializer(), p)) }
        assertEquals(p.stableHash(), back.stableHash())
        // A fixed value: an identity hash (an enum's hashCode()) would differ from run to run, as it did on the emulator.
        assertEquals(EXPECTED_HASH, p.stableHash())
    }

    @Test
    fun `the pilot's Stop can be resumed or started over`() = runTest(UnconfinedTestDispatcher()) {
        val a = TestAdapter(MissionLimits.WPML, MissionSupport.WPML)
        val r = runner(a)
        val p = plan(10)
        r.load("f1", p)
        r.upload(); r.start(); advanceUntilIdle()
        a.op.fly(0..2, 10)
        r.stop(); advanceUntilIdle()
        assertEquals(MissionPhase.INTERRUPTED, r.ui.value.phase)
        assertEquals(3, r.ui.value.resumeFrom)
        r.restart(); advanceUntilIdle()
        assertEquals(p, a.op.uploads.last())
        assertEquals(MissionPhase.READY, r.ui.value.phase)
        assertNull(r.ui.value.resumeFrom)
    }

    @Test
    fun `a disconnect before Go asks for a fresh upload`() = runTest(UnconfinedTestDispatcher()) {
        val a = TestAdapter(MissionLimits.WPML, MissionSupport.WPML)
        val r = runner(a)
        r.load("f1", plan(10))
        r.upload(); advanceUntilIdle()
        assertEquals(MissionPhase.READY, r.ui.value.phase)
        a.link.value = LinkState.Connecting; advanceUntilIdle()
        assertEquals(MissionPhase.READY_TO_UPLOAD, r.ui.value.phase)
    }

    private companion object {
        /** plan(10).stableHash(), computed once; it must not change between runs. */
        const val EXPECTED_HASH = 404_700_543
    }
}
