package org.openutm.djiflight.dji.v5

import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.CompassCalibrationState
import dji.sdk.keyvalue.value.flightcontroller.FailsafeAction
import dji.sdk.keyvalue.value.flightcontroller.IMUCalibrationInfo
import dji.sdk.keyvalue.value.flightcontroller.IMUCalibrationState
import dji.sdk.keyvalue.value.flightcontroller.IMUSensorState
import dji.sdk.keyvalue.value.flightcontroller.IMUState
import dji.sdk.keyvalue.value.gimbal.GimbalCalibrationState
import dji.sdk.keyvalue.value.gimbal.GimbalCalibrationStatusInfo
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.perception.PerceptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.openutm.djiflight.core.adapter.AircraftSettings
import org.openutm.djiflight.core.adapter.AircraftSetup
import org.openutm.djiflight.core.adapter.AircraftStatus
import org.openutm.djiflight.core.adapter.Calibration
import org.openutm.djiflight.core.adapter.CalibrationProgress
import org.openutm.djiflight.core.adapter.CompassHealth
import org.openutm.djiflight.core.adapter.ObstacleSensing
import org.openutm.djiflight.core.adapter.Health
import org.openutm.djiflight.core.adapter.RcLostAction
import org.openutm.djiflight.core.adapter.SensorStatus
import org.openutm.djiflight.core.adapter.SettingChange
import org.openutm.djiflight.core.adapter.SetupItem
import kotlin.coroutines.resume

/**
 * The aircraft's settings and calibrations over MSDK v5.18 keys ([AircraftSetup]). Unverified on
 * aircraft (docs/12, Open items): the keys and value types are read from the 5.18 jar.
 * Every callback implements all its methods explicitly (the runtime SDK treats some API defaults as abstract).
 */
class DjiV5AircraftSetup(
    private val scope: CoroutineScope,
    private val connected: () -> Boolean,
    private val log: (String) -> Unit = {},
) : AircraftSetup {
    private val _status = MutableStateFlow(AircraftStatus())
    override val status: StateFlow<AircraftStatus> = _status.asStateFlow()
    private val keys get() = KeyManager.getInstance()
    private var watch: Job? = null

    override suspend fun refresh(): Result<Unit> {
        if (!connected()) return fail("aircraft not connected")
        val supports = HashSet<SetupItem>()
        fun <T> T?.marks(item: SetupItem): T? = this?.also { supports += item }
        val distance = get(FlightControllerKey.KeyDistanceLimit)
        val settings = AircraftSettings(
            returnHomeHeightM = get(FlightControllerKey.KeyGoHomeHeight).marks(SetupItem.RETURN_HOME_HEIGHT),
            maxAltitudeM = get(FlightControllerKey.KeyHeightLimit).marks(SetupItem.MAX_ALTITUDE),
            maxDistanceM = distance.marks(SetupItem.MAX_DISTANCE),
            maxDistanceEnabled = if (distance != null) get(FlightControllerKey.KeyDistanceLimitEnabled) else null,
            rcLostAction = get(FlightControllerKey.KeyFailsafeAction)?.let(::fromDji).marks(SetupItem.RC_LOST_ACTION),
            lowBatteryPercent = get(FlightControllerKey.KeyLowBatteryWarningThreshold).marks(SetupItem.LOW_BATTERY),
            criticalBatteryPercent = get(FlightControllerKey.KeySeriousLowBatteryWarningThreshold).marks(SetupItem.CRITICAL_BATTERY),
            obstacleAvoidance = obstacleAvoidance().marks(SetupItem.OBSTACLE_AVOIDANCE),
        )
        val compassError = get(FlightControllerKey.KeyCompassHasError)
        val compassStates = get(FlightControllerKey.KeyCompassState)
        val compassCalibrating = get(FlightControllerKey.KeyIsCompassCalibrating) == true
        val imu = get(FlightControllerKey.KeyIMUStatus)
        val gimbal = get(GimbalKey.KeyGimbalCalibrationStatus, ComponentIndexType.LEFT_OR_MAIN)
        if (compassError != null || compassStates != null) supports += SetupItem.COMPASS
        if (imu != null) supports += SetupItem.IMU
        if (gimbal != null) supports += SetupItem.GIMBAL
        supports += SetupItem.HOME_RESET
        _status.update {
            it.copy(
                settings = settings,
                compass = CompassHealth.status(compassStates.orEmpty().mapNotNull { it.compassSensorState?.name }, compassError, compassCalibrating),
                imu = imuStatus(imu),
                gimbal = when (gimbal?.status) {
                    GimbalCalibrationState.IN_PROGRESS -> SensorStatus(Health.WARNING, "Calibrating")
                    GimbalCalibrationState.FAILED -> SensorStatus(Health.ERROR, "Calibration failed")
                    GimbalCalibrationState.IDLE -> SensorStatus(Health.OK, "Normal")
                    else -> SensorStatus()
                },
                supports = supports, readAtMs = System.currentTimeMillis(), error = null,
            )
        }
        return Result.success(Unit)
    }

    override suspend fun set(change: SettingChange): Result<Unit> {
        if (!connected()) return fail("aircraft not connected")
        val r = when (change) {
            is SettingChange.ReturnHomeHeight -> put(FlightControllerKey.KeyGoHomeHeight, change.m)
            is SettingChange.MaxAltitude -> put(FlightControllerKey.KeyHeightLimit, change.m)
            is SettingChange.MaxDistance -> put(FlightControllerKey.KeyDistanceLimitEnabled, change.enabled).mapCatching {
                if (change.enabled) put(FlightControllerKey.KeyDistanceLimit, change.m).getOrThrow()
            }
            is SettingChange.RcLost -> put(FlightControllerKey.KeyFailsafeAction, toDji(change.action))
            is SettingChange.LowBattery -> put(FlightControllerKey.KeyLowBatteryWarningThreshold, change.percent)
            is SettingChange.CriticalBattery -> put(FlightControllerKey.KeySeriousLowBatteryWarningThreshold, change.percent)
            is SettingChange.ObstacleAvoidance -> if (ObstacleSensing.supportsV5(get(ProductKey.KeyProductType)?.name)) {
                done { cb -> PerceptionManager.getInstance().setOverallObstacleAvoidanceEnabled(change.enabled, cb) }
            } else Result.failure(IllegalStateException("no obstacle sensing on this aircraft"))
        }
        r.onFailure { e -> log("setting $change refused: ${e.message}"); _status.update { it.copy(error = e.message) } }
        refresh()
        return r
    }

    override suspend fun calibrate(target: Calibration): Result<Unit> {
        if (!connected()) return fail("aircraft not connected")
        val r = when (target) {
            Calibration.COMPASS -> action(KeyTools.createKey(FlightControllerKey.KeyStartCompassCalibration))
            Calibration.IMU -> action(KeyTools.createKey(FlightControllerKey.KeyStartIMUCalibration))
            Calibration.GIMBAL -> action(KeyTools.createKey(GimbalKey.KeyGimbalCalibrate, ComponentIndexType.LEFT_OR_MAIN))
        }
        r.onSuccess { follow(target) }.onFailure { e -> _status.update { it.copy(calibration = CalibrationProgress(target, CalibrationProgress.State.FAILED, e.message ?: "refused")) } }
        return r
    }

    override suspend fun cancelCalibration(): Result<Unit> {
        watch?.cancel()
        val r = action(KeyTools.createKey(FlightControllerKey.KeyStopCompassCalibration))
        _status.update { it.copy(calibration = null) }
        return r
    }

    override suspend fun resetHomeToAircraft(): Result<Unit> =
        if (!connected()) fail("aircraft not connected") else action(KeyTools.createKey(FlightControllerKey.KeySetHomeLocationUsingAircraftCurrentLocation))

    private fun follow(target: Calibration) {
        watch?.cancel()
        watch = scope.launch {
            val until = System.currentTimeMillis() + 15 * 60_000L
            while (isActive && System.currentTimeMillis() < until) {
                val p = when (target) {
                    Calibration.COMPASS -> when (get(FlightControllerKey.KeyCompassCalibrationStatus)) {
                        CompassCalibrationState.HORIZONTAL -> running(target, COMPASS_LEVEL)
                        CompassCalibrationState.VERTICAL -> running(target, COMPASS_NOSE_DOWN)
                        CompassCalibrationState.SUCCEEDED -> ended(target, true)
                        CompassCalibrationState.FAILED -> ended(target, false, "Move away from metal and power lines, then try again")
                        else -> running(target, COMPASS_LEVEL)
                    }
                    Calibration.IMU -> get(FlightControllerKey.KeyIMUCalibrationInfo).let { info: IMUCalibrationInfo? ->
                        when (info?.calibrationState) {
                            IMUCalibrationState.SUCCESSFUL -> ended(target, true)
                            IMUCalibrationState.FAILED -> ended(target, false, "Keep the aircraft still on a level surface and try again")
                            else -> running(target, "Place the aircraft as shown on the controller and keep it still", info?.calibrationProgress)
                        }
                    }
                    Calibration.GIMBAL -> get(GimbalKey.KeyGimbalCalibrationStatus, ComponentIndexType.LEFT_OR_MAIN).let { s: GimbalCalibrationStatusInfo? ->
                        when (s?.status) {
                            GimbalCalibrationState.IN_PROGRESS -> running(target, "Keep the aircraft still on a level surface", s.progress)
                            GimbalCalibrationState.FAILED -> ended(target, false)
                            else -> ended(target, true)
                        }
                    }
                }
                _status.update { it.copy(calibration = p) }
                if (p.state != CalibrationProgress.State.RUNNING) {
                    refresh()
                    return@launch
                }
                delay(500)
            }
        }
    }

    private fun imuStatus(list: List<IMUState>?): SensorStatus {
        val states = list.orEmpty().flatMap { listOfNotNull(it.gyroscopeState, it.accelerometerState) }
        if (states.isEmpty()) return SensorStatus()
        val worst = states.maxBy(::severity)
        return when (severity(worst)) {
            0 -> SensorStatus(Health.OK, "Normal")
            1 -> SensorStatus(Health.WARNING, words(worst))
            2 -> SensorStatus(Health.ERROR, words(worst))
            else -> SensorStatus()
        }
    }

    private fun severity(s: IMUSensorState) = when (s) {
        IMUSensorState.NORMAL_BIAS -> 0
        IMUSensorState.MEDIUM_BIAS, IMUSensorState.WARMING_UP, IMUSensorState.IN_MOTION, IMUSensorState.CALIBRATING -> 1
        IMUSensorState.UNKNOWN -> -1
        else -> 2
    }

    private fun words(s: IMUSensorState) = s.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun running(t: Calibration, text: String, pct: Int? = null) = CalibrationProgress(t, CalibrationProgress.State.RUNNING, text, pct)
    private fun ended(t: Calibration, ok: Boolean, hint: String = "") =
        CalibrationProgress(t, if (ok) CalibrationProgress.State.SUCCEEDED else CalibrationProgress.State.FAILED, if (ok) "Calibration complete" else "Calibration failed. $hint".trim(), if (ok) 100 else null)

    private fun fail(msg: String): Result<Unit> {
        _status.update { it.copy(error = msg) }
        return Result.failure(IllegalStateException(msg))
    }

    private suspend fun obstacleAvoidance(): Boolean? {
        if (!ObstacleSensing.supportsV5(get(ProductKey.KeyProductType)?.name)) return null
        return readObstacleAvoidance()
    }

    private suspend fun readObstacleAvoidance(): Boolean? = withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine<Boolean?> { cont ->
            runCatching {
                PerceptionManager.getInstance().getOverallObstacleAvoidanceEnabled(object : CommonCallbacks.CompletionCallbackWithParam<Boolean> {
                    override fun onSuccess(v: Boolean?) { if (cont.isActive) cont.resume(v) }
                    override fun onFailure(e: IDJIError) { if (cont.isActive) cont.resume(null) }
                })
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    /** A key's value, or null if the aircraft refuses or doesn't answer within 5 s. */
    private suspend fun <T> get(info: DJIKeyInfo<T>, index: ComponentIndexType? = null): T? = withTimeoutOrNull(5_000) {
        val key: DJIKey<T> = if (index == null) KeyTools.createKey(info) else KeyTools.createKey(info, index)
        suspendCancellableCoroutine<T?> { cont ->
            runCatching {
                keys.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
                    override fun onSuccess(v: T) { if (cont.isActive) cont.resume(v) }
                    override fun onFailure(e: IDJIError) { if (cont.isActive) cont.resume(null) }
                })
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    private suspend fun <T> put(info: DJIKeyInfo<T>, value: T): Result<Unit> =
        done { cb -> keys.setValue(KeyTools.createKey(info), value, cb) }

    private suspend fun action(key: DJIKey.ActionKey<EmptyMsg, EmptyMsg>): Result<Unit> = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine<Result<Unit>> { cont ->
            runCatching {
                keys.performAction(key, object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(r: EmptyMsg?) { if (cont.isActive) cont.resume(Result.success(Unit)) }
                    override fun onFailure(e: IDJIError) { if (cont.isActive) cont.resume(Result.failure(IllegalStateException(e.description() ?: e.errorCode()))) }
                })
            }.onFailure { if (cont.isActive) cont.resume(Result.failure(it)) }
        }
    } ?: Result.failure(IllegalStateException("the aircraft did not answer"))

    private suspend fun done(call: (CommonCallbacks.CompletionCallback) -> Unit): Result<Unit> = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine<Result<Unit>> { cont ->
            runCatching {
                call(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() { if (cont.isActive) cont.resume(Result.success(Unit)) }
                    override fun onFailure(e: IDJIError) { if (cont.isActive) cont.resume(Result.failure(IllegalStateException(e.description() ?: e.errorCode()))) }
                })
            }.onFailure { if (cont.isActive) cont.resume(Result.failure(it)) }
        }
    } ?: Result.failure(IllegalStateException("the aircraft did not answer"))

    companion object {
        const val COMPASS_LEVEL = "Hold the aircraft level and turn it a full circle"
        const val COMPASS_NOSE_DOWN = "Hold the aircraft nose down and turn it a full circle"

        fun fromDji(a: FailsafeAction): RcLostAction? = when (a) {
            FailsafeAction.GOHOME -> RcLostAction.RETURN_HOME
            FailsafeAction.HOVER -> RcLostAction.HOVER
            FailsafeAction.LANDING -> RcLostAction.LAND
            else -> null
        }

        fun toDji(a: RcLostAction): FailsafeAction = when (a) {
            RcLostAction.RETURN_HOME -> FailsafeAction.GOHOME
            RcLostAction.HOVER -> FailsafeAction.HOVER
            RcLostAction.LAND -> FailsafeAction.LANDING
        }
    }
}
