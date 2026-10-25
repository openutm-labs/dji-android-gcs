package org.openutm.djiflight.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openutm.djiflight.app.map.MapController
import org.openutm.djiflight.app.ui.ActionButton
import org.openutm.djiflight.app.ui.Kind
import org.openutm.djiflight.app.ui.Pill
import org.openutm.djiflight.app.ui.Sheet
import org.openutm.djiflight.app.ui.buttonBackground
import org.openutm.djiflight.app.ui.color
import org.openutm.djiflight.app.ui.dp
import org.openutm.djiflight.app.ui.immersive
import org.openutm.djiflight.app.ui.rounded
import org.openutm.djiflight.app.ui.setIfChanged
import org.openutm.djiflight.app.ui.show
import org.openutm.djiflight.app.ui.tabular
import org.openutm.djiflight.app.ui.text
import org.openutm.djiflight.core.PhotoStats
import org.openutm.djiflight.core.adapter.LinkState
import org.openutm.djiflight.core.adapter.MissionSupport
import org.openutm.djiflight.core.adapter.RcLostAction
import org.openutm.djiflight.core.advisory.AdvisorInput
import org.openutm.djiflight.core.advisory.Advisory
import org.openutm.djiflight.core.advisory.FlightAdvisor
import org.openutm.djiflight.core.advisory.Level
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.imagery.ImageryHandoff
import org.openutm.djiflight.core.mission.MissionPhase
import org.openutm.djiflight.core.mission.MissionUi
import org.openutm.djiflight.core.provisioning.DeviceProvisioning
import org.openutm.djiflight.core.session.FlightSnapshot
import org.openutm.djiflight.core.session.Phase
import org.openutm.djiflight.core.sync.SyncStatus
import java.text.DateFormat
import java.util.Date
import kotlin.math.hypot

/**
 * The flying screen (landscape, RC screens), laid out as DJI's own apps are (docs/12, The app):
 * a status bar on top, flight actions down the left in three fixed slots, camera controls on the
 * right inside the camera view, telemetry along the bottom, and the camera or map inset at the
 * bottom left (tap it to swap). Nothing scrolls and nothing moves. Redraws at most twice a second.
 */
class FlyActivity : Activity() {
    private val ui = MainScope()
    private val graph get() = (application as FlightApp).graph
    private lateinit var map: MapController
    private lateinit var cameraView: View
    private lateinit var video: VideoPreview
    private lateinit var mapPrimary: FrameLayout
    private lateinit var primary: FrameLayout
    private lateinit var inset: FrameLayout
    private lateinit var videoLabel: TextView
    private lateinit var warnings: LinearLayout
    private lateinit var hudBottom: LinearLayout
    private lateinit var missionBar: View
    private lateinit var missionText: TextView
    private lateinit var missionProgress: ProgressBar
    private lateinit var recenter: ImageView
    private var mapIsPrimary = false
    /** Sits over the inset: the map or video inside would swallow the tap that swaps them. */
    private lateinit var swapCatcher: View
    private var centeredOn: String? = null
    private var userMovedMap = false

    // Top bar
    /** v4 `dji`: DJI's own status widgets sit in the top bar (and open DJI's pre-flight checklist). */
    private var hasVendorStatus = false
    private var aircraftPill: Pill? = null
    private val utmPill by lazy { Pill(this, 14f) }
    private var gnssChip: Pill? = null
    private var batteryChip: Pill? = null
    private var modeChip: Pill? = null

    // Rail
    private lateinit var railPrimary: ActionButton
    private lateinit var railSecondary: ActionButton
    private lateinit var railPreflight: ActionButton
    private var primaryAction: (() -> Unit)? = null
    private var secondaryAction: (() -> Unit)? = null

    // Telemetry: label → value, unit
    private val cells = LinkedHashMap<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_fly)
        primary = findViewById(R.id.primary)
        mapPrimary = FrameLayout(this)
        findViewById<FrameLayout>(R.id.stage).addView(mapPrimary, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        inset = findViewById(R.id.inset)
        videoLabel = findViewById(R.id.videoLabel)
        warnings = findViewById(R.id.warnings)
        hudBottom = findViewById(R.id.hudBottom)
        missionBar = findViewById(R.id.missionBar)
        missionText = findViewById(R.id.missionText)
        missionProgress = findViewById(R.id.missionProgress)
        recenter = findViewById(R.id.btnRecenter)

        video = graph.platform.video(this)
        cameraView = video.createView(this) ?: View(this).apply { setBackgroundColor(Color.BLACK) }
        map = MapController(this, graph.maps, bottomClearanceDp = 110) { graph.provisioning.value?.onlineMap }
        mapIsPrimary = !video.available || savedInstanceState?.getBoolean("mapPrimary") == true
        placeViews()
        map.attachTo(if (mapIsPrimary) mapPrimary else inset, savedInstanceState)
        map.onUserMove { userMovedMap = true }
        swapCatcher = View(this).apply { setOnClickListener { mapIsPrimary = !mapIsPrimary; placeViews() } }
        placeViews()

        buildTopBar()
        buildRail()
        buildHud()
        findViewById<View>(R.id.stage).addOnLayoutChangeListener { stage, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) stage.post { layoutHud() }
        }
        video.onPanelVisibilityChanged(::layoutVideoPanel)

        askPermissions()
        graph.start(this)
        ui.launch { graph.maps.version.collect { if (map.ready) map.reloadStyle() } }
        ui.launch {
            while (isActive) {
                render()
                delay(500)
            }
        }
        if (graph.provisioning.value == null) startActivity(Intent(this, SetupActivity::class.java))
    }

    // --- building

    private fun buildTopBar() {
        findViewById<ImageView>(R.id.btnMenu).apply {
            background = buttonBackground(Kind.QUIET, 22)
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.text))
            setOnClickListener { showMenu() }
        }
        val vendor = graph.platform.statusWidgets(this)
        hasVendorStatus = vendor != null
        val leading = findViewById<FrameLayout>(R.id.statusLeading)
        val trailing = findViewById<FrameLayout>(R.id.statusTrailing)
        if (vendor != null) {
            // The vendor's own sizes (DJI's widgets don't size themselves), centred in the bar.
            fun place(v: View) = FrameLayout.LayoutParams(v.layoutParams ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)).apply { gravity = Gravity.CENTER_VERTICAL }
            leading.addView(vendor.leading, place(vendor.leading).apply { width = ViewGroup.LayoutParams.MATCH_PARENT })
            trailing.addView(vendor.trailing, place(vendor.trailing))
        } else {
            aircraftPill = Pill(this, 14f).also { p ->
                p.setOnClickListener { openPreflight() }
                leading.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            }
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            modeChip = Pill(this, 13f).also { chips.addView(it) }
            gnssChip = Pill(this, 13f).also { chips.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }) }
            batteryChip = Pill(this, 14f).also { chips.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }) }
            chips.setOnClickListener { openPreflight() }
            trailing.addView(chips, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        }
        utmPill.setOnClickListener { showUtmDetails() }
        findViewById<FrameLayout>(R.id.utmPillBox).addView(utmPill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
    }

    private fun buildRail() {
        fun slot(id: Int) = findViewById<FrameLayout>(id)
        railPrimary = ActionButton(this, vertical = true).apply { label.textSize = 14f }
        railSecondary = ActionButton(this, vertical = true)
        railPreflight = ActionButton(this, vertical = true).set(getString(R.string.fly_rail_preflight), R.drawable.ic_preflight, Kind.SECONDARY)
        slot(R.id.slotPrimary).addView(railPrimary, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        slot(R.id.slotSecondary).addView(railSecondary, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        slot(R.id.slotPreflight).addView(railPreflight, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        railPrimary.setOnClickListener { primaryAction?.invoke() }
        railSecondary.setOnClickListener { secondaryAction?.invoke() }
        railPreflight.setOnClickListener { openPreflight() }
    }

    private fun buildHud() {
        val strip = findViewById<LinearLayout>(R.id.telemetry)
        strip.background = rounded(color(R.color.scrim), dp(8).toFloat())
        missionBar.background = rounded(color(R.color.scrim), dp(8).toFloat())
        missionProgress.progressDrawable = LayerDrawable(
            arrayOf(rounded(0x40FFFFFF, dp(4).toFloat()), ClipDrawable(rounded(color(R.color.accent), dp(4).toFloat()), Gravity.START, ClipDrawable.HORIZONTAL)),
        ).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
        // Key (for renderTelemetry), label, unit
        for ((key, label, unit) in listOf(
            Triple("H", R.string.fly_tel_h, R.string.fly_unit_m), Triple("D", R.string.fly_tel_d, R.string.fly_unit_m),
            Triple("H.S", R.string.fly_tel_hs, R.string.fly_unit_mps), Triple("V.S", R.string.fly_tel_vs, R.string.fly_unit_mps),
        )) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // Fixed width per readout, so the strip doesn't change size as the numbers do.
                minimumWidth = dp(if (unit == R.string.fly_unit_m) 84 else 92)
                setPadding(dp(10), 0, dp(6), 0)
                addView(text(11f, R.color.text2, bold = true).apply { text = this@FlyActivity.getString(label); letterSpacing = 0.06f })
                val row = LinearLayout(this@FlyActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
                val v = text(22f, bold = true).apply { tabular(); text = "—" }
                row.addView(v)
                row.addView(text(12f, R.color.text2).apply { text = " ${this@FlyActivity.getString(unit)}"; setPadding(0, 0, 0, dp(2)) })
                addView(row)
                cells[key] = v
            }
            strip.addView(cell)
        }
        recenter.background = rounded(color(R.color.scrim), dp(26).toFloat())
        recenter.imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.text))
        recenter.setOnClickListener {
            userMovedMap = false
            centeredOn = null
        }
    }

    private fun placeViews() {
        // Keep the SDK tree attached: v4 panels unsubscribe permanently on detach.
        // Resize its stable host for the inset instead of reparenting the widgets.
        if (cameraView.parent == null) primary.addView(cameraView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        video.setCompact(mapIsPrimary)
        if (map.view.parent != null) map.moveTo(if (mapIsPrimary) mapPrimary else inset)
        if (::swapCatcher.isInitialized) {
            (swapCatcher.parent as? FrameLayout)?.removeView(swapCatcher)
            inset.addView(swapCatcher, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        // No camera in this build (sim): no inset to swap with; the map takes the whole stage.
        inset.show(video.available)
        inset.bringToFront()
        primary.show(video.available)
        inset.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), Color.WHITE)
        }
        videoLabel.text = getString(video.labelRes)
        videoLabel.show(!video.available && !mapIsPrimary)
        warnings.layoutParams = (warnings.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = dp(8 + if (!mapIsPrimary) video.overlayTopDp else 0)
            // Reserve the left edge for DJI's take-off/RTH controls.
            marginStart = dp(8 + if (!mapIsPrimary) video.overlayStartDp else 0)
        }
        if (::hudBottom.isInitialized) layoutHud()
    }

    /** A settings/checklist panel needs the full camera area, even when opened from the top bar. */
    private fun layoutVideoPanel(open: Boolean) {
        if (open && mapIsPrimary && video.available) {
            mapIsPrimary = false
            placeViews()
        }
        warnings.show(!open)
        inset.show(video.available && !open)
        hudBottom.show(!open)
        recenter.show(!open && mapIsPrimary && userMovedMap)
    }

    /** The telemetry strip sits to the right of the inset, or at the left edge without one. */
    private fun layoutHud() {
        val stage = findViewById<View>(R.id.stage)
        if (stage.width <= 0 || stage.height <= 0) { stage.post { layoutHud() }; return }
        val w = stage.width
        val insetW = (w * 0.3f).toInt().coerceIn(dp(150), dp(240))
        inset.layoutParams = (inset.layoutParams as FrameLayout.LayoutParams).apply { width = insetW; height = insetW * 10 / 16 }
        primary.layoutParams = FrameLayout.LayoutParams(
            if (mapIsPrimary && video.available) insetW - dp(4) else ViewGroup.LayoutParams.MATCH_PARENT,
            if (mapIsPrimary && video.available) insetW * 10 / 16 - dp(4) else ViewGroup.LayoutParams.MATCH_PARENT,
            if (mapIsPrimary) Gravity.BOTTOM or Gravity.START else Gravity.TOP or Gravity.START,
        ).apply { if (mapIsPrimary && video.available) { marginStart = dp(10); bottomMargin = dp(10) } }
        // DJI widgets cache scaled child dimensions during measurement. Keep the SDK root at
        // full stage size even inside the inset, and scale its picture instead of remeasuring it small.
        val stageH = stage.height
        cameraView.layoutParams = FrameLayout.LayoutParams(w, stageH)
        val compact = mapIsPrimary && video.available
        val previewW = insetW - dp(4)
        val previewH = insetW * 10 / 16 - dp(4)
        val scale = if (compact) minOf(previewW.toFloat() / w, previewH.toFloat() / stageH) else 1f
        cameraView.pivotX = 0f
        cameraView.pivotY = 0f
        cameraView.scaleX = scale
        cameraView.scaleY = scale
        cameraView.translationX = if (compact) (previewW - w * scale) / 2 else 0f
        cameraView.translationY = if (compact) (previewH - stageH * scale) / 2 else 0f
        // Reassign so Android resolves the changed start margin even after the first layout.
        hudBottom.layoutParams = (hudBottom.layoutParams as FrameLayout.LayoutParams).apply {
            marginStart = if (video.available) insetW + dp(16) else dp(8)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            immersive()
            layoutHud()
        }
    }

    // --- rendering

    private var lastLinkConnected = false
    private var handoffOffered: String? = null

    private fun render() {
        val s = graph.service.status.value
        // After a reconnect (battery swap) the SDK may hand out a new video feed: hook it again.
        val connected = s.link is LinkState.Connected
        if (connected && !lastLinkConnected) video.resubscribe()
        lastLinkConnected = connected
        val f = s.activeFlight
        val st = s.lastState
        val p = graph.provisioning.value
        val m = graph.mission.ui.value

        // Map overlays
        map.setArea(f?.plan?.area)
        map.setSurvey(f?.plan?.surveyArea)
        map.setMission(f?.plan?.mission?.waypoints?.map { it.position })
        map.setStart(f?.plan?.mission?.waypoints?.firstOrNull { !it.transit }?.position)
        map.setZones(graph.zones.index.value?.zones.orEmpty())
        map.setAircraftZones(graph.aircraftZones.value)
        map.setTrack(graph.track.value)
        map.setAircraft(st?.position, st?.yawDeg ?: 0.0)
        map.setHome(st?.home)
        map.setPilot(graph.deviceFix.value?.let { LatLng(it.lat, it.lng) })
        val focus = st?.position?.takeIf { it.isValid } ?: f?.plan?.area?.firstOrNull() ?: graph.currentPosition()
        val focusKey = f?.flightId ?: "none"
        if (focus != null && map.ready && centeredOn != focusKey) {
            centeredOn = focusKey
            userMovedMap = false
            f?.plan?.area?.let { map.fit(it + listOfNotNull(st?.position?.takeIf { p2 -> p2.isValid }), dp(24), dp(64), dp(72), dp(150)) } ?: map.center(focus)
        }
        recenter.show(mapIsPrimary && userMovedMap)

        renderTopBar(s, f, p)
        renderTelemetry(st)
        renderMission(f, m)

        val pending = f?.let { graph.service.pendingSamples(it.flightId) } ?: 0
        val adv = FlightAdvisor.advise(
            AdvisorInput(
                f, st, s.telemetryStale, graph.zones.index.value, graph.aircraftZones.value, graph.clock.nowMs(), pending, s.online,
                checklistRequired = p?.checklist?.isNotEmpty() == true, aircraft = graph.adapter.aircraftSetup()?.status?.value,
                groundBelowTakeoffM = groundBelowTakeoff(st),
            ),
        )
        showWarnings(adv)
        renderRail(f, m, p, adv)
        offerHandoff(f, p)
        layoutVideoPanel(video.panelOpen)
    }

    /** Terrain model: how far the ground under the aircraft is below its home point; null without a terrain file. */
    private fun groundBelowTakeoff(st: org.openutm.djiflight.core.adapter.DroneState?): Double? {
        if (st == null || !st.isFlying) return null
        val home = st.home?.takeIf { it.isValid } ?: return null
        val pos = st.position.takeIf { it.isValid } ?: return null
        val t = graph.terrain ?: return null
        return runCatching { (t.elevationM(home) ?: return null) - (t.elevationM(pos) ?: return null) }.getOrNull()
    }

    private fun renderTopBar(s: org.openutm.djiflight.core.ServiceStatus, f: FlightSnapshot?, p: DeviceProvisioning?) {
        val st = s.lastState
        aircraftPill?.let { pill ->
            when {
                s.link is LinkState.Connecting -> pill.set(getString(R.string.fly_aircraft_connecting), Pill.Tone.NEUTRAL)
                s.link !is LinkState.Connected -> pill.set(getString(R.string.fly_aircraft_not_connected), Pill.Tone.NEUTRAL)
                s.telemetryStale -> pill.set(getString(R.string.fly_aircraft_no_data), Pill.Tone.CRIT)
                st?.isFlying == true -> pill.set(getString(R.string.fly_aircraft_in_flight), Pill.Tone.OK)
                st?.motorsOn == true -> pill.set(getString(R.string.fly_aircraft_motors_on), Pill.Tone.WARN)
                (st?.gpsLevel ?: 5) <= 2 -> pill.set(getString(R.string.fly_aircraft_weak_gnss), Pill.Tone.WARN)
                else -> pill.set(getString(R.string.fly_aircraft_ready), Pill.Tone.OK)
            }
        }
        modeChip?.let { c -> c.show(st?.flightMode != null); c.set(st?.flightMode ?: "", Pill.Tone.NEUTRAL) }
        gnssChip?.let { c ->
            val lvl = st?.gpsLevel
            c.set(getString(R.string.fly_chip_gnss, st?.satellites?.toString() ?: "—"), when { lvl == null -> Pill.Tone.NEUTRAL; lvl >= 4 -> Pill.Tone.NEUTRAL; lvl >= 3 -> Pill.Tone.WARN; else -> Pill.Tone.CRIT })
        }
        batteryChip?.let { c ->
            val b = st?.batteryPercent
            c.set(b?.let { getString(R.string.fly_chip_battery, it) } ?: getString(R.string.fly_chip_battery_none), when { b == null -> Pill.Tone.NEUTRAL; b <= 20 -> Pill.Tone.CRIT; b <= 30 -> Pill.Tone.WARN; else -> Pill.Tone.NEUTRAL })
        }
        val (text, tone) = utmStatus(s, f, p)
        utmPill.set(text, tone)
    }

    /** The UTM side in the pilot's words; the identifiers and numbers are one tap away. */
    private fun utmStatus(s: org.openutm.djiflight.core.ServiceStatus, f: FlightSnapshot?, p: DeviceProvisioning?): Pair<String, Pill.Tone> {
        if (p == null) return getString(R.string.fly_utm_not_set_up) to Pill.Tone.WARN
        if (f?.plan == null) return getString(R.string.fly_utm_no_flight) to Pill.Tone.NEUTRAL
        val pending = graph.service.pendingSamples(f.flightId)
        val now = graph.clock.nowMs()
        if (f.serverClosed && graph.service.requiresArchive() && !f.archiveComplete) return getString(R.string.flights_status_uploading) to Pill.Tone.WARN
        return when (f.phase) {
            Phase.PLANNED -> if (f.declareRejected != null) getString(R.string.fly_utm_refused) to Pill.Tone.CRIT
                else if (!s.online) getString(R.string.fly_utm_not_declared_offline) to Pill.Tone.WARN else getString(R.string.fly_utm_declaring) to Pill.Tone.WARN
            Phase.DECLARED -> f.declarationPlan?.startMs?.takeIf { it > now + 60_000 }?.let { getString(R.string.fly_utm_declared_from, clock(it)) to Pill.Tone.OK } ?: (getString(R.string.fly_utm_declared) to Pill.Tone.OK)
            Phase.ACTIVE -> when {
                !s.online && pending > 0 -> getString(R.string.fly_utm_offline_stored, pending) to Pill.Tone.WARN
                f.declarationId == null -> getString(R.string.fly_utm_not_declared) to Pill.Tone.WARN
                else -> getString(R.string.fly_utm_live) to Pill.Tone.OK
            }
            Phase.LANDED -> if (pending > LIVE_BACKLOG || !s.online && pending > 0) getString(R.string.fly_utm_landed_pending, pending) to Pill.Tone.WARN else getString(R.string.fly_utm_landed_uploaded) to Pill.Tone.OK
            Phase.CLOSED -> getString(R.string.fly_utm_closed) to Pill.Tone.OK
            Phase.EXPIRED_UNSYNCED -> getString(R.string.fly_utm_window_closed) to Pill.Tone.CRIT
            Phase.UNDECLARED -> getString(R.string.fly_utm_undeclared) to Pill.Tone.CRIT
            Phase.CANCELLED -> getString(R.string.fly_utm_cancelled) to Pill.Tone.NEUTRAL
        }
    }

    private fun renderTelemetry(st: org.openutm.djiflight.core.adapter.DroneState?) {
        val dist = st?.home?.takeIf { it.isValid && st.position.isValid }?.let { Geo.distanceM(it, st.position) }
        cells["H"]?.setIfChanged(st?.heightAboveTakeoffM?.takeIf { it.isFinite() }?.let { "%.1f".format(it).replace("-0.0", "0.0") } ?: "—")
        cells["D"]?.setIfChanged(dist?.let { "%.0f".format(it) } ?: "—")
        cells["H.S"]?.setIfChanged(st?.let { "%.1f".format(hypot(it.velocityNorthMps, it.velocityEastMps)) } ?: "—")
        cells["V.S"]?.setIfChanged(st?.let { "%.1f".format(-it.velocityDownMps).replace("-0.0", "0.0") } ?: "—")
    }

    private fun renderMission(f: FlightSnapshot?, m: MissionUi) {
        val has = f?.plan?.mission != null && graph.flightInProgress(f)
        missionBar.show(has, keepSpace = true)
        if (!has) return
        val caps = graph.adapter.capabilities.value
        val noMissions = graph.service.status.value.link is LinkState.Connected && caps?.missionSupport == MissionSupport.NONE
        val total = m.waypoints.coerceAtLeast(1)
        val text = when {
            noMissions -> getString(R.string.fly_mission_unsupported, caps?.model)
            m.phase == MissionPhase.RUNNING -> if (m.parts > 1) getString(R.string.fly_mission_running_part, m.waypoint, m.waypoints, m.part, m.parts)
                else getString(R.string.fly_mission_running, m.waypoint, m.waypoints)
            m.phase == MissionPhase.PAUSED -> getString(R.string.fly_mission_paused, m.waypoint, m.waypoints)
            m.phase == MissionPhase.UPLOADING -> getString(R.string.fly_mission_uploading)
            m.phase == MissionPhase.READY -> if (m.waypoint > 1) getString(R.string.fly_mission_uploaded_from, m.waypoint, m.waypoints) else getString(R.string.fly_mission_uploaded, m.waypoints)
            m.phase == MissionPhase.READY_TO_UPLOAD -> getString(R.string.fly_mission_to_upload, m.waypoints)
            m.phase == MissionPhase.PART_DONE -> getString(R.string.fly_mission_part_done, m.part, m.parts)
            m.phase == MissionPhase.INTERRUPTED -> getString(R.string.fly_mission_stopped_at, m.resumeFrom ?: 1, m.waypoints)
            m.phase == MissionPhase.DONE -> getString(R.string.fly_mission_complete)
            m.phase == MissionPhase.FAILED -> getString(R.string.fly_mission_failed, m.message)
            else -> getString(R.string.fly_mission)
        }
        missionText.setIfChanged(text)
        val done = when (m.phase) {
            MissionPhase.DONE -> total
            MissionPhase.INTERRUPTED -> ((m.resumeFrom ?: 1) - 1).coerceAtLeast(0)
            else -> (m.waypoint - 1).coerceAtLeast(0)
        }
        missionProgress.progress = (1000 * done / total)
    }

    private var shownWarnings = emptyList<Advisory>()
    private var allAdvice = emptyList<Advisory>()

    /** Only warnings and critical messages sit over the view; notes are in the "+N" list. */
    private fun showWarnings(adv: List<Advisory>) {
        allAdvice = adv
        val loud = adv.filter { it.level != Level.INFO }
        if (loud == shownWarnings && warnings.childCount > 0 == loud.isNotEmpty()) return
        shownWarnings = loud
        warnings.removeAllViews()
        val top = loud.firstOrNull() ?: return
        val crit = top.level == Level.CRITICAL
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(color(if (crit) R.color.crit else R.color.warn), dp(8).toFloat())
            setPadding(dp(10), dp(8), dp(12), dp(8))
            val fg = color(if (crit) R.color.text else R.color.on_warn)
            addView(ImageView(this@FlyActivity).apply { setImageResource(R.drawable.ic_warning); imageTintList = android.content.res.ColorStateList.valueOf(fg) }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
            addView(text(16f, bold = true).apply { text = advisoryText(top); setTextColor(fg); maxLines = 2; maxWidth = dp(420) })
            if (adv.size > 1) addView(text(14f, bold = true).apply {
                text = this@FlyActivity.getString(R.string.fly_warnings_more, adv.size - 1)
                setTextColor(fg)
                background = rounded(0x33000000, dp(12).toFloat())
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
            setOnClickListener { showAllWarnings() }
        }
        warnings.addView(banner)
    }

    private fun showAllWarnings() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (allAdvice.isEmpty()) body.addView(Sheet.note(this, getString(R.string.fly_warnings_none), R.color.text2))
        allAdvice.forEach { a ->
            body.addView(text(16f, if (a.level == Level.WARNING) R.color.on_warn else R.color.text, bold = a.level != Level.INFO).apply {
                text = advisoryText(a)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(color(when (a.level) { Level.CRITICAL -> R.color.crit; Level.WARNING -> R.color.warn; Level.INFO -> R.color.surface2 }), dp(8).toFloat())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            })
        }
        Sheet(this).title(getString(R.string.fly_warnings_title)).body(body).button(getString(R.string.fly_close)).show()
    }

    // --- the rail: one obvious next step, Stop/Cancel, Pre-flight

    private fun renderRail(f: FlightSnapshot?, m: MissionUi, p: DeviceProvisioning?, adv: List<Advisory>) {
        val st = graph.service.status.value.lastState
        val inProgress = f != null && graph.flightInProgress(f)
        val items = p?.checklist.orEmpty()
        val checklistDue = inProgress && items.isNotEmpty() && f?.checklist == null && f?.recordingClosed == false
        val checklistLabel = getString(R.string.fly_rail_checklist, FlyState.ticked(items.size), items.size)
        val flying = st?.isFlying == true || f?.airborne == true
        val canRun = graph.mission.canRun

        fun primary(label: String, icon: Int?, kind: Kind, enabled: Boolean = true, action: (() -> Unit)?) {
            railPrimary.set(label, icon, kind)
            railPrimary.isEnabled = enabled
            railPrimary.isClickable = action != null
            if (action == null) railPrimary.alpha = 1f
            primaryAction = action
        }
        fun secondary(label: String?, icon: Int? = null, kind: Kind = Kind.SECONDARY, action: (() -> Unit)? = null) {
            railSecondary.show(label != null, keepSpace = true)
            if (label != null) railSecondary.set(label, icon, kind)
            secondaryAction = action
        }

        when {
            p == null -> { primary(getString(R.string.fly_rail_set_up), R.drawable.ic_sliders, Kind.PRIMARY) { startActivity(Intent(this, SetupActivity::class.java)) }; secondary(null) }
            !inProgress -> {
                val plan = { startActivity(Intent(this, PlanActivity::class.java)) }
                if (f != null && handoffPending(f, p)) {
                    primary(getString(R.string.fly_rail_photos), R.drawable.ic_photo, Kind.PRIMARY) { openHandoff(f) }
                    secondary(getString(R.string.fly_rail_plan), R.drawable.ic_plan) { plan() }
                } else {
                    primary(getString(R.string.fly_rail_plan), R.drawable.ic_plan, Kind.PRIMARY) { plan() }
                    secondary(null)
                }
            }
            f!!.plan?.mission != null && m.phase in MISSION_ACTIVE -> {
                when (m.phase) {
                    MissionPhase.RUNNING -> primary(getString(R.string.fly_rail_pause), R.drawable.ic_pause, Kind.WARN, canRun) { graph.mission.pauseOrResume() }
                    MissionPhase.PAUSED -> primary(getString(R.string.fly_rail_resume), R.drawable.ic_play, Kind.PRIMARY, canRun) { graph.mission.pauseOrResume() }
                    MissionPhase.UPLOADING -> primary(getString(R.string.fly_rail_uploading), R.drawable.ic_upload, Kind.SECONDARY, false, null)
                    MissionPhase.READY -> if (checklistDue) primary(checklistLabel, R.drawable.ic_preflight, Kind.PRIMARY) { openPreflight() }
                        else primary(getString(R.string.fly_rail_go), R.drawable.ic_play, Kind.PRIMARY, canRun) { askGo(m) }
                    MissionPhase.READY_TO_UPLOAD -> primary(getString(R.string.fly_rail_upload), R.drawable.ic_upload, Kind.PRIMARY, canRun) { graph.mission.upload() }
                    MissionPhase.FAILED -> primary(getString(R.string.fly_rail_retry_upload), R.drawable.ic_upload, Kind.PRIMARY, canRun) { graph.mission.upload() }
                    MissionPhase.PART_DONE -> primary(getString(R.string.fly_rail_upload_part, m.part + 1), R.drawable.ic_upload, Kind.PRIMARY, canRun) { graph.mission.upload() }
                    MissionPhase.INTERRUPTED -> primary(getString(R.string.fly_rail_resume_ask), R.drawable.ic_play, Kind.PRIMARY, canRun) { askResume(m) }
                    else -> Unit
                }
                when {
                    m.phase == MissionPhase.RUNNING || m.phase == MissionPhase.PAUSED -> secondary(getString(R.string.fly_rail_stop), R.drawable.ic_stop, Kind.DANGER) { askStop(m) }
                    !f.everFlown -> secondary(getString(R.string.fly_rail_cancel), R.drawable.ic_close) { askCancel() }
                    f.everFlown && !flying -> secondary(getString(R.string.fly_rail_finish), R.drawable.ic_flag) { askFinish() }
                    else -> secondary(null)
                }
            }
            else -> {
                when {
                    f.everFlown && !flying -> primary(getString(R.string.fly_rail_finish), R.drawable.ic_flag, Kind.PRIMARY) { askFinish() }
                    flying -> primary(getString(R.string.fly_rail_flying), R.drawable.ic_aircraft, Kind.QUIET, true, null)
                    checklistDue -> primary(checklistLabel, R.drawable.ic_preflight, Kind.PRIMARY) { openPreflight() }
                    else -> primary(getString(R.string.fly_rail_take_off), R.drawable.ic_aircraft, Kind.QUIET, true, null)
                }
                secondary(if (!f.everFlown) getString(R.string.fly_rail_cancel) else null, R.drawable.ic_close) { askCancel() }
            }
        }
        railPrimary.label.maxLines = 2
        // Pre-flight turns amber while one of its checks needs the pilot.
        val aircraftIssue = adv.any { it.code in PREFLIGHT_CODES && it.level != Level.INFO }
        railPreflight.show(p != null, keepSpace = true)
        railPreflight.set(getString(R.string.fly_rail_preflight), R.drawable.ic_preflight, if (aircraftIssue || (checklistDue && railPrimary.label.text != checklistLabel)) Kind.WARN else Kind.SECONDARY)
    }

    // --- sheets

    private fun openPreflight() = PreflightPanel(this, graph, vendorChecklistNote = hasVendorStatus).show()

    private fun showMenu() {
        val pending = graph.service.pendingFlights()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var sheet: Sheet
        var row: LinearLayout? = null
        // Two per row, so all of them fit on a 360 dp-high controller screen without scrolling.
        fun item(label: String, icon: Int, run: () -> Unit) {
            val r = row?.takeIf { it.childCount < 2 } ?: LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also {
                row = it
                list.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply { bottomMargin = dp(8) })
            }
            r.addView(ActionButton(this).set(label, icon, Kind.SECONDARY).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                this.label.maxLines = 1
                this.label.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { sheet.dismiss(); run() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (r.childCount > 0) marginStart = dp(8) })
        }
        item(getString(R.string.fly_menu_flights), R.drawable.ic_list) { startActivity(Intent(this, FlightsActivity::class.java)) }
        item(getString(R.string.fly_menu_plan), R.drawable.ic_plan) { startActivity(Intent(this, PlanActivity::class.java)) }
        item(getString(R.string.fly_menu_preflight), R.drawable.ic_preflight) { openPreflight() }
        item(getString(R.string.fly_menu_setup), R.drawable.ic_sliders) { startActivity(Intent(this, SetupActivity::class.java)) }
        item(if (pending > 0) getString(R.string.fly_menu_upload_waiting, pending) else getString(R.string.fly_upload_now), R.drawable.ic_sync) {
            graph.service.syncSoon()
            Toast.makeText(this, getString(R.string.fly_menu_uploading_toast), Toast.LENGTH_SHORT).show()
        }
        item(getString(R.string.network_status_title), R.drawable.ic_sync) { showNetworkStatus() }
        item(getString(R.string.fly_menu_map_key), R.drawable.ic_map) { showMapKey() }
        list.addView(Sheet.note(this, getString(R.string.fly_menu_version, getString(R.string.app_name), graph.platform.versionName, graph.platform.extraStatus(graph.adapter) ?: graph.platform.label)))
        sheet = Sheet(this).title(getString(R.string.fly_menu_title)).body(list).button(getString(R.string.fly_close))
        sheet.show()
    }

    private fun showMapKey() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun key(swatch: Int, label: String) = body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(View(this@FlyActivity).apply { background = rounded(swatch, dp(4).toFloat()) }, LinearLayout.LayoutParams(dp(28), dp(18)).apply { marginEnd = dp(14) })
            addView(text(16f).apply { text = label })
        })
        key(0xFF1E88E5.toInt(), getString(R.string.fly_mapkey_area))
        key(0xFFE53935.toInt(), getString(R.string.fly_mapkey_zone))
        key(0xFFFB8C00.toInt(), getString(R.string.fly_mapkey_geo_zone))
        key(0xFF26C6DA.toInt(), getString(R.string.fly_mapkey_mission))
        key(0xFFFFFFFF.toInt(), getString(R.string.fly_mapkey_survey))
        key(0xFFFDD835.toInt(), getString(R.string.fly_mapkey_track))
        key(0xFFF4511E.toInt(), getString(R.string.fly_mapkey_aircraft))
        key(0xFF2E9E4F.toInt(), getString(R.string.fly_mapkey_home))
        key(0xFF00ACC1.toInt(), getString(R.string.fly_mapkey_pilot))
        graph.maps.attributions().takeIf { it.isNotEmpty() }?.let { body.addView(Sheet.note(this, getString(R.string.fly_mapkey_credits, it.joinToString(" · ")))) }
        Sheet(this).title(getString(R.string.fly_mapkey_title)).body(body).button(getString(R.string.fly_close)).show()
    }

    private fun showUtmDetails() {
        val s = graph.service.status.value
        val f = s.activeFlight
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(l: String, v: String) = body.addView(Sheet.row(this, l, v))
        val (status, _) = utmStatus(s, f, graph.provisioning.value)
        row(getString(R.string.fly_utm_row_status), status)
        row(getString(R.string.fly_utm_row_network), if (s.online) getString(R.string.fly_utm_online) else getString(R.string.fly_utm_offline))
        if (f?.plan != null) {
            row(getString(R.string.fly_utm_row_declaration), f.declarationId?.take(13) ?: getString(R.string.fly_utm_declaration_not_yet))
            f.declarationPlan?.let { d ->
                val t = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                row(getString(R.string.fly_utm_row_window), getString(R.string.fly_utm_window_value, t.format(Date(d.startMs)), t.format(Date(d.endMs))))
                row(getString(R.string.fly_utm_row_height_band), getString(R.string.fly_utm_height_band_value, d.minAltW84M, d.maxAltW84M))
            }
            f.altitudeReference?.let { row(getString(R.string.fly_utm_row_takeoff_height), getString(R.string.fly_utm_takeoff_height_value, it.ellipsoidM, it.sigmaM, altitudeSourceText(it.source))) }
            row(getString(R.string.fly_utm_row_samples_waiting), "${graph.service.pendingSamples(f.flightId)}")
        }
        s.lastSync?.let { o ->
            row(getString(R.string.fly_utm_row_last_upload), when (o.status) {
                SyncStatus.UP_TO_DATE -> getString(R.string.fly_utm_sync_up_to_date)
                SyncStatus.IDLE -> getString(R.string.fly_utm_sync_idle)
                SyncStatus.BACKOFF -> getString(R.string.fly_utm_sync_backoff)
                SyncStatus.BLOCKED -> getString(R.string.fly_utm_sync_blocked)
                else -> o.status.name.lowercase().replace('_', ' ')
            })
            o.message?.takeIf { it.isNotBlank() }?.let { body.addView(Sheet.note(this, it)) }
        }
        Sheet(this).title(getString(R.string.fly_utm_title)).body(body).button(getString(R.string.fly_close)).button(getString(R.string.fly_upload_now), Kind.PRIMARY, R.drawable.ic_sync) { graph.service.syncSoon() }.show()
    }

    private fun showNetworkStatus() {
        val s = graph.service.status.value
        val flight = s.activeFlight
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(Sheet.row(this, getString(R.string.setup_row_utm_server), graph.provisioning.value?.blenderUrl ?: getString(R.string.setup_not_set)))
        list.addView(Sheet.row(this, getString(R.string.network_status_connection), getString(if (s.online) R.string.network_status_online else R.string.network_status_offline)))
        list.addView(Sheet.row(this, getString(R.string.network_status_aircraft), getString(if (s.link is LinkState.Connected && !s.telemetryStale && s.lastState != null) R.string.network_status_fresh else R.string.network_status_stale)))
        list.addView(Sheet.row(this, getString(R.string.network_status_declaration), getString(if (flight?.declarationId != null) R.string.network_status_declared else R.string.network_status_not_declared)))
        list.addView(Sheet.row(this, getString(R.string.network_status_pending), flight?.let { graph.service.pendingSamples(it.flightId).toString() } ?: "—"))
        list.addView(Sheet.note(this, getString(R.string.network_status_note)))
        Sheet(this).title(getString(R.string.network_status_title)).body(list).button(getString(R.string.fly_close)).show()
    }

    /** Go: what the aircraft will do, the lab checklist, then slide to start (DJI's convention). */
    private fun askGo(m: MissionUi) {
        val f = graph.service.status.value.activeFlight ?: return
        val mission = f.plan?.mission ?: return
        val setup = graph.adapter.aircraftSetup()?.status?.value?.settings
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val km = mission.waypoints.zipWithNext { a, b -> Geo.distanceM(a.position, b.position) }.sum() / 1000
        body.addView(Sheet.row(this, getString(R.string.fly_go_row_route), if (m.parts > 1) getString(R.string.fly_go_route_value_part, m.waypoints, km, m.part, m.parts)
            else getString(R.string.fly_go_route_value, m.waypoints, km)))
        body.addView(Sheet.row(this, getString(R.string.fly_go_row_altitude), getString(R.string.fly_go_altitude_value, mission.waypoints.filter { !it.transit }.maxOfOrNull { it.heightM } ?: 0.0)))
        setup?.returnHomeHeightM?.let { body.addView(Sheet.row(this, getString(R.string.fly_go_row_rth_height), getString(R.string.fly_go_rth_height_value, it))) }
        body.addView(Sheet.row(this, getString(R.string.fly_go_row_at_end),
            if (m.part < m.parts) getString(R.string.fly_go_hover_next_upload) else when (mission.finishAction) {
                org.openutm.djiflight.core.mission.FinishAction.GO_HOME -> getString(R.string.fly_go_return_home)
                org.openutm.djiflight.core.mission.FinishAction.HOVER -> getString(R.string.fly_go_hover)
                org.openutm.djiflight.core.mission.FinishAction.AUTO_LAND -> getString(R.string.fly_go_land)
                org.openutm.djiflight.core.mission.FinishAction.GO_FIRST_WAYPOINT -> getString(R.string.fly_go_first_waypoint)
            }))
        body.addView(Sheet.row(this, getString(R.string.fly_go_row_rc_lost), when (setup?.rcLostAction) {
            RcLostAction.HOVER -> getString(R.string.fly_go_rc_lost_hover); RcLostAction.LAND -> getString(R.string.fly_go_rc_lost_land)
            RcLostAction.RETURN_HOME -> getString(R.string.fly_go_return_home); null -> getString(R.string.fly_go_rc_lost_unknown)
        }, if (setup?.rcLostAction != null && setup.rcLostAction != RcLostAction.RETURN_HOME) R.color.warn else R.color.text))
        if (graph.adapter.capabilities.value?.missionSupport == org.openutm.djiflight.core.adapter.MissionSupport.VIRTUAL_STICK_EXPERIMENTAL) {
            body.addView(Sheet.note(this, getString(R.string.mini2_experimental_notice)))
        }
        val checklistOk = graph.provisioning.value?.checklist.isNullOrEmpty() || f.checklist != null
        body.addView(Sheet.row(this, getString(R.string.fly_go_row_checklist), if (checklistOk) getString(R.string.fly_go_checklist_done) else getString(R.string.fly_go_checklist_not_done), if (checklistOk) R.color.text else R.color.warn))
        body.addView(Sheet.note(this, getString(R.string.fly_go_note)))
        Sheet(this).title(getString(R.string.fly_go_title)).body(body)
            .slide(getString(R.string.fly_go_slide), enabled = checklistOk) { graph.mission.start() }
            .button(getString(R.string.fly_go_not_now))
            .apply { if (!checklistOk) button(getString(R.string.fly_go_open_checklist), Kind.PRIMARY) { openPreflight() } }
            .show()
    }

    private fun askStop(m: MissionUi) {
        Sheet(this).title(getString(R.string.fly_stop_title))
            .message(getString(R.string.fly_stop_message))
            .button(getString(R.string.fly_stop_keep_flying))
            .button(getString(R.string.fly_stop_confirm), Kind.DANGER, R.drawable.ic_stop) { graph.mission.stop() }
            .show()
    }

    /** After RTH and a battery swap, a Stop, or a disconnect: carry on from the last waypoint reached, or start over. */
    private fun askResume(m: MissionUi) {
        val from = m.resumeFrom ?: 1
        Sheet(this).title(getString(R.string.fly_mission_stopped_at, from, m.waypoints))
            .message(getString(R.string.fly_resume_message, from))
            .button(getString(R.string.fly_resume_start_over)) { graph.mission.restart() }
            .button(getString(R.string.fly_resume_from, from), Kind.PRIMARY, R.drawable.ic_play) { graph.mission.resume() }
            .show()
    }

    private fun askFinish() {
        Sheet(this).title(getString(R.string.fly_finish_title))
            .message(getString(R.string.fly_finish_message))
            .button(getString(R.string.fly_finish_not_yet))
            .button(getString(R.string.fly_finish_confirm), Kind.PRIMARY, R.drawable.ic_flag) { ui.launch { graph.service.finish() } }
            .show()
    }

    private fun askCancel() {
        Sheet(this).title(getString(R.string.fly_cancel_title))
            .message(getString(R.string.fly_cancel_message))
            .button(getString(R.string.fly_cancel_keep))
            .button(getString(R.string.fly_cancel_confirm), Kind.DANGER, R.drawable.ic_close) { ui.launch { graph.service.cancel() } }
            .show()
    }

    // --- photos → the lab's processing service (docs/13, Phase A)

    private var handoffStats: Pair<String, PhotoStats>? = null

    private fun stats(f: FlightSnapshot): PhotoStats =
        handoffStats?.takeIf { it.first == f.flightId }?.second ?: graph.service.photoStats(f.flightId).also { handoffStats = f.flightId to it }

    private fun handoffPending(f: FlightSnapshot, p: DeviceProvisioning?): Boolean =
        p?.imagery != null && f.recordingClosed && f.everFlown && f.imageryHandoff == null && ImageryHandoff.pending(f, stats(f))

    /** Opens once by itself after Finish; afterwards from the rail's Photos button. */
    private fun offerHandoff(f: FlightSnapshot?, p: DeviceProvisioning?) {
        if (f == null || !handoffPending(f, p) || handoffOffered == f.flightId) return
        handoffOffered = f.flightId
        openHandoff(f)
    }

    private fun openHandoff(f: FlightSnapshot) {
        val service = graph.provisioning.value?.imagery ?: return
        val st = stats(f)
        val name = ImageryHandoff.mapName(f)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val span = if (st.firstMs != null && st.lastMs != null) getString(R.string.fly_photos_span, clock(st.firstMs!!), clock(st.lastMs!!)) else ""
        body.addView(text(17f, bold = true).apply { text = this@FlyActivity.getString(R.string.fly_photos_count, st.count, span) })
        body.addView(text(14f, R.color.text2).apply {
            text = service.project?.let { this@FlyActivity.getString(R.string.fly_photos_map_name_in_project, service.service, it) } ?: this@FlyActivity.getString(R.string.fly_photos_map_name_in, service.service)
            setPadding(0, dp(12), 0, dp(4))
        })
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(color(R.color.surface3), dp(8).toFloat())
            setPadding(dp(12), dp(6), dp(6), dp(6))
            addView(text(18f, bold = true).apply { text = name; setTextIsSelectable(true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ActionButton(this@FlyActivity).set(this@FlyActivity.getString(R.string.fly_photos_copy), null, Kind.SECONDARY).apply {
                setOnClickListener {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(this@FlyActivity.getString(R.string.fly_photos_clip_label), name))
                    Toast.makeText(this@FlyActivity, this@FlyActivity.getString(R.string.fly_photos_copied, name), Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(dp(96), dp(48)))
        }
        body.addView(nameRow)
        body.addView(text(15f, R.color.text2).apply {
            text = this@FlyActivity.getString(R.string.fly_photos_steps, service.service)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(12), 0, 0)
        })
        Sheet(this).title(getString(R.string.fly_photos_title, service.service)).body(body)
            .button(getString(R.string.fly_photos_later))
            .button(getString(R.string.fly_photos_not_uploading)) { ui.launch { graph.service.markImageryHandedOff(f.flightId, "none", name, st.count) } }
            .button(getString(R.string.fly_photos_uploaded), Kind.PRIMARY, R.drawable.ic_check) { ui.launch { graph.service.markImageryHandedOff(f.flightId, service.service, name, st.count) } }
            .show()
    }

    private fun clock(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

    private fun askPermissions() {
        val wanted = buildList {
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        graph.startDeviceGnss(this)
    }

    override fun onStart() { super.onStart(); map.onStart() }
    override fun onResume() { super.onResume(); immersive(); map.onResume(); video.onResume() }
    override fun onPause() { video.onPause(); map.onPause(); super.onPause() }
    override fun onStop() { map.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); map.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("mapPrimary", mapIsPrimary)
        map.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        ui.cancel()
        map.onDestroy()
        super.onDestroy()
    }

    private companion object {
        val MISSION_ACTIVE = setOf(
            MissionPhase.READY_TO_UPLOAD, MissionPhase.UPLOADING, MissionPhase.READY, MissionPhase.RUNNING, MissionPhase.PAUSED,
            MissionPhase.PART_DONE, MissionPhase.INTERRUPTED, MissionPhase.FAILED,
        )
        /** Live upload runs every 2 s at 1 sample a second: a few waiting is normal, not a backlog. */
        const val LIVE_BACKLOG = 5L
        val PREFLIGHT_CODES = setOf("RTH_ABOVE_DECLARED", "RC_LOST_NOT_RTH", "COMPASS_ERROR", "IMU_ERROR", "CHECKLIST", "ALTITUDE_CHECK")
    }
}
