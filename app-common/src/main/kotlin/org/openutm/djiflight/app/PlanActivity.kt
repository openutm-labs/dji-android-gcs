package org.openutm.djiflight.app

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openutm.djiflight.app.map.MapController
import org.openutm.djiflight.app.ui.ActionButton
import org.openutm.djiflight.app.ui.Kind
import org.openutm.djiflight.app.ui.Sheet
import org.openutm.djiflight.app.ui.Stepper
import org.openutm.djiflight.app.ui.color
import org.openutm.djiflight.app.ui.dp
import org.openutm.djiflight.app.ui.immersive
import org.openutm.djiflight.app.ui.rounded
import org.openutm.djiflight.app.ui.setIfChanged
import org.openutm.djiflight.app.ui.show
import org.openutm.djiflight.app.ui.tabular
import org.openutm.djiflight.app.ui.text
import org.openutm.djiflight.app.ui.topBar
import org.openutm.djiflight.core.adapter.LinkState
import org.openutm.djiflight.core.adapter.MissionSupport
import org.openutm.djiflight.core.advisory.FlightAdvisor
import org.openutm.djiflight.core.advisory.Level
import org.openutm.djiflight.core.altitude.AltitudeSource
import org.openutm.djiflight.core.altitude.GnssDatumCheck
import org.openutm.djiflight.core.altitude.TakeoffReferenceResolver
import org.openutm.djiflight.core.declaration.DeclarationInput
import org.openutm.djiflight.core.declaration.DeclarationPlanner
import org.openutm.djiflight.core.declaration.GroundEstimate
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.kml.Kml
import org.openutm.djiflight.core.mission.PlanLibrary
import org.openutm.djiflight.core.mission.SavedSurvey
import java.io.File
import org.openutm.djiflight.core.mission.CameraModel
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.core.mission.MissionValidator
import org.openutm.djiflight.core.mission.SurveyPlanner
import org.openutm.djiflight.core.mission.SurveyResult
import org.openutm.djiflight.core.mission.SurveyPattern
import org.openutm.djiflight.core.terrain.TerrainCheck
import org.openutm.djiflight.core.terrain.TerrainFollow
import org.openutm.djiflight.core.terrain.TerrainReport
import org.openutm.djiflight.core.mission.SurveySpec
import org.openutm.djiflight.core.model.FlightPlan
import org.openutm.djiflight.core.model.OperationKind
import org.openutm.djiflight.core.provisioning.Pilot
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min

/**
 * Plan and declare a flight, at the site or in advance (the day before, while online).
 * Manual flights: a circle around the take-off point (drag it), or an imported boundary.
 * Mapping missions: tap the survey corners, drag them, or add one on an edge's "+"; the grid,
 * flight time, photos and batteries update as you go. Altitude first; the rest under Advanced.
 */
class PlanActivity : Activity() {
    private val ui = MainScope()
    private val graph get() = (application as FlightApp).graph
    private lateinit var map: MapController

    private data class AircraftChoice(val label: String, val serial: String, val registration: String)

    private var pilots: List<Pilot> = emptyList()
    private var aircraft: List<AircraftChoice> = emptyList()
    private var siteNames: List<String> = emptyList()
    private var center: LatLng? = null
    private var boundary: List<LatLng>? = null
    private var boundaryName: String? = null
    private val survey = ArrayList<LatLng>()
    private val undo = ArrayList<List<LatLng>>()
    private var surveyResult: SurveyResult? = null
    private var plannerProblem: String? = null
    private var area: List<LatLng>? = null
    private var ground: GroundEstimate? = null
    private var signInText: TextView? = null
    private var signInButton: ActionButton? = null
    private var terrainReport: TerrainReport? = null
    private var terrainDirty = true
    private lateinit var terrainText: TextView
    private var groundMsl: Double? = null
    private var startMs: Long = 0
    private var fitted = false
    private var mission = false
    private var lastGridAt = 0L

    // Panel
    private lateinit var body: LinearLayout
    private lateinit var footerNote: TextView
    private lateinit var declareButton: ActionButton
    private lateinit var importButton: ActionButton
    private var compactImport = false
    private var exporting: SavedSurvey? = null
    private lateinit var manualTab: ActionButton
    private lateinit var missionTab: ActionButton
    private val statValues = ArrayList<TextView>()
    private val statLabels = ArrayList<TextView>()
    private lateinit var mapHint: TextView
    private lateinit var undoButton: ActionButton
    private lateinit var clearButton: ActionButton
    private lateinit var manualBox: LinearLayout
    private lateinit var missionBox: LinearLayout
    private lateinit var advancedBox: LinearLayout
    private lateinit var advancedSummary: TextView
    private lateinit var detailsBox: LinearLayout
    private lateinit var detailsSummary: TextView
    private lateinit var utmText: TextView
    private lateinit var groundText: TextView
    private lateinit var boundaryRow: LinearLayout
    private lateinit var boundaryLabel: TextView
    private lateinit var dateButton: ActionButton
    private lateinit var timeButton: ActionButton
    private lateinit var siteName: EditText
    private lateinit var pilotSpinner: Spinner
    private lateinit var aircraftSpinner: Spinner
    private lateinit var siteSpinner: Spinner
    private lateinit var radius: Stepper
    private lateinit var maxHeight: Stepper
    private lateinit var altitude: Stepper
    private lateinit var altitudeHint: TextView
    private lateinit var frontOverlap: Stepper
    private lateinit var sideOverlap: Stepper
    private lateinit var speed: Stepper
    private lateinit var heading: Stepper
    private lateinit var gimbal: Stepper
    private var pattern = SurveyPattern.GRID
    private lateinit var patternSpinner: Spinner
    private lateinit var routeWidth: Stepper
    private lateinit var orbitTop: Stepper
    private lateinit var orbitLayer: Stepper
    private lateinit var widthRow: View
    private lateinit var topRow: View
    private lateinit var layerRow: View
    private val library by lazy { PlanLibrary(File(filesDir, "plans")) }
    private lateinit var patternHint: TextView
    private lateinit var headingRow: View
    private lateinit var sideOverlapRow: View
    private lateinit var travel: Stepper
    private var followTerrain = false
    /** Follow terrain is on, but the terrain file has no height at the take-off point or somewhere on the route. */
    private var followFailed = false
    private var flatWaypoints = 0
    private lateinit var followRow: View
    private lateinit var followToggle: ActionButton
    private lateinit var hours: Stepper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plan)
        val p = graph.provisioning.value
        if (p == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        buildTopBar()
        map = MapController(this, { graph.maps.basemap }, { graph.maps.imagery }, { graph.maps.terrain })
        map.attachTo(findViewById(R.id.mapBox), savedInstanceState)
        map.onMapClick { tapped ->
            if (mission) {
                pushUndo()
                survey += tapped
            } else {
                center = tapped
                boundary = null
                siteSpinner.setSelection(siteNames.lastIndex)
            }
            recompute(force = true)
        }
        map.edit(editor)
        buildMapTools()
        buildPanel(p.legalMaxHeightM)

        ui.launch { graph.maps.version.collect { map.reloadStyle() } }
        startMs = graph.clock.nowMs()
        showKind(false)
        ui.launch {
            while (isActive) {
                recompute()
                delay(1_000)
            }
        }
    }

    // --- map editing

    private val editor = object : MapController.Editor {
        override fun corners(): List<LatLng> = if (mission) survey else listOfNotNull(center?.takeIf { boundary == null })
        override val insertable get() = mission && survey.size >= (if (pattern == SurveyPattern.CORRIDOR) 2 else 3)
        override val closed get() = pattern != SurveyPattern.CORRIDOR
        override fun insert(afterIndex: Int, p: LatLng) {
            pushUndo()
            survey.add(afterIndex + 1, p)
            recompute(force = true)
        }
        override fun move(index: Int, p: LatLng, done: Boolean) {
            if (mission) {
                if (index !in survey.indices) return
                if (dragStart == null) { dragStart = survey.toList() }
                survey[index] = p
                if (done) { dragStart?.let { undo += it }; dragStart = null }
            } else {
                center = p
                if (done) siteSpinner.setSelection(siteNames.lastIndex)
            }
            recompute(force = done)
        }
    }
    private var dragStart: List<LatLng>? = null

    private fun pushUndo() {
        undo += survey.toList()
        if (undo.size > 50) undo.removeAt(0)
    }

    private fun buildMapTools() {
        mapHint = findViewById(R.id.mapHint)
        mapHint.background = rounded(color(R.color.scrim), dp(8).toFloat())
        mapHint.setOnClickListener {
            if (!graph.maps.basemap.isFile && !graph.maps.imagery.isFile) {
                startActivity(Intent(this, SetupActivity::class.java).putExtra("section", "maps"))
            }
        }
        val tools = findViewById<LinearLayout>(R.id.mapTools)
        undoButton = ActionButton(this).set(getString(R.string.plan_undo), R.drawable.ic_undo, Kind.SECONDARY).apply {
            background = rounded(color(R.color.scrim), dp(8).toFloat())
            setOnClickListener {
                undo.removeLastOrNull()?.let { survey.clear(); survey += it }
                recompute(force = true)
            }
        }
        clearButton = ActionButton(this).set(getString(R.string.plan_clear_corners), R.drawable.ic_trash, Kind.SECONDARY).apply {
            background = rounded(color(R.color.scrim), dp(8).toFloat())
            setOnClickListener {
                if (survey.isNotEmpty()) pushUndo()
                survey.clear()
                surveyResult = null
                recompute(force = true)
            }
        }
        tools.addView(undoButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)))
        tools.addView(clearButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { marginStart = dp(8) })
        findViewById<ImageView>(R.id.btnRecenter).apply {
            background = rounded(color(R.color.scrim), dp(26).toFloat())
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.text))
            setOnClickListener { fitted = false; recompute(force = true) }
        }
    }

    // --- top bar and panel

    private fun buildTopBar() {
        // Everything on one line: the title goes first, then the long labels, when they don't fit
        // (narrow RC screens at 640 dp; translations longer than English). Measured, not guessed.
        val paint = android.text.TextPaint().apply { isAntiAlias = true; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        fun w(id: Int, sp: Float) = paint.apply { textSize = sp * resources.displayMetrics.scaledDensity }.measureText(getString(id))
        fun need(title: Boolean, short: Boolean): Float =
            dp(52 + 12) + (if (title) w(R.string.plan_title, 18f) + dp(20) else 0f) + dp(2 * 32 + 6 + 22 + 8 + 32 + 16) +
                if (short) w(R.string.plan_tab_manual_short, 16f) + w(R.string.plan_tab_mission_short, 16f) + w(R.string.plan_import_short, 16f)
                else w(R.string.plan_tab_manual, 16f) + w(R.string.plan_tab_mission, 16f) + w(R.string.plan_import, 16f)
        val width = resources.configuration.screenWidthDp * resources.displayMetrics.density
        val withTitle = need(title = true, short = false) <= width
        val narrow = !withTitle && need(title = false, short = false) > width
        val bar = topBar(if (withTitle) getString(R.string.plan_title) else "") { finish() }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(color(R.color.surface2), dp(10).toFloat())
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        manualTab = ActionButton(this).set(getString(if (narrow) R.string.plan_tab_manual_short else R.string.plan_tab_manual), null, Kind.PRIMARY).apply { setOnClickListener { showKind(false) } }
        missionTab = ActionButton(this).set(getString(if (narrow) R.string.plan_tab_mission_short else R.string.plan_tab_mission), null, Kind.QUIET).apply { setOnClickListener { showKind(true) } }
        tabs.addView(manualTab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        tabs.addView(missionTab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        bar.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        compactImport = narrow
        importButton = ActionButton(this).set(getString(if (narrow) R.string.plan_import_short else R.string.plan_import), R.drawable.ic_import, Kind.SECONDARY).apply {
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_KML) }
        }
        bar.addView(importButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        findViewById<LinearLayout>(R.id.topBar).addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun section(title: String, summary: TextView? = null, collapsible: LinearLayout? = null): View {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(6))
            addView(text(17f, bold = true).apply { text = if (collapsible != null) "$title  ›" else title; tag = title })
            summary?.let { addView(it) }
        }
        if (collapsible != null) {
            head.isClickable = true
            head.background = getDrawable(android.R.drawable.list_selector_background)
            head.setOnClickListener {
                val open = collapsible.visibility != View.VISIBLE
                collapsible.show(open)
                (head.getChildAt(0) as TextView).text = if (open) "$title  ⌄" else "$title  ›"
            }
        }
        return head
    }

    private fun stepper(title: String, v: Double, min: Double, max: Double, step: Double, fmt: (Double) -> String) =
        Stepper(this, title, v, min, max, step, fmt) { recompute(force = true) }

    private fun buildPanel(legal: Double) {
        // The panel takes about 45 % of the width, between 290 and 380 dp; the map gets the rest.
        findViewById<View>(R.id.panel).layoutParams.width = dp((resources.configuration.screenWidthDp * 0.45f).toInt().coerceIn(290, 380))
        body = findViewById(R.id.panelBody)
        footerNote = findViewById(R.id.footerNote)
        declareButton = ActionButton(this).set(getString(R.string.plan_declare), R.drawable.ic_check, Kind.PRIMARY).apply { setOnClickListener { declare() } }
        findViewById<FrameLayout>(R.id.declareSlot).addView(declareButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val stats = findViewById<LinearLayout>(R.id.stats)
        repeat(4) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val v = text(19f, bold = true).apply { tabular(); gravity = Gravity.CENTER; maxLines = 1 }
                val l = text(11.5f, R.color.text2).apply { gravity = Gravity.CENTER; maxLines = 1 }
                addView(v); addView(l)
                statValues += v; statLabels += l
            }
            stats.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        // Manual: area and height
        manualBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        radius = stepper(getString(R.string.plan_area_radius), 500.0, 50.0, 5_000.0, 50.0) { getString(R.string.plan_value_m, it) }
        maxHeight = stepper(getString(R.string.plan_max_height), min(120.0, legal), 10.0, legal, 10.0) { getString(R.string.plan_value_m, it) }
        boundaryLabel = text(15f, R.color.text2)
        boundaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(boundaryLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ActionButton(this@PlanActivity).set(getString(R.string.plan_boundary_remove), R.drawable.ic_close, Kind.SECONDARY).apply {
                setOnClickListener { boundary = null; boundaryName = null; fitted = false; recompute(force = true) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        }
        manualBox.addView(section(getString(R.string.plan_section_area)))
        manualBox.addView(Stepper.row(this, getString(R.string.plan_circle_radius), getString(R.string.plan_circle_radius_hint), radius))
        manualBox.addView(boundaryRow)
        manualBox.addView(Stepper.row(this, getString(R.string.plan_max_height), getString(R.string.plan_max_height_hint, legal), maxHeight))

        // Mapping: altitude first, the rest under Advanced (docs/13)
        missionBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        altitude = stepper(getString(R.string.plan_altitude), 80.0, 20.0, legal, 5.0) { getString(R.string.plan_value_m, it) }
        val altRow = Stepper.row(this, getString(R.string.plan_altitude), getString(R.string.plan_altitude_hint), altitude)
        altitudeHint = altRow.findViewWithTag("hint")
        missionBox.addView(section(getString(R.string.plan_section_survey)))
        missionBox.addView(altRow)
        missionBox.addView(patternRow())
        routeWidth = stepper(getString(R.string.plan_route_width), 60.0, 5.0, 500.0, 5.0) { getString(R.string.plan_value_m, it) }
        orbitTop = stepper(getString(R.string.plan_orbit_top), 80.0, 20.0, legal, 5.0) { getString(R.string.plan_value_m, it) }
        orbitLayer = stepper(getString(R.string.plan_orbit_layer), 20.0, 5.0, 100.0, 5.0) { getString(R.string.plan_value_m, it) }
        widthRow = Stepper.row(this, getString(R.string.plan_route_width), getString(R.string.plan_route_width_hint), routeWidth)
        topRow = Stepper.row(this, getString(R.string.plan_orbit_top), null, orbitTop)
        layerRow = Stepper.row(this, getString(R.string.plan_orbit_layer), null, orbitLayer)
        missionBox.addView(widthRow); missionBox.addView(topRow); missionBox.addView(layerRow)
        val saved = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saved.addView(ActionButton(this).set(getString(R.string.plan_save_draft), null, Kind.SECONDARY).apply { setOnClickListener { saveDraft() } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        saved.addView(ActionButton(this).set(getString(R.string.plan_open_draft), null, Kind.SECONDARY).apply { setOnClickListener { openDrafts() } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        missionBox.addView(saved)
        advancedSummary = text(13f, R.color.text3)
        advancedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        frontOverlap = stepper(getString(R.string.plan_front_overlap), 75.0, 50.0, 90.0, 5.0) { getString(R.string.plan_value_percent, it) }
        sideOverlap = stepper(getString(R.string.plan_side_overlap), 65.0, 40.0, 90.0, 5.0) { getString(R.string.plan_value_percent, it) }
        speed = stepper(getString(R.string.plan_speed), if (graph.adapter.capabilities.value?.missionSupport == MissionSupport.VIRTUAL_STICK_EXPERIMENTAL) 2.0 else 8.0, 1.0, if (graph.adapter.capabilities.value?.missionSupport == MissionSupport.VIRTUAL_STICK_EXPERIMENTAL) 3.0 else 15.0, 1.0) { getString(R.string.plan_value_mps, it) }
        heading = stepper(getString(R.string.plan_heading), 0.0, 0.0, 355.0, 5.0) { getString(R.string.plan_value_degrees, it) }
        gimbal = stepper(getString(R.string.plan_gimbal), -90.0, -90.0, 0.0, 5.0) { getString(R.string.plan_value_degrees, it).replace('-', '−') }
        travel = stepper(getString(R.string.plan_travel), 0.0, 0.0, legal, 5.0) { if (it <= 0.0) getString(R.string.plan_travel_off) else getString(R.string.plan_value_m, it) }
        advancedBox.addView(Stepper.row(this, getString(R.string.plan_front_overlap), null, frontOverlap))
        sideOverlapRow = Stepper.row(this, getString(R.string.plan_side_overlap), null, sideOverlap)
        advancedBox.addView(sideOverlapRow)
        advancedBox.addView(Stepper.row(this, getString(R.string.plan_speed), null, speed))
        headingRow = Stepper.row(this, getString(R.string.plan_heading), getString(R.string.plan_heading_hint), heading)
        advancedBox.addView(headingRow)
        advancedBox.addView(Stepper.row(this, getString(R.string.plan_gimbal), getString(R.string.plan_gimbal_hint), gimbal))
        advancedBox.addView(Stepper.row(this, getString(R.string.plan_travel), getString(R.string.plan_travel_hint), travel))
        followToggle = ActionButton(this).set(getString(R.string.plan_follow_off), null, Kind.SECONDARY).apply {
            setOnClickListener { followTerrain = !followTerrain; recompute(force = true) }
        }
        followRow = toggleRow(getString(R.string.plan_follow_terrain), getString(R.string.plan_follow_terrain_hint), followToggle)
        advancedBox.addView(followRow)
        missionBox.addView(section(getString(R.string.plan_section_advanced), advancedSummary, advancedBox))
        missionBox.addView(advancedBox)

        body.addView(manualBox)
        body.addView(missionBox)

        // Flight details: who, what, where, when
        detailsSummary = text(13f, R.color.text3)
        detailsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        body.addView(section(getString(R.string.plan_section_details), detailsSummary, detailsBox))
        body.addView(detailsBox)
        buildDetails()

        body.addView(section(getString(R.string.plan_section_utm)))
        utmText = text(14f, R.color.text2).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        body.addView(utmText)
        terrainText = text(14f, R.color.text2).apply { setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, dp(8), 0, 0) }
        body.addView(terrainText)
    }

    private fun toggleRow(label: String, hint: String, toggle: ActionButton) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        val texts = LinearLayout(this@PlanActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(15f, bold = true).apply { text = label })
            addView(text(13f, R.color.text3).apply { text = hint })
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(toggle, LinearLayout.LayoutParams(dp(96), dp(48)))
    }

    private fun spinner(labels: List<String>): Spinner = Spinner(this, Spinner.MODE_DROPDOWN).apply {
        adapter = ArrayAdapter(this@PlanActivity, R.layout.spinner_item, labels).apply { setDropDownViewResource(R.layout.spinner_dropdown_item) }
        background = rounded(color(R.color.surface2), dp(8).toFloat())
        setPopupBackgroundDrawable(rounded(color(R.color.surface2), dp(8).toFloat()))
    }

    private fun label(s: String) = text(13f, R.color.text2).apply { text = s; setPadding(0, dp(10), 0, dp(4)) }

    private fun buildDetails() {
        val p = graph.provisioning.value ?: return
        pilots = p.pilots.ifEmpty { if (p.auth is org.openutm.djiflight.core.provisioning.AuthConfig.Dev) listOf(Pilot("pilot-dev", "Dev pilot")) else emptyList() }
        val connected = (graph.service.status.value.link as? LinkState.Connected)?.identity
        aircraft = p.aircraft.map { AircraftChoice("${it.model.ifBlank { getString(R.string.plan_aircraft_model_fallback) }} ${it.registration}".trim(), it.serial, it.registration) }
            .let { list -> if (connected != null && list.none { it.serial == connected.serial }) listOf(AircraftChoice(getString(R.string.plan_aircraft_unlisted, connected.model), connected.serial, "UNREGISTERED")) + list else list }
        val saved = graph.sites.all()
        siteNames = listOf(getString(R.string.plan_site_here)) + saved.map { it.name } + listOf(getString(R.string.plan_site_map))

        pilotSpinner = spinner(pilots.map { it.name })
        aircraftSpinner = spinner(aircraft.map { it.label })
        connected?.let { c -> aircraft.indexOfFirst { it.serial == c.serial }.takeIf { it >= 0 }?.let { aircraftSpinner.setSelection(it) } }
        siteSpinner = spinner(siteNames)
        siteName = EditText(this).apply {
            hint = getString(R.string.plan_site_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(color(R.color.text))
            setHintTextColor(color(R.color.text3))
            textSize = 16f
        }
        siteSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                saved.getOrNull(pos - 1)?.let {
                    center = it.center
                    boundary = null
                    siteName.setText(it.name)
                    fitted = false
                }
                recompute(force = true)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val d = detailsBox
        d.addView(label(getString(R.string.plan_label_pilot))); d.addView(pilotSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        if (p.pilotLogin != null) {
            d.addView(signInRow())
            SignInActivity.warmUp(applicationContext)
        }
        d.addView(label(getString(R.string.plan_label_aircraft))); d.addView(aircraftSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        d.addView(label(getString(R.string.plan_label_site))); d.addView(siteSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        d.addView(siteName)
        d.addView(label(getString(R.string.plan_label_start)))
        val when_ = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dateButton = ActionButton(this).set(getString(R.string.plan_date_today), null, Kind.SECONDARY).apply { setOnClickListener { pickDate() } }
        timeButton = ActionButton(this).set(getString(R.string.plan_time_now), null, Kind.SECONDARY).apply { setOnClickListener { pickTime() } }
        when_.addView(dateButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        when_.addView(timeButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        d.addView(when_)
        hours = stepper(getString(R.string.plan_duration), 3.0, 0.5, 47.0, 0.5) { if (it % 1.0 == 0.0) getString(R.string.plan_value_hours_whole, it) else getString(R.string.plan_value_hours, it) }
        d.addView(Stepper.row(this, getString(R.string.plan_duration), getString(R.string.plan_duration_hint), hours))
        d.addView(label(getString(R.string.plan_label_ground)))
        groundText = text(15f, R.color.text2)
        d.addView(groundText)
        d.addView(ActionButton(this).set(getString(R.string.plan_enter_elevation), null, Kind.SECONDARY).apply { setOnClickListener { askElevation() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
    }

    /** Pilot sign-in (device setup `pilot_login`): who is signed in, and Sign in / Sign out. */
    private fun signInRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, 0)
        signInText = text(14f, R.color.text2)
        addView(signInText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        signInButton = ActionButton(this@PlanActivity).set(getString(R.string.plan_sign_in), null, Kind.SECONDARY).apply {
            setOnClickListener {
                if (signedIn() != null) { graph.pilots.signOut(); recompute(force = true) }
                else startActivity(Intent(this@PlanActivity, SignInActivity::class.java))
            }
        }
        addView(signInButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginStart = dp(8) })
    }

    /** The signed-in pilot, if the device setup has pilot sign-in and the sign-in still counts. */
    private fun signedIn() = graph.provisioning.value?.pilotLogin?.let { graph.pilots.valid(it.issuer) }

    /** Roster name for a signed-in pilot, else the identity server's. */
    private fun signedInName(s: org.openutm.djiflight.core.auth.PilotSignIn) = graph.provisioning.value?.rosterEntry(s)?.name ?: s.name

    private fun showKind(m: Boolean) {
        val noMissions = noMissionSupport()
        if (m && noMissions != null) {
            Sheet(this).title(getString(R.string.plan_no_missions_title, noMissions)).message(getString(R.string.plan_no_missions_message)).button(getString(R.string.plan_ok)).show()
            return
        }
        if (m != mission) fitted = false
        mission = m
        manualTab.kind = if (m) Kind.QUIET else Kind.PRIMARY
        missionTab.kind = if (m) Kind.PRIMARY else Kind.QUIET
        manualBox.show(!m)
        missionBox.show(m)
        undoButton.show(m)
        clearButton.show(m)
        recompute(force = true)
    }

    /** The connected aircraft's mission limits, else this APK's (Waypoint 1.0 in v4, WPML in v5). */
    private fun missionLimits(): MissionLimits = graph.adapter.capabilities.value?.missionLimits ?: graph.platform.defaultMissionLimits

    /** The connected aircraft's camera (e.g. the payload on a Matrice 400), else this APK's default. */
    private fun camera(): CameraModel = graph.adapter.capabilities.value?.camera ?: graph.platform.defaultCamera

    /** Native and experimental execution availability are separate from SDK device connectivity. */
    private fun noMissionSupport(): String? {
        if (graph.service.status.value.link !is LinkState.Connected) return null
        val caps = graph.adapter.capabilities.value ?: return null
        return caps.model.takeIf { caps.missionSupport == MissionSupport.NONE || caps.camera == null }
    }

    private fun spec() = SurveySpec(
        survey, camera(), altitude.current, frontOverlap.current / 100, sideOverlap.current / 100, heading.current, speed.current,
        gimbalPitchDeg = gimbal.current, travelHeightM = travel.current.takeIf { it > 0 }, pattern = pattern,
        corridorWidthM = routeWidth.current, orbitStandoffM = routeWidth.current,
        orbitTopHeightM = maxOf(altitude.current, orbitTop.current), orbitLayerSpacingM = orbitLayer.current,
    )

    /**
     * With Follow terrain on and a terrain file: the survey [altitude] above the ground, from the
     * take-off point ([TerrainFollow]). Without terrain heights for all of it, the flat survey and [followFailed].
     */
    private fun followed(r: SurveyResult): SurveyResult {
        flatWaypoints = r.plan.waypoints.size
        if (!followTerrain) return r
        val t = graph.terrain ?: return r
        val plan = center?.let { c -> runCatching { TerrainFollow.follow(r.plan, c, altitude.current, t, missionLimits()) }.getOrNull() }
        if (plan == null) followFailed = true
        return plan?.let { r.copy(plan = it) } ?: r
    }

    private fun patternLabel(p: SurveyPattern): String = getString(when (p) {
        SurveyPattern.GRID -> R.string.plan_pattern_grid
        SurveyPattern.CROSSHATCH -> R.string.plan_pattern_crosshatch
        SurveyPattern.PERIMETER -> R.string.plan_pattern_perimeter
        SurveyPattern.FIVE_DIRECTION -> R.string.plan_pattern_five
        SurveyPattern.CORRIDOR -> R.string.plan_pattern_corridor
        SurveyPattern.SURROUND -> R.string.plan_pattern_surround
    })

    private fun patternRow(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        patternSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PlanActivity, android.R.layout.simple_spinner_dropdown_item, SurveyPattern.entries.map(::patternLabel))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (::gimbal.isInitialized) choosePattern(SurveyPattern.entries[position])
                }
            }
        }
        box.addView(patternSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        patternHint = text(13f, R.color.text3).apply { setPadding(0, dp(6), 0, 0) }
        box.addView(patternHint)
        return box
    }

    private fun choosePattern(p: SurveyPattern) {
        if (p == pattern) return
        pattern = p
        gimbal.setValue(p.defaultGimbalDeg)
        if (p == SurveyPattern.SURROUND) routeWidth.setValue(20.0)
        patternSpinner.setSelection(p.ordinal)
        recompute(force = true)
    }

    private fun saveDraft() {
        if (survey.size < (if (pattern == SurveyPattern.CORRIDOR) 2 else 3)) return refuse(getString(R.string.plan_refuse_corners))
        val name = EditText(this).apply { setText(siteName.text.toString()); hint = getString(R.string.plan_draft_name); maxLines = 1 }
        Sheet(this).title(getString(R.string.plan_save_draft)).body(name).button(getString(R.string.plan_ok)) {
            runCatching { library.save(SavedSurvey(name = name.text.toString().trim(), points = survey.toList(), pattern = pattern,
                heightM = altitude.current, frontOverlap = frontOverlap.current / 100, sideOverlap = sideOverlap.current / 100,
                headingDeg = heading.current, speedMps = speed.current, gimbalPitchDeg = gimbal.current,
                travelHeightM = travel.current.takeIf { it > 0 }, followTerrain = followTerrain, widthM = routeWidth.current,
                topHeightM = maxOf(altitude.current, orbitTop.current), layerSpacingM = orbitLayer.current)) }
                .onFailure { refuse(it.message ?: getString(R.string.plan_draft_failed)) }
        }.show()
    }

    private fun openDrafts() {
        val plans = library.list()
        if (plans.isEmpty()) {
            Sheet(this).title(getString(R.string.plan_open_draft)).message(getString(R.string.plan_drafts_empty))
                .button(getString(R.string.plan_import_draft)) { importDraft() }.button(getString(R.string.plan_ok)).show()
            return
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(Sheet.note(this, getString(R.string.plan_draft_actions_hint)))
        lateinit var sheet: Sheet
        plans.forEach { draft ->
            list.addView(ActionButton(this).set(draft.name, null, Kind.SECONDARY).apply {
                setOnClickListener { sheet.dismiss(); loadDraft(draft) }
                setOnLongClickListener {
                    Sheet(this@PlanActivity).title(draft.name)
                        .button(getString(R.string.plan_export_draft)) {
                            exporting = draft
                            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                                .putExtra(Intent.EXTRA_TITLE, "openutm-${draft.id}.json"), REQ_PLAN_EXPORT)
                        }
                        .button(getString(R.string.plan_delete_draft), Kind.DANGER) {
                            Sheet(this@PlanActivity).title(draft.name).message(getString(R.string.plan_delete_draft_question))
                                .button(getString(R.string.plan_ok), Kind.DANGER) {
                                    runCatching { library.delete(draft) }.onFailure { refuse(getString(R.string.plan_draft_failed)) }; sheet.dismiss()
                                }.show()
                        }.button(getString(R.string.plan_ok)).show()
                    true
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
        sheet = Sheet(this).title(getString(R.string.plan_open_draft)).body(list)
            .button(getString(R.string.plan_import_draft)) { importDraft() }.button(getString(R.string.plan_ok))
        sheet.show()
    }

    private fun importDraft() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"), REQ_PLAN_IMPORT)
    }

    private fun loadDraft(d: SavedSurvey) {
        pushUndo(); survey.clear(); survey += d.points
        choosePattern(d.pattern)
        altitude.setValue(d.heightM); frontOverlap.setValue(d.frontOverlap * 100); sideOverlap.setValue(d.sideOverlap * 100)
        heading.setValue(d.headingDeg); speed.setValue(d.speedMps); gimbal.setValue(d.gimbalPitchDeg)
        travel.setValue(d.travelHeightM ?: 0.0); routeWidth.setValue(d.widthM)
        orbitTop.setValue(d.topHeightM ?: d.heightM); orbitLayer.setValue(d.layerSpacingM)
        followTerrain = d.followTerrain; siteName.setText(d.name); fitted = false
        showKind(true)
        recompute(force = true)
    }

    // --- live recompute

    private fun recompute(force: Boolean = false) {
        if (!::body.isInitialized) return
        val p = graph.provisioning.value ?: return
        if (mission && noMissionSupport() != null) showKind(false)
        if (siteSpinner.selectedItemPosition == 0) graph.currentPosition()?.let { center = it }

        // The grid is the slow part: at most ~6 times a second while a corner is being dragged.
        val now = System.currentTimeMillis()
        if (force || now - lastGridAt > 150) {
            lastGridAt = now
            followFailed = false
            plannerProblem = null
            surveyResult = if (mission && survey.size >= (if (pattern == SurveyPattern.CORRIDOR) 2 else 3)) runCatching { SurveyPlanner.plan(spec()) }.onFailure { plannerProblem = it.message }.getOrNull()?.let(::followed) else null
            terrainDirty = true
        }
        area = when {
            // The transit from the take-off point to the first line and back is flown too: declare it.
            mission -> surveyResult?.let { r -> Geo.bufferedHull(r.plan.waypoints.map { it.position } + survey + listOfNotNull(center), 30.0) }
            boundary != null -> boundary
            else -> center?.let { Geo.circle(it, radius.current) }
        }
        ground = groundEstimate()
        if (terrainDirty || force) {
            terrainDirty = false
            terrainReport = terrainReport()
        }

        map.setArea(area)
        map.setSurvey(if (mission && pattern != SurveyPattern.CORRIDOR) survey else null)
        map.setMission(surveyResult?.plan?.waypoints?.map { it.position })
        map.setStart(surveyResult?.plan?.waypoints?.firstOrNull { !it.transit }?.position)
        map.setHandles(if (mission) survey else listOfNotNull(center?.takeIf { boundary == null }), midpoints = mission && survey.size >= (if (pattern == SurveyPattern.CORRIDOR) 2 else 3), closed = pattern != SurveyPattern.CORRIDOR)
        map.setZones(graph.zones.index.value?.zones.orEmpty())
        map.setAircraftZones(graph.aircraftZones.value)
        map.setHome(center)
        map.setPilot(graph.deviceFix.value?.let { LatLng(it.lat, it.lng) })
        if (!fitted && map.ready) (area ?: (survey.takeIf { it.size >= 2 }) ?: center?.let { listOf(it) })?.let { map.fit(it, dp(40), dp(64), dp(40), dp(80)); fitted = true }

        val mapMissing = !graph.maps.basemap.isFile && !graph.maps.imagery.isFile
        mapHint.isClickable = mapMissing
        mapHint.setIfChanged(
            when {
                mapMissing -> getString(R.string.plan_hint_map_missing)
                !mission -> if (boundary != null) getString(R.string.plan_hint_imported, boundaryName ?: getString(R.string.plan_hint_imported_fallback)) else getString(R.string.plan_hint_move_takeoff)
                pattern == SurveyPattern.CORRIDOR && survey.size < 2 -> getString(R.string.plan_corridor_add_point)
                pattern == SurveyPattern.CORRIDOR -> getString(R.string.plan_hint_drag_corner)
                survey.isEmpty() -> getString(R.string.plan_hint_first_corner)
                survey.size < 3 -> getString(R.string.plan_hint_add_corner, survey.size + 1)
                else -> getString(R.string.plan_hint_drag_corner)
            },
        )
        undoButton.isEnabled = undo.isNotEmpty()
        clearButton.isEnabled = survey.isNotEmpty()

        renderStats()
        renderTexts(p.legalMaxHeightM)
    }

    private fun renderStats() {
        val r = surveyResult
        fun stat(i: Int, v: String, l: String, warn: Boolean = false) {
            statValues[i].setIfChanged(v)
            statValues[i].setTextColor(color(if (warn) R.color.warn else R.color.text))
            statLabels[i].setIfChanged(l)
        }
        if (mission) {
            val experimental = graph.adapter.capabilities.value?.missionSupport == MissionSupport.VIRTUAL_STICK_EXPERIMENTAL
            val mins = r?.let { it.estimatedFlightTimeS / 60 }
            val batteries = if (experimental) null else mins?.let { ceil(it / usableMinutes()).toInt().coerceAtLeast(1) }
            stat(0, mins?.let { (if (experimental) "≥ " else "") + getString(R.string.plan_stat_minutes, it) } ?: "—", getString(if (experimental) R.string.plan_stat_cruise_only else R.string.plan_stat_flight_time), warn = experimental)
            val footprint = if (pattern == SurveyPattern.CORRIDOR && survey.size >= 2) {
                val length = survey.zipWithNext { a, b -> Geo.distanceM(a,b) }.sum()
                "≈ " + formatArea(length * routeWidth.current)
            } else if (survey.size >= 3) hectares(survey) else "—"
            stat(1, footprint, getString(R.string.plan_stat_area))
            stat(2, r?.let { getString(R.string.plan_stat_photos_value, photoEstimate(it)) } ?: "—", getString(R.string.plan_stat_photos))
            stat(3, batteries?.toString() ?: "—", if (experimental) getString(R.string.plan_stat_battery_unmeasured) else resources.getQuantityString(R.plurals.plan_stat_batteries, batteries ?: 0), warn = experimental || (batteries ?: 1) > 1)
        } else {
            stat(0, area?.let { hectares(it) } ?: "—", getString(R.string.plan_stat_area))
            stat(1, if (boundary != null) getString(R.string.plan_stat_boundary) else getString(R.string.plan_value_m, radius.current), getString(if (boundary != null) R.string.plan_stat_imported else R.string.plan_stat_radius))
            stat(2, getString(R.string.plan_value_m, maxHeight.current), getString(R.string.plan_stat_max_height))
            stat(3, hours.current.let { if (it % 1.0 == 0.0) getString(R.string.plan_value_hours_whole, it) else getString(R.string.plan_value_hours, it) }, getString(R.string.plan_stat_duration))
        }
    }

    private fun renderTexts(legal: Double) {
        val r = surveyResult
        importButton.label.text = getString(if (compactImport) R.string.plan_import_short else if (mission && pattern == SurveyPattern.CORRIDOR) R.string.plan_import_line else R.string.plan_import)
        altitudeHint.setIfChanged(
            camera().name + " · " + (r?.let { if (pattern == SurveyPattern.PERIMETER) getString(R.string.plan_gsd_perimeter, it.gsdCm) else resources.getQuantityString(R.plurals.plan_gsd_lines, it.lineCount, it.gsdCm, it.lineCount) }
                ?: getString(R.string.plan_gsd, gsdAt(altitude.current))),
        )
        patternHint.setIfChanged(
            when (pattern) {
                SurveyPattern.GRID -> getString(R.string.plan_pattern_grid_hint)
                SurveyPattern.CROSSHATCH -> getString(R.string.plan_pattern_crosshatch_hint)
                SurveyPattern.PERIMETER -> getString(R.string.plan_pattern_perimeter_hint)
                SurveyPattern.FIVE_DIRECTION -> getString(R.string.plan_pattern_five_hint)
                SurveyPattern.CORRIDOR -> getString(R.string.plan_pattern_corridor_hint)
                SurveyPattern.SURROUND -> getString(R.string.plan_pattern_surround_hint)
            },
        )
        widthRow.show(pattern == SurveyPattern.CORRIDOR || pattern == SurveyPattern.SURROUND)
        topRow.show(pattern == SurveyPattern.SURROUND); layerRow.show(pattern == SurveyPattern.SURROUND)
        headingRow.show(pattern != SurveyPattern.PERIMETER)
        sideOverlapRow.show(pattern != SurveyPattern.PERIMETER)
        val terrainFile = graph.terrain != null
        followRow.show(terrainFile)
        val followLabel = getString(if (followTerrain) R.string.plan_follow_on else R.string.plan_follow_off)
        if (followToggle.label.text.toString() != followLabel) followToggle.set(followLabel, null, if (followTerrain) Kind.PRIMARY else Kind.SECONDARY)
        advancedSummary.setIfChanged(
            getString(
                R.string.plan_advanced_summary,
                frontOverlap.current, sideOverlap.current, speed.current, heading.current, gimbal.current,
                if (travel.current > 0) getString(R.string.plan_value_m, travel.current) else getString(R.string.plan_travel_off_lower),
            ).replace("-", "−").let { if (followTerrain && terrainFile) getString(R.string.plan_advanced_summary_terrain, it) else it },
        )
        val session = signedIn()
        val login = graph.provisioning.value?.pilotLogin
        signInText?.setIfChanged(
            session?.let { getString(R.string.plan_signed_in, signedInName(it), DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.validUntilMs))) }
                ?: getString(if (login?.required == true) R.string.plan_sign_in_required else R.string.plan_sign_in_optional),
        )
        val signInLabel = getString(if (session != null) R.string.plan_sign_out else R.string.plan_sign_in)
        if (signInButton?.label?.text?.toString() != signInLabel) signInButton?.set(signInLabel, null, Kind.SECONDARY)
        pilotSpinner.show(session == null)
        val pilot = session?.let { signedInName(it) } ?: pilots.getOrNull(pilotSpinner.selectedItemPosition)?.name ?: getString(R.string.plan_no_pilot)
        val ac = aircraft.getOrNull(aircraftSpinner.selectedItemPosition)?.let { it.registration.ifBlank { it.label } } ?: getString(R.string.plan_no_aircraft)
        val whenText = if (startMs <= graph.clock.nowMs() + 60_000) getString(R.string.plan_when_now) else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(startMs))
        detailsSummary.setIfChanged(
            getString(
                R.string.plan_details_summary,
                pilot, ac, siteName.text.toString().ifBlank { siteNames.getOrNull(siteSpinner.selectedItemPosition)?.substringBefore(" (") ?: "" }, whenText,
                hours.current.let { if (it % 1.0 == 0.0) getString(R.string.plan_value_hours_whole, it) else getString(R.string.plan_value_hours, it) },
            ),
        )
        dateButton.label.setIfChanged(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(startMs)))
        timeButton.label.setIfChanged(if (startMs <= graph.clock.nowMs() + 60_000) getString(R.string.plan_time_now) else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(startMs)))
        groundText.setIfChanged(
            ground?.let { getString(R.string.plan_ground_value, sourceLabel(it.source), it.ellipsoidM, it.sigmaM) }
                ?: getString(R.string.plan_ground_unknown),
        )
        boundaryRow.show(!mission && boundary != null)
        boundaryLabel.setIfChanged(getString(R.string.plan_boundary_imported, boundaryName ?: getString(R.string.plan_boundary_fallback)))

        val a = area
        val utm = StringBuilder()
        var problem: String? = if (mission) plannerProblem else null
        if (a == null) problem = problem ?: getString(if (mission) R.string.plan_problem_corners else R.string.plan_problem_takeoff)
        r?.let { res ->
            val parts = MissionValidator.split(res.plan, missionLimits()).size
            if (parts > 1) utm.appendLine(resources.getQuantityString(R.plurals.plan_utm_parts, parts, parts, missionLimits().maxWaypoints))
            if (travel.current > 0 && res.plan.travelHeightM == null) utm.appendLine(getString(R.string.plan_utm_travel_ignored, res.plan.waypoints.maxOf { it.heightM }))
            if (gimbal.current != -90.0) utm.appendLine(getString(R.string.plan_utm_gsd_tilted))
        }
        if (a != null) {
            val conflicts = FlightAdvisor.areaConflicts(a, graph.zones.index.value, graph.aircraftZones.value)
            utm.appendLine(if (conflicts.isEmpty()) getString(R.string.plan_utm_no_zones) else getString(R.string.plan_utm_touches, org.openutm.djiflight.core.zones.ZoneSummary.of(conflicts).joinToString { zoneDescription(it) }))
            val age = graph.zones.ageMs()
            if (age == null) utm.appendLine(getString(R.string.plan_utm_zones_missing))
            draft(legal)?.let { d ->
                val t = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                utm.appendLine(getString(R.string.plan_utm_declares, t.format(Date(d.startMs)), t.format(Date(d.endMs))))
                utm.appendLine(getString(R.string.plan_utm_sync_grace, DeclarationPlanner.DEFAULT_SYNC_GRACE_MS / 3_600_000.0))
                utm.appendLine(getString(R.string.plan_utm_up_to, maxHeight(legal)))
                utm.append(getString(R.string.plan_utm_height_band, d.minAltW84M, d.maxAltW84M))
            } ?: run { if (ground == null) problem = problem ?: getString(R.string.plan_problem_ground) }
        }
        utmText.setIfChanged(utm.toString().trim())
        val (terrainLines, terrainWarn, terrainBlock) = terrainLines(legal)
        terrainText.setIfChanged(terrainLines)
        terrainText.setTextColor(color(if (terrainWarn) R.color.warn else R.color.text2))
        if (terrainBlock != null) problem = problem ?: terrainBlock
        if (login?.required == true && session == null) problem = problem ?: getString(R.string.plan_problem_sign_in)
        if (session == null && pilots.getOrNull(pilotSpinner.selectedItemPosition) == null) problem = problem ?: getString(R.string.plan_refuse_no_pilots)
        if (aircraft.getOrNull(aircraftSpinner.selectedItemPosition) == null) problem = problem ?: getString(R.string.plan_refuse_no_aircraft)
        footerNote.setIfChanged(problem ?: getString(if (graph.service.status.value.online) R.string.plan_footer_online else R.string.plan_footer_offline))
        footerNote.setTextColor(color(if (problem != null) R.color.warn else R.color.text2))
        declareButton.isEnabled = problem == null
    }

    /** Planning estimate: 65 % of DJI's rated flight time, for reserve, wind and the climb. */
    private fun usableMinutes(): Double = when (camera()) {
        CameraModel.MAVIC_3E, CameraModel.MAVIC_3T -> 29.0
        CameraModel.MAVIC_3M -> 28.0
        CameraModel.ZENMUSE_P1_35, CameraModel.ZENMUSE_L2, CameraModel.ZENMUSE_H30_WIDE -> 38.0
        CameraModel.MAVIC_2_PRO -> 20.0
        else -> 19.5
    }

    /** Photos are taken along the route between the first and last survey waypoint. */
    private fun photoEstimate(r: SurveyResult): Int {
        val w = r.plan.waypoints
        val first = w.indexOfFirst { !it.transit }
        val last = w.indexOfLast { !it.transit }
        if (first < 0 || last <= first || r.photoSpacingM <= 0) return 0
        val len = (first until last).sumOf { Geo.distanceM(w[it].position, w[it + 1].position) }
        return (len / r.photoSpacingM).toInt() + r.lineCount
    }

    private fun gsdAt(h: Double): Double = camera().gsdM(h) * 100

    private fun hectares(ring: List<LatLng>): String {
        if (ring.size < 3) return "—"
        val lat0 = Math.toRadians(ring.map { it.lat }.average())
        val pts = ring.map { Math.toRadians(it.lng) * cos(lat0) * 6_371_000.0 to Math.toRadians(it.lat) * 6_371_000.0 }
        val m2 = abs(pts.indices.sumOf { i -> val (x1, y1) = pts[i]; val (x2, y2) = pts[(i + 1) % pts.size]; x1 * y2 - x2 * y1 }) / 2
        return formatArea(m2)
    }

    private fun formatArea(m2: Double): String = if (m2 >= 1_000_000) getString(R.string.plan_area_km2, m2 / 1e6) else if (m2 >= 100_000) getString(R.string.plan_area_ha_whole, m2 / 1e4) else getString(R.string.plan_area_ha, m2 / 1e4)

    /** The route (mission) or area (manual) against the terrain model, from the planned take-off point. */
    private fun terrainReport(): TerrainReport? {
        val t = graph.terrain ?: return null
        val c = center ?: return null
        return runCatching {
            if (mission) surveyResult?.plan?.waypoints?.let { TerrainCheck.route(t, c, it) }
            else area?.let { TerrainCheck.area(t, c, it, maxHeight.current) }
        }.getOrNull()
    }

    /** Lines for the UTM section, whether any is a warning, and a reason that blocks declaring. */
    private fun terrainLines(legal: Double): Triple<String, Boolean, String?> {
        // Asked to follow the terrain and can't: declaring the flat survey instead would surprise the pilot.
        val failed = if (mission && followFailed) getString(R.string.plan_terrain_follow_failed) else null
        val r = terrainReport ?: return Triple(
            listOfNotNull(if (graph.terrain == null) getString(R.string.plan_terrain_none) else null, failed).joinToString("\n"),
            failed != null, failed,
        )
        val adv = TerrainCheck.advisories(r, legal)
        val lines = buildList {
            failed?.let { add(it) }
            surveyResult?.plan?.takeIf { mission && it.terrainFollowM != null }?.let { p ->
                val added = (p.waypoints.size - flatWaypoints).coerceAtLeast(0)
                add(resources.getQuantityString(R.plurals.plan_terrain_follow, added, p.terrainFollowM, added))
                add(getString(R.string.plan_terrain_follow_heights, p.waypoints.minOf { it.heightM }, p.waypoints.maxOf { it.heightM }))
            }
            add(getString(if (mission) R.string.plan_terrain_ground_route else R.string.plan_terrain_ground_area, signed(r.lowestM), signed(r.highestM)))
            r.minClearanceM?.takeIf { adv.none { a -> a.code.startsWith("TERRAIN_INTO") || a.code == "TERRAIN_LOW_CLEARANCE" } }?.let { add(getString(R.string.plan_terrain_clearance, it)) }
            adv.forEach { add(advisoryText(it)) }
            terrainCrossCheck()?.let { add(it) }
        }
        return Triple(lines.joinToString("\n"), adv.isNotEmpty() || failed != null, failed ?: adv.firstOrNull { it.level == Level.CRITICAL }?.let { advisoryText(it) })
    }

    private fun signed(v: Double) = "%+.0f".format(v).replace("-", "−")

    /** A ground height from GNSS or a saved site far from the terrain model's: worth a look before declaring. */
    private fun terrainCrossCheck(): String? {
        val g = ground ?: return null
        if (g.source == AltitudeSource.TERRAIN_MODEL) return null
        val c = center ?: return null
        val t = graph.terrain ?: return null
        val model = t.elevationM(c)?.let { graph.grid.mslToEllipsoid(c.lat, c.lng, it) } ?: return null
        val d = g.ellipsoidM - model
        val tolerance = maxOf(20.0, 3 * kotlin.math.sqrt(g.sigmaM * g.sigmaM + t.sigmaM * t.sigmaM))
        if (kotlin.math.abs(d) <= tolerance) return null
        if (g.source == AltitudeSource.DEVICE_GNSS) {
            val n = graph.grid.mslToEllipsoid(c.lat, c.lng, 0.0)
            if (GnssDatumCheck.check(g.ellipsoidM, model - n, n, tolerance / 1.5) == GnssDatumCheck.Verdict.LIKELY_SEA_LEVEL) {
                return getString(R.string.plan_terrain_gnss_msl, kotlin.math.abs(n))
            }
        }
        return getString(if (d > 0) R.string.plan_terrain_check_lower else R.string.plan_terrain_check_higher, kotlin.math.abs(d))
    }

    private fun sourceLabel(s: AltitudeSource) = altitudeSourceText(s)

    /** Entered elevation > saved site > this device's GNSS (if here) > nearest saved site > the aircraft (if here) > the terrain model. */
    private fun groundEstimate(): GroundEstimate? {
        val c = center ?: return null
        groundMsl?.let { return GroundEstimate(graph.grid.mslToEllipsoid(c.lat, c.lng, it), 15.0, AltitudeSource.SITE_ESTIMATE) }
        graph.sites.all().getOrNull(siteSpinner.selectedItemPosition - 1)?.let { return it.ground.copy(source = AltitudeSource.SITE_ESTIMATE) }
        val fix = graph.deviceFix.value
        if (fix != null && Geo.distanceM(LatLng(fix.lat, fix.lng), c) < 300) graph.deviceGroundEstimate()?.let { return it }
        graph.sites.nearest(c)?.let { return it.ground.copy(source = AltitudeSource.SITE_ESTIMATE) }
        val st = graph.service.status.value.lastState
        val home = st?.home ?: st?.position
        if (st != null && home != null && home.isValid && Geo.distanceM(home, c) < 300) {
            TakeoffReferenceResolver.djiCandidate(st.takeoffMslM, home, graph.grid)?.let { return GroundEstimate(it.ellipsoidM, it.sigmaM, it.source) }
        }
        graph.terrain?.let { t ->
            t.elevationM(c)?.let { return GroundEstimate(graph.grid.mslToEllipsoid(c.lat, c.lng, it), t.sigmaM, AltitudeSource.TERRAIN_MODEL) }
        }
        return null
    }

    private fun askElevation() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setTextColor(color(R.color.text)); textSize = 22f
            groundMsl?.let { setText("%.0f".format(it)) }
        }
        Sheet(this).title(getString(R.string.plan_elevation_title))
            .body(input).body(Sheet.note(this, getString(R.string.plan_elevation_note)))
            .button(getString(R.string.plan_elevation_clear)) { groundMsl = null; recompute(force = true) }
            .button(getString(R.string.plan_elevation_use), Kind.PRIMARY) { groundMsl = input.text.toString().replace(',', '.').toDoubleOrNull(); recompute(force = true) }
            .show()
        input.requestFocus()
    }

    private fun draft(legal: Double) = runCatching {
        val a = area ?: return null
        val g = ground ?: return null
        DeclarationPlanner.plan(
            DeclarationInput(
                clientRef = "preview", ring = a, ground = g, plannedMaxHeightM = maxHeight(legal), legalMaxHeightM = legal,
                missionMaxHeightM = surveyResult?.plan?.maxHeightM, groundRiseM = groundRise(), plannedStartMs = startMs, plannedEndMs = startMs + (hours.current * 3_600_000).toLong(),
                nowMs = graph.clock.nowMs(), aircraftSerial = "-", originatingParty = "-", submittedBy = "-",
            ),
        )
    }.getOrNull()

    /**
     * A mission declares its highest point (travel altitude included) + 10 m, within the legal limit;
     * that counts from the ground, so over terrain the route follows, from its highest ground.
     */
    private fun maxHeight(legal: Double): Double =
        if (mission) min(legal + groundRise(), (surveyResult?.plan?.maxHeightM ?: altitude.current) + 10) else min(legal, maxHeight.current)

    /** How far a terrain-following route's highest ground is above take-off; 0 for any other flight. */
    private fun groundRise(): Double = if (mission) surveyResult?.plan?.groundRiseM ?: 0.0 else 0.0

    private fun refuse(msg: String) {
        Sheet(this).title(getString(R.string.plan_refuse_title)).message(msg).button(getString(R.string.plan_ok)).show()
    }

    private fun declare() {
        val p = graph.provisioning.value ?: return
        val session = signedIn()
        if (p.pilotLogin?.required == true && session == null) return refuse(getString(R.string.plan_refuse_sign_in))
        // A signed-in pilot is recorded by their roster id if the roster lists them, else by the identity server's id.
        val pilotId = session?.let { p.rosterEntry(it)?.id ?: it.subject }
            ?: (pilots.getOrNull(pilotSpinner.selectedItemPosition) ?: return refuse(getString(R.string.plan_refuse_no_pilots))).id
        val ac = aircraft.getOrNull(aircraftSpinner.selectedItemPosition) ?: return refuse(getString(R.string.plan_refuse_no_aircraft))
        val a = area ?: return refuse(getString(if (mission) R.string.plan_refuse_corners else R.string.plan_refuse_takeoff))
        val g = ground ?: return refuse(getString(R.string.plan_refuse_ground))
        val r = surveyResult
        if (mission && r == null) return refuse(getString(R.string.plan_refuse_grid))
        r?.let { res ->
            val limits = missionLimits()
            val problems = MissionValidator.split(res.plan, limits).flatMap { MissionValidator.validate(it, limits, p.legalMaxHeightM) }
            if (problems.isNotEmpty()) return refuse(problems.first())
        }
        val siteNameText = siteName.text.toString().trim().ifBlank { null }
        val plan = FlightPlan(
            flightId = UUID.randomUUID().toString(), kind = if (mission) OperationKind.MISSION else OperationKind.MANUAL, area = Geo.closeRing(a), ground = g,
            plannedMaxHeightM = maxHeight(p.legalMaxHeightM), legalMaxHeightM = p.legalMaxHeightM, missionMaxHeightM = r?.plan?.maxHeightM,
            plannedStartMs = startMs, plannedEndMs = startMs + (hours.current * 3_600_000).toLong(), aircraftSerial = ac.serial, registrationId = ac.registration,
            pilotId = pilotId, lab = p.lab, operatorId = p.operatorId, description = "${p.lab} ${if (mission) "mapping mission" else "manual VLOS flight"}${siteNameText?.let { " at $it" } ?: ""}",
            siteName = siteNameText, mission = r?.plan, surveyArea = if (mission) survey.toList() else null,
            // The flight record keeps no email: the roster's name, else the identity server's id.
            pilotSignIn = session?.let { s -> s.copy(email = null, name = p.rosterEntry(s)?.name ?: s.name.takeUnless { n -> n == s.email } ?: s.subject) },
        )
        val current = graph.service.status.value.activeFlight
        val go = {
            ui.launch {
                if (current != null && graph.flightInProgress(current) && !current.everFlown) graph.service.cancel()
                graph.service.plan(plan)
                FlyState.clearTicks()
                launch { graph.zones.refresh(Geo.bbox(plan.area)) }
                finish()
            }
        }
        val conflicts = FlightAdvisor.areaConflicts(a, graph.zones.index.value, graph.aircraftZones.value)
        when {
            current != null && graph.flightInProgress(current) && current.everFlown -> refuse(getString(R.string.plan_refuse_in_flight))
            current != null && graph.flightInProgress(current) -> Sheet(this).title(getString(R.string.plan_replace_title))
                .message(getString(R.string.plan_replace_message)).button(getString(R.string.plan_replace_keep)).button(getString(R.string.plan_replace), Kind.PRIMARY) { go() }.show()
            conflicts.isNotEmpty() -> Sheet(this).title(getString(R.string.plan_zones_title))
                .message(org.openutm.djiflight.core.zones.ZoneSummary.of(conflicts).joinToString("\n") { getString(R.string.plan_zones_summary_item, zoneDescription(it)) } + "\n\n" + getString(R.string.plan_zones_question))
                .button(getString(R.string.plan_go_back)).button(getString(R.string.plan_declare_anyway), Kind.WARN) { go() }.show()
            else -> go()
        }
    }

    private fun pickDate() {
        val c = Calendar.getInstance().apply { timeInMillis = startMs }
        DatePickerDialog(this, { _, y, m, d ->
            c.set(y, m, d)
            startMs = c.timeInMillis.coerceAtLeast(graph.clock.nowMs())
            recompute(force = true)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply { datePicker.minDate = graph.clock.nowMs() - 1000 }.show()
    }

    private fun pickTime() {
        val c = Calendar.getInstance().apply { timeInMillis = startMs }
        TimePickerDialog(this, { _, h, m ->
            c.set(Calendar.HOUR_OF_DAY, h)
            c.set(Calendar.MINUTE, m)
            startMs = c.timeInMillis.coerceAtLeast(graph.clock.nowMs())
            recompute(force = true)
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == REQ_PLAN_EXPORT) {
            runCatching {
                val plan = checkNotNull(exporting) { getString(R.string.plan_draft_failed) }
                checkNotNull(contentResolver.openOutputStream(uri)).use { it.write(library.encode(plan).toByteArray(Charsets.UTF_8)) }
                exporting = null
            }.onFailure { refuse(it.message ?: getString(R.string.plan_draft_failed)) }
            return
        }
        if (requestCode == REQ_PLAN_IMPORT) {
            runCatching {
                val buffer = java.io.ByteArrayOutputStream()
                checkNotNull(contentResolver.openInputStream(uri)).use { input ->
                    val block = ByteArray(4096)
                    while (true) {
                        val n = input.read(block); if (n < 0) break
                        require(buffer.size() + n <= 512_000) { getString(R.string.plan_draft_failed) }
                        buffer.write(block, 0, n)
                    }
                }
                // Give an imported copy its own identity: importing never overwrites a local plan.
                library.decode(buffer.toString("UTF-8")).copy(id = UUID.randomUUID().toString()).also { library.save(it); loadDraft(it) }
            }.onFailure { refuse(it.message ?: getString(R.string.plan_draft_failed)) }
            return
        }
        if (requestCode != REQ_KML) return
        runCatching {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            Kml.read(bytes).firstOrNull { if (mission && pattern == SurveyPattern.CORRIDOR) it.line != null else it.polygon != null }
                ?: error(getString(if (mission && pattern == SurveyPattern.CORRIDOR) R.string.plan_kml_no_line else R.string.plan_kml_no_polygon))
        }.onSuccess { pm ->
            val ring = if (mission && pattern == SurveyPattern.CORRIDOR) pm.line!! else pm.polygon!!
            if (mission) {
                pushUndo()
                survey.clear()
                survey += if (ring.first() == ring.last()) ring.dropLast(1) else ring
            } else {
                boundary = Geo.closeRing(ring)
                boundaryName = pm.name ?: getString(R.string.plan_boundary_name_default)
                center = LatLng(ring.map { it.lat }.average(), ring.map { it.lng }.average())
                siteSpinner.setSelection(siteNames.lastIndex)
            }
            pm.name?.let { if (siteName.text.isBlank()) siteName.setText(it) }
            fitted = false
            recompute(force = true)
        }.onFailure { Sheet(this).title(getString(R.string.plan_kml_failed_title)).message(it.message ?: getString(R.string.plan_unknown_error)).button(getString(R.string.plan_ok)).show() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onStart() { super.onStart(); if (::map.isInitialized) map.onStart() }
    override fun onResume() { super.onResume(); immersive(); if (::map.isInitialized) map.onResume() }
    override fun onPause() { if (::map.isInitialized) map.onPause(); super.onPause() }
    override fun onStop() { if (::map.isInitialized) map.onStop(); super.onStop() }
    override fun onDestroy() {
        ui.cancel()
        if (::map.isInitialized) map.onDestroy()
        super.onDestroy()
    }

    private companion object {
        const val REQ_KML = 7
        const val REQ_PLAN_IMPORT = 8
        const val REQ_PLAN_EXPORT = 9
    }
}
