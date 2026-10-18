package org.openutm.djiflight.dji.v5

import android.content.Context
import com.dji.wpmzsdk.manager.WPMZManager
import dji.sdk.wpmz.value.mission.WaylineCheckError
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.waypoint3.WaylineExecutingInfoListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionExecuteStateListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import dji.v5.manager.aircraft.waypoint3.model.WaylineExecutingInfo
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.openutm.djiflight.core.adapter.MissionEvent
import org.openutm.djiflight.core.adapter.MissionOperator
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.core.mission.MissionPlan
import org.openutm.djiflight.core.mission.MissionValidator
import org.openutm.djiflight.core.mission.WpmlTarget
import org.openutm.djiflight.core.mission.WpmlWriter
import java.io.File
import kotlin.coroutines.resume

/**
 * [MissionOperator] over MSDK v5's `WaypointMissionManager`: the plan is written as a WPML KMZ
 * ([WpmlWriter]), checked with DJI's own validator, pushed to the aircraft, then started by name.
 *
 * [target] is read at upload time: on the Matrice 400 it depends on the mounted payload.
 */
class DjiV5MissionOperator(
    context: Context,
    private val target: () -> WpmlTarget?,
    private val log: (String) -> Unit = {},
    private val legalMaxHeightM: Double = 120.0,
) : MissionOperator {
    private val manager get() = WaypointMissionManager.getInstance()
    private val dir = File(context.filesDir, "missions").apply { mkdirs() }
    private val _events = MutableSharedFlow<MissionEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<MissionEvent> = _events.asSharedFlow()

    /** The uploaded KMZ's name without ".kmz": v5 starts and stops missions by file name. */
    @Volatile private var missionName: String? = null
    @Volatile private var waylineIds: List<Int> = emptyList()
    @Volatile private var total = 0
    @Volatile private var running = false
    @Volatile private var lastState: WaypointMissionExecuteState? = null

    private val stateListener = WaypointMissionExecuteStateListener { state ->
        val previous = lastState
        lastState = state
        when (state) {
            WaypointMissionExecuteState.EXECUTING -> if (!running) {
                running = true
                _events.tryEmit(MissionEvent.Started)
            }
            WaypointMissionExecuteState.FINISHED -> if (running) {
                running = false
                _events.tryEmit(MissionEvent.Finished(null))
            }
            // Stopped by the pilot or the aircraft (RC sticks, low battery, obstacle): back to READY/IDLE without FINISHED.
            WaypointMissionExecuteState.READY, WaypointMissionExecuteState.IDLE -> if (running && previous != WaypointMissionExecuteState.FINISHED) {
                running = false
                _events.tryEmit(MissionEvent.Finished("mission ended before the last waypoint ($previous → $state)"))
            }
            else -> Unit
        }
    }

    private val progressListener = object : WaylineExecutingInfoListener {
        override fun onWaylineExecutingInfoUpdate(info: WaylineExecutingInfo) {
            if (info.missionFileName != null && missionName != null && info.missionFileName != missionName) return
            _events.tryEmit(MissionEvent.Progress(info.currentWaypointIndex, total, reached = false))
        }

        override fun onWaylineExecutingInterruptReasonUpdate(error: IDJIError?) {
            error?.let { log("wayline interrupted: ${it.errorCode()} ${it.description()}") }
        }
    }

    init {
        manager.addWaypointMissionExecuteStateListener(stateListener)
        manager.addWaylineExecutingInfoListener(progressListener)
    }

    override suspend fun upload(plan: MissionPlan): Result<Unit> {
        val problems = MissionValidator.validate(plan, MissionLimits.WPML, legalMaxHeightM)
        if (problems.isNotEmpty()) return Result.failure(IllegalArgumentException(problems.joinToString("; ")))
        val t = target() ?: return Result.failure(IllegalStateException("no WPML profile for this aircraft and payload"))

        val name = "openutm-${Integer.toHexString(plan.hashCode() xor t.hashCode())}"
        val kmz = File(dir, "$name.kmz")
        withContext(Dispatchers.IO) {
            dir.listFiles()?.filter { it.name != kmz.name }?.forEach { it.delete() }
            kmz.outputStream().use { WpmlWriter.writeKmz(plan, t, it, System.currentTimeMillis()) }
        }
        validate(kmz)?.let { return Result.failure(IllegalStateException("DJI rejected the mission file: $it")) }

        total = plan.waypoints.size
        val pushed = suspendCancellableCoroutine { cont ->
            manager.pushKMZFileToAircraft(kmz.absolutePath, object : CommonCallbacks.CompletionCallbackWithProgress<Double> {
                override fun onProgressUpdate(progress: Double) {
                    // 0..1 or 0..100 (undocumented): report percent either way.
                    val pct = if (progress <= 1.0) progress * 100 else progress
                    _events.tryEmit(MissionEvent.UploadProgress(pct.toInt().coerceIn(0, 100), 100))
                }
                override fun onSuccess() {
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }
                override fun onFailure(error: IDJIError) {
                    if (cont.isActive) cont.resume(Result.failure(IllegalStateException(describe(error))))
                }
            })
        }
        pushed.onSuccess {
            missionName = name
            waylineIds = runCatching { manager.getAvailableWaylineIDs(kmz.absolutePath) }.getOrNull().orEmpty().ifEmpty { listOf(0) }
            _events.tryEmit(MissionEvent.Uploaded)
        }
        return pushed
    }

    override suspend fun start(): Result<Unit> {
        val name = missionName ?: return Result.failure(IllegalStateException("upload the mission first"))
        return complete { manager.startMission(name, waylineIds, it) }
    }

    override suspend fun pause() = complete { manager.pauseMission(it) }
    override suspend fun resume() = complete { manager.resumeMission(it) }

    override suspend fun stop(): Result<Unit> {
        val name = missionName ?: return Result.success(Unit)
        running = false // a stop the pilot asked for is not "ended early"
        return complete { manager.stopMission(name, it) }
    }

    /** DJI's own WPML validator; null if the file passes, else the problems it lists. */
    private fun validate(kmz: File): String? = runCatching {
        val errors = WPMZManager.getInstance().checkValidation(kmz.absolutePath)?.value.orEmpty()
            .filter { it != WaylineCheckError.NoError }
        if (errors.isEmpty()) null else errors.joinToString()
    }.getOrElse {
        // The validator is a native library: if it can't run, say so and let the aircraft decide.
        log("WPML validator unavailable: $it")
        null
    }

    private suspend fun complete(call: (CommonCallbacks.CompletionCallback) -> Unit): Result<Unit> = suspendCancellableCoroutine { cont ->
        call(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                if (cont.isActive) cont.resume(Result.success(Unit))
            }
            override fun onFailure(error: IDJIError) {
                if (cont.isActive) cont.resume(Result.failure(IllegalStateException(describe(error))))
            }
        })
    }

    private fun describe(e: IDJIError) = listOfNotNull(e.description()?.takeIf { it.isNotBlank() }, e.hint()?.takeIf { it.isNotBlank() }, e.errorCode())
        .distinct().joinToString(" – ")
}
