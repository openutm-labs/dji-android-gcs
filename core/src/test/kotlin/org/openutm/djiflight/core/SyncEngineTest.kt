package org.openutm.djiflight.core

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.openutm.djiflight.core.altitude.AltitudeCandidate
import org.openutm.djiflight.core.altitude.Egm96Grid
import org.openutm.djiflight.core.declaration.DeclarationPlan
import org.openutm.djiflight.core.geo.BBox
import org.openutm.djiflight.core.journal.FlightRepository
import org.openutm.djiflight.core.model.TelemetrySample
import org.openutm.djiflight.core.rid.FlightIdentity
import org.openutm.djiflight.core.session.FlightEvent
import org.openutm.djiflight.core.session.FlightRecorder
import org.openutm.djiflight.core.session.Phase
import org.openutm.djiflight.core.sync.BackendResult
import org.openutm.djiflight.core.sync.OperationState
import org.openutm.djiflight.core.sync.SyncConfig
import org.openutm.djiflight.core.sync.SyncEngine
import org.openutm.djiflight.core.sync.SyncStatus
import org.openutm.djiflight.core.sync.UtmBackend
import org.openutm.djiflight.core.time.ManualClock
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A scripted Blender: records what it received and fails on demand. */
class FakeBackend(private val clock: ManualClock) : UtmBackend {
    var online = true
    var declareTimesOutAfterCreating = false
    val declarations = LinkedHashMap<String, DeclarationPlan>()
    val states = ArrayList<Pair<String, OperationState>>()
    val received = ArrayList<TelemetrySample>()
    var requests = 0

    private fun offline() = BackendResult.Transient("offline")

    override suspend fun declare(plan: DeclarationPlan): BackendResult<String> {
        requests++
        if (!online) return offline()
        val id = "decl-${declarations.size + 1}"
        declarations[id] = plan
        if (declareTimesOutAfterCreating) { declareTimesOutAfterCreating = false; return BackendResult.Transient("read timed out") }
        return BackendResult.Ok(id)
    }

    override suspend fun findDeclaration(clientRef: String, startMs: Long, endMs: Long): BackendResult<String?> {
        requests++
        if (!online) return offline()
        return BackendResult.Ok(declarations.entries.firstOrNull { it.value.clientRef == clientRef }?.key)
    }

    override suspend fun setState(declarationId: String, state: OperationState): BackendResult<Unit> {
        requests++
        if (!online) return offline()
        states += declarationId to state
        return BackendResult.Ok(Unit)
    }

    override suspend fun sendTelemetry(flight: FlightIdentity, samples: List<TelemetrySample>, reference: AltitudeCandidate): BackendResult<Unit> {
        requests++
        if (!online) return offline()
        val plan = declarations[flight.declarationId]!!
        val now = clock.nowMs()
        if (now < plan.startMs || now > plan.endMs) return BackendResult.WindowClosed("does not match any current operation")
        if (states.lastOrNull { it.first == flight.declarationId }?.second != OperationState.ACTIVATED) return BackendResult.NotActivated("not one of Activated")
        received += samples
        return BackendResult.Ok(Unit)
    }

    override suspend fun zones(area: BBox, startMs: Long, endMs: Long) = BackendResult.Ok("""{"count":0,"results":[]}""")
}

class SyncEngineTest {
    @TempDir
    lateinit var tmp: File
    private val grid = Egm96Grid.bundled()
    private val t0 = 1_792_142_000_000L

    private inner class Rig {
        val clock = ManualClock(t0)
        val backend = FakeBackend(clock)
        val engine = SyncEngine(backend, clock, SyncConfig(batchSize = 50, declareLeadMs = 5_000))
        val journal = FlightRepository(tmp).create("flight-${System.nanoTime()}")
        val rec = FlightRecorder(journal, grid)

        suspend fun pass() = engine.syncOnce(rec.snapshot, journal, rec.lastRecordedSeq) { e -> rec.record(e); rec.snapshot }

        /** Fly [seconds] at 10 Hz from the current clock, advancing it. */
        fun fly(seconds: Int, flying: Boolean = true) {
            repeat(seconds * 10) {
                clock.advance(100)
                rec.onDroneState(TestData.state(clock.now, h = if (flying) 30.0 else 0.0, flying = flying))
            }
        }
    }

    @Test
    fun `offline flight is declared beforehand, buffered, and fully backfilled later (H3)`() = runTest {
        val r = Rig()
        r.rec.record(FlightEvent.Planned(t0, TestData.plan(t0, flightId = r.journal.flightId, startInMs = 0)))
        assertEquals(SyncStatus.UP_TO_DATE, r.pass().status)          // declared while online at base
        assertEquals(Phase.DECLARED, r.rec.snapshot.phase)

        r.backend.online = false                                       // drive to the site: no coverage
        r.clock.advance(10 * 60_000)
        r.fly(5, flying = false); r.fly(600); r.fly(5, flying = false) // 10 minutes in the air
        r.rec.record(FlightEvent.FinishRequested(r.clock.now))
        repeat(5) { r.clock.advance(61_000); assertEquals(SyncStatus.BACKOFF, r.pass().status) }

        r.backend.online = true                                        // back in coverage hours later
        r.clock.advance(3 * 3_600_000L)
        var out = r.pass()
        while (out.status == SyncStatus.UP_TO_DATE) out = r.pass()
        assertEquals(SyncStatus.DONE, out.status)
        val recorded = r.journal.readSamples()
        assertEquals(recorded.map { it.seq }, r.backend.received.map { it.seq })   // every sample, in order, once
        assertTrue(recorded.size > 600)
        assertEquals(listOf(OperationState.ACTIVATED, OperationState.ENDED), r.backend.states.map { it.second })
        assertEquals(Phase.CLOSED, r.rec.snapshot.phase)
    }

    @Test
    fun `a declare that timed out after the server created it is found, not duplicated`() = runTest {
        val r = Rig()
        r.rec.record(FlightEvent.Planned(t0, TestData.plan(t0, flightId = r.journal.flightId)))
        r.backend.declareTimesOutAfterCreating = true
        assertEquals(SyncStatus.BACKOFF, r.pass().status)
        assertTrue(r.rec.snapshot.declareUnanswered)
        r.clock.advance(3_000)
        r.pass()
        assertEquals(1, r.backend.declarations.size)
        assertEquals("decl-1", r.rec.snapshot.declarationId)
    }

    @Test
    fun `telemetry waits for the window to open instead of declaring it expired`() = runTest {
        val r = Rig()
        r.rec.record(FlightEvent.Planned(t0, TestData.plan(t0, flightId = r.journal.flightId, startInMs = 10 * 60_000)))
        r.pass()
        r.fly(30) // pilot took off 10 minutes early
        val early = r.pass()
        assertEquals(SyncStatus.BACKOFF, early.status)
        assertEquals(null, r.rec.snapshot.windowExpired)
        r.clock.advance(10 * 60_000)
        var out = r.pass()
        while (out.status == SyncStatus.UP_TO_DATE && r.journal.cursor < r.rec.lastRecordedSeq) out = r.pass()
        assertEquals(r.rec.lastRecordedSeq, r.journal.cursor)
        assertTrue(r.backend.received.first().t < r.backend.declarations.values.single().startMs) // pre-start samples kept
    }

    @Test
    fun `samples still on the device when the window closes are kept and flagged`() = runTest {
        val r = Rig()
        r.rec.record(FlightEvent.Planned(t0, TestData.plan(t0, flightId = r.journal.flightId, startInMs = 0, durationMs = 20 * 60_000)))
        r.pass()
        r.backend.online = false
        r.fly(60)
        r.clock.advance(7 * 3_600_000L) // past planned end + 6 h grace
        r.backend.online = true
        val out = r.pass()
        assertEquals(SyncStatus.DONE, out.status) // Ended on the server, but…
        assertEquals(Phase.CLOSED, r.rec.snapshot.phase)
        assertTrue(r.rec.snapshot.windowExpired != null) // …the samples are flagged, still in the journal
        assertTrue(r.backend.received.isEmpty())
        assertTrue(r.journal.readSamples().size >= 60)
    }

    @Test
    fun `cancel before take-off cancels on the server`() = runTest {
        val r = Rig()
        r.rec.record(FlightEvent.Planned(t0, TestData.plan(t0, flightId = r.journal.flightId)))
        r.pass()
        r.rec.record(FlightEvent.CancelRequested(t0 + 1000))
        assertEquals(SyncStatus.DONE, r.pass().status)
        assertEquals(listOf(OperationState.CANCELLED), r.backend.states.map { it.second })
        assertEquals(Phase.CANCELLED, r.rec.snapshot.phase)
    }

    @Test
    fun `backoff doubles up to the cap and resets on success`() = runTest {
        val r = Rig()
        r.rec.record(FlightEvent.Planned(t0, TestData.plan(t0, flightId = r.journal.flightId)))
        r.backend.online = false
        val delays = (1..7).map {
            val o = r.pass()
            val d = o.retryAtMs!! - r.clock.now
            r.clock.now = o.retryAtMs!!
            d
        }
        assertEquals(listOf(2_000L, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000), delays)
        r.backend.online = true
        assertEquals(SyncStatus.UP_TO_DATE, r.pass().status)
    }
}
