package org.openutm.djiflight.core.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openutm.djiflight.core.adapter.AircraftIdentity
import org.openutm.djiflight.core.adapter.DroneState
import org.openutm.djiflight.core.altitude.AltitudeCandidate
import org.openutm.djiflight.core.altitude.AltitudeSource
import org.openutm.djiflight.core.altitude.Egm96Grid
import org.openutm.djiflight.core.altitude.GnssAverager
import org.openutm.djiflight.core.altitude.GnssFix
import org.openutm.djiflight.core.altitude.TakeoffReferenceResolver
import org.openutm.djiflight.core.journal.FlightJournal
import org.openutm.djiflight.core.model.TelemetrySample

data class RecorderConfig(
    /** Sample interval while flying or with motors on. */
    val activeIntervalMs: Long = 1_000,
    /** Sample interval on the ground with motors off (a heartbeat, so the record shows the aircraft was there). */
    val idleIntervalMs: Long = 10_000,
    /** fsync samples every N appends; events are always synced. */
    val syncEverySamples: Int = 5,
)

/**
 * The single writer of one flight's journal. Not thread-safe by design: exactly one coroutine
 * ([FlightActor]) calls it. Telemetry upload never writes flight state; it reports back here.
 */
class FlightRecorder(
    private val journal: FlightJournal,
    private val grid: Egm96Grid,
    private val config: RecorderConfig = RecorderConfig(),
) {
    var snapshot: FlightSnapshot = FlightSnapshot.fold(journal.flightId, journal.readEvents())
        private set

    private var lastSeq: Long = journal.readSamples().lastOrNull()?.seq ?: -1
    private var lastSampleT: Long = Long.MIN_VALUE
    private var lastFlying: Boolean = snapshot.airborne
    private var lastMode: String? = null
    private var unsynced = 0

    /** device clock + offset = GNSS time. */
    @Volatile
    var clockOffsetMs: Long = 0
        private set
    private var pilotLat: Double? = null
    private var pilotLng: Double? = null
    private val gnss = GnssAverager()

    val lastRecordedSeq: Long get() = lastSeq

    fun record(e: FlightEvent) {
        journal.appendEvent(e)
        snapshot = snapshot.apply(e)
    }

    fun onDeviceFix(fix: GnssFix) {
        clockOffsetMs = fix.gnssTimeMs - fix.deviceTimeMs
        if (fix.lat.isFinite() && fix.lng.isFinite()) {
            pilotLat = fix.lat
            pilotLng = fix.lng
        }
        if (!snapshot.everFlown) gnss.add(fix)
    }

    /** [nowMs] is already GNSS-corrected (the service's clock). */
    fun onAircraft(identity: AircraftIdentity, nowMs: Long) {
        if (snapshot.aircraft != identity && !snapshot.recordingClosed) record(FlightEvent.AircraftConnected(nowMs, identity))
    }

    fun onDroneState(s: DroneState) {
        if (snapshot.plan == null || snapshot.recordingClosed) return
        val t = s.timeMs + clockOffsetMs
        val edge = s.isFlying != lastFlying
        val modeChanged = s.flightMode != null && s.flightMode != lastMode && lastMode != null
        lastMode = s.flightMode ?: lastMode

        if ((s.motorsOn || s.isFlying) && snapshot.takeoffReference == null) resolveTakeoffReference(s, t)

        val interval = if (s.isFlying || s.motorsOn) config.activeIntervalMs else config.idleIntervalMs
        val due = lastSampleT == Long.MIN_VALUE || t - lastSampleT >= interval
        val positionOk = s.position.isValid
        if (positionOk && t > lastSampleT && (due || edge || modeChanged)) appendSample(s, t, forceSync = edge)

        if (edge) {
            lastFlying = s.isFlying
            if (s.isFlying) {
                // Resolve now if motors-on came too early for the phone's GNSS average; the last resolution wins.
                val ref = snapshot.takeoffReference
                val deviceArrivedLate = ref != null && ref.candidates.none { it.source == AltitudeSource.DEVICE_GNSS } && gnss.candidate() != null
                if (ref == null || deviceArrivedLate) resolveTakeoffReference(s, t)
                record(FlightEvent.TookOff(t, lastSeq))
            } else {
                record(FlightEvent.Landed(t, lastSeq))
            }
        }
    }

    private fun appendSample(s: DroneState, t: Long, forceSync: Boolean) {
        val sample = TelemetrySample(
            seq = ++lastSeq, t = t, lat = s.lat, lng = s.lng, h = s.heightAboveTakeoffM,
            vn = s.velocityNorthMps, ve = s.velocityEastMps, vd = s.velocityDownMps, yaw = s.yawDeg,
            flying = s.isFlying, motors = s.motorsOn, gps = s.gpsLevel, sats = s.satellites, mode = s.flightMode,
            pilotLat = pilotLat, pilotLng = pilotLng, photos = s.photoCount,
        )
        unsynced++
        val sync = forceSync || unsynced >= config.syncEverySamples
        journal.appendSamples(listOf(sample), sync)
        if (sync) unsynced = 0
        lastSampleT = t
    }

    /** RTK (fixed) > device GNSS (cross-checked with DJI) > DJI > the plan's site estimate. */
    private fun resolveTakeoffReference(s: DroneState, t: Long) {
        val at = s.home?.takeIf { it.isValid } ?: s.position
        val rtk = s.rtk?.takeIf { it.fixed && it.ellipsoidM != null }
            ?.let { AltitudeCandidate(AltitudeSource.RTK, it.ellipsoidM!!, it.sigmaM ?: 0.05) }
        // The plan's ground height counts as the device's candidate when this device measured it at the
        // site (e.g. a flight planned just before take-off, before this recorder saw any fixes).
        val planned = snapshot.plan?.ground?.takeIf { it.source == AltitudeSource.DEVICE_GNSS }
            ?.let { AltitudeCandidate(AltitudeSource.DEVICE_GNSS, it.ellipsoidM, it.sigmaM) }
        val device = gnss.candidate() ?: planned
        val dji = TakeoffReferenceResolver.djiCandidate(s.takeoffMslM, at, grid)
        val ref = TakeoffReferenceResolver.resolve(rtk, device, dji) ?: return
        record(FlightEvent.TakeoffReferenceResolved(t, TakeoffReferenceResolver.keepChoice(snapshot.takeoffReference, ref)))
    }
}

/** Serialises every write to one flight through one coroutine. */
class FlightActor(scope: CoroutineScope, private val recorder: FlightRecorder) {
    private sealed interface Msg {
        data class State(val s: DroneState) : Msg
        data class Fix(val f: GnssFix) : Msg
        data class Aircraft(val id: AircraftIdentity, val nowMs: Long) : Msg
        data class Event(val e: FlightEvent, val done: CompletableDeferred<FlightSnapshot>) : Msg
        data class Read(val done: CompletableDeferred<FlightSnapshot>) : Msg
    }

    // States are many and replaceable; never let them block the producer (the SDK's callback thread).
    private val states = Channel<Msg>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val commands = Channel<Msg>(capacity = Channel.UNLIMITED)
    private val _snapshot = MutableStateFlow(recorder.snapshot)
    val snapshot: StateFlow<FlightSnapshot> = _snapshot.asStateFlow()
    val flightId: String get() = recorder.snapshot.flightId

    private val job = scope.launch {
            while (true) {
                val msg = kotlinx.coroutines.selects.select {
                    commands.onReceive { it }
                    states.onReceive { it }
                }
                when (msg) {
                    is Msg.State -> recorder.onDroneState(msg.s)
                    is Msg.Fix -> recorder.onDeviceFix(msg.f)
                    is Msg.Aircraft -> recorder.onAircraft(msg.id, msg.nowMs)
                    is Msg.Event -> {
                        // Apply states that arrived before this event first: "Finish" must not drop the last samples.
                        drainStates()
                        recorder.record(msg.e)
                        msg.done.complete(recorder.snapshot)
                    }
                    is Msg.Read -> {
                        drainStates()
                        msg.done.complete(recorder.snapshot)
                    }
                }
                _snapshot.value = recorder.snapshot
            }
        }

    /** Stops the actor (flight deleted). */
    fun close() = job.cancel()

    private fun drainStates() {
        while (true) {
            val m = states.tryReceive().getOrNull() ?: return
            recorder.onDroneState((m as Msg.State).s)
        }
    }

    fun offerState(s: DroneState) {
        states.trySend(Msg.State(s))
    }

    fun offerFix(f: GnssFix) {
        commands.trySend(Msg.Fix(f))
    }

    fun offerAircraft(id: AircraftIdentity, nowMs: Long) {
        commands.trySend(Msg.Aircraft(id, nowMs))
    }

    suspend fun record(e: FlightEvent): FlightSnapshot = CompletableDeferred<FlightSnapshot>().also { commands.send(Msg.Event(e, it)) }.await()

    /** Snapshot after everything queued so far has been applied. */
    suspend fun current(): FlightSnapshot = CompletableDeferred<FlightSnapshot>().also { commands.send(Msg.Read(it)) }.await()

    val clockOffsetMs: Long get() = recorder.clockOffsetMs
}
