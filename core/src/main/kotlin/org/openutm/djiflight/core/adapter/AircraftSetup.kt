package org.openutm.djiflight.core.adapter

import kotlinx.coroutines.flow.StateFlow

/**
 * The aircraft's own checks, settings and calibrations, SDK-neutral (docs/12, Pre-flight panel):
 * what DJI Pilot 2 and GO 4 show in their pre-flight checklist and flight-controller settings.
 *
 * Rules for implementers:
 * - Nothing is written except on the pilot's tap ([set], [calibrate], [resetHomeToAircraft]).
 * - Every write is read back; [status] shows what the aircraft reports, never what was asked for.
 * - Rows the aircraft or SDK doesn't support are left out of [AircraftStatus.supports].
 * - Re-read everything on [refresh]; the screen calls it when it opens and after each change.
 */
interface AircraftSetup {
    val status: StateFlow<AircraftStatus>

    /** Re-reads every setting and sensor state from the aircraft. */
    suspend fun refresh(): Result<Unit>

    /** Writes one setting, then reads it back into [status]. */
    suspend fun set(change: SettingChange): Result<Unit>

    /** Starts a calibration; progress and the result arrive in [AircraftStatus.calibration]. */
    suspend fun calibrate(target: Calibration): Result<Unit>

    /** Stops a running compass calibration (the only one the SDKs let the pilot cancel). */
    suspend fun cancelCalibration(): Result<Unit> = Result.success(Unit)

    /** Moves the home point to where the aircraft is now (DJI: "update home point"). */
    suspend fun resetHomeToAircraft(): Result<Unit>
}

enum class Health { OK, WARNING, ERROR, UNKNOWN }

/** One sensor's state, with the SDK's words reduced to what the pilot needs. */
data class SensorStatus(val health: Health = Health.UNKNOWN, val detail: String? = null)

enum class RcLostAction { RETURN_HOME, HOVER, LAND }

enum class Calibration { COMPASS, IMU, GIMBAL }

enum class SetupItem {
    RETURN_HOME_HEIGHT, MAX_ALTITUDE, MAX_DISTANCE, RC_LOST_ACTION, LOW_BATTERY, CRITICAL_BATTERY, OBSTACLE_AVOIDANCE,
    COMPASS, IMU, GIMBAL, HOME_RESET,
}

/** What the aircraft reports; null = not read (yet) or not supported. */
data class AircraftSettings(
    val returnHomeHeightM: Int? = null,
    val maxAltitudeM: Int? = null,
    val maxDistanceM: Int? = null,
    /** False: the distance limit is off (DJI's default on most aircraft). */
    val maxDistanceEnabled: Boolean? = null,
    val rcLostAction: RcLostAction? = null,
    val lowBatteryPercent: Int? = null,
    val criticalBatteryPercent: Int? = null,
    val obstacleAvoidance: Boolean? = null,
)

/** A calibration in progress or just finished. */
data class CalibrationProgress(
    val target: Calibration,
    val state: State,
    /** What the pilot does now, e.g. "Hold the aircraft level and turn it 360°". */
    val instruction: String,
    /** 0..100 when the SDK reports it. */
    val percent: Int? = null,
) {
    enum class State { RUNNING, SUCCEEDED, FAILED }
}

data class AircraftStatus(
    val settings: AircraftSettings = AircraftSettings(),
    val compass: SensorStatus = SensorStatus(),
    val imu: SensorStatus = SensorStatus(),
    val gimbal: SensorStatus = SensorStatus(),
    val calibration: CalibrationProgress? = null,
    val supports: Set<SetupItem> = emptySet(),
    /** When everything was last read, device clock; null = never. */
    val readAtMs: Long? = null,
    /** The last failure, in the SDK's words. */
    val error: String? = null,
)

/** The ranges the SDKs accept (DJI MSDK v4 and v5 documentation); the screen's steppers stay inside them. */
object SettingLimits {
    val RETURN_HOME_HEIGHT_M = 20..500
    val MAX_ALTITUDE_M = 20..500
    val MAX_DISTANCE_M = 15..8000
    val LOW_BATTERY_PERCENT = 15..50
    val CRITICAL_BATTERY_PERCENT = 10..45
}

sealed interface SettingChange {
    data class ReturnHomeHeight(val m: Int) : SettingChange
    data class MaxAltitude(val m: Int) : SettingChange
    data class MaxDistance(val m: Int, val enabled: Boolean) : SettingChange
    data class RcLost(val action: RcLostAction) : SettingChange
    data class LowBattery(val percent: Int) : SettingChange
    data class CriticalBattery(val percent: Int) : SettingChange
    data class ObstacleAvoidance(val enabled: Boolean) : SettingChange
}
