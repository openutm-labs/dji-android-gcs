package org.openutm.djiflight.core

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.openutm.djiflight.core.altitude.Egm96Grid
import org.openutm.djiflight.core.auth.TokenProvider
import org.openutm.djiflight.core.blender.BlenderBackend
import org.openutm.djiflight.core.http.*
import org.openutm.djiflight.core.journal.FlightJournal
import org.openutm.djiflight.core.journal.FlightRepository
import org.openutm.djiflight.core.session.FlightEvent
import org.openutm.djiflight.core.session.FlightRecorder
import org.openutm.djiflight.core.sync.*
import kotlin.test.*

/** Required (never silently skipped) when the shared contract harness supplies its environment. */
class ArchiveContractIntegrationTest {
    @TempDir lateinit var tmp: File

    @Test fun `expired declaration archive survives lost HTTP response and process restart`(): Unit = runBlocking {
        val url = System.getenv("UTM_TEST_FLIGHT_BLENDER_URL")
        assumeTrue(!url.isNullOrBlank(), "Run the shared Flight Blender contract harness")
        requireNotNull(url)
        val token = requireNotNull(System.getenv("UTM_TEST_FLIGHT_BLENDER_TOKEN"))
        val http = UrlConnectionTransport()
        var loseResponse = true
        val bodies = arrayListOf<String>()
        val lossy = HttpTransport { request ->
            if (request.method == "PUT") bodies += requireNotNull(request.body).toString(Charsets.UTF_8)
            val response = http.execute(request)
            if (request.method == "PUT" && response.status == 200 && loseResponse) {
                loseResponse = false
                throw IOException("Injected lost response after the real server committed")
            }
            response
        }
        val tokens = object : TokenProvider { override fun token(forceRefresh: Boolean) = token }
        val backend = BlenderBackend(url, tokens, lossy, submittedBy = "archive-test")
        val journal = FlightRepository(tmp).create(UUID.randomUUID().toString())
        val grid = Egm96Grid.bundled()
        var recorder = FlightRecorder(journal, grid)
        val start = 1_577_836_800_000L // 2020: well outside the live receipt window.
        recorder.record(FlightEvent.Planned(start, TestData.plan(start, flightId = journal.flightId, startInMs = 0)))
        recorder.record(FlightEvent.Declared(start, "00000000-0000-0000-0000-000000000001"))
        repeat(421) { recorder.onDroneState(TestData.state(start + it * 1_000L, h = 30.0, flying = true)) }
        recorder.record(FlightEvent.FinishRequested(start + 421_000))
        recorder.record(FlightEvent.ServerClosed(start + 422_000, OperationState.ENDED))
        val uploader = ArchiveUploader()
        assertEquals(SyncStatus.BACKOFF, uploader.sync(recorder.snapshot, journal, recorder.lastRecordedSeq, backend, 10,
            { recorder.record(it); recorder.snapshot }, System.currentTimeMillis()).status)
        assertEquals(-1L, journal.archiveCursor)
        val reopened = FlightJournal(journal.dir)
        recorder = FlightRecorder(reopened, grid)
        assertEquals(SyncStatus.DONE, uploader.sync(recorder.snapshot, reopened, recorder.lastRecordedSeq, backend, 10,
            { recorder.record(it); recorder.snapshot }, System.currentTimeMillis()).status)
        assertEquals(bodies[0], bodies[1], "The durable outbox must replay identical bytes")
        assertTrue(FlightRecorder(reopened, grid).snapshot.archiveComplete)
        val samples = arrayListOf<JsonElement>()
        var after = -1L
        do {
            val response = http.execute(HttpRequest("GET", "$url/adapter_ops/sessions/${journal.flightId}?after=$after&limit=200", mapOf("Authorization" to "Bearer $token")))
            assertEquals(200, response.status, response.body)
            val page = Json.parseToJsonElement(response.body).jsonObject
            assertTrue(page.getValue("archive").jsonObject.getValue("complete").jsonPrimitive.boolean)
            val rows = page.getValue("samples").jsonArray
            samples.addAll(rows)
            after = rows.lastOrNull()?.jsonObject?.getValue("sequence")?.jsonPrimitive?.long ?: after
        } while (rows.size == 200)
        assertEquals(journal.readSamples().size, samples.size)
        assertEquals((0L until samples.size.toLong()).toList(), samples.map { it.jsonObject.getValue("sequence").jsonPrimitive.long })
        assertEquals(-1L, reopened.cursor, "Archive acknowledgement must not advance the live cursor")
    }
}
