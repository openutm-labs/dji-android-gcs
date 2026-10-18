package org.openutm.djiflight.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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
import org.openutm.djiflight.core.advisory.AdvisorInput
import org.openutm.djiflight.core.advisory.FlightAdvisor
import org.openutm.djiflight.core.altitude.Egm96Grid
import org.openutm.djiflight.core.export.FlightExport
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.geo.LocalPlane
import org.openutm.djiflight.core.geo.XY
import org.openutm.djiflight.core.journal.FlightRepository
import org.openutm.djiflight.core.mission.CameraModel
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.core.mission.MissionPhase
import org.openutm.djiflight.core.mission.MissionPlan
import org.openutm.djiflight.core.mission.MissionRunner
import org.openutm.djiflight.core.mission.MissionValidator
import org.openutm.djiflight.core.mission.SurveyPlanner
import org.openutm.djiflight.core.mission.SurveySpec
import org.openutm.djiflight.core.mission.TravelLegs
import org.openutm.djiflight.core.mission.WpmlTarget
import org.openutm.djiflight.core.mission.WpmlWriter
import org.openutm.djiflight.core.model.TelemetrySample
import org.openutm.djiflight.core.provisioning.ProvisioningCodec
import org.openutm.djiflight.core.session.FlightEvent
import org.openutm.djiflight.core.session.FlightRecorder
import org.openutm.djiflight.core.session.FlightSnapshot
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase A of docs/13: what DroneDeploy pilots expect when they switch (checklist, gimbal, travel altitude, photo hand-off). */
@OptIn(ExperimentalCoroutinesApi::class)
class MigrationFeaturesTest {
    @TempDir lateinit var tmp: File
    private val grid = Egm96Grid.bundled()
    private val plane = LocalPlane(TestData.NAIROBI)
    private fun square(sideM: Double) = listOf(XY(0.0, 0.0), XY(sideM, 0.0), XY(sideM, sideM), XY(0.0, sideM)).map(plane::toLatLng)
    private fun survey(travel: Double? = 100.0, gimbal: Double = -80.0) =
        SurveyPlanner.plan(SurveySpec(square(200.0), CameraModel.MAVIC_3E, 80.0, 0.75, 0.65, 0.0, 8.0, gimbalPitchDeg = gimbal, travelHeightM = travel)).plan

    // --- photo count -> hand-off card

    private fun sample(seq: Long, photos: Int?) = TelemetrySample(seq, 1_000L * seq, -1.29, 36.82, 50.0, 0.0, 0.0, 0.0, 0.0, true, true, photos = photos)

    @Test
    fun `photo count per flight ignores earlier photos and survives an app restart`() {
        // 40 photos from an earlier flight this session; this flight takes 12, the app restarts (count from 0), then 5 more.
        val s = listOf(sample(0, 40), sample(1, 40), sample(2, 45), sample(3, 52), sample(4, null), sample(5, 3), sample(6, 5))
        val st = PhotoStats.of(s)
        assertEquals(12 + 5, st.count)
        assertEquals(2_000L, st.firstMs)
        assertEquals(6_000L, st.lastMs)
        assertEquals(PhotoStats(0, null, null), PhotoStats.of(listOf(sample(0, 7), sample(1, 7))))
    }

    @Test
    fun `the recorder stores the running photo count - checklist and hand-off are replayed from the journal`() {
        val t0 = 1_792_142_000_000L
        val j = FlightRepository(tmp).create("f1")
        val rec = FlightRecorder(j, grid)
        rec.record(FlightEvent.Planned(t0, TestData.plan(t0, flightId = "f1")))
        rec.record(FlightEvent.ChecklistCompleted(t0 + 1, listOf("Propellers secure", "SD card formatted"), "pilot-1"))
        for (i in 0 until 60) rec.onDroneState(TestData.state(t0 + 10_000 + i * 1_000L, h = 30.0, flying = true).copy(photoCount = i / 3))
        rec.record(FlightEvent.ImageryHandedOff(t0 + 100_000, "DroneDeploy", "Ngong 2026-10-17 f1", 19))

        val samples = j.readSamples()
        assertEquals(19, PhotoStats.of(samples).count)
        val again = FlightSnapshot.fold("f1", FlightRepository(tmp).list().single { it.flightId == "f1" }.readEvents())
        assertEquals(listOf("Propellers secure", "SD card formatted"), again.checklist?.items)
        assertEquals("pilot-1", again.checklist?.pilotId)
        assertEquals("Ngong 2026-10-17 f1", again.imageryHandoff?.name)
        val geo = FlightExport.geoJson(again, samples)
        assertTrue("\"photos\":19" in geo && "\"checklist_items\":[\"Propellers secure\",\"SD card formatted\"]" in geo && "\"imagery_service\":\"DroneDeploy\"" in geo, geo.take(600))
    }

    @Test
    fun `hand-off names the map after the site, date and flight, and lists the photos to upload`() {
        val t0 = 1_792_142_000_000L // 2026-10-16 09:13 UTC
        val plan = TestData.plan(t0, flightId = "c497ad07-d62e-48d4-b069-05202bddde9d").copy(siteName = "Ngong forest")
        val f = FlightSnapshot("c497ad07-d62e-48d4-b069-05202bddde9d", plan = plan, everFlown = true, finishRequested = true)
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val name = org.openutm.djiflight.core.imagery.ImageryHandoff.mapName(f, utc)
        assertEquals("Ngong forest 2026-10-16 c497ad07", name)
        assertEquals("Kenya Flying Labs (test) 2026-10-16 c497ad07", org.openutm.djiflight.core.imagery.ImageryHandoff.mapName(f.copy(plan = plan.copy(siteName = " ")), utc))
        // The pilot's date, not UTC's: 21:30 UTC is already the next day in Nairobi (UTC+3) and still the same day in Lima (UTC-5).
        val late = f.copy(plan = plan.copy(plannedStartMs = 1_792_186_200_000L)) // 2026-10-16 21:30 UTC
        assertEquals("Ngong forest 2026-10-17 c497ad07", org.openutm.djiflight.core.imagery.ImageryHandoff.mapName(late, java.util.TimeZone.getTimeZone("Africa/Nairobi")))
        assertEquals("Ngong forest 2026-10-16 c497ad07", org.openutm.djiflight.core.imagery.ImageryHandoff.mapName(late, java.util.TimeZone.getTimeZone("America/Lima")))
        val stats = PhotoStats(312, t0, t0 + 2_520_000)
        val steps = org.openutm.djiflight.core.imagery.ImageryHandoff.steps(org.openutm.djiflight.core.provisioning.ImageryService("DroneDeploy", "Ngong 2026"), name, stats) { "T$it" }
        assertTrue(steps.startsWith("312 photos, taken T$t0–T${t0 + 2_520_000}."), steps)
        assertTrue("DroneDeploy → Ngong 2026" in steps && name in steps, steps)
        assertTrue(org.openutm.djiflight.core.imagery.ImageryHandoff.pending(f, stats))
        assertTrue(!org.openutm.djiflight.core.imagery.ImageryHandoff.pending(f, PhotoStats(0, null, null)))
        assertTrue(!org.openutm.djiflight.core.imagery.ImageryHandoff.pending(f.copy(imageryHandoff = FlightEvent.ImageryHandedOff(t0, "DroneDeploy", name, 312)), stats))
        assertTrue(!org.openutm.djiflight.core.imagery.ImageryHandoff.pending(f.copy(finishRequested = false), stats)) // still flying: not yet
    }

    // --- device setup + advisor

    @Test
    fun `device setup carries the lab checklist and its processing service`() {
        val json = """{"device_name":"KE-SC-01","blender_url":"https://utm.example.org","auth":{"type":"dev"},"lab":"Kenya Flying Labs","operator_id":"OPR-KE-001",
            "checklist":["Propellers secure","Batteries charged"],"imagery":{"service":"DroneDeploy","project":"Ngong forest 2026"}}"""
        val p = ProvisioningCodec.decode(json)
        assertEquals(listOf("Propellers secure", "Batteries charged"), p.checklist)
        assertEquals("Ngong forest 2026", p.imagery?.project)
        assertTrue(p.problems(allowDevAuth = true).isEmpty())
        assertEquals(listOf("checklist has an empty item"), p.copy(checklist = listOf("ok", " ")).problems(allowDevAuth = true))
        val old = ProvisioningCodec.decode(json.replace(""""checklist":["Propellers secure","Batteries charged"],"imagery":{"service":"DroneDeploy","project":"Ngong forest 2026"}""", """"x":1"""))
        assertTrue(old.checklist.isEmpty() && old.imagery == null)
    }

    @Test
    fun `the advisor asks for the checklist until it is done, louder once flying`() {
        val t0 = 1_792_142_000_000L
        val snap = FlightSnapshot("f1", plan = TestData.plan(t0, flightId = "f1"))
        fun codes(s: FlightSnapshot, flying: Boolean, required: Boolean = true) =
            FlightAdvisor.advise(AdvisorInput(s, TestData.state(t0, flying = flying, h = if (flying) 20.0 else 0.0), false, null, emptyList(), t0, checklistRequired = required))
                .filter { it.code == "CHECKLIST" }
        assertEquals("INFO", codes(snap, flying = false).single().level.name)
        assertEquals("WARNING", codes(snap, flying = true).single().level.name)
        assertTrue(codes(snap.copy(checklist = FlightEvent.ChecklistCompleted(t0, listOf("a"))), flying = true).isEmpty())
        assertTrue(codes(snap, flying = true, required = false).isEmpty())
    }

    // --- gimbal angle + travel altitude

    @Test
    fun `travel legs - climb over the aircraft, level to the start, out at travel height, never photographed`() {
        val plan = survey()
        assertEquals(-80.0, plan.gimbalPitchDeg)
        assertEquals(100.0, plan.maxHeightM)                        // the declaration must cover the travel height
        val home = plane.toLatLng(XY(-150.0, -150.0))
        val e = TravelLegs.expand(plan, home, intro = true, outro = true)
        assertEquals(2, e.lead)
        val w = e.plan.waypoints
        assertEquals(plan.waypoints.size + 3, w.size)
        assertEquals(home, w[0].position)
        assertEquals(listOf(100.0, 100.0), w.take(2).map { it.heightM })
        assertEquals(100.0, w.last().heightM)
        assertEquals(listOf(true, true), w.take(2).map { it.transit })
        assertTrue(w.last().transit && w.subList(2, w.size - 1).none { it.transit })
        assertEquals(TravelLegs.OFFSET_M, Geo.distanceM(w[1].position, w[2].position), 0.01)
        assertTrue(MissionValidator.validate(e.plan, MissionLimits.WAYPOINT_V1, 120.0).isEmpty())
        // Aircraft right under the start: no separate climb point (would be too close).
        assertEquals(1, TravelLegs.expand(plan, plan.waypoints[0].position, intro = true, outro = false).lead)
        // No travel height: nothing added.
        assertEquals(survey(travel = null).waypoints, TravelLegs.expand(survey(travel = null), home, true, true).plan.waypoints)
    }

    @Test
    fun `travel altitude must be above the mission and within the legal limit`() {
        val plan = survey()
        assertTrue(MissionValidator.validate(plan.copy(travelHeightM = 80.0), MissionLimits.WPML, 120.0).any { "must be above the mission altitude" in it })
        assertTrue(MissionValidator.validate(plan.copy(travelHeightM = 130.0), MissionLimits.WPML, 120.0).any { "exceeds the legal limit" in it })
        assertTrue(MissionValidator.validate(plan.copy(gimbalPitchDeg = -100.0), MissionLimits.WPML, 120.0).any { "gimbal angle" in it })
        // The planner drops a travel height at or below the survey height.
        assertNull(survey(travel = 60.0).travelHeightM)
    }

    @Test
    fun `WPML - climb to the travel height first, photos only between the first and last survey waypoints, RTH at travel height`() {
        val e = TravelLegs.expand(survey(), plane.toLatLng(XY(-150.0, -150.0)), intro = true, outro = true)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(ByteArrayInputStream(WpmlWriter.waylines(e.plan, WpmlTarget.MAVIC_3E).toByteArray())).documentElement
        fun text(name: String) = doc.getElementsByTagNameNS(WpmlWriter.NAMESPACE, name).item(0).textContent.trim()
        assertEquals("100", text("takeOffSecurityHeight"))
        assertEquals("100", text("globalRTHHeight"))
        val groups = doc.getElementsByTagNameNS(WpmlWriter.NAMESPACE, "actionGroup")
        val interval = (0 until groups.length).map { groups.item(it) as org.w3c.dom.Element }.single {
            it.getElementsByTagNameNS(WpmlWriter.NAMESPACE, "actionTriggerType").item(0).textContent == "multipleDistance"
        }
        fun idx(name: String) = interval.getElementsByTagNameNS(WpmlWriter.NAMESPACE, name).item(0).textContent.toInt()
        assertEquals(2, idx("actionGroupStartIndex"))
        assertEquals(e.plan.waypoints.size - 2, idx("actionGroupEndIndex"))
        assertEquals("-80", doc.getElementsByTagNameNS(WpmlWriter.NAMESPACE, "gimbalPitchRotateAngle").item(0).textContent)
    }

    // --- the runner adds the legs at upload (also after a resume) and keeps the pilot's numbering

    private class Op : MissionOperator {
        val flow = MutableSharedFlow<MissionEvent>(extraBufferCapacity = 256)
        override val events: SharedFlow<MissionEvent> = flow
        val uploads = mutableListOf<MissionPlan>()
        override suspend fun upload(plan: MissionPlan): Result<Unit> = Result.success(Unit).also { uploads += plan }
        override suspend fun start() = Result.success(Unit).also { flow.emit(MissionEvent.Started) }
        override suspend fun pause() = Result.success(Unit)
        override suspend fun resume() = Result.success(Unit)
        override suspend fun stop() = Result.success(Unit)
    }

    private class Adapter : DroneAdapter {
        val op = Op()
        override val link = MutableStateFlow<LinkState>(LinkState.Connected(AircraftIdentity("SN1", "Mavic 3 Enterprise")))
        override val capabilities: StateFlow<DroneCapabilities?> = MutableStateFlow(DroneCapabilities("Mavic 3 Enterprise", MissionSupport.WPML, MissionLimits.WPML, false, 10))
        override val states: SharedFlow<DroneState> = MutableSharedFlow()
        override fun missionOperator(): MissionOperator = op
        override fun start() = Unit
        override fun stop() = Unit
        override fun resubscribe() = Unit
    }

    @Test
    fun `runner - travel legs on upload and on resume, progress numbered without them`() = runTest(UnconfinedTestDispatcher()) {
        val a = Adapter()
        val home = plane.toLatLng(XY(-150.0, -150.0))
        val r = MissionRunner(backgroundScope, a, MissionLimits.WPML, null, { home }) { 120.0 }
        val plan = survey()
        val n = plan.waypoints.size
        r.load("f1", plan)
        r.upload(); r.start(); advanceUntilIdle()
        val first = a.op.uploads.single()
        assertEquals(n + 3, first.waypoints.size)
        // Operator indices include the 2 lead points: reaching operator index 7 = mission waypoint 6 (index 5).
        (0..7).forEach { a.op.flow.tryEmit(MissionEvent.Progress(it, n + 3, reached = true)) }
        assertEquals(6, r.ui.value.waypoint)
        a.op.flow.tryEmit(MissionEvent.Finished("low battery RTH")); advanceUntilIdle()
        assertEquals(MissionPhase.INTERRUPTED, r.ui.value.phase)
        assertEquals(6, r.ui.value.resumeFrom)
        r.resume(); advanceUntilIdle()
        val rest = a.op.uploads.last()
        assertEquals(plan.waypoints.subList(5, n), rest.waypoints.filter { !it.transit })
        assertEquals(listOf(true, true), rest.waypoints.take(2).map { it.transit }) // climb + approach again
        r.start(); advanceUntilIdle()
        (0 until rest.waypoints.size).forEach { a.op.flow.tryEmit(MissionEvent.Progress(it, rest.waypoints.size, reached = true)) }
        a.op.flow.tryEmit(MissionEvent.Finished(null)); advanceUntilIdle()
        assertEquals(MissionPhase.DONE, r.ui.value.phase)
        assertEquals(n, r.ui.value.waypoint)
    }
}
