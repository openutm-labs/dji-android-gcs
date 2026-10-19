package org.openutm.djiflight.fake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.openutm.djiflight.core.BlenderDb
import org.openutm.djiflight.core.FlightService
import org.openutm.djiflight.core.ServiceConfig
import org.openutm.djiflight.core.altitude.AltitudeSource
import org.openutm.djiflight.core.altitude.Egm96Grid
import org.openutm.djiflight.core.auth.DevTokenProvider
import org.openutm.djiflight.core.blender.BlenderBackend
import org.openutm.djiflight.core.declaration.GroundEstimate
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.http.HttpRequest
import org.openutm.djiflight.core.http.HttpResponse
import org.openutm.djiflight.core.http.HttpTransport
import org.openutm.djiflight.core.http.UrlConnectionTransport
import org.openutm.djiflight.core.journal.FlightRepository
import org.openutm.djiflight.core.model.FlightPlan
import org.openutm.djiflight.core.model.OperationKind
import org.openutm.djiflight.core.session.Phase
import org.openutm.djiflight.core.sync.SyncConfig
import org.openutm.djiflight.core.sync.SyncEngine
import org.openutm.djiflight.core.time.CorrectedClock
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Drops every request while [offline], like a phone out of coverage. */
class FlakyTransport(private val inner: HttpTransport) : HttpTransport {
    @Volatile var offline = false
    @Volatile var refused = 0
    override fun execute(request: HttpRequest): HttpResponse {
        if (offline) { refused++; throw IOException("simulated outage") }
        return inner.execute(request)
    }
}

/**
 * The whole app minus Android: a recorded ArduCopter SITL mission replayed through the adapter
 * contract → recorder → journal → sync → the local Flight Blender, with an 8 s outage mid-flight.
 * The connector left a 29 s gap for a 25 s outage (launch-risks H3); this must leave none.
 */
class PipelineBlenderTest {
    @TempDir
    lateinit var tmp: File

    /** Cancelled after every test, pass or fail, so nothing writes into a deleted temp dir. */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterEach
    fun tearDown() = scope.cancel()
    private val url = System.getProperty("blender.url", "http://localhost:8000")

    @Test
    fun `SITL mission replay reaches Blender complete despite a mid-flight outage`(): Unit = runBlocking {
        val probe = UrlConnectionTransport(2_000, 5_000)
        assumeTrue(runCatching { probe.execute(HttpRequest("GET", "$url/ping")).status == 200 }.getOrDefault(false), "Blender not reachable at $url")

        val track = Track.bundled("sitl-auto-mission.jsonl")
        val adapter = ReplayDroneAdapter(track, scope, speed = 4.0)
        val clock = CorrectedClock()
        val flaky = FlakyTransport(UrlConnectionTransport())
        val backend = BlenderBackend(url, DevTokenProvider(), flaky, submittedBy = "dji-flight-pipeline-test@openutm.local")
        val sync = SyncEngine(backend, clock, SyncConfig(declareLeadMs = 2_000, clockSkewMs = 1_000, minBackoffMs = 1_000, maxBackoffMs = 2_000))
        val repo = FlightRepository(tmp) { backend.serverId }
        val service = FlightService(adapter, repo, sync, clock, Egm96Grid.bundled(), ServiceConfig(liveSyncIntervalMs = 1_000), scope)
        scope.launch { adapter.simulatedDeviceFixes().collect(service::onDeviceFix) }

        val now = clock.nowMs()
        val flightId = UUID.randomUUID().toString()
        service.start()
        service.plan(
            FlightPlan(
                flightId = flightId, kind = OperationKind.MISSION,
                area = Geo.bufferedHull(track.states.map { org.openutm.djiflight.core.geo.LatLng(it.lat, it.lng) }, 30.0),
                ground = GroundEstimate(track.header.deviceGnssEllipsoidM!!, 3.0, AltitudeSource.SITE_ESTIMATE),
                plannedMaxHeightM = 60.0, missionMaxHeightM = 30.0,
                plannedStartMs = now, plannedEndMs = now + 30 * 60_000,
                aircraftSerial = track.header.serial, registrationId = "HU-SITL-0001", pilotId = "pilot-sitl",
                lab = "dji-flight pipeline test", operatorId = "OPR-SITL", description = "SITL replay through dji-flight core",
            ),
        )

        // 8 s outage starting 10 s in (40 s of track time at 4x: mid-mission).
        delay(10_000)
        fun lines(n: String) = File(File(tmp, flightId), n).takeIf { it.exists() }?.readLines()?.size ?: -1
        println("before outage: ${service.status.value.lastSync} phase=${service.status.value.activeFlight?.phase} samples=${lines("samples.log")} events=${lines("events.log")} cursor=${File(File(tmp, flightId), "cursor").takeIf { it.exists() }?.readText()}")
        flaky.offline = true
        delay(8_000)
        flaky.offline = false
        println("after outage: refused=${flaky.refused} samples=${lines("samples.log")} last=${service.status.value.lastSync}")
        assertTrue(flaky.refused > 0, "the outage never hit a request")

        withTimeout(60_000) { while (!adapter.finished) delay(200) }
        delay(1_500)
        service.finish()
        val closed = withTimeout(60_000) { service.status.first { it.activeFlight?.phase == Phase.CLOSED } }.activeFlight!!
        service.stop()

        val journal = repo.open(flightId)
        val samples = journal.readSamples()
        val id = assertNotNull(closed.declarationId)
        assertEquals(samples.last().seq, journal.cursor, "every recorded sample accepted")
        val ref = closed.takeoffReference!!.chosen
        assertEquals(180.94, ref.ellipsoidM, 1.5)
        println("pipeline: declaration $id, ${samples.size} samples, reference ${ref.source} ${"%.2f".format(ref.ellipsoidM)} m, refused during outage ${flaky.refused}")

        val stored = BlenderDb.observationCount(id, expected = samples.size)
        if (stored == null) {
            println("docker/psql not available: skipped the stored-observation check")
            return@runBlocking
        }
        println("stored: $stored")
        assertEquals(samples.size, stored.total, "stored = recorded")
        assertEquals(samples.size, stored.withHeight)
        assertTrue(stored.airborne >= 20 && stored.ground >= 1, "both Ground and Airborne states: $stored")
        assertTrue(stored.maxGapS <= 2.5, "largest gap between stored samples ${stored.maxGapS} s (outage must be backfilled)")
    }
}
