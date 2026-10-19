package org.openutm.djiflight.core

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.openutm.djiflight.core.altitude.Egm96Grid
import org.openutm.djiflight.core.declaration.DeclarationPlan
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.journal.FlightJournal
import org.openutm.djiflight.core.journal.FlightRepository
import org.openutm.djiflight.core.session.FlightEvent
import org.openutm.djiflight.core.session.FlightRecorder
import org.openutm.djiflight.core.sync.*
import org.openutm.djiflight.core.time.ManualClock
import org.openutm.djiflight.core.zones.ZoneService
import java.io.File
import kotlin.test.*

class ServerRoutingTest {
    @TempDir lateinit var tmp: File
    private val local = ServerIdentity.of("http://192.0.2.1:8000")
    private val panama = ServerIdentity.of("https://blender.panama.openutm.net")
    private val clock = ManualClock(1_792_142_000_000L)
    private fun named(fake: FakeBackend, id: String) = object : UtmBackend by fake { override val serverId = id; override fun snapshot(): UtmBackend = this }
    private fun recorder(j: FlightJournal) = FlightRecorder(j, Egm96Grid.bundled()).also {
        it.record(FlightEvent.Planned(clock.nowMs(), TestData.plan(clock.nowMs(), flightId = j.flightId, startInMs = 0)))
    }
    private suspend fun pass(engine: SyncEngine, j: FlightJournal, r: FlightRecorder) =
        engine.syncOnce(r.snapshot, j, r.lastRecordedSeq) { r.record(it); r.snapshot }

    @Test fun `identity normalizes host default port and trailing slash but separates API paths`() {
        assertEquals(panama, ServerIdentity.of("https://BLENDER.panama.openutm.net:443/"))
        assertNotEquals(panama, ServerIdentity.of("https://blender.panama.openutm.net/api"))
        assertNotEquals(local, panama)
        assertFailsWith<IllegalArgumentException> { ServerIdentity.of("https://secret@example.org/") }
        assertFailsWith<IllegalArgumentException> { ServerIdentity.of("https://example.org/?secret=value") }
    }

    @Test fun `old flights stay on local while new flights bind Panama and survive restart`() = runTest {
        val old = FakeBackend(clock)
        val hosted = FakeBackend(clock)
        val switch = SwitchableBackend(named(old, local))
        val repo = FlightRepository(tmp) { switch.serverId }
        val j = repo.create("local-flight")
        val r = recorder(j)
        val engine = SyncEngine(switch, clock)
        switch.target = named(hosted, panama)
        assertEquals(SyncStatus.BLOCKED, pass(engine, j, r).status)
        assertEquals(0, old.requests)
        assertEquals(0, hosted.requests)
        assertTrue(j.readEvents().none { it is FlightEvent.DeclareAttempted })
        val new = repo.create("panama-flight")
        val nr = recorder(new)
        assertEquals(panama, repo.open(new.flightId).serverId)
        assertEquals(SyncStatus.UP_TO_DATE, pass(engine, new, nr).status)
        assertEquals(1, hosted.requests)
        switch.target = named(old, local)
        assertEquals(SyncStatus.UP_TO_DATE, pass(engine, j, r).status)
        assertEquals(1, old.requests)
        assertEquals(local, repo.open(j.flightId).serverId)
    }

    @Test fun `legacy migration uses previous setup once and preserves existing and corrupt bindings`() = runTest {
        val repo = FlightRepository(tmp)
        val old = repo.create("legacy")
        recorder(old)
        val corrupt = repo.create("corrupt")
        File(corrupt.dir, "server-id").writeText("broken")
        val other = FlightRepository(tmp) { panama }.create("already-hosted")
        val before = File(old.dir, "events.log").readBytes()
        repo.migrateLegacy(local)
        repo.migrateLegacy(panama)
        assertEquals(local, repo.open(old.flightId).serverId)
        assertEquals(panama, repo.open(other.flightId).serverId)
        assertEquals("broken", File(corrupt.dir, "server-id").readText())
        assertContentEquals(before, File(old.dir, "events.log").readBytes())
        val fake = FakeBackend(clock)
        val engine = SyncEngine(named(fake, panama), clock)
        val reopened = repo.open(corrupt.flightId)
        assertEquals(SyncStatus.BLOCKED, pass(engine, reopened, recorder(reopened)).status)
        assertEquals(0, fake.requests)
    }

    @Test fun `unknown legacy origin never gets claimed by a later setup`() = runTest {
        val repo = FlightRepository(tmp)
        repo.create("unknown")
        repo.migrateLegacy(null)
        repo.migrateLegacy(panama)
        val j = repo.open("unknown")
        val fake = FakeBackend(clock)
        assertNull(j.serverId)
        assertEquals(SyncStatus.BLOCKED, pass(SyncEngine(named(fake, panama), clock), j, recorder(j)).status)
        assertEquals(0, fake.requests)
    }

    @Test fun `switch during declaration cannot redirect activation or telemetry in the same pass`() = runTest {
        val original = FakeBackend(clock)
        val hosted = FakeBackend(clock)
        val switch = SwitchableBackend()
        switch.target = object : UtmBackend by original {
            override val serverId = local
            override fun snapshot(): UtmBackend = this
            override suspend fun declare(plan: DeclarationPlan): BackendResult<String> {
                switch.target = named(hosted, panama)
                return original.declare(plan)
            }
        }
        val j = FlightRepository(tmp) { local }.create("in-flight-test")
        val r = recorder(j)
        repeat(50) { clock.advance(100); r.onDroneState(TestData.state(clock.nowMs(), h = 30.0, flying = true)) }
        val engine = SyncEngine(switch, clock, SyncConfig(declareLeadMs = 0, clockSkewMs = 0))
        pass(engine, j, r)
        assertTrue(original.received.isNotEmpty())
        assertEquals(listOf(OperationState.ACTIVATED), original.states.map { it.second })
        assertEquals(0, hosted.requests)
        assertEquals(SyncStatus.BLOCKED, pass(engine, j, r).status)
    }

    @Test fun `an unconfigured snapshot stays unconfigured after setup arrives`() = runTest {
        val switch = SwitchableBackend()
        val frozen = switch.snapshot()
        val hosted = FakeBackend(clock)
        switch.target = named(hosted, panama)
        assertIs<BackendResult.Transient>(frozen.zones(Geo.bbox(Geo.circle(TestData.NAIROBI, 50.0)), 0, 1))
        assertNull(frozen.serverId)
        assertEquals(0, hosted.requests)
    }

    @Test fun `zone cache is isolated and late responses from the previous server are ignored`() = runTest {
        val fake = FakeBackend(clock)
        val switch = SwitchableBackend(named(fake, local))
        val zones = ZoneService(switch, File(tmp, "zones.json"), clock)
        val area = Geo.bbox(Geo.circle(TestData.NAIROBI, 50.0))
        assertIs<BackendResult.Ok<*>>(zones.refresh(area))
        assertNotNull(zones.index.value)
        switch.target = named(fake, panama)
        zones.backendChanged()
        assertNull(zones.index.value)
        assertNull(ZoneService(switch, File(tmp, "zones.json"), clock).index.value)
        switch.target = object : UtmBackend by fake {
            override val serverId = local
            override fun snapshot(): UtmBackend = this
            override suspend fun zones(area: org.openutm.djiflight.core.geo.BBox, startMs: Long, endMs: Long): BackendResult<String> {
                switch.target = named(fake, panama)
                zones.backendChanged()
                return fake.zones(area, startMs, endMs)
            }
        }
        assertIs<BackendResult.Transient>(zones.refresh(area))
        assertNull(zones.index.value)
    }
}
