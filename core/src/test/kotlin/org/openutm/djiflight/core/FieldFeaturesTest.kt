package org.openutm.djiflight.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.openutm.djiflight.core.adapter.DroneAdapter
import org.openutm.djiflight.core.adapter.DroneCapabilities
import org.openutm.djiflight.core.adapter.DroneState
import org.openutm.djiflight.core.adapter.LinkState
import org.openutm.djiflight.core.advisory.AdvisorInput
import org.openutm.djiflight.core.advisory.FlightAdvisor
import org.openutm.djiflight.core.advisory.Level
import org.openutm.djiflight.core.altitude.AltitudeSource
import org.openutm.djiflight.core.altitude.Egm96Grid
import org.openutm.djiflight.core.declaration.DeclarationPlan
import org.openutm.djiflight.core.declaration.GroundEstimate
import org.openutm.djiflight.core.export.FlightExport
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.journal.FlightRepository
import org.openutm.djiflight.core.kml.Kml
import org.openutm.djiflight.core.provisioning.AuthConfig
import org.openutm.djiflight.core.provisioning.DeviceProvisioning
import org.openutm.djiflight.core.provisioning.Pilot
import org.openutm.djiflight.core.provisioning.ProvisioningCodec
import org.openutm.djiflight.core.session.FlightEvent
import org.openutm.djiflight.core.session.FlightSnapshot
import org.openutm.djiflight.core.sites.Site
import org.openutm.djiflight.core.sites.SiteStore
import org.openutm.djiflight.core.sync.BackendResult
import org.openutm.djiflight.core.sync.OperationState
import org.openutm.djiflight.core.sync.SwitchableBackend
import org.openutm.djiflight.core.sync.SyncEngine
import org.openutm.djiflight.core.time.ManualClock
import org.openutm.djiflight.core.update.Release
import org.openutm.djiflight.core.update.ReleaseChannel
import org.openutm.djiflight.core.update.Updates
import org.openutm.djiflight.core.zones.Zone
import org.openutm.djiflight.core.zones.ZoneIndex
import org.openutm.djiflight.core.zones.ZoneService
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FieldFeaturesTest {
    @TempDir
    lateinit var tmp: File
    private val t0 = 1_792_142_000_000L

    private val provisioning = DeviceProvisioning(
        deviceName = "KE-SC-01", blenderUrl = "https://utm.example.org", lab = "Kenya Flying Labs", operatorId = "OPR-KE-001",
        auth = AuthConfig.ClientCredentials("https://passport.example.org/oauth/token/", "client-abc", "s3cret"),
        pilots = listOf(Pilot("p1", "Amina"), Pilot("p2", "Otieno")), country = "KE",
    )

    @Test
    fun `provisioning round-trips through the QR form and plain JSON`() {
        val code = ProvisioningCodec.encode(provisioning)
        assertTrue(code.startsWith(ProvisioningCodec.PREFIX))
        assertEquals(provisioning, ProvisioningCodec.decode(code))
        assertEquals(provisioning, ProvisioningCodec.decode(ProvisioningCodec.toJson(provisioning)))
        assertTrue(code.length < 1_000, "QR payload ${code.length} chars")
        assertTrue(provisioning.problems(allowDevAuth = false).isEmpty())
        val dev = provisioning.copy(blenderUrl = "http://10.0.2.2:8000", auth = AuthConfig.Dev)
        assertTrue(dev.problems(allowDevAuth = true).isEmpty())
        assertTrue(dev.problems(allowDevAuth = false).any { "debug" in it })
        assertTrue(provisioning.copy(pilots = listOf(Pilot("p1", "a"), Pilot("p1", "b"))).problems(false).any { "duplicate" in it })
        assertFailsWith<IllegalArgumentException> { ProvisioningCodec.decode(ProvisioningCodec.PREFIX + "not-base64!") }
        assertFailsWith<IllegalArgumentException> { ProvisioningCodec.decode("hello") }
    }

    private val kml = """<?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2"><Document>
          <Placemark><name>Farm block A</name><Polygon><outerBoundaryIs><LinearRing><coordinates>
            36.820,-1.290,0 36.824,-1.290,0 36.824,-1.294,0 36.820,-1.294,0 36.820,-1.290,0
          </coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
          <Placemark><name>Road</name><LineString><coordinates>36.81,-1.28 36.82,-1.29</coordinates></LineString></Placemark>
        </Document></kml>"""

    @Test
    fun `KML and KMZ site boundaries are read`() {
        val pms = Kml.parse(kml)
        assertEquals(listOf("Farm block A", "Road"), pms.map { it.name })
        assertEquals(5, pms[0].polygon!!.size)
        assertEquals(LatLng(-1.290, 36.820), pms[0].polygon!!.first())
        assertEquals(2, pms[1].line!!.size)
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("doc.kml")); z.write(kml.toByteArray()); z.closeEntry() }
        }.toByteArray()
        assertEquals(pms, Kml.read(zip))
        // DTDs (and so external entities) are refused.
        assertFailsWith<IllegalArgumentException> { Kml.parse("""<?xml version="1.0"?><!DOCTYPE kml [<!ENTITY x SYSTEM "file:///etc/passwd">]><kml>&x;</kml>""") }
    }

    private fun flyingSnapshot(declared: Boolean = true): FlightSnapshot {
        val plan = TestData.plan(t0, flightId = "f-adv")
        var s = FlightSnapshot("f-adv").apply(FlightEvent.Planned(t0, plan))
        if (declared) {
            s = s.apply(FlightEvent.DeclareAttempted(t0, DeclarationPlan("f-adv", plan.area, plan.ground.ellipsoidM - 20, plan.ground.ellipsoidM + 120, t0, t0 + 3_600_000, "SN", "lab", "x")))
                .apply(FlightEvent.Declared(t0, "decl"))
        }
        return s.apply(FlightEvent.TookOff(t0 + 1000, 0))
    }

    @Test
    fun `advisor warns about battery, height, area, zones and missing declaration, worst first`() {
        val far = Geo.destination(TestData.NAIROBI, 0.0, 700.0) // outside the 500 m declared circle
        val zone = Zone("z", "Wilson ATZ", listOf(Geo.circle(Geo.destination(far, 0.0, 150.0), 100.0)), 0.0, 120.0, "AGL", "blender")
        val out = FlightAdvisor.advise(
            AdvisorInput(
                snapshot = flyingSnapshot(declared = false),
                state = TestData.state(t0 + 5000, far.lat, far.lng, h = 130.0, flying = true).copy(batteryPercent = 18),
                telemetryStale = false, zones = ZoneIndex(listOf(zone), t0), nowMs = t0 + 5000,
            ),
        )
        val codes = out.map { it.code }
        assertTrue(codes.containsAll(listOf("BATTERY_CRITICAL", "ABOVE_LEGAL_HEIGHT", "OUTSIDE_AREA", "NOT_DECLARED", "NEAR_ZONE")), codes.toString())
        assertEquals(Level.CRITICAL, out.first().level)
        assertTrue(out.zipWithNext().all { (a, b) -> a.level >= b.level })

        val inside = FlightAdvisor.advise(AdvisorInput(flyingSnapshot(), TestData.state(t0, h = 50.0, flying = true).copy(batteryPercent = 80), false,
            ZoneIndex(listOf(zone.copy(rings = listOf(Geo.circle(TestData.NAIROBI, 50.0)))), t0), nowMs = t0 + 3_600_000 - 60_000))
        assertTrue(inside.any { it.code == "IN_ZONE" && it.level == Level.CRITICAL })
        assertTrue(inside.any { it.code == "WINDOW_ENDING" })
        assertTrue(inside.none { it.code == "OUTSIDE_AREA" || it.code == "NOT_DECLARED" })

        assertEquals(listOf("z"), FlightAdvisor.areaConflicts(Geo.circle(far, 300.0), ZoneIndex(listOf(zone), t0)).map { it.id })
    }

    @Test
    fun `export writes a GeoJSON track with W84 altitudes and a CSV row per sample`() {
        val j = FlightRepository(tmp).create("f-exp")
        val rec = org.openutm.djiflight.core.session.FlightRecorder(j, Egm96Grid.bundled())
        rec.record(FlightEvent.Planned(t0, TestData.plan(t0, flightId = "f-exp")))
        for (i in 0 until 40) rec.onDroneState(TestData.state(t0 + i * 500L, h = if (i in 5..30) 20.0 else 0.0, flying = i in 5..30, motors = true))
        val samples = j.readSamples()
        val geo = Json.parseToJsonElement(FlightExport.geoJson(rec.snapshot, samples)).jsonObject
        val features = geo["features"]!!.jsonArray
        assertEquals(2, features.size)
        val coords = features[0].jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(samples.size, coords.size)
        val ref = rec.snapshot.altitudeReference!!.ellipsoidM
        val flyingIdx = samples.indexOfFirst { it.flying }
        assertEquals(ref + 20.0, coords[flyingIdx].jsonArray[2].jsonPrimitive.content.toDouble(), 0.01)
        val csv = FlightExport.csv(rec.snapshot, samples).trim().lines()
        assertEquals(samples.size + 1, csv.size)
        assertTrue(csv[0].startsWith("seq,time_utc,lat,lng"))
        assertEquals("%.2f".format(java.util.Locale.ROOT, ref + 20.0), csv[flyingIdx + 1].split(",")[5])
    }

    @Test
    fun `update channel picks the newest installable release and checks its hash`() {
        val apk = File(tmp, "app.apk").apply { writeBytes(ByteArray(10_000) { (it % 251).toByte() }) }
        val sha = Updates.sha256(apk)
        val ch = ReleaseChannel(listOf(
            Release("org.openutm.djiflight.v4", 3, "0.3.0", "dji-v4-0.3.0.apk", sha),
            Release("org.openutm.djiflight.v4", 4, "0.4.0", "dji-v4-0.4.0.apk", sha, minSdk = 30),
            Release("org.openutm.djiflight.v4.sim", 9, "0.9.0", "sim.apk", sha),
        ))
        val parsed = Updates.parse(Updates.toJson(ch))
        assertEquals(3, Updates.available(parsed, "org.openutm.djiflight.v4", 2, sdkInt = 25)!!.versionCode)
        assertEquals(4, Updates.available(parsed, "org.openutm.djiflight.v4", 2, sdkInt = 34)!!.versionCode)
        assertEquals(null, Updates.available(parsed, "org.openutm.djiflight.v4", 4, sdkInt = 34))
        assertEquals("https://x.org/rel/dji-v4-0.3.0.apk", Updates.resolveUrl("https://x.org/rel/latest.json", "dji-v4-0.3.0.apk"))
        assertTrue(Updates.verify(apk, ch.releases[0]))
        assertTrue(!Updates.verify(apk, ch.releases[0].copy(sha256 = "00".repeat(32))))
    }

    @Test
    fun `zones are cached on the device and survive a restart offline`() = runTest {
        val clock = ManualClock(t0)
        val backend = FakeBackend(clock)
        val body = """{"count":1,"results":[{"id":"z1","name":"Test zone","upper_limit":"120","lower_limit":"0","altitude_ref":"AGL",
            "raw_geo_fence":{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Polygon",
            "coordinates":[[[36.8,-1.3],[36.83,-1.3],[36.83,-1.28],[36.8,-1.28],[36.8,-1.3]]]}}]}}]}"""
        val zonesBackend = object : org.openutm.djiflight.core.sync.UtmBackend by backend {
            override suspend fun zones(area: org.openutm.djiflight.core.geo.BBox, startMs: Long, endMs: Long) = BackendResult.Ok(body)
        }
        val cache = File(tmp, "zones.json")
        val svc = ZoneService(zonesBackend, cache, clock)
        assertEquals(null, svc.index.value)
        assertIs<BackendResult.Ok<ZoneIndex>>(svc.refresh(Geo.bbox(Geo.circle(TestData.NAIROBI, 500.0))))
        clock.advance(3_600_000)
        val offline = ZoneService(SwitchableBackend(null), cache, clock)
        assertEquals(listOf("Test zone"), offline.index.value!!.zones.map { it.name })
        assertEquals(3_600_000L, offline.ageMs())
        assertIs<BackendResult.Transient>(offline.refresh(Geo.bbox(Geo.circle(TestData.NAIROBI, 500.0))))
        assertEquals(1, offline.index.value!!.zones.size) // a failed refresh keeps the cache
    }

    @Test
    fun `sites merge nearby entries and are found again`() {
        val store = SiteStore(File(tmp, "sites.json"))
        val g = GroundEstimate(1645.0, 4.0, AltitudeSource.DEVICE_GNSS)
        store.upsert(Site("Farm A", TestData.NAIROBI, g, t0))
        store.upsert(Site("Farm A (re-measured)", Geo.destination(TestData.NAIROBI, 90.0, 50.0), g.copy(ellipsoidM = 1646.2), t0 + 1))
        store.upsert(Site("School", Geo.destination(TestData.NAIROBI, 0.0, 5_000.0), g, t0))
        assertEquals(2, store.all().size)
        assertEquals(1646.2, store.nearest(TestData.NAIROBI)!!.ground.ellipsoidM)
        assertEquals(null, store.nearest(Geo.destination(TestData.NAIROBI, 180.0, 2_000.0)))
    }

    @Test
    fun `finished, exported flights are pruned and active ones never are`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val clock = ManualClock(t0)
            val repo = FlightRepository(tmp)
            val svc = FlightService(StubAdapter, repo, SyncEngine(SwitchableBackend(null), clock), clock, Egm96Grid.bundled(), parent = scope)
            svc.start()
            val old = svc.plan(TestData.plan(t0, flightId = "old"))
            svc.cancel()
            svc.markExported(old.flightId)
            svc.plan(TestData.plan(t0, flightId = "current"))
            clock.advance(100L * 86_400_000L)
            assertEquals(1, svc.prune())
            assertEquals(listOf("current"), svc.snapshots().map { it.flightId })
            assertTrue(!File(tmp, "old").exists())
            assertTrue(!svc.delete("current"), "an active flight must not be deletable")
            svc.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `unprovisioned backend makes calls wait, not fail`() = runTest {
        val r = SwitchableBackend().setState("x", OperationState.ENDED)
        assertIs<BackendResult.Transient>(r)
        assertEquals("device not provisioned", r.message)
    }
}

/** A connected-nowhere adapter for service tests. */
object StubAdapter : DroneAdapter {
    override val link = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val capabilities = MutableStateFlow<DroneCapabilities?>(null)
    override val states = MutableSharedFlow<DroneState>()
    override fun missionOperator() = null
    override fun start() = Unit
    override fun stop() = Unit
    override fun resubscribe() = Unit
}
