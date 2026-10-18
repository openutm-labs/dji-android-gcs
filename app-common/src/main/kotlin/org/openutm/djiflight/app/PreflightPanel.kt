package org.openutm.djiflight.app

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openutm.djiflight.app.ui.ActionButton
import org.openutm.djiflight.app.ui.Kind
import org.openutm.djiflight.app.ui.Pill
import org.openutm.djiflight.app.ui.Sheet
import org.openutm.djiflight.app.ui.Stepper
import org.openutm.djiflight.app.ui.color
import org.openutm.djiflight.app.ui.dp
import org.openutm.djiflight.app.ui.rounded
import org.openutm.djiflight.app.ui.setIfChanged
import org.openutm.djiflight.app.ui.show
import org.openutm.djiflight.app.ui.text
import org.openutm.djiflight.core.adapter.AircraftSetup
import org.openutm.djiflight.core.adapter.AircraftStatus
import org.openutm.djiflight.core.adapter.Calibration
import org.openutm.djiflight.core.adapter.CalibrationProgress
import org.openutm.djiflight.core.adapter.Health
import org.openutm.djiflight.core.adapter.LinkState
import org.openutm.djiflight.core.adapter.RcLostAction
import org.openutm.djiflight.core.adapter.SensorStatus
import org.openutm.djiflight.core.adapter.SettingChange
import org.openutm.djiflight.core.adapter.SettingLimits
import org.openutm.djiflight.core.adapter.SetupItem
import org.openutm.djiflight.core.advisory.FlightAdvisor
import org.openutm.djiflight.core.altitude.AltitudeSource
import org.openutm.djiflight.core.altitude.GnssDatumCheck
import org.openutm.djiflight.core.advisory.Level
import java.text.DateFormat
import java.util.Date
import kotlin.math.min

/**
 * Pre-flight (docs/12, The app): the lab's checklist, the aircraft's own checks with calibration,
 * and the flight-controller settings pilots know from DJI Pilot 2 and GO 4, in the same order as
 * DJI's system status list. Every change is made on the pilot's tap and shown as the aircraft
 * reads it back. In v4, DJI's own pre-flight checklist stays one tap away on its status widget; in
 * v5, DJI's status list (same place) and DJI's settings panel ([Platform.vendorSettings]).
 */
class PreflightPanel(private val activity: Activity, private val graph: AppGraph, private val vendorChecklistNote: Boolean) {
    private val ui = MainScope()
    private val sheet = Sheet(activity, wide = true)
    private val setup: AircraftSetup? get() = graph.adapter.aircraftSetup()

    private val checks = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val checklistBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val checklistTitle = activity.text(17f, bold = true)
    private val checklistDone = ActionButton(activity).set(activity.getString(R.string.preflight_checklist_done), R.drawable.ic_check, Kind.PRIMARY)
    private val tickRows = ArrayList<Pair<View, ImageView>>()
    private val aircraftRows = LinkedHashMap<String, Triple<TextView, Pill, ActionButton?>>()
    private val calibrationBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val calibrationText = activity.text(18f, bold = true)
    private val calibrationBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
    private val calibrationCancel = ActionButton(activity).set(activity.getString(R.string.preflight_cancel), R.drawable.ic_close, Kind.SECONDARY)
    private val settingsBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val settingsNote = activity.text(14f, R.color.text3)
    private var rows: SettingRows? = null

    fun show() {
        val cols = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        cols.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = activity.dp(14) })
        cols.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = activity.dp(14) })

        left.addView(checks)
        buildAltitudeChoice()
        left.addView(altitudeBox)
        buildChecklist()
        left.addView(checklistBox)
        left.addView(header(activity.getString(R.string.preflight_header_aircraft)))
        // The key names the row in code; the label is what the pilot reads.
        for ((key, label) in listOf(
            "Connection" to R.string.preflight_row_connection, "GNSS" to R.string.preflight_row_gnss, "Battery" to R.string.preflight_row_battery,
            "Flight mode" to R.string.preflight_row_flight_mode, "Home point" to R.string.preflight_row_home_point, "Compass" to R.string.preflight_row_compass,
            "IMU" to R.string.preflight_row_imu, "Gimbal" to R.string.preflight_row_gimbal,
        )) left.addView(aircraftRow(key, activity.getString(label)))
        if (vendorChecklistNote) left.addView(Sheet.note(activity, activity.getString(R.string.preflight_dji_sensor_note)))
        buildCalibration()
        left.addView(calibrationBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(10) })

        right.addView(header(activity.getString(R.string.preflight_header_settings)))
        // The vendor's full settings and calibration screens, next to the app's own (v5: DJI Pilot 2's).
        graph.platform.vendorSettings(activity)?.let { intent ->
            right.addView(ActionButton(activity).set(activity.getString(R.string.preflight_dji_settings), R.drawable.ic_sliders, Kind.SECONDARY).apply {
                setOnClickListener { activity.startActivity(intent) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(52)).apply { bottomMargin = activity.dp(10) })
        }
        right.addView(settingsNote)
        right.addView(settingsBox)

        sheet.title(activity.getString(R.string.preflight_title)).body(cols).button(activity.getString(R.string.preflight_close), Kind.SECONDARY).onDismiss { ui.cancel() }.show()
        ui.launch { setup?.refresh(); render() }
        ui.launch {
            while (isActive) {
                render()
                delay(700)
            }
        }
    }

    // --- take-off height, when the controller and the aircraft disagree (docs/12, Altitude reference)

    private val altitudeBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val altitudeText = activity.text(15f, R.color.text)

    private fun buildAltitudeChoice() {
        altitudeBox.background = rounded(activity.color(R.color.surface2), activity.dp(8).toFloat(), activity.dp(2), activity.color(R.color.warn))
        altitudeBox.setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
        altitudeBox.addView(activity.text(16f, bold = true).apply { text = activity.getString(R.string.preflight_takeoff_title) })
        altitudeBox.addView(altitudeText.apply { setPadding(0, activity.dp(4), 0, activity.dp(8)); setLineSpacing(activity.dp(2).toFloat(), 1f) })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for ((source, label) in listOf(
            AltitudeSource.DEVICE_GNSS to activity.getString(R.string.preflight_takeoff_controller),
            AltitudeSource.DJI_TAKEOFF_MSL to activity.getString(R.string.preflight_takeoff_aircraft),
        )) {
            row.addView(ActionButton(activity).set(label, null, Kind.SECONDARY).apply {
                setOnClickListener {
                    val f = graph.service.status.value.activeFlight ?: return@setOnClickListener
                    ui.launch { graph.service.chooseTakeoffReference(f.flightId, source); toast(activity.getString(R.string.preflight_takeoff_chosen, label)) }
                }
            }, LinearLayout.LayoutParams(0, activity.dp(48), 1f).apply { if (row.childCount > 0) marginStart = activity.dp(8) })
        }
        altitudeBox.addView(row)
        altitudeBox.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = activity.dp(8) }
    }

    private fun renderAltitudeChoice(f: org.openutm.djiflight.core.session.FlightSnapshot?) {
        val ref = f?.takeoffReference?.takeIf { it.pilotCheckNeeded && !f.recordingClosed }
        altitudeBox.show(ref != null)
        if (ref == null) return
        val device = ref.candidates.firstOrNull { it.source == AltitudeSource.DEVICE_GNSS }
        val dji = ref.candidates.firstOrNull { it.source == AltitudeSource.DJI_TAKEOFF_MSL }
        val home = graph.service.status.value.lastState?.let { it.home ?: it.position }?.takeIf { it.isValid }
        val n = home?.let { graph.grid.mslToEllipsoid(it.lat, it.lng, 0.0) }
        val terrainMsl = home?.let { h -> graph.terrain?.let { t -> runCatching { t.elevationM(h) }.getOrNull() } }
        val lines = buildList {
            add(activity.getString(R.string.preflight_takeoff_readings, device?.ellipsoidM ?: Double.NaN, dji?.ellipsoidM ?: Double.NaN, ref.disagreementM ?: 0.0))
            if (terrainMsl != null && n != null) {
                add(activity.getString(R.string.preflight_takeoff_terrain, terrainMsl + n))
                if (device != null && GnssDatumCheck.check(device.ellipsoidM, terrainMsl, n, 20.0) == GnssDatumCheck.Verdict.LIKELY_SEA_LEVEL) {
                    add(activity.getString(R.string.preflight_takeoff_sea_level))
                }
            }
            add(activity.getString(R.string.preflight_takeoff_band))
        }
        altitudeText.setIfChanged(lines.joinToString("\n"))
    }

    private fun header(s: String) = activity.text(17f, bold = true).apply { text = s; setPadding(0, activity.dp(14), 0, activity.dp(6)) }

    // --- lab checklist

    private fun buildChecklist() {
        val items = graph.provisioning.value?.checklist.orEmpty()
        checklistBox.removeAllViews()
        tickRows.clear()
        if (items.isEmpty()) return
        checklistBox.addView(checklistTitle.apply { setPadding(0, 0, 0, activity.dp(6)) })
        val ticks = FlyState.ticks(items.size)
        items.forEachIndexed { i, item ->
            val box = ImageView(activity).apply {
                setImageResource(R.drawable.ic_check)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(activity.dp(3), activity.dp(3), activity.dp(3), activity.dp(3))
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = activity.dp(52)
                isClickable = true
                background = activity.getDrawable(android.R.drawable.list_selector_background)
                addView(box, LinearLayout.LayoutParams(activity.dp(30), activity.dp(30)).apply { marginEnd = activity.dp(14) })
                addView(activity.text(16f).apply { text = item }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener {
                    if (graph.service.status.value.activeFlight?.checklist != null) return@setOnClickListener
                    ticks[i] = !ticks[i]
                    render()
                }
            }
            tickRows += row to box
            checklistBox.addView(row)
        }
        checklistBox.addView(checklistDone, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(52)).apply { topMargin = activity.dp(8) })
        checklistDone.setOnClickListener {
            val f = graph.service.status.value.activeFlight ?: return@setOnClickListener
            if (!ticks.all { it }) return@setOnClickListener
            ui.launch {
                graph.service.completeChecklist(f.flightId, items, f.plan?.pilotId)
                FlyState.clearTicks()
                render()
            }
        }
    }

    private fun renderChecklist() {
        val items = graph.provisioning.value?.checklist.orEmpty()
        if (items.isEmpty()) return
        val f = graph.service.status.value.activeFlight
        val done = f?.checklist
        val canTick = f?.plan != null && !f.recordingClosed && done == null
        val ticks = FlyState.ticks(items.size)
        val n = if (done != null) items.size else ticks.count { it }
        checklistTitle.setIfChanged(
            when {
                done != null -> activity.getString(R.string.preflight_checklist_done_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(done.at)))
                f?.plan == null || f.recordingClosed -> activity.getString(R.string.preflight_checklist_no_plan)
                else -> activity.getString(R.string.preflight_checklist_progress, n, items.size)
            },
        )
        tickRows.forEachIndexed { i, (row, box) ->
            val on = done != null || ticks.getOrElse(i) { false }
            box.background = if (on) rounded(activity.color(R.color.ok), activity.dp(6).toFloat())
            else rounded(0, activity.dp(6).toFloat(), activity.dp(2), activity.color(R.color.text2))
            box.imageAlpha = if (on) 255 else 0
            row.isEnabled = canTick
            row.alpha = if (canTick || done != null) 1f else 0.5f
        }
        checklistDone.show(done == null && canTick)
        checklistDone.isEnabled = ticks.all { it }
        checklistDone.label.setIfChanged(if (ticks.all { it }) activity.getString(R.string.preflight_checklist_done) else activity.getString(R.string.preflight_checklist_left, items.size - n))
    }

    // --- aircraft checks and calibration

    private fun aircraftRow(key: String, label: String): View {
        val value = activity.text(15f, R.color.text2).apply { maxLines = 2 }
        val pill = Pill(activity, 13f)
        val action = when (key) {
            "Compass" -> ActionButton(activity).set(activity.getString(R.string.preflight_calibrate), null, Kind.SECONDARY).also { b -> b.setOnClickListener { askCalibrate(Calibration.COMPASS) } }
            "IMU" -> ActionButton(activity).set(activity.getString(R.string.preflight_calibrate), null, Kind.SECONDARY).also { b -> b.setOnClickListener { askCalibrate(Calibration.IMU) } }
            "Gimbal" -> ActionButton(activity).set(activity.getString(R.string.preflight_calibrate), null, Kind.SECONDARY).also { b -> b.setOnClickListener { askCalibrate(Calibration.GIMBAL) } }
            "Home point" -> ActionButton(activity).set(activity.getString(R.string.preflight_update), null, Kind.SECONDARY).also { b -> b.setOnClickListener { askHome() } }
            else -> null
        }
        aircraftRows[key] = Triple(value, pill, action)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = activity.dp(48)
            val texts = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(activity.text(15f, bold = true).apply { text = label })
                addView(value)
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = activity.dp(6) })
            if (action != null) {
                action.minimumHeight = activity.dp(44)
                addView(action, LinearLayout.LayoutParams(activity.dp(110), activity.dp(44)).apply { marginStart = activity.dp(8) })
            }
            tag = key
        }
    }

    private fun buildCalibration() {
        calibrationBox.background = rounded(activity.color(R.color.surface2), activity.dp(10).toFloat())
        calibrationBox.setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
        calibrationBox.addView(calibrationText)
        calibrationBox.addView(calibrationBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(10)).apply { topMargin = activity.dp(10) })
        calibrationBox.addView(calibrationCancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(48)).apply { topMargin = activity.dp(10) })
        calibrationCancel.setOnClickListener { ui.launch { setup?.cancelCalibration() } }
        calibrationBox.show(false)
    }

    private fun askCalibrate(target: Calibration) {
        val s = setup ?: return
        val (title, text) = when (target) {
            Calibration.COMPASS -> R.string.preflight_calibrate_compass_title to R.string.preflight_calibrate_compass_text
            Calibration.IMU -> R.string.preflight_calibrate_imu_title to R.string.preflight_calibrate_imu_text
            Calibration.GIMBAL -> R.string.preflight_calibrate_gimbal_title to R.string.preflight_calibrate_gimbal_text
        }
        Sheet(activity).title(activity.getString(title)).message(activity.getString(text))
            .button(activity.getString(R.string.preflight_not_now))
            .button(activity.getString(R.string.preflight_start), Kind.PRIMARY) { ui.launch { if (s.calibrate(target).isSuccess) showCalibration(target) ; render() } }
            .show()
    }

    /**
     * The calibration in large type, in its own sheet: the pilot is holding the aircraft and glances
     * at the screen, as with DJI's own calibration screens. It follows the aircraft's stages live.
     */
    private fun showCalibration(target: Calibration) {
        val s = setup ?: return
        val title = activity.getString(when (target) {
            Calibration.COMPASS -> R.string.preflight_calibration_compass
            Calibration.IMU -> R.string.preflight_calibration_imu
            Calibration.GIMBAL -> R.string.preflight_calibration_gimbal
        })
        val step = activity.text(24f, bold = true).apply { setLineSpacing(activity.dp(4).toFloat(), 1f) }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(step)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(12)).apply { topMargin = activity.dp(16) })
        }
        var button: ActionButton? = null
        val sheet = Sheet(activity).title(title).body(body)
            .button(activity.getString(if (target == Calibration.COMPASS) R.string.preflight_cancel_calibration else R.string.preflight_hide), Kind.SECONDARY, ref = { button = it }) {
                if (target == Calibration.COMPASS && s.status.value.calibration?.state == CalibrationProgress.State.RUNNING) ui.launch { s.cancelCalibration() }
            }
        sheet.show()
        ui.launch {
            while (isActive && sheet.isShowing) {
                val c = s.status.value.calibration?.takeIf { it.target == target }
                if (c != null) {
                    step.setIfChanged(c.instruction)
                    step.setTextColor(activity.color(when (c.state) {
                        CalibrationProgress.State.RUNNING -> R.color.text
                        CalibrationProgress.State.SUCCEEDED -> R.color.ok
                        CalibrationProgress.State.FAILED -> R.color.crit
                    }))
                    bar.isIndeterminate = c.state == CalibrationProgress.State.RUNNING && c.percent == null
                    c.percent?.let { bar.progress = it }
                    if (c.state != CalibrationProgress.State.RUNNING) {
                        button?.set(activity.getString(R.string.preflight_done), R.drawable.ic_check, if (c.state == CalibrationProgress.State.SUCCEEDED) Kind.PRIMARY else Kind.SECONDARY)
                    }
                }
                delay(300)
            }
        }
    }

    private fun askHome() {
        val s = setup ?: return
        Sheet(activity).title(activity.getString(R.string.preflight_home_title))
            .message(activity.getString(R.string.preflight_home_message))
            .button(activity.getString(R.string.preflight_cancel))
            .button(activity.getString(R.string.preflight_update), Kind.PRIMARY) {
                ui.launch { val r = s.resetHomeToAircraft(); toast(if (r.isSuccess) activity.getString(R.string.preflight_home_updated) else activity.getString(R.string.preflight_home_not_updated, r.exceptionOrNull()?.message)) }
            }
            .show()
    }

    private fun health(s: SensorStatus): Pair<String, Pill.Tone> = when (s.health) {
        Health.OK -> activity.getString(R.string.preflight_pill_ok) to Pill.Tone.OK
        Health.WARNING -> (s.detail ?: activity.getString(R.string.preflight_pill_check)) to Pill.Tone.WARN
        Health.ERROR -> (s.detail ?: activity.getString(R.string.preflight_pill_error)) to Pill.Tone.CRIT
        Health.UNKNOWN -> activity.getString(R.string.preflight_no_value) to Pill.Tone.NEUTRAL
    }

    private fun renderAircraft(a: AircraftStatus?) {
        val st = graph.service.status.value
        val d = st.lastState
        val connected = st.link is LinkState.Connected
        fun row(key: String, value: String, pill: Pair<String, Pill.Tone>?, actionEnabled: Boolean = false, visible: Boolean = true) {
            val (v, p, act) = aircraftRows[key] ?: return
            (v.parent?.parent as? View)?.show(visible)
            v.setIfChanged(value)
            if (pill == null) p.show(false) else { p.show(true); p.set(pill.first, pill.second) }
            act?.isEnabled = actionEnabled
        }
        val none = activity.getString(R.string.preflight_no_value)
        val idn = (st.link as? LinkState.Connected)?.identity
        row("Connection", idn?.let { activity.getString(R.string.preflight_connection_identity, it.model, it.serial) }
            ?: if (st.link is LinkState.Connecting) activity.getString(R.string.preflight_connecting) else activity.getString(R.string.preflight_not_connected),
            if (connected) (if (st.telemetryStale) activity.getString(R.string.preflight_pill_no_data) to Pill.Tone.CRIT else activity.getString(R.string.preflight_pill_connected) to Pill.Tone.OK)
            else activity.getString(R.string.preflight_pill_off) to Pill.Tone.NEUTRAL)
        row("GNSS", d?.let { activity.getString(R.string.preflight_gnss_value, it.satellites ?: 0, it.gpsLevel ?: 0) } ?: none,
            d?.gpsLevel?.let {
                if (it >= 4) activity.getString(R.string.preflight_pill_good) to Pill.Tone.OK
                else if (it >= 3) activity.getString(R.string.preflight_pill_fair) to Pill.Tone.WARN
                else activity.getString(R.string.preflight_pill_weak) to Pill.Tone.CRIT
            })
        row("Battery", d?.batteryPercent?.let {
            a?.settings?.lowBatteryPercent?.let { l -> activity.getString(R.string.preflight_battery_value_warns, it, l) } ?: activity.getString(R.string.preflight_battery_value, it)
        } ?: none,
            d?.batteryPercent?.let {
                if (it > 30) activity.getString(R.string.preflight_pill_ok) to Pill.Tone.OK
                else if (it > 20) activity.getString(R.string.preflight_pill_low) to Pill.Tone.WARN
                else activity.getString(R.string.preflight_pill_land) to Pill.Tone.CRIT
            })
        row("Flight mode", d?.flightMode ?: none, null)
        val supports = a?.supports.orEmpty()
        val idle = a?.calibration?.state != CalibrationProgress.State.RUNNING && d?.motorsOn != true
        row("Home point", activity.getString(if (d?.home?.isValid == true) R.string.preflight_home_recorded else R.string.preflight_home_not_recorded),
            if (d?.home?.isValid == true) activity.getString(R.string.preflight_pill_set) to Pill.Tone.OK else none to Pill.Tone.NEUTRAL,
            actionEnabled = connected && SetupItem.HOME_RESET in supports && d?.isFlying != true, visible = connected)
        row("Compass", a?.compass?.detail ?: none, a?.compass?.let(::health), connected && SetupItem.COMPASS in supports && idle, visible = SetupItem.COMPASS in supports)
        row("IMU", a?.imu?.detail ?: none, a?.imu?.let(::health), connected && SetupItem.IMU in supports && idle, visible = SetupItem.IMU in supports)
        row("Gimbal", a?.gimbal?.detail ?: none, a?.gimbal?.let(::health), connected && SetupItem.GIMBAL in supports && idle, visible = SetupItem.GIMBAL in supports)

        val c = a?.calibration
        calibrationBox.show(c != null)
        if (c != null) {
            val what = activity.getString(when (c.target) {
                Calibration.COMPASS -> R.string.preflight_row_compass
                Calibration.IMU -> R.string.preflight_row_imu
                Calibration.GIMBAL -> R.string.preflight_row_gimbal
            })
            calibrationText.setIfChanged(activity.getString(R.string.preflight_calibration_line, what, c.instruction))
            calibrationText.setTextColor(activity.color(if (c.state == CalibrationProgress.State.FAILED) R.color.crit else R.color.text))
            calibrationBar.show(c.state == CalibrationProgress.State.RUNNING)
            calibrationBar.isIndeterminate = c.percent == null
            c.percent?.let { calibrationBar.progress = it }
            calibrationCancel.show(c.state == CalibrationProgress.State.RUNNING && c.target == Calibration.COMPASS)
        }
    }

    // --- settings

    private inner class SettingRows(a: AircraftStatus) {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val pending = HashMap<SetupItem, Job>()
        val steppers = HashMap<SetupItem, Stepper>()
        val hints = HashMap<SetupItem, TextView>()
        var rcButtons: List<Pair<RcLostAction, ActionButton>> = emptyList()
        var distanceToggle: ActionButton? = null
        var obstacleToggle: ActionButton? = null

        init {
            val s = a.settings
            val plan = graph.service.status.value.activeFlight?.plan
            val legal = plan?.legalMaxHeightM ?: graph.provisioning.value?.legalMaxHeightM ?: 120.0
            // unitFormat: the value with its unit, e.g. R.string.preflight_stepper_m.
            fun stepper(item: SetupItem, label: String, hint: String, v: Int?, range: IntRange, step: Int, unitFormat: Int, change: (Int) -> SettingChange) {
                if (item !in a.supports || v == null) return
                val st = Stepper(activity, label, v.toDouble(), range.first.toDouble(), range.last.toDouble(), step.toDouble(), { activity.getString(unitFormat, it) }, typedStep = 1.0) { nv ->
                    write(item, change(nv.toInt()))
                }
                steppers[item] = st
                val row = Stepper.row(activity, label, hint, st)
                hints[item] = row.findViewWithTag("hint")
                box.addView(row)
            }
            stepper(SetupItem.RETURN_HOME_HEIGHT, activity.getString(R.string.preflight_setting_rth), "", s.returnHomeHeightM, SettingLimits.RETURN_HOME_HEIGHT_M, 5, R.string.preflight_stepper_m) {
                SettingChange.ReturnHomeHeight(it)
            }
            stepper(SetupItem.MAX_ALTITUDE, activity.getString(R.string.preflight_setting_max_altitude), activity.getString(R.string.preflight_setting_legal_limit, legal), s.maxAltitudeM,
                SettingLimits.MAX_ALTITUDE_M, 10, R.string.preflight_stepper_m) { SettingChange.MaxAltitude(it) }
            if (SetupItem.MAX_DISTANCE in a.supports && s.maxDistanceM != null) {
                val t = ActionButton(activity).set(activity.getString(if (s.maxDistanceEnabled == true) R.string.preflight_on else R.string.preflight_off), null, if (s.maxDistanceEnabled == true) Kind.PRIMARY else Kind.SECONDARY)
                distanceToggle = t
                t.setOnClickListener {
                    val now = graph.adapter.aircraftSetup()?.status?.value?.settings ?: return@setOnClickListener
                    write(SetupItem.MAX_DISTANCE, SettingChange.MaxDistance(steppers[SetupItem.MAX_DISTANCE]?.current?.toInt() ?: (now.maxDistanceM ?: 500), now.maxDistanceEnabled != true))
                }
                box.addView(toggleRow(activity.getString(R.string.preflight_setting_distance_limit), activity.getString(R.string.preflight_setting_distance_limit_hint), t))
                stepper(SetupItem.MAX_DISTANCE, activity.getString(R.string.preflight_setting_max_distance), activity.getString(R.string.preflight_setting_max_distance_hint), s.maxDistanceM,
                    SettingLimits.MAX_DISTANCE_M, 50, R.string.preflight_stepper_m) {
                    SettingChange.MaxDistance(it, graph.adapter.aircraftSetup()?.status?.value?.settings?.maxDistanceEnabled == true)
                }
            }
            if (SetupItem.RC_LOST_ACTION in a.supports && s.rcLostAction != null) {
                box.addView(activity.text(15f, bold = true).apply { text = activity.getString(R.string.preflight_setting_rc_lost); setPadding(0, activity.dp(10), 0, activity.dp(6)) })
                val seg = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                rcButtons = listOf(
                    RcLostAction.RETURN_HOME to activity.getString(R.string.preflight_rc_return_home),
                    RcLostAction.HOVER to activity.getString(R.string.preflight_rc_hover),
                    RcLostAction.LAND to activity.getString(R.string.preflight_rc_land),
                ).map { (act, label) ->
                    act to ActionButton(activity).set(label, null, Kind.SECONDARY).also { b ->
                        b.setPadding(activity.dp(4), b.paddingTop, activity.dp(4), b.paddingBottom)
                        b.label.textSize = 15f
                        b.setOnClickListener { write(SetupItem.RC_LOST_ACTION, SettingChange.RcLost(act)) }
                        seg.addView(b, LinearLayout.LayoutParams(0, activity.dp(48), 1f).apply { if (seg.childCount > 0) marginStart = activity.dp(6) })
                    }
                }
                box.addView(seg)
            }
            stepper(SetupItem.LOW_BATTERY, activity.getString(R.string.preflight_setting_low_battery), "", s.lowBatteryPercent, SettingLimits.LOW_BATTERY_PERCENT, 5, R.string.preflight_stepper_percent) {
                SettingChange.LowBattery(it)
            }
            stepper(SetupItem.CRITICAL_BATTERY, activity.getString(R.string.preflight_setting_critical_battery), activity.getString(R.string.preflight_setting_critical_battery_hint), s.criticalBatteryPercent,
                SettingLimits.CRITICAL_BATTERY_PERCENT, 5, R.string.preflight_stepper_percent) { SettingChange.CriticalBattery(it) }
            if (SetupItem.OBSTACLE_AVOIDANCE in a.supports && s.obstacleAvoidance != null) {
                val t = ActionButton(activity).set(activity.getString(if (s.obstacleAvoidance == true) R.string.preflight_on else R.string.preflight_off), null, Kind.SECONDARY)
                obstacleToggle = t
                t.setOnClickListener {
                    val now = graph.adapter.aircraftSetup()?.status?.value?.settings?.obstacleAvoidance ?: return@setOnClickListener
                    write(SetupItem.OBSTACLE_AVOIDANCE, SettingChange.ObstacleAvoidance(!now))
                }
                box.addView(toggleRow(activity.getString(R.string.preflight_setting_obstacle), activity.getString(R.string.preflight_setting_obstacle_hint), t))
            }
        }

        private fun toggleRow(label: String, hint: String, toggle: ActionButton) = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(6), 0, activity.dp(6))
            val texts = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(activity.text(15f, bold = true).apply { text = label })
                addView(activity.text(13f, R.color.text3).apply { text = hint })
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle, LinearLayout.LayoutParams(activity.dp(96), activity.dp(48)))
        }

        /** Waits for the pilot to stop tapping, then writes once and reads back. */
        fun write(item: SetupItem, change: SettingChange) {
            pending[item]?.cancel()
            settingsNote.setIfChanged(activity.getString(R.string.preflight_saving))
            pending[item] = ui.launch {
                if (change is SettingChange.ReturnHomeHeight || change is SettingChange.MaxAltitude || change is SettingChange.LowBattery ||
                    change is SettingChange.CriticalBattery || (change is SettingChange.MaxDistance && steppers.containsKey(item))
                ) delay(700)
                val r = setup?.set(change)
                pending.remove(item)
                settingsNote.setIfChanged(
                    if (r?.isSuccess == true) activity.getString(R.string.preflight_saved)
                    else activity.getString(R.string.preflight_not_saved, r?.exceptionOrNull()?.message ?: activity.getString(R.string.preflight_no_aircraft)),
                )
                render()
            }
        }

        fun update(a: AircraftStatus) {
            val s = a.settings
            val plan = graph.service.status.value.activeFlight?.plan
            fun sync(item: SetupItem, v: Int?) { if (item !in pending) v?.let { steppers[item]?.setValue(it.toDouble()) } }
            sync(SetupItem.RETURN_HOME_HEIGHT, s.returnHomeHeightM)
            sync(SetupItem.MAX_ALTITUDE, s.maxAltitudeM)
            sync(SetupItem.MAX_DISTANCE, s.maxDistanceM)
            sync(SetupItem.LOW_BATTERY, s.lowBatteryPercent)
            sync(SetupItem.CRITICAL_BATTERY, s.criticalBatteryPercent)
            steppers[SetupItem.MAX_DISTANCE]?.let { st -> (st.parent as? View)?.show(s.maxDistanceEnabled == true) }
            distanceToggle?.set(activity.getString(if (s.maxDistanceEnabled == true) R.string.preflight_on else R.string.preflight_off), null, if (s.maxDistanceEnabled == true) Kind.PRIMARY else Kind.SECONDARY)
            obstacleToggle?.set(activity.getString(if (s.obstacleAvoidance == true) R.string.preflight_on else R.string.preflight_off), null, if (s.obstacleAvoidance == true) Kind.PRIMARY else Kind.SECONDARY)
            rcButtons.forEach { (act, b) -> b.kind = if (s.rcLostAction == act) Kind.PRIMARY else Kind.SECONDARY }
            hints[SetupItem.RETURN_HOME_HEIGHT]?.let { h ->
                val ceiling = plan?.let { min(it.plannedMaxHeightM, it.legalMaxHeightM) }
                val rth = s.returnHomeHeightM
                h.setIfChanged(if (ceiling == null) activity.getString(R.string.preflight_rth_hint_no_plan) else activity.getString(R.string.preflight_rth_hint_ceiling, ceiling))
                h.setTextColor(activity.color(if (ceiling != null && rth != null && rth > ceiling + 0.5) R.color.warn else R.color.text3))
            }
            hints[SetupItem.MAX_ALTITUDE]?.let { h ->
                val legal = plan?.legalMaxHeightM ?: graph.provisioning.value?.legalMaxHeightM ?: 120.0
                h.setTextColor(activity.color(if ((s.maxAltitudeM ?: 0) > legal + 0.5) R.color.warn else R.color.text3))
            }
            if (s.rcLostAction != null && plan?.mission != null && s.rcLostAction != RcLostAction.RETURN_HOME) {
                settingsNote.setIfChanged(activity.getString(R.string.preflight_missions_expect_rth))
            }
        }
    }

    private fun renderSettings(a: AircraftStatus?) {
        val connected = graph.service.status.value.link is LinkState.Connected
        if (a == null || !connected) {
            if (rows != null) { settingsBox.removeAllViews(); rows = null }
            settingsNote.setIfChanged(activity.getString(
                if (graph.adapter.aircraftSetup() == null && connected) R.string.preflight_settings_unreadable else R.string.preflight_settings_connect,
            ))
            return
        }
        val reading = activity.getString(R.string.preflight_settings_reading)
        if (a.readAtMs == null && a.supports.isEmpty()) { settingsNote.setIfChanged(reading); return }
        val r = rows ?: SettingRows(a).also { rows = it; settingsBox.removeAllViews(); settingsBox.addView(it.box) }
        // Replaces only the waiting notes (compared as shown, so it holds in every language).
        if (settingsNote.text.isNullOrEmpty() || settingsNote.text.startsWith(reading) || settingsNote.text.startsWith(activity.getString(R.string.preflight_settings_connect))) {
            settingsNote.setIfChanged(a.readAtMs?.let { activity.getString(R.string.preflight_settings_read_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))) } ?: "")
        }
        a.error?.let { if (r.pending.isEmpty()) settingsNote.setIfChanged(activity.getString(R.string.preflight_aircraft_says, it)) }
        r.update(a)
    }

    // --- whole panel

    private fun render() {
        val a = setup?.status?.value
        val f = graph.service.status.value.activeFlight
        checks.removeAllViews()
        val plan = f?.plan
        if (plan != null && !f.recordingClosed) {
            FlightAdvisor.aircraftChecks(a, plan, graph.service.status.value.lastState?.isFlying == true).forEach { adv ->
                checks.addView(activity.text(15f, if (adv.level == Level.INFO) R.color.text2 else R.color.on_warn, bold = adv.level != Level.INFO).apply {
                    text = activity.advisoryText(adv)
                    setPadding(activity.dp(10), activity.dp(8), activity.dp(10), activity.dp(8))
                    background = rounded(activity.color(if (adv.level == Level.CRITICAL) R.color.crit else if (adv.level == Level.WARNING) R.color.warn else R.color.surface2), activity.dp(8).toFloat())
                    if (adv.level == Level.CRITICAL) setTextColor(activity.color(R.color.text))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = activity.dp(8) }
                })
            }
        }
        renderAltitudeChoice(f)
        renderChecklist()
        renderAircraft(a)
        renderSettings(a)
    }

    private fun toast(s: String) = android.widget.Toast.makeText(activity, s, android.widget.Toast.LENGTH_SHORT).show()
}

/** Checklist ticks survive closing the panel (Later) until the checklist is done or the flight changes. */
object FlyState {
    private var ticks: BooleanArray? = null
    fun ticks(n: Int): BooleanArray = ticks?.takeIf { it.size == n } ?: BooleanArray(n).also { ticks = it }
    fun ticked(n: Int): Int = ticks?.takeIf { it.size == n }?.count { it } ?: 0
    fun clearTicks() { ticks = null }
}
