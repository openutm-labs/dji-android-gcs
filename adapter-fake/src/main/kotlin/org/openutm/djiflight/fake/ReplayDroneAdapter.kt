package org.openutm.djiflight.fake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openutm.djiflight.core.adapter.AircraftIdentity
import org.openutm.djiflight.core.adapter.AircraftSetup
import org.openutm.djiflight.core.adapter.DroneAdapter
import org.openutm.djiflight.core.adapter.DroneCapabilities
import org.openutm.djiflight.core.adapter.DroneState
import org.openutm.djiflight.core.adapter.LinkState
import org.openutm.djiflight.core.adapter.MissionEvent
import org.openutm.djiflight.core.adapter.MissionOperator
import org.openutm.djiflight.core.adapter.MissionSupport
import org.openutm.djiflight.core.altitude.GnssFix
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.core.mission.MissionPlan
import org.openutm.djiflight.core.time.Clock
import kotlin.random.Random

/**
 * Plays a [Track] through the same contract the DJI adapters implement, so the core, the `sim`
 * APK and tests run without a drone. [speed] > 1 compresses time; states are stamped with
 * [deviceClock] (the device's own, uncorrected clock), as a real SDK callback would be.
 */
class ReplayDroneAdapter(
    private val track: Track,
    private val scope: CoroutineScope,
    private val speed: Double = 1.0,
    private val deviceClock: Clock = Clock.SYSTEM,
    /** After the track ends, keep sending the last (landed) state at 1 Hz, like a connected aircraft. */
    private val idleAfterEnd: Boolean = true,
    private val connectDelayMs: Long = 500,
    /** > 0: the mission operator flies one waypoint per this many ms, so Pause, Stop and Resume can be tried (the `sim` app). */
    missionStepMs: Long = 0,
) : DroneAdapter {
    private val _link = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val link: StateFlow<LinkState> = _link.asStateFlow()
    private val _caps = MutableStateFlow<DroneCapabilities?>(null)
    override val capabilities: StateFlow<DroneCapabilities?> = _caps.asStateFlow()
    private val _states = MutableSharedFlow<DroneState>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val states: SharedFlow<DroneState> = _states.asSharedFlow()

    private val identity = AircraftIdentity(track.header.serial, track.header.model, "replay")
    private val missions = FakeMissionOperator(scope, missionStepMs)
    private val setup = FakeAircraftSetup(scope)
    private var job: Job? = null
    @Volatile private var staleUntilResubscribe = false

    /** How often the core asked for a resubscribe (the stale-telemetry watchdog). */
    @Volatile var resubscribeCount = 0
        private set

    /** True once the whole track has been played. */
    @Volatile var finished = false
        private set

    override fun missionOperator(): MissionOperator? = if (link.value is LinkState.Connected) missions else null

    override fun aircraftSetup(): AircraftSetup? = if (link.value is LinkState.Connected) setup else null

    override fun start() {
        if (job != null) return
        job = scope.launch { play() }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _link.value = LinkState.Disconnected
    }

    override fun resubscribe() {
        resubscribeCount++
        staleUntilResubscribe = false
    }

    private suspend fun connect() {
        _link.value = LinkState.Connecting
        delay((connectDelayMs / speed).toLong())
        _caps.value = DroneCapabilities(
            model = track.header.model,
            missionSupport = MissionSupport.WAYPOINT_V1,
            missionLimits = MissionLimits.WAYPOINT_V1,
            hasRtk = false,
            nativeRateHz = 10,
            camera = org.openutm.djiflight.core.mission.CameraModel.P4P_V2,
        )
        _link.value = LinkState.Connected(identity)
    }

    /** Pacing uses a monotonic clock: wall-clock jumps (NTP, time zone, a sleeping host) must not skip states. */
    private fun monoMs() = System.nanoTime() / 1_000_000

    private suspend fun play() {
        connect()
        val faults = ArrayDeque(track.faults)
        var gpsLostUntil = -1L
        var linkDownUntil = -1L
        var staleUntil = -1L
        var start = monoMs()
        for (s in track.states) {
            while (faults.isNotEmpty() && faults.first().t <= s.t) {
                val f = faults.removeFirst()
                when (f.kind) {
                    FaultKind.LINK_DROP -> { linkDownUntil = f.t + f.durationMs; _link.value = LinkState.Disconnected }
                    FaultKind.STALE -> { staleUntil = f.t + f.durationMs; staleUntilResubscribe = true }
                    FaultKind.GPS_LOSS -> gpsLostUntil = f.t + f.durationMs
                }
            }
            val wait = start + (s.t / speed).toLong() - monoMs()
            if (wait > 0) {
                delay(wait)
            } else if (wait < -1_000) {
                // The process or device stalled (overloaded emulator, sleeping host): resume from here
                // as if paused, rather than dropping the states that are now "late".
                start -= wait
            }
            if (!scope.isActive) return
            if (linkDownUntil >= 0) {
                if (s.t < linkDownUntil) continue // the aircraft flew on; the app heard nothing
                linkDownUntil = -1
                connect()
            }
            if (staleUntilResubscribe && s.t < staleUntil) continue // link up, no states (B4)
            staleUntilResubscribe = false
            if (link.value is LinkState.Connected) _states.emit(toDrone(s, noFix = s.t < gpsLostUntil))
        }
        finished = true
        val last = track.states.lastOrNull() ?: return
        while (idleAfterEnd && scope.isActive) {
            _states.emit(toDrone(last.copy(flying = false, motors = false, vn = 0.0, ve = 0.0, vd = 0.0), noFix = false))
            delay((1000 / speed).toLong().coerceAtLeast(20))
        }
    }

    /** Simulated survey camera: one photo per [PHOTO_EVERY_M] flown while airborne. */
    private var flownM = 0.0
    private var lastPos: TrackState? = null

    private fun photoCount(s: TrackState): Int {
        val p = lastPos
        if (p != null && s.flying && p.flying) flownM += org.openutm.djiflight.core.geo.Geo.distanceM(
            org.openutm.djiflight.core.geo.LatLng(p.lat, p.lng), org.openutm.djiflight.core.geo.LatLng(s.lat, s.lng),
        )
        lastPos = s
        return (flownM / PHOTO_EVERY_M).toInt()
    }

    private fun toDrone(s: TrackState, noFix: Boolean) = DroneState(
        timeMs = deviceClock.nowMs(),
        lat = if (noFix) Double.NaN else s.lat,
        lng = if (noFix) Double.NaN else s.lng,
        heightAboveTakeoffM = s.h,
        velocityNorthMps = s.vn, velocityEastMps = s.ve, velocityDownMps = s.vd,
        yawDeg = s.yaw, isFlying = s.flying, motorsOn = s.motors,
        gpsLevel = if (noFix) 0 else s.gps, satellites = if (noFix) 0 else s.sats,
        flightMode = s.mode, home = track.header.takeoff, takeoffMslM = track.header.djiTakeoffMslM,
        photoCount = photoCount(s),
    )

    /**
     * What the pilot's phone would report standing at the take-off point: 1 Hz fixes with a little
     * noise around the track's device-GNSS height. GNSS time = device time + [clockErrorMs].
     */
    fun simulatedDeviceFixes(clockErrorMs: Long = 0, seed: Int = 7): Flow<GnssFix> = flow {
        val rnd = Random(seed)
        val h = track.header.deviceGnssEllipsoidM ?: return@flow
        val sigma = track.header.deviceGnssSigmaM
        while (true) {
            val now = deviceClock.nowMs()
            emit(GnssFix(now + clockErrorMs, now, track.header.takeoff.lat + rnd.nextDouble(-2e-6, 2e-6),
                track.header.takeoff.lng + rnd.nextDouble(-2e-6, 2e-6), h + rnd.nextDouble(-sigma, sigma) / 3, sigma))
            delay((1000 / speed).toLong().coerceAtLeast(20))
        }
    }
}

/**
 * Accepts any valid plan. With [stepMs] = 0 it reports the whole route at once (tests); with a
 * [scope] and [stepMs] > 0 it reaches one waypoint per [stepMs], and honours Pause, Resume and Stop,
 * so the `sim` app can walk a mission's every state, including Resume… after a Stop.
 */
class FakeMissionOperator(private val scope: CoroutineScope? = null, private val stepMs: Long = 0) : MissionOperator {
    private val _events = MutableSharedFlow<MissionEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<MissionEvent> = _events.asSharedFlow()
    var uploaded: MissionPlan? = null
        private set
    private val paused = MutableStateFlow(false)
    private var run: Job? = null

    override suspend fun upload(plan: MissionPlan): Result<Unit> {
        run?.cancel()
        uploaded = plan
        _events.emit(MissionEvent.UploadProgress(plan.waypoints.size, plan.waypoints.size))
        _events.emit(MissionEvent.Uploaded)
        return Result.success(Unit)
    }

    override suspend fun start(): Result<Unit> {
        val plan = uploaded ?: return Result.failure(IllegalStateException("no mission uploaded"))
        _events.emit(MissionEvent.Started)
        if (scope == null || stepMs <= 0) {
            plan.waypoints.indices.forEach { _events.emit(MissionEvent.Progress(it, plan.waypoints.size, reached = true)) }
            _events.emit(MissionEvent.Finished(null))
            return Result.success(Unit)
        }
        paused.value = false
        run?.cancel()
        run = scope.launch {
            for (i in plan.waypoints.indices) {
                _events.emit(MissionEvent.Progress(i, plan.waypoints.size, reached = false))
                // The aircraft holds position while paused: only unpaused time counts towards the next waypoint.
                var left = stepMs
                while (left > 0) {
                    paused.first { !it }
                    val tick = minOf(TICK_MS, left)
                    delay(tick)
                    left -= tick
                }
                _events.emit(MissionEvent.Progress(i, plan.waypoints.size, reached = true))
            }
            _events.emit(MissionEvent.Finished(null))
        }
        return Result.success(Unit)
    }

    override suspend fun pause(): Result<Unit> { paused.value = true; return Result.success(Unit) }

    private companion object {
        const val TICK_MS = 100L
    }
    override suspend fun resume(): Result<Unit> { paused.value = false; return Result.success(Unit) }
    override suspend fun stop(): Result<Unit> {
        run?.cancel()
        run = null
        return Result.success(Unit)
    }
}

/** The replay's simulated survey camera: one photo every this many metres flown. */
const val PHOTO_EVERY_M = 20.0
