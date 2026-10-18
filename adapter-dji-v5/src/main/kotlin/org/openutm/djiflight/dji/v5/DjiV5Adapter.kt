package org.openutm.djiflight.dji.v5

import android.content.Context
import com.dji.wpmzsdk.manager.WPMZManager
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RtkMobileStationKey
import dji.sdk.keyvalue.value.camera.CameraType
import dji.sdk.keyvalue.value.camera.MediaFileType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel
import dji.sdk.keyvalue.value.product.ProductType
import dji.sdk.keyvalue.value.rtkmobilestation.RTKPositioningSolution
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.KeyManager
import dji.v5.manager.SDKManager
import dji.v5.manager.aircraft.flysafe.FlyZoneManager
import dji.v5.manager.aircraft.flysafe.info.FlyZoneInformation
import dji.v5.manager.aircraft.flysafe.info.FlyZoneShape
import dji.v5.manager.aircraft.rtk.RTKCenter
import dji.v5.manager.aircraft.rtk.RTKLocationInfoListener
import dji.v5.manager.aircraft.rtk.RTKSystemStateListener
import dji.v5.manager.interfaces.SDKManagerCallback
import dji.v5.network.DJINetworkManager
import dji.v5.network.IDJINetworkStatusListener
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
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.core.zones.Zone
import kotlin.coroutines.resume
import kotlin.math.abs

/** Where DJI SDK registration stands; registration needs internet on first launch only. */
sealed interface Registration {
    data object NotStarted : Registration
    data object Initializing : Registration
    data object Registering : Registration
    data object Registered : Registration
    data class Failed(val reason: String) : Registration
}

/**
 * [DroneAdapter] over DJI Mobile SDK v5.18 (with [DjiV5MissionOperator] and [DjiV5Camera], the only
 * files that import `dji.*`): Mavic 3 Enterprise, Matrice 400, and other MSDK v5 aircraft.
 *
 * v5 has no single state callback: each value is its own key with its own push rate. The latest
 * value of each is kept here, and one [DroneState] is published per position update.
 * Every key listener is dropped and re-added on each product (re)connect and on [resubscribe]
 * (launch-risks B4: the connector subscribed once and lost all positions after a reboot).
 */
class DjiV5Adapter(private val context: Context, private val log: (String) -> Unit = {}) : DroneAdapter {
    private val _link = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val link: StateFlow<LinkState> = _link.asStateFlow()
    private val _caps = MutableStateFlow<DroneCapabilities?>(null)
    override val capabilities: StateFlow<DroneCapabilities?> = _caps.asStateFlow()
    private val _states = MutableSharedFlow<DroneState>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val states: SharedFlow<DroneState> = _states.asSharedFlow()
    private val _registration = MutableStateFlow<Registration>(Registration.NotStarted)
    val registration: StateFlow<Registration> = _registration.asStateFlow()

    private val keys get() = KeyManager.getInstance()
    @Volatile private var started = false
    @Volatile private var fcConnected = false

    // Latest value of each key (SDK threads write, the emitter reads).
    @Volatile private var height = Double.NaN
    @Volatile private var vn = 0.0
    @Volatile private var ve = 0.0
    @Volatile private var vd = 0.0
    @Volatile private var yaw = Double.NaN
    @Volatile private var flying = false
    @Volatile private var motorsOn = false
    @Volatile private var gpsLevel: Int? = null
    @Volatile private var satellites: Int? = null
    @Volatile private var flightMode: String? = null
    @Volatile private var home: LatLng? = null
    @Volatile private var homeSet = false
    @Volatile private var takeoffMsl: Double? = null
    @Volatile private var battery: Int? = null
    @Volatile private var rtkConnected = false
    @Volatile private var rtkFixed = false
    @Volatile private var rtkTakeoffAlt: Double? = null
    @Volatile private var rtkSigma: Double? = null
    /** JPEGs the main camera reported since start; counts across reconnects (battery swaps). */
    private val photos = java.util.concurrent.atomic.AtomicInteger(0)

    // Identity, read once per connection.
    @Volatile private var identity: AircraftIdentity? = null
    @Volatile private var aircraft: V5Aircraft? = null
    @Volatile private var productType: ProductType? = null
    @Volatile private var cameraType: CameraType? = null

    private var missions: DjiV5MissionOperator? = null
    private val setup by lazy {
        DjiV5AircraftSetup(CoroutineScope(SupervisorJob() + Dispatchers.Default), { _link.value is LinkState.Connected }, log)
    }

    override fun aircraftSetup(): AircraftSetup? = if (_link.value is LinkState.Connected) setup else null

    override fun start() {
        if (started) return
        started = true
        _registration.value = Registration.Initializing
        removeEmptySdkFiles()
        SDKManager.getInstance().init(context.applicationContext, sdkCallback)
        // Registration needs the internet once; retry whenever a network appears (DJI sample).
        DJINetworkManager.getInstance().addNetworkStatusListener(networkListener)
    }

    /**
     * MSDK 5.18 writes `files/sdkconfig.json` (and `test.json`) at start-up. A run killed while
     * writing leaves them empty, and then every later start exits in native code right after init
     * (seen on the emulator, 2026-10-18; docs/12, Open items). The SDK rewrites them, so an empty one
     * is only removed, never edited.
     */
    private fun removeEmptySdkFiles() {
        for (name in listOf("sdkconfig.json", "test.json")) {
            val f = java.io.File(context.filesDir, name)
            if (f.isFile && f.length() == 0L) log("removing an empty $name left by an interrupted run: ${f.delete()}")
        }
    }

    override fun stop() {
        unlistenAll()
        _link.value = LinkState.Disconnected
    }

    override fun resubscribe() {
        log("resubscribing to the aircraft's keys")
        listenAll()
        if (identity == null) readIdentity()
    }

    override fun missionOperator(): MissionOperator? {
        if (_caps.value?.missionSupport != MissionSupport.WPML) return null
        return missions ?: DjiV5MissionOperator(context, { aircraft?.wpml }, log).also { missions = it }
    }

    /**
     * DJI GEO zones around the aircraft (`FlyZoneManager.getFlyZonesInSurroundingArea`), read-only.
     * Offline behaviour is unverified (docs/12); an empty list on failure.
     */
    override suspend fun nearbyRestrictions(): List<Zone> {
        val st = lastPosition ?: return emptyList()
        if (_link.value !is LinkState.Connected) return emptyList()
        return suspendCancellableCoroutine { cont ->
            FlyZoneManager.getInstance().getFlyZonesInSurroundingArea(
                LocationCoordinate2D(st.lat, st.lng),
                object : CommonCallbacks.CompletionCallbackWithParam<List<FlyZoneInformation>> {
                    override fun onSuccess(zones: List<FlyZoneInformation>?) {
                        if (cont.isActive) cont.resume(zones.orEmpty().mapNotNull(::toZone))
                    }
                    override fun onFailure(e: IDJIError) {
                        log("fly zones unavailable: ${e.description()}")
                        if (cont.isActive) cont.resume(emptyList())
                    }
                },
            )
        }
    }

    private fun toZone(z: FlyZoneInformation): Zone? {
        val rings = when (z.shape) {
            FlyZoneShape.CIRCLE -> z.circleCenter?.let { c -> listOfNotNull(validLatLng(c.latitude, c.longitude)?.takeIf { z.circleRadius > 0 }?.let { Geo.circle(it, z.circleRadius, 24) }) }
            FlyZoneShape.MULTI_POLYGON -> z.multiPolygonFlyZoneInformation.orEmpty().mapNotNull { sub ->
                sub.polygonPoints?.mapNotNull { validLatLng(it.latitude, it.longitude) }?.takeIf { it.size >= 3 }?.let { Geo.closeRing(it) }
                    ?: sub.cylinderCenter?.let { validLatLng(it.latitude, it.longitude) }?.takeIf { sub.cylinderRadius > 0 }?.let { Geo.circle(it, sub.cylinderRadius, 24) }
            }
            else -> null
        }.orEmpty()
        if (rings.isEmpty()) return null
        val subLimits = z.multiPolygonFlyZoneInformation.orEmpty().mapNotNull { it.limitedHeight.takeIf { h -> h > 0 }?.toDouble() }
        return Zone(
            id = "dji-${z.flyZoneID}", name = z.name?.takeIf { it.isNotBlank() } ?: "DJI zone ${z.flyZoneID}", rings = rings,
            lowerM = z.lowerLimit.takeIf { it > 0 }, upperM = (subLimits + listOfNotNull(z.upperLimit.takeIf { it > 0 })).minOrNull(),
            altitudeRef = "AGL", source = "DJI GEO", category = z.category?.name,
        )
    }

    // --- SDK lifecycle

    private val sdkCallback = object : SDKManagerCallback {
        override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
            if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                runCatching { WPMZManager.getInstance().init(context.applicationContext) }.onFailure { log("WPMZ init failed: $it") }
                _registration.value = Registration.Registering
                SDKManager.getInstance().registerApp()
            }
        }

        override fun onRegisterSuccess() {
            _registration.value = Registration.Registered
            log("DJI registration succeeded (MSDK ${SDKManager.getInstance().sdkVersion})")
            if (_link.value == LinkState.Disconnected) _link.value = LinkState.Connecting
            listenAll()
        }

        override fun onRegisterFailure(error: IDJIError) {
            _registration.value = Registration.Failed(error.description() ?: error.errorCode() ?: "unknown error")
            log("DJI registration failed: ${error.errorCode()} ${error.description()}")
        }

        override fun onProductConnect(productId: Int) = attach()
        override fun onProductChanged(productId: Int) = attach()
        override fun onProductDisconnect(productId: Int) = detach()
        override fun onDatabaseDownloadProgress(current: Long, total: Long) = Unit
    }

    private val networkListener = IDJINetworkStatusListener { available ->
        val reg = _registration.value
        if (available && (reg is Registration.Failed || reg == Registration.Registering) && !SDKManager.getInstance().isRegistered) {
            SDKManager.getInstance().registerApp()
        }
    }

    private fun attach() {
        listenAll()
        readIdentity()
    }

    private fun detach() {
        fcConnected = false
        identity = null
        aircraft = null
        _link.value = if (_registration.value == Registration.Registered) LinkState.Connecting else LinkState.Disconnected
    }

    // --- keys

    /** [current] = also deliver the key's present value at once (not for events such as new files). */
    private fun <T> listen(info: DJIKeyInfo<T>, index: ComponentIndexType? = null, current: Boolean = true, onValue: (T?) -> Unit) {
        val key: DJIKey<T> = if (index == null) KeyTools.createKey(info) else KeyTools.createKey(info, index)
        keys.listen(key, this, current, CommonCallbacks.KeyListener { _, v -> onValue(v) })
    }

    private fun unlistenAll() {
        runCatching { keys.cancelListen(this) }
        runCatching { RTKCenter.getInstance().removeRTKLocationInfoListener(rtkLocationListener) }
        runCatching { RTKCenter.getInstance().removeRTKSystemStateListener(rtkStateListener) }
    }

    @Synchronized
    private fun listenAll() {
        if (_registration.value != Registration.Registered) return
        unlistenAll()
        listen(FlightControllerKey.KeyConnection) { c ->
            val now = c == true
            if (now && !fcConnected) {
                fcConnected = true
                readIdentity()
            } else if (!now && fcConnected) {
                detach()
            }
        }
        listen(FlightControllerKey.KeyAircraftVelocity) { v -> if (v != null) { vn = v.x ?: 0.0; ve = v.y ?: 0.0; vd = v.z ?: 0.0 } }
        listen(FlightControllerKey.KeyAircraftAttitude) { a -> yaw = a?.yaw ?: Double.NaN }
        listen(FlightControllerKey.KeyIsFlying) { flying = it == true }
        listen(FlightControllerKey.KeyAreMotorsOn) { motorsOn = it == true }
        listen(FlightControllerKey.KeyGPSSignalLevel) { gpsLevel = gpsLevel(it) }
        listen(FlightControllerKey.KeyGPSSatelliteCount) { satellites = it }
        listen(FlightControllerKey.KeyFlightModeString) { flightMode = it }
        listen(FlightControllerKey.KeyIsHomeLocationSet) { homeSet = it == true }
        listen(FlightControllerKey.KeyHomeLocation) { h -> home = h?.let { validLatLng(it.latitude, it.longitude) } }
        listen(FlightControllerKey.KeyTakeoffLocationAltitude) { a -> takeoffMsl = a?.takeIf { it.isFinite() && it != 0.0 } }
        listen(BatteryKey.KeyChargeRemainingInPercent) { battery = it }
        listen(RtkMobileStationKey.KeyRTKTakeoffAltitudeInfo) { info -> rtkTakeoffAlt = info?.takeIf { it.valid == true }?.altitude?.takeIf { it.isFinite() && it != 0.0 } }
        listen(CameraKey.KeyCameraType, ComponentIndexType.LEFT_OR_MAIN) { t ->
            if (t != cameraType) {
                cameraType = t
                identity?.let(::publish) // a payload swap on the M400 changes what missions we can fly
            }
        }
        // A JPEG+DNG shot reports two files: count JPEGs only.
        listen(CameraKey.KeyNewlyGeneratedMediaFile, ComponentIndexType.LEFT_OR_MAIN, current = false) { f ->
            if (f?.type == MediaFileType.JPEG) photos.incrementAndGet()
        }
        // Drives emission: one DroneState per position update.
        listen(FlightControllerKey.KeyAircraftLocation3D) { loc ->
            if (loc == null) return@listen
            height = loc.altitude ?: Double.NaN
            emit(validLatLng(loc.latitude, loc.longitude))
        }
        runCatching {
            RTKCenter.getInstance().addRTKSystemStateListener(rtkStateListener)
            RTKCenter.getInstance().addRTKLocationInfoListener(rtkLocationListener)
        }.onFailure { log("RTK listeners unavailable: $it") }
    }

    private val rtkStateListener = RTKSystemStateListener { s ->
        val connected = s.getRTKConnected() && s.getIsRTKEnabled()
        if (connected != rtkConnected) {
            rtkConnected = connected
            identity?.let(::publish)
        }
    }

    private val rtkLocationListener = RTKLocationInfoListener { info ->
        val loc = info.rtkLocation
        rtkFixed = loc?.positioningSolution == RTKPositioningSolution.FIXED_POINT
        rtkSigma = loc?.stdAltitude?.takeIf { it.isFinite() && it > 0 }
    }

    @Volatile private var lastPosition: LatLng? = null

    private fun emit(position: LatLng?) {
        if (position != null) lastPosition = position
        _states.tryEmit(
            DroneState(
                timeMs = System.currentTimeMillis(),
                lat = position?.lat ?: Double.NaN,
                lng = position?.lng ?: Double.NaN,
                heightAboveTakeoffM = height,
                velocityNorthMps = vn,
                velocityEastMps = ve,
                velocityDownMps = vd,
                yawDeg = yaw,
                isFlying = flying,
                motorsOn = motorsOn,
                gpsLevel = gpsLevel,
                satellites = satellites,
                flightMode = flightMode,
                home = home?.takeIf { homeSet },
                takeoffMslM = takeoffMsl,
                // Datum of DJI's RTK take-off altitude is unverified (docs/12, Open items), as in v4.
                rtk = if (rtkConnected) RtkState(fixed = rtkFixed, ellipsoidM = rtkTakeoffAlt, sigmaM = rtkSigma ?: 0.1) else null,
                batteryPercent = battery,
                photoCount = photos.get(),
            ),
        )
    }

    // --- identity

    private fun readIdentity() {
        get(FlightControllerKey.KeySerialNumber) { serial ->
            if (serial.isNullOrBlank()) {
                // Without a serial the declaration's aircraft_id can't be checked; stay Connecting, retry on resubscribe.
                log("flight controller serial unavailable")
                _link.value = LinkState.Connecting
                return@get
            }
            get(ProductKey.KeyProductType) { type ->
                productType = type
                get(CameraKey.KeyCameraType, ComponentIndexType.LEFT_OR_MAIN) { cam ->
                    if (cam != null) cameraType = cam
                    get(ProductKey.KeyFirmwareVersion) { fw ->
                        val described = V5Models.describe(type, cameraType, 0)
                        publish(AircraftIdentity(serial, described.model, fw?.takeIf { it.isNotBlank() }))
                    }
                }
            }
        }
    }

    private fun <T> get(info: DJIKeyInfo<T>, index: ComponentIndexType? = null, then: (T?) -> Unit) {
        val key: DJIKey<T> = if (index == null) KeyTools.createKey(info) else KeyTools.createKey(info, index)
        keys.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
            override fun onSuccess(value: T) = then(value)
            override fun onFailure(error: IDJIError) = then(null)
        })
    }

    @Synchronized
    private fun publish(id: AircraftIdentity) {
        val a = V5Models.describe(productType, cameraType, payloadPosition(ComponentIndexType.LEFT_OR_MAIN))
        aircraft = a
        val withModel = id.copy(model = a.model)
        identity = withModel
        fcConnected = true
        _caps.value = DroneCapabilities(
            model = a.model,
            missionSupport = if (a.wpml != null) MissionSupport.WPML else MissionSupport.NONE,
            missionLimits = if (a.wpml != null) MissionLimits.WPML else null,
            hasRtk = rtkConnected,
            nativeRateHz = 10,
            camera = a.camera,
        )
        _link.value = LinkState.Connected(withModel)
    }

    companion object {
        /** WPML `payloadPositionIndex`: 0 = main gimbal (M300/M350: front left), 1 = right, 2 = top. */
        fun payloadPosition(index: ComponentIndexType): Int = when (index) {
            ComponentIndexType.RIGHT -> 1
            ComponentIndexType.UP -> 2
            else -> 0
        }

        /** DJI GPSSignalLevel → 0..5 (LEVEL_10 is the RTK-grade level); none → null. */
        fun gpsLevel(l: GPSSignalLevel?): Int? = when (l) {
            GPSSignalLevel.LEVEL_0 -> 0
            GPSSignalLevel.LEVEL_1 -> 1
            GPSSignalLevel.LEVEL_2 -> 2
            GPSSignalLevel.LEVEL_3 -> 3
            GPSSignalLevel.LEVEL_4 -> 4
            GPSSignalLevel.LEVEL_5, GPSSignalLevel.LEVEL_10 -> 5
            else -> null
        }

        /** v5 reports 0,0 (not NaN) without a fix; treat it, and anything out of range, as no position. */
        fun validLatLng(lat: Double?, lng: Double?): LatLng? {
            if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) return null
            if (abs(lat) < 1e-7 && abs(lng) < 1e-7) return null
            if (abs(lat) > 90 || abs(lng) > 180) return null
            return LatLng(lat, lng)
        }

    }
}
