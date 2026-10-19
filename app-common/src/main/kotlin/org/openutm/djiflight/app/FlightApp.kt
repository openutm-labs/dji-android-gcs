package org.openutm.djiflight.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openutm.djiflight.app.background.FlightForegroundService
import org.openutm.djiflight.app.background.SyncJobService
import org.openutm.djiflight.core.FlightService
import org.openutm.djiflight.core.ServiceConfig
import org.openutm.djiflight.core.adapter.LinkState
import org.openutm.djiflight.core.altitude.Egm96Grid
import org.openutm.djiflight.core.altitude.GnssAverager
import org.openutm.djiflight.core.altitude.GnssFix
import org.openutm.djiflight.core.auth.ClientCredentialsTokenProvider
import org.openutm.djiflight.core.auth.DevTokenProvider
import org.openutm.djiflight.core.blender.BlenderBackend
import org.openutm.djiflight.core.declaration.GroundEstimate
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.http.UrlConnectionTransport
import org.openutm.djiflight.core.journal.FlightRepository
import org.openutm.djiflight.core.mission.FileMissionProgressStore
import org.openutm.djiflight.core.mission.MissionRunner
import org.openutm.djiflight.core.provisioning.AuthConfig
import org.openutm.djiflight.core.provisioning.DeviceProvisioning
import org.openutm.djiflight.core.provisioning.ImageryService
import org.openutm.djiflight.core.provisioning.Pilot
import org.openutm.djiflight.core.provisioning.ProvisioningCodec
import org.openutm.djiflight.core.session.FlightSnapshot
import org.openutm.djiflight.core.session.Phase
import org.openutm.djiflight.core.sites.Site
import org.openutm.djiflight.core.sites.SiteStore
import org.openutm.djiflight.core.sync.SwitchableBackend
import org.openutm.djiflight.core.sync.SyncEngine
import org.openutm.djiflight.core.time.CorrectedClock
import org.openutm.djiflight.core.zones.Zone
import org.openutm.djiflight.core.zones.ZoneService
import org.openutm.djiflight.core.terrain.TerrainModel
import java.io.File

/** Shared by every APK; each subclass supplies its [Platform] (the DroneAdapter, video, build facts). */
abstract class FlightApp : Application() {
    lateinit var graph: AppGraph
        private set

    abstract fun platform(): Platform

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this, platform())
    }
}

/** Builds the app once per process. Everything below the adapter is the DJI-free core. */
class AppGraph(private val app: Application, val platform: Platform) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock = CorrectedClock()
    val adapter = platform.createAdapter(app, scope)
    val grid: Egm96Grid by lazy { Egm96Grid.bundled() }
    val transport = UrlConnectionTransport()

    // --- provisioning: the backend is swapped in when the device is set up, without a restart
    val backend = SwitchableBackend()
    private val secure = SecureStore(app, "provisioning")
    private val _provisioning = MutableStateFlow<DeviceProvisioning?>(null)
    val provisioning: StateFlow<DeviceProvisioning?> = _provisioning.asStateFlow()

    val sites = SiteStore(File(app.filesDir, "sites.json"))
    val zones = ZoneService(backend, File(app.filesDir, "zones.json"), clock)
    val maps = RegionPacks(app)
    val terrainFiles = TerrainFiles(maps)
    /** The pilot signed in through the lab's identity server, if the device setup has `pilot_login`. */
    val pilots = PilotSessions(app) { clock.nowMs() }
    /** The lab's terrain model, if its terrain file is installed. */
    val terrain: TerrainModel? get() = terrainFiles.model()
    val updates = UpdateManager(app, platform.versionCode)

    private val _aircraftZones = MutableStateFlow<List<Zone>>(emptyList())
    val aircraftZones: StateFlow<List<Zone>> = _aircraftZones.asStateFlow()

    private val _deviceFix = MutableStateFlow<GnssFix?>(null)
    val deviceFix: StateFlow<GnssFix?> = _deviceFix.asStateFlow()
    private val deviceGround = GnssAverager(windowMs = 30_000)

    /** The active flight's track (1 Hz), for the map. */
    private val _track = MutableStateFlow<List<LatLng>>(emptyList())
    val track: StateFlow<List<LatLng>> = _track.asStateFlow()

    private val flightRepo = FlightRepository(File(app.filesDir, "flights")) { backend.serverId }

    val service: FlightService by lazy {
        FlightService(
            adapter = adapter,
            repo = flightRepo,
            sync = SyncEngine(backend, clock),
            clock = clock,
            grid = grid,
            config = ServiceConfig(),
            parent = scope,
        )
    }

    val mission = MissionRunner(scope, adapter, platform.defaultMissionLimits, FileMissionProgressStore(File(app.filesDir, "mission-progress.json"))) {
        _provisioning.value?.legalMaxHeightM ?: 120.0
    }

    init {
        // Re-validated on every start: a release build must not keep a dev token a debug build stored.
        val previous = secure.read()?.let { runCatching { ProvisioningCodec.decode(it) }.getOrNull() }
            ?.takeIf { it.problems(allowDevAuth = platform.debug).isEmpty() }
        flightRepo.migrateLegacy(previous?.let { org.openutm.djiflight.core.sync.ServerIdentity.of(it.blenderUrl) })
        previous?.let(::applyProvisioning)
    }

    /** Validates, stores (encrypted) and applies a provisioning; returns the problems if refused. */
    fun provision(p: DeviceProvisioning): List<String> {
        val problems = p.problems(allowDevAuth = platform.debug)
        if (problems.isNotEmpty()) return problems
        val changingServer = backend.serverId != org.openutm.djiflight.core.sync.ServerIdentity.of(p.blenderUrl)
        val status = service.status.value
        if (changingServer && (status.lastState?.isFlying == true || status.activeFlight?.let { it.everFlown && !it.recordingClosed } == true)) {
            return listOf("Land and finish the current flight before changing the UTM server.")
        }
        secure.write(ProvisioningCodec.toJson(p))
        applyProvisioning(p)
        service.backendChanged()
        return emptyList()
    }

    /** Debug builds only: the workspace's local Blender with its dev-token bypass, and a sample checklist and processing service. */
    fun devProvisioning() = DeviceProvisioning(
        deviceName = "dev-${Build.MODEL}", blenderUrl = platform.devBlenderUrl, auth = AuthConfig.Dev,
        lab = "Development", operatorId = "OPR-DEV", pilots = listOf(Pilot("pilot-dev", "Dev pilot")),
        checklist = listOf("Propellers and arms secure", "Batteries charged and seated", "SD card in, enough space"),
        imagery = ImageryService("DroneDeploy", project = "Dev test project"),
    )

    /** Read-only credential check for manual setup; does not replace the active server. */
    suspend fun checkProvisioning(p: DeviceProvisioning): org.openutm.djiflight.core.sync.BackendResult<Unit> {
        val problems = p.problems(allowDevAuth = platform.debug)
        if (problems.isNotEmpty()) return org.openutm.djiflight.core.sync.BackendResult.Rejected(400, problems.joinToString("; "))
        return configuredBackend(p).checkConnection()
    }

    private fun configuredBackend(p: DeviceProvisioning): BlenderBackend {
        val tokens = when (val a = p.auth) {
            AuthConfig.Dev -> DevTokenProvider(clock)
            is AuthConfig.ClientCredentials -> ClientCredentialsTokenProvider(transport, a.tokenUrl, a.clientId, a.clientSecret, a.scope, a.audience, clock)
        }
        return BlenderBackend(p.blenderUrl, tokens, transport, submittedBy = "${p.operatorId}/${p.deviceName}")
    }

    private fun applyProvisioning(p: DeviceProvisioning) {
        backend.target = configuredBackend(p)
        zones.backendChanged()
        // A sign-in from another identity server (or none configured now) no longer says who flies.
        pilots.current.value?.let { s -> if (s.issuer.trimEnd('/') != p.pilotLogin?.issuer?.trimEnd('/')) pilots.signOut() }
        _provisioning.value = p
    }

    fun flightInProgress(f: FlightSnapshot) = f.plan != null && f.phase in setOf(Phase.PLANNED, Phase.DECLARED, Phase.ACTIVE, Phase.LANDED)

    /** Best ground estimate from this device's own GNSS, if it has been still long enough. */
    fun deviceGroundEstimate(): GroundEstimate? = synchronized(deviceGround) { deviceGround.candidate() }
        ?.let { GroundEstimate(it.ellipsoidM, it.sigmaM, it.source) }

    fun currentPosition(): LatLng? =
        service.status.value.lastState?.position?.takeIf { it.isValid } ?: _deviceFix.value?.let { LatLng(it.lat, it.lng) }?.takeIf { it.isValid }

    private var started = false
    private var adapterStarted = false

    /** From a screen: everything, including the drone SDK. */
    @Synchronized
    fun start(context: Context) {
        startCommon(context)
        if (!adapterStarted) {
            adapterStarted = true
            service.startAdapter()
            watchAircraftZones()
        }
    }

    /** From the background sync job: uploads only. */
    @Synchronized
    fun startBackground(context: Context) = startCommon(context)

    private fun startCommon(context: Context) {
        if (started) return
        started = true
        service.start(withAdapter = false)
        Log.i(TAG, "pruned ${service.prune()} old exported flight(s)")
        watchConnectivity(context)
        val simulated = platform.deviceFixes(adapter)
        if (simulated != null) scope.launch { simulated.collect(::onDeviceFix) } else startDeviceGnss(context)
        logTransitions()
        followActiveFlight(context)
        keepZonesFresh()
    }

    private fun onDeviceFix(fix: GnssFix) {
        _deviceFix.value = fix
        synchronized(deviceGround) { deviceGround.add(fix) }
        service.onDeviceFix(fix)
    }

    /** The phone's/controller's own GNSS: pilot position, take-off height (WGS-84 ellipsoid) and GNSS time. */
    @SuppressLint("MissingPermission")
    fun startDeviceGnss(context: Context) {
        if (platform.deviceFixes(adapter) != null) return
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) return
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, object : LocationListener {
            override fun onLocationChanged(l: Location) = onDeviceFix(l.toFix())
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }, Looper.getMainLooper())
    }

    private fun watchConnectivity(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = service.setOnline(true)
            override fun onLost(network: Network) = service.setOnline(cm.activeNetwork != null)
        })
        service.setOnline(cm.activeNetwork != null)
    }

    /**
     * Follows the active flight: the foreground service while it is in progress, the background job
     * once it isn't, the mission runner, the 1 Hz map track, and the site's measured ground height.
     */
    private fun followActiveFlight(context: Context) = scope.launch {
        var flightId: String? = null
        var fgsRunning = false
        var savedRef: Any? = null
        while (isActive) {
            val s = service.status.value
            val f = s.activeFlight
            if (f?.flightId != flightId) {
                flightId = f?.flightId
                _track.value = f?.let { snap -> service.samples(snap.flightId).filter { it.flying }.map { LatLng(it.lat, it.lng) } } ?: emptyList()
                savedRef = null
            }
            mission.load(f?.flightId, f?.plan?.mission)
            val inProgress = f != null && flightInProgress(f)
            if (inProgress && !fgsRunning) FlightForegroundService.start(context)
            if (!inProgress && fgsRunning && service.pendingFlights() > 0) SyncJobService.schedule(context)
            fgsRunning = inProgress
            s.lastState?.takeIf { it.isFlying && it.position.isValid }?.let { st ->
                _track.value = (_track.value + st.position).takeLast(7_200)
            }
            val ref = f?.takeoffReference
            if (f != null && ref != null && ref !== savedRef) {
                savedRef = ref
                val name = f.plan?.siteName?.takeIf { it.isNotBlank() }
                val at = s.lastState?.home?.takeIf { it.isValid } ?: f.plan?.area?.let { a -> LatLng(a.map { it.lat }.average(), a.map { it.lng }.average()) }
                if (name != null && at != null) sites.upsert(Site(name, at, GroundEstimate(ref.chosen.ellipsoidM, ref.chosen.sigmaM, ref.chosen.source), clock.nowMs()))
            }
            delay(1_000)
        }
    }

    /** DJI's own GEO zones near the aircraft, refreshed every minute while connected. */
    private fun watchAircraftZones() = scope.launch {
        while (isActive) {
            if (adapter.link.value is LinkState.Connected) runCatching { adapter.nearbyRestrictions() }.onSuccess { _aircraftZones.value = it }
            delay(60_000)
        }
    }

    /** Blender zones around where we are: on start, then whenever older than 6 h and online. */
    private fun keepZonesFresh() = scope.launch {
        delay(5_000)
        while (isActive) {
            val age = zones.ageMs()
            val here = currentPosition()
            if (here != null && provisioning.value != null && service.status.value.online && (age == null || age > 6 * 3_600_000L)) {
                zones.refresh(Geo.bbox(Geo.circle(here, 2_000.0)))
            }
            delay(5 * 60_000L)
        }
    }

    /** Phase changes, link changes and sync results to logcat (`adb logcat -s OpenUTMFlight`): field debugging without a UI. */
    private fun logTransitions() = scope.launch {
        var last = ""
        service.status.collect { s ->
            val f = s.activeFlight
            val line = "link=${s.link::class.simpleName} stale=${s.telemetryStale} online=${s.online} " +
                "flight=${f?.flightId?.take(8)} phase=${f?.phase} decl=${f?.declarationId} " +
                "sync=${s.lastSync?.status} +${s.lastSync?.uploadedSamples ?: 0} ${s.lastSync?.message ?: ""}"
            if (line != last) {
                Log.i(TAG, line)
                last = line
            }
        }
    }

    companion object {
        const val TAG = "OpenUTMFlight"
    }
}

private fun Location.toFix() = GnssFix(
    gnssTimeMs = time,
    deviceTimeMs = System.currentTimeMillis(),
    lat = latitude,
    lng = longitude,
    ellipsoidM = if (hasAltitude()) altitude else null, // Android: metres above the WGS-84 ellipsoid
    verticalAccuracyM = if (Build.VERSION.SDK_INT >= 26 && hasVerticalAccuracy()) verticalAccuracyMeters.toDouble() else null,
)
