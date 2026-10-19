package org.openutm.djiflight.core.mission

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.openutm.djiflight.core.adapter.DroneAdapter
import org.openutm.djiflight.core.adapter.LinkState
import org.openutm.djiflight.core.adapter.MissionEvent
import org.openutm.djiflight.core.adapter.MissionOperator
import org.openutm.djiflight.core.geo.LatLng
import java.io.File

enum class MissionPhase { NONE, READY_TO_UPLOAD, UPLOADING, READY, RUNNING, PAUSED, PART_DONE, INTERRUPTED, DONE, FAILED }

data class MissionUi(
    val phase: MissionPhase = MissionPhase.NONE,
    /** 1-based part being flown, of [parts] uploads still needed from the current start point. */
    val part: Int = 0,
    val parts: Int = 0,
    /** 1-based waypoint the aircraft is heading to, in the whole mission. */
    val waypoint: Int = 0,
    val waypoints: Int = 0,
    /** INTERRUPTED: the 1-based waypoint a resume flies on from (the last one reached). */
    val resumeFrom: Int? = null,
    val message: String = "",
)

/** The last waypoint reached in the active flight's mission, kept across app restarts. */
@Serializable
data class MissionProgress(val flightId: String, val planHash: Int, val reached: Int)

/**
 * A plan's identity that is the same in every process. Not [MissionPlan.hashCode]: that includes
 * [FinishAction]'s, and an enum's hash is an identity hash that changes at every start, so progress
 * saved before an app restart never matched the plan read back from the journal after it.
 */
fun MissionPlan.stableHash(): Int = Json.encodeToString(MissionPlan.serializer(), this).hashCode()

interface MissionProgressStore {
    fun load(): MissionProgress?
    fun save(progress: MissionProgress?)
}

/** One small JSON file, replaced atomically; written only by the [MissionRunner]. */
class FileMissionProgressStore(private val file: File) : MissionProgressStore {
    override fun load(): MissionProgress? = runCatching { Json.decodeFromString(MissionProgress.serializer(), file.readText()) }.getOrNull()

    override fun save(progress: MissionProgress?) {
        if (progress == null) {
            file.delete()
            return
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Json.encodeToString(MissionProgress.serializer(), progress))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/**
 * Drives the flight's mission through the aircraft's operator. Only the pilot's taps start things.
 *
 * - A plan longer than the SDK takes (99 waypoints on Waypoint 1.0) is flown as consecutive parts.
 * - **Interruptions** (RTH for a battery swap, the pilot's Stop, an SDK error, the aircraft
 *   disconnecting) leave the mission INTERRUPTED at the last waypoint reached. [resume] uploads the
 *   rest of the route from that waypoint as a new mission: the aircraft flies back to it first and
 *   re-flies the partial segment, so a survey line is never left with a gap. This works the same on
 *   MSDK v4 and v5 (v5's own breakpoint query only works after an explicit pause, not after RTH).
 * - Progress is saved per flight ([MissionProgressStore]), so a resume also survives an app restart.
 *
 * Waypoint progress: an operator reports the waypoint it is heading to, and `reached = true` once
 * there (v4). An operator that never reports `reached` (v5: "current executing waypoint") is taken to
 * have reached only the one before, and its clean finish is trusted as complete.
 */
class MissionRunner(
    private val scope: CoroutineScope,
    private val adapter: DroneAdapter,
    /** Until the aircraft reports its own: Waypoint 1.0 in v4, WPML in v5. */
    private val defaultLimits: MissionLimits,
    private val store: MissionProgressStore? = null,
    /** Where the aircraft is now (on the ground before take-off): the travel climb starts above it. */
    private val aircraftPosition: () -> LatLng? = { null },
    private val legalMaxHeightM: () -> Double,
) {
    private val _ui = MutableStateFlow(MissionUi())
    val ui: StateFlow<MissionUi> = _ui.asStateFlow()

    private var flightId: String? = null
    private var plan: MissionPlan? = null
    /** Whole-mission index the current parts begin at: 0, or the resume point. */
    private var start = 0
    private var parts: List<MissionPlan> = emptyList()
    private var offsets: List<Int> = emptyList()
    /** The part uploaded or being flown (0-based). */
    private var partIdx = 0
    /** Last waypoint reached, whole-mission index; -1 = none yet. */
    private var reached = -1
    private var sawReachedFlags = false
    /** Travel-leg points uploaded before the part's first waypoint ([TravelLegs]). */
    private var lead = 0
    private var events: Job? = null
    private var op: MissionOperator? = null

    init {
        scope.launch {
            adapter.link.collect { if (it !is LinkState.Connected) onDisconnected() }
        }
    }

    private val limits get() = adapter.capabilities.value?.missionLimits ?: defaultLimits
    val canRun: Boolean get() = adapter.missionOperator() != null && parts.isNotEmpty()

    /** Called whenever the active flight changes; a no-op if it carries the same mission. */
    @Synchronized
    fun load(flightId: String?, plan: MissionPlan?) {
        if (flightId == this.flightId && plan == this.plan) return
        this.flightId = flightId
        this.plan = plan
        events?.cancel()
        op = null
        sawReachedFlags = false
        if (plan == null || flightId == null) {
            parts = emptyList()
            _ui.value = MissionUi()
            return
        }
        val saved = store?.load()?.takeIf { it.flightId == flightId && it.planHash == plan.stableHash() }
        reached = saved?.reached ?: -1
        when {
            reached >= plan.waypoints.lastIndex -> {
                split(0)
                _ui.value = MissionUi(MissionPhase.DONE, parts.size, parts.size, plan.waypoints.size, plan.waypoints.size, message = "mission complete")
            }
            reached >= 0 -> {
                split(0)
                interrupted("the app restarted after waypoint ${reached + 1}")
            }
            else -> {
                split(0)
                _ui.value = MissionUi(MissionPhase.READY_TO_UPLOAD, 1, parts.size, 0, plan.waypoints.size, message = "${plan.waypoints.size} waypoints in ${parts.size} part(s)")
            }
        }
    }

    /** Upload the next part: the first, the one after a finished part, or the same one after a failure. */
    fun upload() = act {
        val next = synchronized(this) {
            when (_ui.value.phase) {
                MissionPhase.PART_DONE -> partIdx + 1
                MissionPhase.READY_TO_UPLOAD, MissionPhase.FAILED -> partIdx
                else -> return@act
            }.coerceIn(0, parts.lastIndex)
        }
        uploadPart(next)
    }

    /** INTERRUPTED: fly the rest of the route, starting at the last waypoint reached. */
    fun resume() = act {
        synchronized(this) {
            if (_ui.value.phase != MissionPhase.INTERRUPTED) return@act
            split(reached.coerceAtLeast(0))
        }
        uploadPart(0)
    }

    /** INTERRUPTED: fly the whole route again from the first waypoint. */
    fun restart() = act {
        synchronized(this) {
            if (_ui.value.phase != MissionPhase.INTERRUPTED) return@act
            reached = -1
            save()
            split(0)
        }
        uploadPart(0)
    }

    fun start() = act { operator()?.start()?.onFailure { fail(it.message ?: "start failed") } }

    fun pauseOrResume() = act {
        val o = operator() ?: return@act
        if (_ui.value.phase == MissionPhase.PAUSED) o.resume().onSuccess { _ui.update { it.copy(phase = MissionPhase.RUNNING, message = "flying") } }
        else o.pause().onSuccess { _ui.update { it.copy(phase = MissionPhase.PAUSED, message = "paused") } }
    }

    fun stop() = act {
        operator()?.stop()?.onSuccess { synchronized(this) { interrupted("stopped by the pilot") } }
    }

    private suspend fun uploadPart(idx: Int) {
        val o = operator() ?: return fail("this aircraft has no mission support")
        val part = synchronized(this) {
            partIdx = idx
            sawReachedFlags = false
            parts[idx]
        }
        // Travel legs: in on the first part of a run (the start, or a resume), out on the last.
        val expectedCamera = part.cameraName
        if (expectedCamera != null && expectedCamera != adapter.capabilities.value?.camera?.name) {
            return fail("survey camera changed or is unknown: reopen Plan and review the route for the connected camera")
        }
        val expanded = TravelLegs.expand(part, aircraftPosition(), intro = idx == 0, outro = idx == parts.lastIndex)
        val problems = MissionValidator.validate(expanded.plan, limits, legalMaxHeightM())
        if (problems.isNotEmpty()) return fail(problems.first())
        synchronized(this) { lead = expanded.lead }
        val from = offsets[idx] + 1
        _ui.update { it.copy(phase = MissionPhase.UPLOADING, part = idx + 1, parts = parts.size, waypoint = from, resumeFrom = null, message = "uploading waypoints $from–${from + part.waypoints.size - 1}") }
        o.upload(expanded.plan)
            .onSuccess { _ui.update { it.copy(phase = MissionPhase.READY, message = "part ${it.part} of ${it.parts} uploaded: tap Go") } }
            .onFailure { fail(it.message ?: "upload failed") }
    }

    /** Parts covering the plan from whole-mission index [from]; each part starts where the last ended. */
    private fun split(from: Int) {
        val p = plan ?: return
        start = from
        val rest = if (from == 0) p else p.copy(waypoints = p.waypoints.subList(from, p.waypoints.size), gimbalPitchDeg = p.pitchAt(from))
        parts = MissionValidator.split(rest, limits)
        offsets = parts.runningFold(from) { acc, part -> acc + part.waypoints.size - 1 }.dropLast(1)
        partIdx = 0
    }

    private fun operator(): MissionOperator? {
        val o = adapter.missionOperator() ?: return null
        synchronized(this) {
            if (o !== op) {
                op = o
                events?.cancel()
                events = scope.launch { o.events.collect(::onEvent) }
            }
        }
        return o
    }

    @Synchronized
    private fun onEvent(e: MissionEvent) {
        val p = plan ?: return
        when (e) {
            is MissionEvent.UploadProgress -> _ui.update { it.copy(message = "uploading ${e.uploaded}/${e.total}") }
            MissionEvent.Uploaded -> Unit
            MissionEvent.Started -> _ui.update { it.copy(phase = MissionPhase.RUNNING, message = "flying part ${it.part} of ${it.parts}") }
            is MissionEvent.Progress -> {
                if (e.reached) sawReachedFlags = true
                val partLast = parts.getOrNull(partIdx)?.waypoints?.lastIndex ?: return
                val offset = offsets.getOrElse(partIdx) { start }
                // Operator indices count the travel-leg points; the pilot's numbering doesn't.
                val local = ((if (e.reached) e.targetWaypoint else e.targetWaypoint - 1) - lead).coerceAtMost(partLast)
                val global = offset + local
                if (local >= 0 && global > reached && global <= p.waypoints.lastIndex) {
                    reached = global
                    save()
                }
                val target = (offset + (e.targetWaypoint - lead).coerceIn(0, partLast) + 1).coerceAtMost(p.waypoints.size)
                _ui.update { it.copy(waypoint = target, waypoints = p.waypoints.size) }
            }
            is MissionEvent.Finished -> {
                val part = parts.getOrNull(partIdx) ?: return
                val partEnd = offsets[partIdx] + part.waypoints.lastIndex
                val complete = e.error == null && (!sawReachedFlags || reached >= partEnd)
                when {
                    !complete -> interrupted(e.error ?: "the mission ended before waypoint ${partEnd + 1}")
                    partIdx < parts.lastIndex -> {
                        reached = maxOf(reached, partEnd)
                        save()
                        _ui.update { it.copy(phase = MissionPhase.PART_DONE, message = "part ${partIdx + 1} done: upload part ${partIdx + 2}") }
                    }
                    else -> {
                        reached = p.waypoints.lastIndex
                        save()
                        _ui.update { it.copy(phase = MissionPhase.DONE, waypoint = p.waypoints.size, message = "mission complete") }
                    }
                }
            }
        }
    }

    @Synchronized
    private fun onDisconnected() {
        when (_ui.value.phase) {
            // In the air, or about to be: the pilot resumes after reconnecting (a battery swap reboots the aircraft).
            MissionPhase.RUNNING, MissionPhase.PAUSED -> interrupted("the aircraft disconnected")
            // An uploaded mission may not survive an aircraft reboot: upload it again.
            MissionPhase.READY, MissionPhase.UPLOADING -> _ui.update { it.copy(phase = MissionPhase.READY_TO_UPLOAD, message = "the aircraft disconnected: upload again") }
            else -> Unit
        }
    }

    /** Callers hold the lock. */
    private fun interrupted(why: String) {
        val p = plan ?: return
        if (reached >= p.waypoints.lastIndex) {
            _ui.update { it.copy(phase = MissionPhase.DONE, message = "mission complete") }
            return
        }
        val from = reached.coerceAtLeast(0) + 1
        _ui.update {
            it.copy(
                phase = MissionPhase.INTERRUPTED, waypoints = p.waypoints.size, resumeFrom = from,
                message = "$why. Resume flies on from waypoint $from of ${p.waypoints.size}",
            )
        }
    }

    private fun save() {
        val id = flightId ?: return
        val p = plan ?: return
        runCatching { store?.save(MissionProgress(id, p.stableHash(), reached)) }
    }

    private fun fail(msg: String) = _ui.update { it.copy(phase = MissionPhase.FAILED, message = msg) }

    private fun act(block: suspend () -> Unit) {
        scope.launch { block() }
    }
}
