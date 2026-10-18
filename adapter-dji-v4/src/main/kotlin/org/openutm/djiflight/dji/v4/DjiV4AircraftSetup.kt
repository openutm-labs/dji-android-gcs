package org.openutm.djiflight.dji.v4

import dji.common.error.DJIError
import dji.common.flightcontroller.CompassState
import dji.common.flightcontroller.CompassCalibrationState
import dji.common.flightcontroller.ConnectionFailSafeBehavior
import dji.common.flightcontroller.imu.CalibrationState
import dji.common.flightcontroller.imu.IMUState
import dji.common.flightcontroller.imu.SensorState
import dji.common.util.CommonCallbacks
import dji.keysdk.FlightControllerKey
import dji.keysdk.GimbalKey
import dji.keysdk.KeyManager
import dji.sdk.flightcontroller.FlightController
import dji.sdk.products.Aircraft
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
 * The aircraft's settings and calibrations over MSDK v4.18 ([AircraftSetup]). Unverified on aircraft
 * (docs/12, Open items): the calls and enums are read from the 4.18 jar.
 *
 * Sensor states are read from the SDK's key cache (`KeyManager`), never through the components'
 * single-listener `set…Callback` setters, which DJI's UI Library may be using for its own panels.
 */
class DjiV4AircraftSetup(
    private val scope: CoroutineScope,
    private val aircraft: () -> Aircraft?,
    private val log: (String) -> Unit = {},
) : AircraftSetup {
    private val _status = MutableStateFlow(AircraftStatus())
    override val status: StateFlow<AircraftStatus> = _status.asStateFlow()
    private var watch: Job? = null

    private fun fc(): FlightController? = aircraft()?.takeIf { it.isConnected }?.flightController

    override suspend fun refresh(): Result<Unit> {
        val fc = fc() ?: return fail("aircraft not connected")
        val supports = HashSet<SetupItem>()
        fun <T> T?.marks(item: SetupItem): T? = this?.also { supports += item }
        val maxRadius = get<Int> { fc.getMaxFlightRadius(it) }
        val settings = AircraftSettings(
            returnHomeHeightM = get<Int> { fc.getGoHomeHeightInMeters(it) }.marks(SetupItem.RETURN_HOME_HEIGHT),
            maxAltitudeM = get<Int> { fc.getMaxFlightHeight(it) }.marks(SetupItem.MAX_ALTITUDE),
            maxDistanceM = maxRadius.marks(SetupItem.MAX_DISTANCE),
            maxDistanceEnabled = if (maxRadius != null) get<Boolean> { fc.getMaxFlightRadiusLimitationEnabled(it) } else null,
            rcLostAction = get<ConnectionFailSafeBehavior> { fc.getConnectionFailSafeBehavior(it) }?.let(::fromDji).marks(SetupItem.RC_LOST_ACTION),
            lowBatteryPercent = get<Int> { fc.getLowBatteryWarningThreshold(it) }.marks(SetupItem.LOW_BATTERY),
            criticalBatteryPercent = get<Int> { fc.getSeriousLowBatteryWarningThreshold(it) }.marks(SetupItem.CRITICAL_BATTERY),
            obstacleAvoidance = fc.flightAssistant?.takeIf { ObstacleSensing.supportsV4(aircraft()?.model?.name) }?.let { fa -> get<Boolean> { fa.getCollisionAvoidanceEnabled(it) } }.marks(SetupItem.OBSTACLE_AVOIDANCE),
        )
        if (fc.compass != null) supports += SetupItem.COMPASS
        supports += SetupItem.IMU
        supports += SetupItem.HOME_RESET
        if (aircraft()?.gimbal != null) supports += SetupItem.GIMBAL
        val compass = compassStatus(fc)
        val imu = imuStatus()
        _status.update {
            it.copy(settings = settings, compass = compass, imu = imu, gimbal = gimbalStatus(), supports = supports, readAtMs = System.currentTimeMillis(), error = null)
        }
        return Result.success(Unit)
    }

    override suspend fun set(change: SettingChange): Result<Unit> {
        val fc = fc() ?: return fail("aircraft not connected")
        val r = when (change) {
            is SettingChange.ReturnHomeHeight -> done { fc.setGoHomeHeightInMeters(change.m, it) }
            is SettingChange.MaxAltitude -> done { fc.setMaxFlightHeight(change.m, it) }
            is SettingChange.MaxDistance -> done { fc.setMaxFlightRadiusLimitationEnabled(change.enabled, it) }.mapCatching {
                if (change.enabled) done { cb -> fc.setMaxFlightRadius(change.m, cb) }.getOrThrow()
            }
            is SettingChange.RcLost -> done { fc.setConnectionFailSafeBehavior(toDji(change.action), it) }
            is SettingChange.LowBattery -> done { fc.setLowBatteryWarningThreshold(change.percent, it) }
            is SettingChange.CriticalBattery -> done { fc.setSeriousLowBatteryWarningThreshold(change.percent, it) }
            is SettingChange.ObstacleAvoidance -> fc.flightAssistant?.takeIf { ObstacleSensing.supportsV4(aircraft()?.model?.name) }?.let { fa -> done { fa.setCollisionAvoidanceEnabled(change.enabled, it) } }
                ?: Result.failure(IllegalStateException("no obstacle sensing on this aircraft"))
        }
        r.onFailure { e -> log("setting $change refused: ${e.message}"); _status.update { it.copy(error = e.message) } }
        refresh()
        return r
    }

    override suspend fun calibrate(target: Calibration): Result<Unit> {
        val fc = fc() ?: return fail("aircraft not connected")
        val r = when (target) {
            Calibration.COMPASS -> fc.compass?.let { c -> done { c.startCalibration(it) } } ?: Result.failure(IllegalStateException("no compass"))
            Calibration.IMU -> done { fc.startIMUCalibration(it) }
            Calibration.GIMBAL -> aircraft()?.gimbal?.let { g -> done { g.startCalibration(it) } } ?: Result.failure(IllegalStateException("no gimbal"))
        }
        r.onSuccess { follow(target) }.onFailure { e -> _status.update { it.copy(calibration = CalibrationProgress(target, CalibrationProgress.State.FAILED, e.message ?: "refused")) } }
        return r
    }

    override suspend fun cancelCalibration(): Result<Unit> {
        watch?.cancel()
        val r = fc()?.compass?.let { c -> done { c.stopCalibration(it) } } ?: Result.success(Unit)
        _status.update { it.copy(calibration = null) }
        return r
    }

    override suspend fun resetHomeToAircraft(): Result<Unit> = fc()?.let { fc -> done { fc.setHomeLocationUsingAircraftCurrentLocation(it) } } ?: fail("aircraft not connected")

    /** Polls the calibration's state from the key cache until it ends (DJI: up to a few minutes for the IMU). */
    private fun follow(target: Calibration) {
        watch?.cancel()
        watch = scope.launch {
            val until = System.currentTimeMillis() + 15 * 60_000L
            while (isActive && System.currentTimeMillis() < until) {
                val p = when (target) {
                    Calibration.COMPASS -> when (cached<CompassCalibrationState>(FlightControllerKey.create(FlightControllerKey.COMPASS_CALIBRATION_STATUS))) {
                        CompassCalibrationState.HORIZONTAL -> running(target, COMPASS_LEVEL)
                        CompassCalibrationState.VERTICAL -> running(target, COMPASS_NOSE_DOWN)
                        CompassCalibrationState.SUCCESSFUL -> ended(target, true)
                        CompassCalibrationState.FAILED -> ended(target, false, "Move away from metal and power lines, then try again")
                        else -> running(target, COMPASS_LEVEL)
                    }
                    Calibration.IMU -> cached<IMUState>(FlightControllerKey.create(FlightControllerKey.IMU_STATE)).let { s ->
                        when (s?.calibrationState) {
                            CalibrationState.SUCCESSFUL -> ended(target, true)
                            CalibrationState.FAILED -> ended(target, false, "Keep the aircraft still on a level surface and try again")
                            else -> running(target, "Keep the aircraft still on a level surface (5–10 minutes)", s?.calibrationProgress)
                        }
                    }
                    Calibration.GIMBAL -> when {
                        cached<Boolean>(GimbalKey.create(GimbalKey.IS_CALIBRATING)) == true ->
                            running(target, "Keep the aircraft still on a level surface", cached<Int>(GimbalKey.create(GimbalKey.CALIBRATION_PROGRESS)))
                        else -> ended(target, cached<Boolean>(GimbalKey.create(GimbalKey.IS_CALIBRATION_SUCCESSFUL)) != false)
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

    private suspend fun compassStatus(fc: FlightController): SensorStatus {
        val compass = fc.compass ?: return SensorStatus()
        val state = readKey<CompassState>(FlightControllerKey.create(FlightControllerKey.COMPASS_STATE))
        fun states(s: CompassState): List<String> = listOfNotNull(s.sensorState?.name) + s.subCompassStates.orEmpty().flatMap(::states)
        return CompassHealth.status(state?.let(::states).orEmpty(), compass.hasError(), compass.isCalibrating)
    }

    private suspend fun imuStatus(): SensorStatus {
        val s = readKey<IMUState>(FlightControllerKey.create(FlightControllerKey.IMU_STATE)) ?: return SensorStatus()
        fun states(state: IMUState): List<SensorState> = listOfNotNull(state.gyroscopeState, state.accelerometerState) + state.subIMUState.orEmpty().flatMap(::states)
        val worst = states(s).maxByOrNull { severity(it) }
        return when (worst) {
            SensorState.NORMAL_BIAS -> SensorStatus(Health.OK, "Normal")
            SensorState.MEDIUM_BIAS, SensorState.WARMING_UP, SensorState.IN_MOTION, SensorState.CALIBRATING -> SensorStatus(Health.WARNING, words(worst))
            SensorState.LARGE_BIAS, SensorState.DATA_EXCEPTION, SensorState.CALIBRATION_FAILED, SensorState.DISCONNECTED -> SensorStatus(Health.ERROR, words(worst))
            else -> SensorStatus()
        }
    }

    private fun gimbalStatus(): SensorStatus = when (cached<Boolean>(GimbalKey.create(GimbalKey.IS_CALIBRATING))) {
        true -> SensorStatus(Health.WARNING, "Calibrating")
        false -> SensorStatus(Health.OK, "Normal")
        null -> SensorStatus()
    }

    private fun severity(s: SensorState) = when (s) {
        SensorState.NORMAL_BIAS -> 0
        SensorState.MEDIUM_BIAS, SensorState.WARMING_UP, SensorState.IN_MOTION, SensorState.CALIBRATING -> 1
        SensorState.UNKNOWN -> -1
        else -> 2
    }

    private fun words(s: SensorState) = s.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun running(t: Calibration, text: String, pct: Int? = null) = CalibrationProgress(t, CalibrationProgress.State.RUNNING, text, pct)
    private fun ended(t: Calibration, ok: Boolean, hint: String = "") =
        CalibrationProgress(t, if (ok) CalibrationProgress.State.SUCCEEDED else CalibrationProgress.State.FAILED, if (ok) "Calibration complete" else "Calibration failed. $hint".trim(), if (ok) 100 else null)

    /** Fetch instead of relying on a cache that no widget may have populated. No single-listener setters. */
    private suspend fun <T> readKey(key: dji.keysdk.DJIKey): T? = withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine<T?> { cont ->
            val manager = KeyManager.getInstance()
            if (manager == null) { cont.resume(null); return@suspendCancellableCoroutine }
            runCatching {
                manager.getValue(key, object : dji.keysdk.callback.GetCallback {
                    @Suppress("UNCHECKED_CAST")
                    override fun onSuccess(value: Any) { if (cont.isActive) cont.resume(value as? T) }
                    override fun onFailure(error: DJIError) { if (cont.isActive) cont.resume(null) }
                })
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: dji.keysdk.DJIKey): T? = runCatching { KeyManager.getInstance()?.getValue(key) as? T }.getOrNull()

    private fun fail(msg: String): Result<Unit> {
        _status.update { it.copy(error = msg) }
        return Result.failure(IllegalStateException(msg))
    }

    /** A getter, or null if the aircraft refuses or doesn't answer within 5 s. */
    private suspend fun <T> get(call: (CommonCallbacks.CompletionCallbackWith<T>) -> Unit): T? = withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine<T?> { cont ->
            runCatching {
                call(object : CommonCallbacks.CompletionCallbackWith<T> {
                    override fun onSuccess(v: T) { if (cont.isActive) cont.resume(v) }
                    override fun onFailure(e: DJIError?) { if (cont.isActive) cont.resume(null) }
                })
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    private suspend fun done(call: (CommonCallbacks.CompletionCallback<DJIError>) -> Unit): Result<Unit> = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine<Result<Unit>> { cont ->
            runCatching {
                call(CommonCallbacks.CompletionCallback<DJIError> { err ->
                    if (cont.isActive) cont.resume(if (err == null) Result.success(Unit) else Result.failure(IllegalStateException(err.description)))
                })
            }.onFailure { if (cont.isActive) cont.resume(Result.failure(it)) }
        }
    } ?: Result.failure(IllegalStateException("the aircraft did not answer"))

    companion object {
        const val COMPASS_LEVEL = "Hold the aircraft level and turn it a full circle"
        const val COMPASS_NOSE_DOWN = "Hold the aircraft nose down and turn it a full circle"

        fun fromDji(b: ConnectionFailSafeBehavior): RcLostAction? = when (b) {
            ConnectionFailSafeBehavior.GO_HOME -> RcLostAction.RETURN_HOME
            ConnectionFailSafeBehavior.HOVER -> RcLostAction.HOVER
            ConnectionFailSafeBehavior.LANDING -> RcLostAction.LAND
            else -> null
        }

        fun toDji(a: RcLostAction): ConnectionFailSafeBehavior = when (a) {
            RcLostAction.RETURN_HOME -> ConnectionFailSafeBehavior.GO_HOME
            RcLostAction.HOVER -> ConnectionFailSafeBehavior.HOVER
            RcLostAction.LAND -> ConnectionFailSafeBehavior.LANDING
        }
    }
}
