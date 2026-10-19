package org.openutm.djiflight.dji.v4

import android.content.Context
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.common.flightcontroller.FlightControllerState
import dji.common.flightcontroller.GPSSignalLevel
import dji.common.flightcontroller.PositioningSolution
import dji.common.flightcontroller.flyzone.FlyZoneInformation
import dji.common.flightcontroller.flyzone.SubFlyZoneShape
import dji.common.util.CommonCallbacks
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.flightcontroller.FlightController
import dji.sdk.media.MediaFile
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.openutm.djiflight.core.adapter.AircraftIdentity
import org.openutm.djiflight.core.adapter.AircraftSetup
import org.openutm.djiflight.core.adapter.DroneAdapter
import org.openutm.djiflight.core.adapter.DroneCapabilities
import org.openutm.djiflight.core.adapter.DroneState
import org.openutm.djiflight.core.adapter.LinkState
import org.openutm.djiflight.core.adapter.MissionOperator
import org.openutm.djiflight.core.adapter.MissionSupport
import org.openutm.djiflight.core.adapter.RtkState
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.mission.CameraModel
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.core.zones.Zone
import kotlin.coroutines.resume

/** Where DJI SDK registration stands; registration needs internet on first launch only (DJI FAQ). */
sealed interface Registration {
    data object NotStarted : Registration
    data object Registering : Registration
    data object Registered : Registration
    data class Failed(val reason: String) : Registration
}

/**
 * [DroneAdapter] over DJI Mobile SDK v4.18 (the only file set that imports `dji.*`, with [WaypointV1Mapper]).
 *
 * The SDK calls back on its own threads; everything here only publishes into flows.
 * Callbacks are re-registered whenever the product or a component reconnects (battery swap,
 * flight controller reboot), and whenever the core's watchdog calls [resubscribe] (launch-risks B4).
 */
class DjiV4Adapter(private val context: Context, private val enableMini2Survey: Boolean = false, private val log: (String) -> Unit = {}) : DroneAdapter {
    private val _link = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val link: StateFlow<LinkState> = _link.asStateFlow()
    private val _caps = MutableStateFlow<DroneCapabilities?>(null)
    override val capabilities: StateFlow<DroneCapabilities?> = _caps.asStateFlow()
    private val _states = MutableSharedFlow<DroneState>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val states: SharedFlow<DroneState> = _states.asSharedFlow()
    private val _registration = MutableStateFlow<Registration>(Registration.NotStarted)
    val registration: StateFlow<Registration> = _registration.asStateFlow()

    @Volatile private var fc: FlightController? = null
    @Volatile private var batteryPercent: Int? = null
    @Volatile private var rtk: RtkState? = null
    /** JPEGs the camera reported since start; counts across reconnects (battery swaps). */
    private val photos = java.util.concurrent.atomic.AtomicInteger(0)
    private var missions: DjiV4MissionOperator? = null
    @Volatile private var stickTelemetry: Pair<Long, DroneState>? = null
    private val stickScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stickMission by lazy { org.openutm.djiflight.core.mission.VirtualStickMission(
        Mini2VirtualStickPort({ DJISDKManager.getInstance().product as? Aircraft }, { stickTelemetry }), stickScope) }
    private val setup by lazy {
        DjiV4AircraftSetup(CoroutineScope(SupervisorJob() + Dispatchers.Default), { DJISDKManager.getInstance().product as? Aircraft }, log)
    }

    override fun start() {
        if (_registration.value == Registration.Registering || _registration.value == Registration.Registered) return
        _registration.value = Registration.Registering
        DJISDKManager.getInstance().registerApp(context.applicationContext, sdkCallback)
    }

    override fun stop() {
        stickScope.launch { if (enableMini2Survey) stickMission.stop() }
        stickTelemetry = null
        fc?.setStateCallback(null)
        fc = null
        DJISDKManager.getInstance().stopConnectionToProduct()
        _link.value = LinkState.Disconnected
    }

    override fun resubscribe() {
        log("resubscribing to the flight controller")
        attach(DJISDKManager.getInstance().product)
    }

    /**
     * DJI GEO zones within ~20 km of the aircraft (`FlyZoneManager`), as read-only zones.
     * Offline behaviour is unverified (docs/12); an empty list on failure.
     */
    override suspend fun nearbyRestrictions(): List<Zone> = suspendCancellableCoroutine { cont ->
        val mgr = runCatching { DJISDKManager.getInstance().flyZoneManager }.getOrNull()
        if (mgr == null || _link.value !is LinkState.Connected) {
            cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }
        mgr.getFlyZonesInSurroundingArea(object : CommonCallbacks.CompletionCallbackWith<ArrayList<FlyZoneInformation>> {
            override fun onSuccess(zones: ArrayList<FlyZoneInformation>?) {
                if (cont.isActive) cont.resume(zones.orEmpty().mapNotNull(::toZone))
            }
            override fun onFailure(e: DJIError?) {
                log("fly zones unavailable: ${e?.description}")
                if (cont.isActive) cont.resume(emptyList())
            }
        })
    }

    private fun toZone(z: FlyZoneInformation): Zone? {
        val rings = z.subFlyZones.orEmpty().mapNotNull { sub ->
            when (sub.shape) {
                SubFlyZoneShape.POLYGON -> sub.vertices?.map { LatLng(it.latitude, it.longitude) }?.takeIf { it.size >= 3 }?.let { Geo.closeRing(it) }
                SubFlyZoneShape.CYLINDER -> sub.center?.takeIf { sub.radius > 0 }?.let { Geo.circle(LatLng(it.latitude, it.longitude), sub.radius, 24) }
                else -> null
            }
        }.ifEmpty {
            val c = z.coordinate ?: return null
            if (z.radius <= 0) return null
            listOf(Geo.circle(LatLng(c.latitude, c.longitude), z.radius, 24))
        }
        return Zone(
            id = "dji-${z.flyZoneID}", name = z.name?.takeIf { it.isNotBlank() } ?: "DJI zone ${z.flyZoneID}", rings = rings,
            lowerM = null, upperM = z.subFlyZones?.mapNotNull { it.maxFlightHeight.takeIf { h -> h > 0 }?.toDouble() }?.minOrNull(),
            altitudeRef = "AGL", source = "DJI GEO", category = z.category?.name,
        )
    }

    override fun missionOperator(): MissionOperator? {
        if (_caps.value?.missionSupport == MissionSupport.VIRTUAL_STICK_EXPERIMENTAL) return stickMission
        if (_caps.value?.missionSupport != MissionSupport.WAYPOINT_V1) return null
        return missions ?: DjiV4MissionOperator().also { missions = it }
    }

    override fun aircraftSetup(): AircraftSetup? = if (_link.value is LinkState.Connected) setup else null

    private val sdkCallback = object : DJISDKManager.SDKManagerCallback {
        override fun onRegister(error: DJIError?) {
            if (error == DJISDKError.REGISTRATION_SUCCESS) {
                _registration.value = Registration.Registered
                _link.value = LinkState.Connecting
                DJISDKManager.getInstance().startConnectionToProduct()
            } else {
                _registration.value = Registration.Failed(error?.description ?: "unknown error")
                log("DJI registration failed: ${error?.description}")
            }
        }

        override fun onProductDisconnect() = detach()
        override fun onProductConnect(product: BaseProduct?) = attach(product)
        override fun onProductChanged(product: BaseProduct?) = attach(product)

        override fun onComponentChange(key: BaseProduct.ComponentKey?, old: BaseComponent?, new: BaseComponent?) {
            new?.setComponentListener(BaseComponent.ComponentListener { connected ->
                if (connected) attach(DJISDKManager.getInstance().product)
            })
            attach(DJISDKManager.getInstance().product)
        }

        override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) = Unit
        override fun onDatabaseDownloadProgress(current: Long, total: Long) = Unit
    }

    private fun detach() {
        stickTelemetry = null
        batteryPercent = null
        _caps.value = null
        stickScope.launch { if (enableMini2Survey) stickMission.stop() }
        fc?.setStateCallback(null)
        fc = null
        _link.value = if (_registration.value == Registration.Registered) LinkState.Connecting else LinkState.Disconnected
    }

    private fun attach(product: BaseProduct?) {
        val aircraft = product as? Aircraft
        val controller = aircraft?.flightController
        if (aircraft == null || controller == null || !aircraft.isConnected) return detach()
        fc = controller
        controller.setStateCallback(FlightControllerState.Callback(::onState))
        aircraft.battery?.setStateCallback { b -> batteryPercent = b.chargeRemainingInPercent }
        // One callback per camera: a JPEG+DNG shot reports two files, so only JPEGs are counted.
        runCatching { aircraft.camera?.setMediaFileCallback { f -> if (f?.mediaType == MediaFile.MediaType.JPEG) photos.incrementAndGet() } }
        runCatching {
            controller.rtk?.setStateCallback { r ->
                rtk = RtkState(
                    fixed = r.isRTKBeingUsed && r.positioningSolution == PositioningSolution.FIXED_POINT,
                    // Datum of the RTK take-off altitude is unverified (docs/12, Open items).
                    ellipsoidM = r.takeOffAltitude.toDouble().takeIf { it.isFinite() && it != 0.0 },
                    sigmaM = 0.1,
                )
            }
        }
        val model = aircraft.model?.displayName ?: "DJI aircraft"
        controller.getSerialNumber(object : CommonCallbacks.CompletionCallbackWith<String> {
            override fun onSuccess(serial: String?) {
                if (serial.isNullOrBlank()) return onFailure(null)
                controller.getFirmwareVersion(object : CommonCallbacks.CompletionCallbackWith<String> {
                    override fun onSuccess(fw: String?) = publish(AircraftIdentity(serial, model, fw))
                    override fun onFailure(e: DJIError?) = publish(AircraftIdentity(serial, model, null))
                })
            }

            override fun onFailure(e: DJIError?) {
                // Without a serial the declaration's aircraft_id can't be checked; stay Connecting, retry on resubscribe.
                log("flight controller serial unavailable: ${e?.description}")
                _link.value = LinkState.Connecting
            }
        })
    }

    private fun publish(identity: AircraftIdentity) {
        val waypointV1 = identity.model in WAYPOINT_V1_MODELS
        _caps.value = DroneCapabilities(
            model = identity.model,
            missionSupport = if (waypointV1) MissionSupport.WAYPOINT_V1 else if (enableMini2Survey && identity.model in setOf("DJI Mini 2", "Mini 2")) MissionSupport.VIRTUAL_STICK_EXPERIMENTAL else MissionSupport.NONE,
            missionLimits = if (waypointV1) MissionLimits.WAYPOINT_V1 else if (enableMini2Survey && identity.model in setOf("DJI Mini 2", "Mini 2")) MissionLimits.WAYPOINT_V1.copy(maxWaypoints = 2_000) else null,
            hasRtk = rtk != null,
            nativeRateHz = 10,
            camera = CAMERAS[identity.model],
        )
        _link.value = LinkState.Connected(identity)
    }

    private fun onState(st: FlightControllerState) {
        val loc = st.aircraftLocation
        val home = st.homeLocation?.takeIf { st.isHomeLocationSet }?.let { LatLng(it.latitude, it.longitude) }
        val sample = DroneState(
                timeMs = System.currentTimeMillis(),
                lat = loc?.latitude ?: Double.NaN,
                lng = loc?.longitude ?: Double.NaN,
                heightAboveTakeoffM = loc?.altitude?.toDouble() ?: Double.NaN,
                velocityNorthMps = st.velocityX.toDouble(),
                velocityEastMps = st.velocityY.toDouble(),
                velocityDownMps = st.velocityZ.toDouble(),
                yawDeg = st.attitude?.yaw ?: Double.NaN,
                isFlying = st.isFlying,
                motorsOn = st.areMotorsOn(),
                gpsLevel = st.gpsSignalLevel?.takeIf { it != GPSSignalLevel.NONE }?.value()?.coerceAtMost(5),
                satellites = st.satelliteCount,
                flightMode = st.flightModeString,
                home = home,
                takeoffMslM = st.takeoffLocationAltitude.toDouble().takeIf { it.isFinite() },
                rtk = rtk,
                batteryPercent = batteryPercent,
                photoCount = photos.get(),
            )
        stickTelemetry = android.os.SystemClock.elapsedRealtime() to sample
        _states.tryEmit(sample)
    }

    companion object {
        /** Survey cameras by `Model.getDisplayName()`; others fall back to the APK's default (P4P V2.0). */
        val CAMERAS = mapOf(
            "Phantom 4 Pro" to CameraModel.P4P_V2, "Phantom 4 Pro V2.0" to CameraModel.P4P_V2, "Phantom 4 Advanced" to CameraModel.P4P_V2,
            "Mavic 2 Pro" to CameraModel.MAVIC_2_PRO,
            "DJI Mini 2" to CameraModel.MINI_2, "Mini 2" to CameraModel.MINI_2,
        )

        /**
         * Waypoint 1.0 in MSDK v4 (DJI hardware table + staff replies, docs/12): Phantom 4 family,
         * Mavic 2 family, M200 V2 series. Consumer Mini/Air models have none; the M300 uses Waypoint 2.0.
         * Matched on `Model.getDisplayName()`; confirm the exact strings on real aircraft.
         */
        val WAYPOINT_V1_MODELS = setOf(
            "Phantom 4", "Phantom 4 Pro", "Phantom 4 Pro V2.0", "Phantom 4 Advanced", "Phantom 4 RTK",
            "Mavic 2 Pro", "Mavic 2 Zoom", "Mavic 2 Enterprise", "Mavic 2 Enterprise Dual",
            "Matrice 200 V2", "Matrice 210 V2", "Matrice 210 RTK V2",
        )
    }
}
