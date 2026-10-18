package org.openutm.djiflight.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.openutm.djiflight.core.altitude.AltitudeCandidate
import org.openutm.djiflight.core.altitude.AltitudeSource
import org.openutm.djiflight.core.auth.DevTokenProvider
import org.openutm.djiflight.core.blender.BlenderBackend
import org.openutm.djiflight.core.declaration.DeclarationPlan
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.http.HttpRequest
import org.openutm.djiflight.core.http.UrlConnectionTransport
import org.openutm.djiflight.core.model.TelemetrySample
import org.openutm.djiflight.core.rid.FlightIdentity
import org.openutm.djiflight.core.sync.BackendResult
import org.openutm.djiflight.core.sync.OperationState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Talks to the workspace's local Flight Blender (`./stack/openutm.sh up`, dev auth bypass).
 * Skipped when it isn't reachable. Re-runs the behaviour docs/12 relies on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlenderIntegrationTest {
    private val url = System.getProperty("blender.url", "http://localhost:8000")
    private val transport = UrlConnectionTransport(connectTimeoutMs = 2_000, readTimeoutMs = 15_000)
    private val backend = BlenderBackend(url, DevTokenProvider(), transport, submittedBy = "dji-flight-test@openutm.local")
    private val reference = AltitudeCandidate(AltitudeSource.DEVICE_GNSS, 1645.0, 4.0)

    @BeforeAll
    fun blenderUp() {
        val up = runCatching { transport.execute(HttpRequest("GET", "$url/ping")).status == 200 }.getOrDefault(false)
        assumeTrue(up, "Flight Blender not reachable at $url; start it with ./stack/openutm.sh up")
    }

    private fun plan(startInMs: Long, windowMs: Long) = System.currentTimeMillis().let { now ->
        DeclarationPlan(
            clientRef = UUID.randomUUID().toString(),
            ring = Geo.circle(TestData.NAIROBI, 500.0),
            minAltW84M = 1625.0, maxAltW84M = 1785.0,
            startMs = now + startInMs, endMs = now + startInMs + windowMs,
            aircraftSerial = "TESTP4PV2-0001", originatingParty = "dji-flight integration test",
            submittedBy = "dji-flight-test@openutm.local",
        )
    }

    private fun identity(id: String) = FlightIdentity(id, "TESTP4PV2-0001", "KE-TEST-0001", "OPR-KE-TEST", "dji-flight integration test")

    private fun samples(fromSeq: Long, n: Int, endMs: Long, flying: Boolean = true) = (0 until n).map { i ->
        TelemetrySample(
            seq = fromSeq + i, t = endMs - (n - i) * 1000L, lat = TestData.NAIROBI.lat + i * 1e-5, lng = TestData.NAIROBI.lng,
            h = if (flying) 30.0 else 0.0, vn = 1.1, ve = 0.0, vd = 0.0, yaw = 0.0, flying = flying, motors = true, gps = 5,
            pilotLat = TestData.NAIROBI.lat, pilotLng = TestData.NAIROBI.lng,
        )
    }

    @Test
    fun `declare, find by client ref, activate, live and back-dated batches, end`(): Unit = runBlocking {
        val p = plan(startInMs = 2_000, windowMs = 10 * 60_000)
        val id = assertIs<BackendResult.Ok<String>>(backend.declare(p)).value
        assertEquals(id, assertIs<BackendResult.Ok<String?>>(backend.findDeclaration(p.clientRef, p.startMs, p.endMs)).value)
        assertEquals(null, assertIs<BackendResult.Ok<String?>>(backend.findDeclaration("no-such-ref", p.startMs, p.endMs)).value)

        delay(2_500)
        assertIs<BackendResult.Ok<Unit>>(backend.setState(id, OperationState.ACTIVATED))
        val now = System.currentTimeMillis()
        // A 2-minute backlog (some of it stamped before the declared start) in one request, then a live batch.
        val backlog = samples(0, 120, now - 2_000)
        assertIs<BackendResult.Ok<Unit>>(backend.sendTelemetry(identity(id), backlog, reference))
        assertIs<BackendResult.Ok<Unit>>(backend.sendTelemetry(identity(id), samples(120, 2, now + 1_000), reference))
        assertIs<BackendResult.Ok<Unit>>(backend.setState(id, OperationState.ENDED))

        val stored = BlenderDb.observationCount(id, expected = 122)
        if (stored != null) {
            assertEquals(122, stored.total, "stored observations")
            assertEquals(122, stored.withHeight)
        } else {
            println("docker/psql not available: skipped the stored-observation check")
        }
    }

    @Test
    fun `300-state batch is accepted`(): Unit = runBlocking {
        val p = plan(startInMs = 1_500, windowMs = 5 * 60_000)
        val id = assertIs<BackendResult.Ok<String>>(backend.declare(p)).value
        delay(2_000)
        backend.setState(id, OperationState.ACTIVATED)
        val r = backend.sendTelemetry(identity(id), samples(0, 300, System.currentTimeMillis()), reference)
        assertIs<BackendResult.Ok<Unit>>(r)
        backend.setState(id, OperationState.ENDED)
        BlenderDb.observationCount(id, expected = 300)?.let { assertEquals(300, it.total) }
    }

    @Test
    fun `telemetry outside the window is classified as WindowClosed, and Ended still works`(): Unit = runBlocking {
        val p = plan(startInMs = 3_000, windowMs = 4_000)
        val id = assertIs<BackendResult.Ok<String>>(backend.declare(p)).value
        backend.setState(id, OperationState.ACTIVATED)
        // Before the window opens: same answer as after it closes (why the engine waits for the start).
        assertIs<BackendResult.WindowClosed>(backend.sendTelemetry(identity(id), samples(0, 1, System.currentTimeMillis()), reference))
        delay(8_000)
        assertIs<BackendResult.WindowClosed>(backend.sendTelemetry(identity(id), samples(1, 1, System.currentTimeMillis()), reference))
        assertIs<BackendResult.Ok<Unit>>(backend.setState(id, OperationState.ENDED))
    }

    @Test
    fun `a restriction zone created in Blender reaches the zone cache and the advisor`(): Unit = runBlocking {
        val center = Geo.destination(TestData.NAIROBI, 45.0, 2_000.0)
        val ring = Geo.circle(center, 300.0, 16)
        val name = "dji-flight test zone ${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()
        val body = kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("FeatureCollection"))
            put("features", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("Feature"))
                    put("properties", kotlinx.serialization.json.buildJsonObject {
                        put("name", kotlinx.serialization.json.JsonPrimitive(name))
                        put("upper_limit", kotlinx.serialization.json.JsonPrimitive(120))
                        put("lower_limit", kotlinx.serialization.json.JsonPrimitive(0))
                        put("start_time", kotlinx.serialization.json.JsonPrimitive(org.openutm.djiflight.core.time.Rfc3339.format(now - 3_600_000)))
                        put("end_time", kotlinx.serialization.json.JsonPrimitive(org.openutm.djiflight.core.time.Rfc3339.format(now + 2 * 86_400_000L)))
                    })
                    put("geometry", kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("Polygon"))
                        put("coordinates", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonArray(ring.map {
                            kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(it.lng), kotlinx.serialization.json.JsonPrimitive(it.lat)))
                        }))))
                    })
                })
            })
        }
        val auth = mapOf("Authorization" to "Bearer ${DevTokenProvider().token()}", "Content-Type" to "application/json")
        val created = transport.execute(HttpRequest("PUT", "$url/geo_fence_ops/set_geo_fence", auth, body.toString().toByteArray()))
        assertEquals(200, created.status, created.body)
        val cache = kotlin.io.path.createTempFile("zones", ".json").toFile()
        val zones = org.openutm.djiflight.core.zones.ZoneService(backend, cache, org.openutm.djiflight.core.time.Clock.SYSTEM)
        val idx = assertIs<BackendResult.Ok<org.openutm.djiflight.core.zones.ZoneIndex>>(zones.refresh(Geo.bbox(Geo.circle(TestData.NAIROBI, 500.0)))).value
        val zone = idx.zones.firstOrNull { it.name == name }
        try {
            assertNotNull(zone, "zone not returned; got ${idx.zones.map { it.name }}")
            assertEquals(120.0, zone.upperM)
            // A declared area reaching it conflicts; an aircraft 50 m from its edge is warned.
            assertTrue(org.openutm.djiflight.core.advisory.FlightAdvisor.areaConflicts(Geo.circle(TestData.NAIROBI, 1_800.0), idx).any { it.name == name })
            val near = Geo.destination(center, 225.0, 350.0)
            assertTrue(idx.near(near).any { it.zone.name == name && it.distanceM in 40.0..60.0 })
        } finally {
            zone?.let { transport.execute(HttpRequest("DELETE", "$url/geo_fence_ops/geo_fence/${it.id}/delete", auth)) }
            cache.delete()
        }
    }

    @Test
    fun `declaration in the past is rejected, not retried`(): Unit = runBlocking {
        val p = plan(startInMs = -60_000, windowMs = 30_000)
        val r = backend.declare(p)
        assertIs<BackendResult.Rejected>(r)
        assertTrue("past" in r.message, r.message)
    }

    @Test
    fun `declared polygon and altitude band are stored as sent (B3 check)`(): Unit = runBlocking {
        val p = plan(startInMs = 60_000, windowMs = 60_000)
        val id = assertIs<BackendResult.Ok<String>>(backend.declare(p)).value
        val body = transport.execute(
            HttpRequest("GET", "$url/flight_declaration_ops/flight_declaration/$id", mapOf("Authorization" to "Bearer ${DevTokenProvider().token()}")),
        ).body
        val vol = Json.parseToJsonElement(body).jsonObject["operational_intent"].toString()
        assertTrue("1625" in vol && "1785" in vol, "volume altitudes: $vol")
        backend.setState(id, OperationState.CANCELLED)
    }
}
