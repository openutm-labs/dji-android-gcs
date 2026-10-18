package org.openutm.djiflight.fake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openutm.djiflight.core.adapter.AircraftSettings
import org.openutm.djiflight.core.adapter.AircraftSetup
import org.openutm.djiflight.core.adapter.AircraftStatus
import org.openutm.djiflight.core.adapter.Calibration
import org.openutm.djiflight.core.adapter.CalibrationProgress
import org.openutm.djiflight.core.adapter.Health
import org.openutm.djiflight.core.adapter.RcLostAction
import org.openutm.djiflight.core.adapter.SensorStatus
import org.openutm.djiflight.core.adapter.SettingChange
import org.openutm.djiflight.core.adapter.SettingLimits
import org.openutm.djiflight.core.adapter.SetupItem

/**
 * A simulated aircraft's settings and calibrations, for the `sim` app and pilot training: DJI's
 * defaults, writes that take a moment and are range-checked as the SDKs do, and calibrations that
 * step through the same stages as a real compass or IMU calibration.
 */
class FakeAircraftSetup(private val scope: CoroutineScope, private val writeDelayMs: Long = 300, private val stepMs: Long = 2_500, private val nowMs: () -> Long = System::currentTimeMillis) : AircraftSetup {
    private val _status = MutableStateFlow(
        AircraftStatus(
            settings = AircraftSettings(
                returnHomeHeightM = 30, maxAltitudeM = 120, maxDistanceM = 500, maxDistanceEnabled = true,
                rcLostAction = RcLostAction.RETURN_HOME, lowBatteryPercent = 30, criticalBatteryPercent = 15, obstacleAvoidance = true,
            ),
            compass = SensorStatus(Health.OK, "Normal"),
            imu = SensorStatus(Health.OK, "Normal"),
            gimbal = SensorStatus(Health.OK, "Normal"),
            supports = SetupItem.entries.toSet(),
        ),
    )
    override val status: StateFlow<AircraftStatus> = _status.asStateFlow()
    private var running: Job? = null

    override suspend fun refresh(): Result<Unit> {
        delay(writeDelayMs)
        _status.update { it.copy(readAtMs = nowMs(), error = null) }
        return Result.success(Unit)
    }

    override suspend fun set(change: SettingChange): Result<Unit> {
        delay(writeDelayMs)
        val s = _status.value.settings
        val next = when (change) {
            is SettingChange.ReturnHomeHeight -> check(change.m, SettingLimits.RETURN_HOME_HEIGHT_M) { s.copy(returnHomeHeightM = it) }
            is SettingChange.MaxAltitude -> check(change.m, SettingLimits.MAX_ALTITUDE_M) { s.copy(maxAltitudeM = it) }
            is SettingChange.MaxDistance -> check(change.m, SettingLimits.MAX_DISTANCE_M) { s.copy(maxDistanceM = it, maxDistanceEnabled = change.enabled) }
            is SettingChange.RcLost -> Result.success(s.copy(rcLostAction = change.action))
            is SettingChange.LowBattery -> check(change.percent, SettingLimits.LOW_BATTERY_PERCENT) { s.copy(lowBatteryPercent = it) }
            is SettingChange.CriticalBattery -> check(change.percent, SettingLimits.CRITICAL_BATTERY_PERCENT) { s.copy(criticalBatteryPercent = it) }
            is SettingChange.ObstacleAvoidance -> Result.success(s.copy(obstacleAvoidance = change.enabled))
        }
        return next.map { settings -> _status.update { it.copy(settings = settings, error = null) } }
            .onFailure { e -> _status.update { it.copy(error = e.message) } }
    }

    private fun check(v: Int, range: IntRange, apply: (Int) -> AircraftSettings): Result<AircraftSettings> =
        if (v in range) Result.success(apply(v)) else Result.failure(IllegalArgumentException("out of range (${range.first}–${range.last})"))

    override suspend fun calibrate(target: Calibration): Result<Unit> {
        if (_status.value.calibration?.state == CalibrationProgress.State.RUNNING) return Result.failure(IllegalStateException("a calibration is already running"))
        val steps = when (target) {
            Calibration.COMPASS -> listOf(COMPASS_LEVEL, COMPASS_NOSE_DOWN)
            Calibration.IMU -> listOf("Keep the aircraft still on a level surface")
            Calibration.GIMBAL -> listOf("Keep the aircraft still on a level surface")
        }
        running?.cancel()
        running = scope.launch {
            steps.forEachIndexed { i, text ->
                _status.update { it.copy(calibration = CalibrationProgress(target, CalibrationProgress.State.RUNNING, text, (100 * i) / steps.size)) }
                delay(stepMs)
            }
            val ok = SensorStatus(Health.OK, "Calibrated")
            _status.update {
                when (target) {
                    Calibration.COMPASS -> it.copy(compass = ok)
                    Calibration.IMU -> it.copy(imu = ok)
                    Calibration.GIMBAL -> it.copy(gimbal = ok)
                }.copy(calibration = CalibrationProgress(target, CalibrationProgress.State.SUCCEEDED, "Calibration complete", 100))
            }
        }
        return Result.success(Unit)
    }

    override suspend fun cancelCalibration(): Result<Unit> {
        running?.cancel()
        running = null
        _status.update { it.copy(calibration = null) }
        return Result.success(Unit)
    }

    override suspend fun resetHomeToAircraft(): Result<Unit> {
        delay(writeDelayMs)
        return Result.success(Unit)
    }

    companion object {
        /** DJI's two compass stages, in the words DJI's own apps use. */
        const val COMPASS_LEVEL = "Hold the aircraft level and turn it a full circle"
        const val COMPASS_NOSE_DOWN = "Hold the aircraft nose down and turn it a full circle"
    }
}
