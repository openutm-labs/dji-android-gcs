package org.openutm.djiflight.core.mission

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openutm.djiflight.core.adapter.MissionEvent
import org.openutm.djiflight.core.adapter.MissionOperator
import org.openutm.djiflight.core.geo.Geo
import kotlin.math.ceil

interface VirtualStickPort {
    fun nowMs(): Long
    fun state(): StickState?
    suspend fun enable(): Result<Unit>
    suspend fun command(value: StickCommand): Result<Unit>
    suspend fun release(): Result<Unit>
    suspend fun prepareCamera(): Result<Unit>
    suspend fun pitch(degrees: Double): Result<Unit>
    suspend fun shoot(): Result<Unit>
    suspend fun returnHome(): Result<Unit>
}

data class CapturePoint(val waypoint: MissionWaypoint, val originalIndex: Int, val original: Boolean, val shoot: Boolean)

/** Stop-and-shoot sampling avoids assuming that a consumer camera can sustain an arbitrary rate. */
object VirtualStickRoute {
    fun sample(plan: MissionPlan): List<CapturePoint> {
        require(plan.waypoints.size >= 2)
        val out = ArrayList<CapturePoint>()
        var pitch = plan.gimbalPitchDeg
        plan.waypoints.forEachIndexed { i, raw ->
            pitch = raw.gimbalPitchDeg ?: pitch
            val w = raw.copy(gimbalPitchDeg = pitch)
            out += CapturePoint(w, i, true, w.takePhoto || !w.transit && plan.photoDistanceIntervalM != null)
            val next = plan.waypoints.getOrNull(i + 1) ?: return@forEachIndexed
            val interval = plan.photoDistanceIntervalM?.takeIf { !w.transit && !next.transit } ?: return@forEachIndexed
            require(interval.isFinite() && interval >= 1) { "Mini 2 photo spacing must be at least 1 m" }
            val pieces = ceil(Geo.distanceM(w.position, next.position) / interval).toInt().coerceAtLeast(1)
            require(pieces <= 4_000 && out.size + pieces <= 4_000) { "split this Mini 2 route into smaller missions" }
            for (j in 1 until pieces) {
                val f = j.toDouble() / pieces
                out += CapturePoint(w.copy(lat = w.lat + (next.lat - w.lat) * f, lng = w.lng + (next.lng - w.lng) * f,
                    heightM = w.heightM + (next.heightM - w.heightM) * f), i + 1, false, true)
            }
        }
        require(out.size <= 4_000)
        return out
    }
}

/** Experimental Mini 2 operator; supplied only by explicitly opted-in debug builds. No automatic takeoff. */
class VirtualStickMission(private val port: VirtualStickPort, private val scope: CoroutineScope) : MissionOperator {
    private val eventBus = MutableSharedFlow<MissionEvent>(extraBufferCapacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<MissionEvent> = eventBus
    private val mutex = Mutex()
    private var plan: MissionPlan? = null
    private var job: Job? = null
    @Volatile private var paused = false
    @Volatile private var releaseError: String? = null

    override suspend fun upload(plan: MissionPlan): Result<Unit> = mutex.withLock {
        runCatching {
            check(job?.isActive != true) { "stop the active mission before replacing it" }
            val errors = MissionValidator.validate(plan, MissionLimits.WAYPOINT_V1.copy(maxWaypoints = 2_000), 120.0)
            require(errors.isEmpty()) { errors.joinToString("; ") }
            require(plan.waypoints.all { it.heightM in 5.0..120.0 } && plan.maxHeightM <= 120) { "Mini 2 test targets must stay within 5–120 m above takeoff" }
            require(plan.autoSpeedMps <= 3) { "experimental Mini 2 speed is limited to 3 m/s" }
            require(plan.finishAction == FinishAction.GO_HOME || plan.finishAction == FinishAction.HOVER) { "Mini 2 test missions may finish with RTH or hover only" }
            VirtualStickRoute.sample(plan)
            this.plan = plan
            eventBus.emit(MissionEvent.Uploaded)
        }
    }
    override suspend fun start(): Result<Unit> = mutex.withLock {
        var acquired = false
        runCatching {
            check(job?.isActive != true) { "mission is already running" }
            val p = checkNotNull(plan) { "prepare the mission first" }
            val initial = checkNotNull(port.state()) { "no aircraft telemetry" }
            val guidance = VirtualStickGuidance(initial.position)
            check(initial.airborne) { "take off manually before starting this experimental mission" }
            val rth = initial.returnHomeHeightM
            check(initial.failsafeReturnsHome && rth != null && rth.isFinite() && rth in p.maxHeightM..minOf(120.0,p.maxHeightM+10)) {
                "verify RC-loss RTH and set return height between the route ceiling and its declared margin before starting"
            }
            check(p.waypoints.all { Geo.distanceM(initial.position, it.position) <= 200 }) { "route exceeds the 200 m experimental radius" }
            // Validate freshness, GPS and reserve before obtaining command authority.
            check(port.nowMs() - initial.receivedMs in 0..500 && initial.gpsHealthy && !initial.pilotOverride && (initial.batteryPercent ?: 0) > 25) { "fresh GPS telemetry, neutral sticks and battery above 25% required" }
            withTimeout(5_000) { port.prepareCamera().getOrThrow() }
            acquired = true
            withTimeout(3_000) { port.enable().getOrThrow() }
            withTimeout(2_000) {
                while (port.state()?.authorityAvailable != true) {
                    check(port.state()?.pilotOverride != true) { "pilot override during authority acquisition" }
                    delay(50)
                }
            }
            paused = false
            releaseError = null
            job = scope.launch {
                var capture: Deferred<Result<Unit>>? = null
                var completed = false
                try {
                    eventBus.emit(MissionEvent.Started)
                    val points = VirtualStickRoute.sample(p)
                    var index = 0
                    var settlingSince: Long? = null
                    val started = port.nowMs()
                    while (index < points.size && isActive) {
                        val now = port.nowMs()
                        check(now - started <= 30 * 60_000) { "experimental mission time limit reached" }
                        val point = points[index]
                        val step = guidance.step(now, port.state(), point.waypoint, p.autoSpeedMps, held = paused || capture != null)
                        check(step.failure == null) { step.failure ?: "control guard failed" }
                        withTimeout(300) { port.command(if (paused || capture != null) StickCommand() else step.command).getOrThrow() }
                        if (paused) { settlingSince = null; delay(100); continue }
                        if (capture != null) {
                            if (capture.isCompleted) { capture.await().getOrThrow(); capture = null; index++; settlingSince = null }
                        } else if (step.reached) {
                            val settled = settlingSince ?: now.also { settlingSince = it }
                            if (now - settled >= 500) {
                                val pitch = point.waypoint.gimbalPitchDeg ?: p.gimbalPitchDeg
                                capture = async { runCatching {
                                    withTimeout(8_000) {
                                        port.pitch(pitch).getOrThrow()
                                        if (point.shoot) {
                                            val before = checkNotNull(port.state()).photoCount
                                            port.shoot().getOrThrow()
                                            while ((port.state()?.photoCount ?: before) <= before) delay(100)
                                        }
                                    }
                                    if (point.original) eventBus.emit(MissionEvent.Progress(point.originalIndex, p.waypoints.size, true))
                                } }
                            }
                        } else settlingSince = null
                        delay(100)
                    }
                    completed = true
                } catch (e: CancellationException) {
                    if (currentCoroutineContext().isActive) {
                        eventBus.emit(MissionEvent.Finished("camera or command acknowledgement timed out"))
                    } else throw e
                } catch (e: Exception) {
                    eventBus.emit(MissionEvent.Finished(e.message ?: "Virtual Stick mission interrupted"))
                } finally {
                    withContext(NonCancellable) {
                        capture?.cancelAndJoin()
                        // Never send another motion command after physical pilot override.
                        if (runCatching { port.state()?.let { !it.pilotOverride && it.authorityAvailable && port.nowMs() - it.receivedMs in 0..500 } == true }.getOrDefault(false)) runCatching { withTimeout(500) { port.command(StickCommand()) } }
                        runCatching { withTimeout(2_000) { port.release().getOrThrow() } }.onFailure {
                            releaseError = "control release was not confirmed: use the physical controller and follow the aircraft procedure"
                            eventBus.emit(MissionEvent.Finished(releaseError))
                        }
                    }
                }
                if (completed && releaseError == null) {
                    val result = if (p.finishAction == FinishAction.GO_HOME) runCatching { withTimeout(3_000) { port.returnHome().getOrThrow() } } else Result.success(Unit)
                    eventBus.emit(MissionEvent.Finished(result.exceptionOrNull()?.message))
                }
            }
        }.onFailure { if (acquired) runCatching { withTimeout(2_000) { port.release() } } }
    }
    override suspend fun pause(): Result<Unit> = mutex.withLock { paused = true; Result.success(Unit) }
    override suspend fun resume(): Result<Unit> = mutex.withLock { paused = false; Result.success(Unit) }
    override suspend fun stop(): Result<Unit> = mutex.withLock {
        job?.cancelAndJoin(); job = null
        releaseError?.let { Result.failure(IllegalStateException(it)) } ?: Result.success(Unit)
    }
}
