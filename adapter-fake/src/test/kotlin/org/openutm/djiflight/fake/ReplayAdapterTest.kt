package org.openutm.djiflight.fake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.openutm.djiflight.core.FlightService
import org.openutm.djiflight.core.ServiceConfig
import org.openutm.djiflight.core.TestData
import org.openutm.djiflight.core.adapter.DroneState
import org.openutm.djiflight.core.adapter.LinkState
import org.openutm.djiflight.core.altitude.AltitudeCandidate
import org.openutm.djiflight.core.altitude.Egm96Grid
import org.openutm.djiflight.core.declaration.DeclarationPlan
import org.openutm.djiflight.core.geo.BBox
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.journal.FlightRepository
import org.openutm.djiflight.core.model.TelemetrySample
import org.openutm.djiflight.core.rid.FlightIdentity
import org.openutm.djiflight.core.sync.BackendResult
import org.openutm.djiflight.core.sync.OperationState
import org.openutm.djiflight.core.sync.SyncEngine
import org.openutm.djiflight.core.sync.UtmBackend
import org.openutm.djiflight.core.time.CorrectedClock
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayAdapterTest {
    @TempDir
    lateinit var tmp: File

    /** Cancelled after every test, pass or fail, so nothing writes into a deleted temp dir. */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `bundled SITL track parses with its take-off references`() {
        val t = Track.bundled("sitl-auto-mission.jsonl")
        assertEquals(476, t.states.size)
        assertEquals(137.08, t.header.djiTakeoffMslM!!, 1e-9)
        assertEquals(180.94, t.header.deviceGnssEllipsoidM!!, 1e-9)
        // The two references agree through EGM96, as they should for SITL ground truth.
        val n = Egm96Grid.bundled().undulationM(t.header.takeoff.lat, t.header.takeoff.lng)
        assertEquals(t.header.deviceGnssEllipsoidM!!, t.header.djiTakeoffMslM!! + n, 0.05)
        assertTrue(t.states.any { it.flying } && t.states.first().h < 0.5)
    }

    @Test
    fun `ground lead-in shifts the track and stays on the ground`() {
        val t = Track.bundled("sitl-auto-mission.jsonl")
        val led = t.withGroundLead(60_000)
        assertEquals(t.states.size + 600, led.states.size)
        assertTrue(led.states.take(600).none { it.flying || it.motors })
        assertEquals(t.states.first { it.flying }.t + 60_000, led.states.first { it.flying }.t)
        assertEquals(t.durationMs + 60_000, led.durationMs)
    }

    @Test
    fun `synthetic manual flight takes off, circles and lands`() {
        val t = SyntheticTracks.manualVlos(TestData.NAIROBI)
        val flying = t.states.filter { it.flying }
        assertTrue(flying.isNotEmpty() && !t.states.first().flying && !t.states.last().flying)
        assertEquals(40.0, flying.maxOf { it.h }, 1e-9)
        assertTrue(flying.maxOf { Geo.distanceM(TestData.NAIROBI, org.openutm.djiflight.core.geo.LatLng(it.lat, it.lng)) } in 119.0..121.0)
    }

    @Test
    fun `a stalled process pauses the replay instead of dropping states`(): Unit = runBlocking {
        // What the overloaded emulator did: the replay's thread stops for seconds mid-flight.
        val single = java.util.concurrent.Executors.newSingleThreadExecutor()
        val replayScope = CoroutineScope(single.asCoroutineDispatcher() + SupervisorJob())
        val track = SyntheticTracks.manualVlos(TestData.NAIROBI)
        val adapter = ReplayDroneAdapter(track, replayScope, speed = 20.0)
        val count = java.util.concurrent.atomic.AtomicInteger()
        scope.launch { adapter.states.collect { count.incrementAndGet() } }
        adapter.start()
        delay(1_500)
        replayScope.launch { Thread.sleep(2_500) }
        withTimeout(30_000) { while (!adapter.finished) delay(50) }
        replayScope.cancel()
        single.shutdown()
        assertTrue(count.get() >= track.states.size, "received ${count.get()} of ${track.states.size} states")
    }

    @Test
    fun `link drop and GPS loss are replayed`(): Unit = runBlocking {
        val track = SyntheticTracks.manualVlos(TestData.NAIROBI).withFaults(listOf(
            TrackFault(15_000, FaultKind.GPS_LOSS, 2_000),
            TrackFault(20_000, FaultKind.LINK_DROP, 3_000),
        ))
        val adapter = ReplayDroneAdapter(track, scope, speed = 10.0)
        val seen = Collections.synchronizedList(ArrayList<DroneState>())
        val links = Collections.synchronizedList(ArrayList<LinkState>())
        scope.launch { adapter.states.collect { seen += it } }
        scope.launch { adapter.link.collect { links += it } }
        adapter.start()
        withTimeout(10_000) { while (seen.size < 300) delay(50) }
        assertTrue(seen.any { it.lat.isNaN() }, "GPS loss produced no NaN positions")
        assertTrue(links.count { it is LinkState.Connected } >= 2, "link never dropped and came back: $links")
    }

    @Test
    fun `stale telemetry makes the service ask the adapter to resubscribe (B4)`(): Unit = runBlocking {
        val track = SyntheticTracks.manualVlos(TestData.NAIROBI).withFaults(listOf(TrackFault(12_000, FaultKind.STALE, 60_000)))
        val adapter = ReplayDroneAdapter(track, scope, speed = 1.0)
        val service = FlightService(adapter, FlightRepository(tmp), SyncEngine(NullBackend, CorrectedClock()), CorrectedClock(), Egm96Grid.bundled(),
            ServiceConfig(staleAfterMs = 2_000, resubscribeEveryMs = 1_000), scope)
        service.start()
        withTimeout(25_000) { service.status.first { it.telemetryStale } }
        withTimeout(10_000) { while (adapter.resubscribeCount == 0) delay(100) }
        withTimeout(10_000) { service.status.first { !it.telemetryStale } } // resubscribing brought states back
        service.stop()
    }

    @Test
    fun `fake mission operator reports upload and progress`(): Unit = runBlocking {
        val adapter = ReplayDroneAdapter(SyntheticTracks.manualVlos(TestData.NAIROBI), scope, speed = 10.0)
        adapter.start()
        withTimeout(5_000) { adapter.link.filterIsInstance<LinkState.Connected>().first() }
        val ops = adapter.missionOperator()!!
        val plan = org.openutm.djiflight.core.mission.MissionPlan(
            listOf(org.openutm.djiflight.core.mission.MissionWaypoint(-1.29, 36.82, 30.0), org.openutm.djiflight.core.mission.MissionWaypoint(-1.291, 36.82, 30.0)), 5.0,
        )
        assertTrue(ops.upload(plan).isSuccess)
        assertTrue(ops.start().isSuccess)
    }
}

/** A backend that is never reachable. */
object NullBackend : UtmBackend {
    private val off = BackendResult.Transient("no backend")
    override suspend fun declare(plan: DeclarationPlan) = off
    override suspend fun findDeclaration(clientRef: String, startMs: Long, endMs: Long) = off
    override suspend fun setState(declarationId: String, state: OperationState) = off
    override suspend fun sendTelemetry(flight: FlightIdentity, samples: List<TelemetrySample>, reference: AltitudeCandidate) = off
    override suspend fun zones(area: BBox, startMs: Long, endMs: Long) = off
}
