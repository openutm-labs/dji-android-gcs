package org.openutm.djiflight.dji.v4

import dji.common.error.DJIError
import dji.common.mission.waypoint.Waypoint
import dji.common.mission.waypoint.WaypointAction
import dji.common.mission.waypoint.WaypointActionType
import dji.common.mission.waypoint.WaypointMission
import dji.common.mission.waypoint.WaypointMissionDownloadEvent
import dji.common.mission.waypoint.WaypointMissionExecutionEvent
import dji.common.mission.waypoint.WaypointMissionFinishedAction
import dji.common.mission.waypoint.WaypointMissionFlightPathMode
import dji.common.mission.waypoint.WaypointMissionHeadingMode
import dji.common.mission.waypoint.WaypointMissionState
import dji.common.mission.waypoint.WaypointMissionUploadEvent
import dji.common.util.CommonCallbacks
import dji.sdk.mission.MissionControl
import dji.sdk.mission.waypoint.WaypointMissionOperator
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.openutm.djiflight.core.adapter.MissionEvent
import org.openutm.djiflight.core.adapter.MissionOperator
import org.openutm.djiflight.core.mission.FinishAction
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.core.mission.MissionPlan
import org.openutm.djiflight.core.mission.MissionValidator
import kotlin.coroutines.resume

/** Core [MissionPlan] → DJI v4 Waypoint 1.0 `WaypointMission`. */
object WaypointV1Mapper {
    fun toMission(plan: MissionPlan): WaypointMission {
        val waypoints = plan.waypoints.mapIndexed { i, w ->
            Waypoint(w.lat, w.lng, w.heightM.toFloat()).apply {
                w.speedMps?.let { speed = it.toFloat() }
                w.headingDeg?.let { heading = (((it + 180) % 360 + 360) % 360 - 180).toInt() }
                gimbalPitch = plan.gimbalPitchDeg.toFloat()
                // Photos on the leg from this waypoint to the next: survey legs only, not travel legs.
                val surveyLeg = !w.transit && plan.waypoints.getOrNull(i + 1)?.transit == false
                plan.photoDistanceIntervalM?.takeIf { surveyLeg }?.let { shootPhotoDistanceInterval = it.toFloat() }
                if (w.takePhoto) addAction(WaypointAction(WaypointActionType.START_TAKE_PHOTO, 0))
            }
        }
        return WaypointMission.Builder()
            .apply { waypoints.forEach { addWaypoint(it) } }
            .waypointCount(waypoints.size)
            .autoFlightSpeed(plan.autoSpeedMps.toFloat())
            .maxFlightSpeed(plan.maxSpeedMps.toFloat().coerceIn(WaypointMission.MIN_FLIGHT_SPEED, WaypointMission.MAX_FLIGHT_SPEED))
            .finishedAction(
                when (plan.finishAction) {
                    FinishAction.GO_HOME -> WaypointMissionFinishedAction.GO_HOME
                    FinishAction.HOVER -> WaypointMissionFinishedAction.NO_ACTION
                    FinishAction.AUTO_LAND -> WaypointMissionFinishedAction.AUTO_LAND
                    FinishAction.GO_FIRST_WAYPOINT -> WaypointMissionFinishedAction.GO_FIRST_WAYPOINT
                },
            )
            .headingMode(if (plan.waypoints.any { it.headingDeg != null }) WaypointMissionHeadingMode.USING_WAYPOINT_HEADING else WaypointMissionHeadingMode.AUTO)
            .flightPathMode(WaypointMissionFlightPathMode.NORMAL)
            .setExitMissionOnRCSignalLostEnabled(plan.exitOnRcSignalLost)
            .setGimbalPitchRotationEnabled(true)
            .build()
    }
}

/** [MissionOperator] over the v4 `WaypointMissionOperator`. */
class DjiV4MissionOperator(private val legalMaxHeightM: Double = 120.0) : MissionOperator {
    private val op: WaypointMissionOperator get() = MissionControl.getInstance().waypointMissionOperator
    private val _events = MutableSharedFlow<MissionEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<MissionEvent> = _events.asSharedFlow()

    private val listener = object : WaypointMissionOperatorListener {
        override fun onDownloadUpdate(e: WaypointMissionDownloadEvent) = Unit

        override fun onUploadUpdate(e: WaypointMissionUploadEvent) {
            e.progress?.let { _events.tryEmit(MissionEvent.UploadProgress(it.uploadedWaypointIndex + 1, it.totalWaypointCount)) }
            if (e.currentState == WaypointMissionState.READY_TO_EXECUTE) _events.tryEmit(MissionEvent.Uploaded)
        }

        override fun onExecutionUpdate(e: WaypointMissionExecutionEvent) {
            e.progress?.let { _events.tryEmit(MissionEvent.Progress(it.targetWaypointIndex, it.totalWaypointCount, it.isWaypointReached)) }
        }

        override fun onExecutionStart() {
            _events.tryEmit(MissionEvent.Started)
        }

        override fun onExecutionFinish(error: DJIError?) {
            _events.tryEmit(MissionEvent.Finished(error?.description))
        }
    }

    init {
        op.addListener(listener)
    }

    override suspend fun upload(plan: MissionPlan): Result<Unit> {
        val problems = MissionValidator.validate(plan, MissionLimits.WAYPOINT_V1, legalMaxHeightM)
        if (problems.isNotEmpty()) return Result.failure(IllegalArgumentException(problems.joinToString("; ")))
        op.loadMission(WaypointV1Mapper.toMission(plan))?.let { return Result.failure(IllegalStateException(it.description)) }
        return complete { op.uploadMission(it) }
    }

    override suspend fun start() = complete { op.startMission(it) }
    override suspend fun pause() = complete { op.pauseMission(it) }
    override suspend fun resume() = complete { op.resumeMission(it) }
    override suspend fun stop() = complete { op.stopMission(it) }

    private suspend fun complete(call: (CommonCallbacks.CompletionCallback<DJIError>) -> Unit): Result<Unit> =
        suspendCancellableCoroutine { cont ->
            call(CommonCallbacks.CompletionCallback<DJIError> { err ->
                if (cont.isActive) cont.resume(if (err == null) Result.success(Unit) else Result.failure(IllegalStateException(err.description)))
            })
        }
}
