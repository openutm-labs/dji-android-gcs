package org.openutm.djiflight.core

import org.junit.jupiter.api.io.TempDir
import org.openutm.djiflight.core.altitude.AltitudeSource
import org.openutm.djiflight.core.altitude.Egm96Grid
import org.openutm.djiflight.core.altitude.GnssFix
import org.openutm.djiflight.core.journal.CrcLineFile
import org.openutm.djiflight.core.journal.FlightJournal
import org.openutm.djiflight.core.journal.FlightRepository
import org.openutm.djiflight.core.model.TelemetrySample
import org.openutm.djiflight.core.session.FlightEvent
import org.openutm.djiflight.core.session.FlightRecorder
import org.openutm.djiflight.core.session.Phase
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JournalAndRecorderTest {
    @TempDir
    lateinit var tmp: File
    private val grid = Egm96Grid.bundled()

    private fun sample(seq: Long) = TelemetrySample(seq, 1_000L * seq, -1.29, 36.82, 1.0, 0.0, 0.0, 0.0, 0.0, false, false)

    @Test
    fun `torn tail is ignored and repaired before the next append`() {
        val f = File(tmp, "x.log")
        CrcLineFile(f).append(listOf("""{"a":1}""", """{"a":2}"""), sync = true)
        f.appendText("0badc0de {\"a\":3") // power cut mid-line: no newline
        assertEquals(listOf("""{"a":1}""", """{"a":2}"""), CrcLineFile(f).read().lines)

        val reopened = CrcLineFile(f) // opening cuts the torn fragment off
        reopened.append(listOf("""{"a":4}"""), sync = true)
        assertEquals(listOf("""{"a":1}""", """{"a":2}""", """{"a":4}"""), reopened.read().lines)
    }

    @Test
    fun `a corrupted middle line is skipped, the rest survives`() {
        val f = File(tmp, "y.log")
        val lines = CrcLineFile(f)
        lines.append(listOf("""{"a":1}""", """{"a":2}""", """{"a":3}"""), sync = true)
        RandomAccessFile(f, "rw").use { it.seek(f.readText().indexOf("\"a\":2") + 4L); it.write('9'.code) }
        val r = lines.read()
        assertEquals(listOf("""{"a":1}""", """{"a":3}"""), r.lines)
        assertEquals(1, r.corruptLines)
    }

    @Test
    fun `sample reader resumes from where it stopped and the cursor is durable`() {
        val j = FlightRepository(tmp).create("f1")
        j.appendSamples((0L..9L).map(::sample), sync = true)
        val reader = j.sampleReader()
        assertEquals((0L..3L).toList(), reader.after(-1, 4).map { it.seq })
        j.cursor = 3
        j.appendSamples((10L..12L).map(::sample), sync = false)
        assertEquals((4L..12L).toList(), reader.after(j.cursor, 100).map { it.seq })
        assertEquals(3, FlightJournal(File(tmp, "f1")).cursor)
    }

    @Test
    fun `recorder downsamples 10 Hz to 1 Hz, marks take-off and landing, idles at 0_1 Hz`() {
        val j = FlightRepository(tmp).create("f2")
        val rec = FlightRecorder(j, grid)
        val t0 = 1_792_142_000_000L
        // Ground height entered or from a saved site, so the aircraft's own value is the reference.
        val sitePlan = TestData.plan(t0, flightId = "f2").let { it.copy(ground = it.ground.copy(source = AltitudeSource.SITE_ESTIMATE)) }
        rec.record(FlightEvent.Planned(t0, sitePlan))
        rec.record(FlightEvent.Declared(t0, "decl-f2"))
        // 30 s on the ground, motors off (10 Hz): expect 3 heartbeat samples
        for (i in 0 until 300) rec.onDroneState(TestData.state(t0 + i * 100L))
        // 60 s flying at 10 Hz: expect ~60 samples, plus the take-off edge
        for (i in 300 until 900) rec.onDroneState(TestData.state(t0 + i * 100L, h = 20.0, flying = true, vn = 2.0))
        // land, motors still on for 2 s
        for (i in 900 until 920) rec.onDroneState(TestData.state(t0 + i * 100L, motors = true))

        val samples = j.readSamples()
        val events = j.readEvents()
        assertEquals(samples.map { it.seq }, samples.indices.map { it.toLong() })
        assertTrue(samples.zipWithNext().all { (a, b) -> b.t > a.t })
        val ground = samples.count { !it.flying && !it.motors }
        val flying = samples.count { it.flying }
        assertEquals(3, ground)
        assertTrue(flying in 60..61, "flying samples: $flying")
        assertEquals(1, events.count { it is FlightEvent.TookOff })
        assertEquals(1, events.count { it is FlightEvent.Landed })
        assertEquals(Phase.LANDED, rec.snapshot.phase)
        // The take-off reference was resolved before take-off from DJI's value (no device GNSS here).
        val ref = rec.snapshot.takeoffReference!!
        assertEquals(AltitudeSource.DJI_TAKEOFF_MSL, ref.chosen.source)
        assertEquals(1661.0 - 16.06, ref.chosen.ellipsoidM, 0.05)
    }

    @Test
    fun `a flight flown without a declaration shows as UNDECLARED`() {
        val rec = FlightRecorder(FlightRepository(tmp).create("f5"), grid)
        val t0 = 1_792_142_000_000L
        rec.record(FlightEvent.Planned(t0, TestData.plan(t0, flightId = "f5")))
        for (i in 0 until 20) rec.onDroneState(TestData.state(t0 + i * 100L, h = 5.0, flying = i >= 5))
        assertEquals(Phase.UNDECLARED, rec.snapshot.phase)
    }

    @Test
    fun `a plan measured with this device's GNSS seeds the take-off reference`() {
        // Planned at the site seconds before take-off: the recorder hasn't seen enough fixes yet.
        val rec = FlightRecorder(FlightRepository(tmp).create("f6"), grid)
        val t0 = 1_792_142_000_000L
        rec.record(FlightEvent.Planned(t0, TestData.plan(t0, flightId = "f6"))) // ground: DEVICE_GNSS, sigma 5
        rec.onDroneState(TestData.state(t0 + 1_000, h = 12.0, flying = true))
        val ref = rec.snapshot.takeoffReference!!
        assertEquals(AltitudeSource.DEVICE_GNSS, ref.chosen.source)
        assertEquals(5.0, ref.chosen.sigmaM)
        assertTrue(ref.candidates.any { it.source == AltitudeSource.DJI_TAKEOFF_MSL })
    }

    @Test
    fun `device GNSS wins over DJI and corrects the sample clock`() {
        val j = FlightRepository(tmp).create("f3")
        val rec = FlightRecorder(j, grid)
        val t0 = 1_792_142_000_000L
        rec.record(FlightEvent.Planned(t0, TestData.plan(t0, flightId = "f3")))
        // The controller's clock is 90 s slow; GNSS knows better.
        repeat(6) { i -> rec.onDeviceFix(GnssFix(t0 + 90_000 + i * 1000, t0 + i * 1000, -1.29215, 36.82185, 1646.0, 4.0)) }
        rec.onDroneState(TestData.state(t0 + 10_000, motors = true))
        val ref = rec.snapshot.takeoffReference!!
        assertEquals(AltitudeSource.DEVICE_GNSS, ref.chosen.source)
        assertEquals(1646.0, ref.chosen.ellipsoidM, 1e-9)
        assertEquals(1.06, ref.disagreementM!!, 0.06)
        val s = j.readSamples().single()
        assertEquals(t0 + 100_000, s.t)
        assertEquals(-1.29215, s.pilotLat!!, 1e-9)
    }

    @Test
    fun `restart reproduces exactly the same state from the journal (no H1 stale snapshot)`() {
        val j = FlightRepository(tmp).create("f4")
        val rec = FlightRecorder(j, grid)
        val t0 = 1_792_142_000_000L
        rec.record(FlightEvent.Planned(t0, TestData.plan(t0, flightId = "f4")))
        rec.record(FlightEvent.Declared(t0 + 1, "decl-1"))
        for (i in 0 until 50) rec.onDroneState(TestData.state(t0 + i * 200L, h = 10.0, flying = i in 10..40))
        rec.record(FlightEvent.Activated(t0 + 20_000))
        rec.record(FlightEvent.FinishRequested(t0 + 30_000))
        rec.record(FlightEvent.ServerClosed(t0 + 31_000, org.openutm.djiflight.core.sync.OperationState.ENDED))

        val replayed = FlightRecorder(FlightJournal(File(tmp, "f4")), grid)
        assertEquals(rec.snapshot, replayed.snapshot)
        assertEquals(Phase.CLOSED, replayed.snapshot.phase)
        assertEquals(rec.lastRecordedSeq, replayed.lastRecordedSeq)
        // A closed flight records nothing more, even if states keep coming.
        replayed.onDroneState(TestData.state(t0 + 60_000, flying = true))
        assertEquals(rec.lastRecordedSeq, replayed.lastRecordedSeq)
    }
}
