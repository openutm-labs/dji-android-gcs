package org.openutm.djiflight.dji.v4

import android.os.SystemClock
import dji.common.Stick
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.flightcontroller.virtualstick.*
import dji.common.flightcontroller.ConnectionFailSafeBehavior
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import dji.common.util.CommonCallbacks
import dji.keysdk.FlightControllerKey
import dji.keysdk.KeyManager
import dji.keysdk.RemoteControllerKey
import dji.sdk.products.Aircraft
import kotlinx.coroutines.suspendCancellableCoroutine
import org.openutm.djiflight.core.adapter.DroneState
import org.openutm.djiflight.core.mission.*
import kotlin.coroutines.resume
import kotlin.math.abs

/** DJI v4 ground frame: pitch = east, roll = north; throttle is upward velocity. */
class Mini2VirtualStickPort(private val aircraft: () -> Aircraft?, private val latest: () -> Pair<Long, DroneState>?) : VirtualStickPort {
    @Volatile private var controlled: Aircraft? = null
    @Volatile private var returnAircraft: Aircraft? = null
    private fun connected() = aircraft()?.takeIf { it.isConnected && it.model?.displayName in setOf("DJI Mini 2", "Mini 2") }
    override fun nowMs() = SystemClock.elapsedRealtime()
    override fun state(): StickState? {
        val (received, s) = latest() ?: return null
        val a = connected() ?: return null
        if (controlled != null && controlled !== a) return null
        val fc = a.flightController ?: return null
        val keys = KeyManager.getInstance() ?: return null
        val left = keys.getValue(RemoteControllerKey.create(RemoteControllerKey.LEFT_STICK_VALUE)) as? Stick ?: return null
        val right = keys.getValue(RemoteControllerKey.create(RemoteControllerKey.RIGHT_STICK_VALUE)) as? Stick ?: return null
        val overridden = listOf(left.horizontalPosition, left.verticalPosition, right.horizontalPosition, right.verticalPosition).any { abs(it) > 40 } || fc.state.isGoingHome || fc.state.isFailsafeEnabled
        val enabled = keys.getValue(FlightControllerKey.create(FlightControllerKey.VIRTUAL_STICK_CONTROL_MODE_ENABLED)) as? Boolean ?: false
        return StickState(received, s.position, s.heightAboveTakeoffM, s.yawDeg, s.isFlying, (s.gpsLevel ?: 0) >= 4,
            overridden, enabled && fc.isVirtualStickControlModeAvailable, s.batteryPercent, s.photoCount ?: 0,
            (keys.getValue(FlightControllerKey.create(FlightControllerKey.GO_HOME_HEIGHT_IN_METERS)) as? Number)?.toDouble(),
            keys.getValue(FlightControllerKey.create(FlightControllerKey.CONNECTION_FAIL_SAFE_BEHAVIOR)) == ConnectionFailSafeBehavior.GO_HOME)
    }
    override suspend fun enable(): Result<Unit> {
        val a = connected() ?: return Result.failure(IllegalStateException("Mini 2 disconnected"))
        val fc = a.flightController ?: return Result.failure(IllegalStateException("flight controller unavailable"))
        controlled = a
        returnAircraft = a
        fc.rollPitchControlMode = RollPitchControlMode.VELOCITY
        fc.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
        fc.verticalControlMode = VerticalControlMode.VELOCITY
        fc.yawControlMode = YawControlMode.ANGULAR_VELOCITY
        return complete { fc.setVirtualStickModeEnabled(true, it) }
    }
    override suspend fun command(value: StickCommand): Result<Unit> {
        val fc = controlled?.takeIf { it === connected() }?.flightController
            ?: return Result.failure(IllegalStateException("controlled Mini 2 disconnected or changed"))
        return complete { fc.sendVirtualStickFlightControlData(FlightControlData(value.eastMps.toFloat(), value.northMps.toFloat(), value.yawRateDegS.toFloat(), value.upMps.toFloat()), it) }
    }
    override suspend fun release(): Result<Unit> {
        val a = controlled ?: return Result.success(Unit)
        val fc = a.takeIf { it === connected() }?.flightController
            ?: return Result.failure(IllegalStateException("controlled Mini 2 disconnected; release is unconfirmed"))
        return complete { fc.setVirtualStickModeEnabled(false, it) }.onSuccess { controlled = null }
    }
    override suspend fun prepareCamera(): Result<Unit> {
        val camera = connected()?.camera ?: return Result.failure(IllegalStateException("Mini 2 camera unavailable"))
        return complete { camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, it) }
            .fold(onSuccess = { complete { camera.setPhotoAspectRatio(SettingsDefinitions.PhotoAspectRatio.RATIO_4_3, it) } }, onFailure = { Result.failure(it) })
            .fold(onSuccess = { complete { camera.setShootPhotoMode(SettingsDefinitions.ShootPhotoMode.SINGLE, it) } }, onFailure = { Result.failure(it) })
    }
    override suspend fun pitch(degrees: Double): Result<Unit> {
        val gimbal = controlled?.takeIf { it === connected() }?.gimbal ?: return Result.failure(IllegalStateException("controlled Mini 2 gimbal unavailable"))
        return complete { gimbal.rotate(Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(degrees.toFloat()).time(0.5).build(), it) }
    }
    override suspend fun shoot(): Result<Unit> {
        val camera = controlled?.takeIf { it === connected() }?.camera ?: return Result.failure(IllegalStateException("controlled Mini 2 camera unavailable"))
        return complete { camera.startShootPhoto(it) }
    }
    override suspend fun returnHome(): Result<Unit> {
        val fc = returnAircraft?.takeIf { it === connected() }?.flightController ?: return Result.failure(IllegalStateException("mission aircraft disconnected or changed"))
        return complete { fc.startGoHome(it) }
    }
    private suspend fun complete(call: (CommonCallbacks.CompletionCallback<DJIError>) -> Unit): Result<Unit> = suspendCancellableCoroutine { c ->
        call(CommonCallbacks.CompletionCallback<DJIError> { e -> if (c.isActive) c.resume(if (e == null) Result.success(Unit) else Result.failure(IllegalStateException(e.description))) })
    }
}
