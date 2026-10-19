package org.openutm.djiflight.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.openutm.djiflight.app.ui.ActionButton
import org.openutm.djiflight.app.ui.Kind
import org.openutm.djiflight.app.ui.Pill
import org.openutm.djiflight.app.ui.Sheet
import org.openutm.djiflight.app.ui.color
import org.openutm.djiflight.app.ui.dp
import org.openutm.djiflight.app.ui.immersive
import org.openutm.djiflight.app.ui.rounded
import org.openutm.djiflight.app.ui.text
import org.openutm.djiflight.app.ui.topBar
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.provisioning.AuthConfig
import org.openutm.djiflight.core.provisioning.DeviceProvisioning
import org.openutm.djiflight.core.provisioning.PanamaDeployment
import org.openutm.djiflight.core.provisioning.LabAircraft
import org.openutm.djiflight.core.provisioning.ProvisioningCodec
import org.openutm.djiflight.core.sync.BackendResult
import org.openutm.djiflight.core.update.Release
import java.io.File

/**
 * Device setup (docs/12, Auth and provisioning): a QR code on phones, or a file / pasted text on the
 * Smart Controller (no camera). Plus the offline map files, restriction zones and app updates.
 * Sections on the left; after a first setup, one button back to flying.
 */
class SetupActivity : Activity() {
    private val ui = MainScope()
    private val graph get() = (application as FlightApp).graph
    private var importTarget: File? = null
    private var update: Release? = null
    private var section = Section.DEVICE
    private var justSetUp = false
    private var progress: String? = null
    private lateinit var nav: LinearLayout
    private lateinit var content: LinearLayout

    private enum class Section(val title: Int, val icon: Int) {
        DEVICE(R.string.setup_section_device, R.drawable.ic_sliders),
        MAPS(R.string.setup_section_maps, R.drawable.ic_map),
        ZONES(R.string.setup_section_zones, R.drawable.ic_warning),
        UPDATES(R.string.setup_section_updates, R.drawable.ic_upload),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        if (intent.getStringExtra("section") == "maps") section = Section.MAPS
        graph.start(this)
        val bar = topBar(getString(R.string.setup_title)) { finish() }
        findViewById<LinearLayout>(R.id.topBar).addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        nav = findViewById(R.id.nav)
        content = findViewById(R.id.content)
        render()
    }

    private fun render() {
        nav.removeAllViews()
        for (s in Section.entries) {
            nav.addView(ActionButton(this).set(getString(s.title), s.icon, if (s == section) Kind.PRIMARY else Kind.QUIET).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setOnClickListener { section = s; render() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(6) })
        }
        content.removeAllViews()
        when (section) {
            Section.DEVICE -> device()
            Section.MAPS -> maps()
            Section.ZONES -> zones()
            Section.UPDATES -> updates()
        }
    }

    private fun heading(s: String) = content.addView(text(20f, bold = true).apply { text = s; setPadding(0, dp(4), 0, dp(10)) })
    private fun body(s: String, colorId: Int = R.color.text2) = content.addView(text(16f, colorId).apply { text = s; setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, 0, 0, dp(8)) })
    private fun row(l: String, v: String) = content.addView(Sheet.row(this, l, v))
    private fun button(label: String, icon: Int?, kind: Kind = Kind.SECONDARY, enabled: Boolean = true, run: () -> Unit) =
        content.addView(ActionButton(this).set(label, icon, kind).apply { isEnabled = enabled; setOnClickListener { run() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })

    private fun device() {
        val p = graph.provisioning.value
        heading(getString(R.string.setup_section_device))
        if (p == null) {
            if (graph.platform.isSimulation) body(getString(R.string.setup_not_set_up))
            else {
                row(getString(R.string.setup_row_utm_server), PanamaDeployment.BLENDER_URL)
                body(getString(R.string.setup_panama_default))
                button(getString(R.string.setup_panama_manual), R.drawable.ic_sliders, Kind.PRIMARY) { panamaSetup() }
            }
        } else {
            content.addView(Pill(this, 15f).apply { set(getString(if (justSetUp) R.string.setup_pill_ready else R.string.setup_pill_set_up), Pill.Tone.OK) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            if (justSetUp) button(getString(R.string.setup_start_flying), R.drawable.ic_play, Kind.PRIMARY) { finish() }
            row(getString(R.string.setup_row_device), p.deviceName)
            row(getString(R.string.setup_row_lab), "${p.lab}${p.country?.let { ", $it" } ?: ""}")
            row(getString(R.string.setup_row_operator), p.operatorId)
            row(getString(R.string.setup_row_utm_server), p.blenderUrl)
            row(getString(R.string.setup_row_sign_in), getString(if (p.auth is AuthConfig.Dev) R.string.setup_sign_in_dev else R.string.setup_sign_in_device))
            row(getString(R.string.setup_row_pilots_aircraft), "${p.pilots.size} · ${p.aircraft.size}")
            row(getString(R.string.setup_row_pilot_sign_in), p.pilotLogin?.let { l ->
                getString(if (l.required) R.string.setup_pilot_sign_in_required else R.string.setup_pilot_sign_in_optional, android.net.Uri.parse(l.issuer).host ?: l.issuer)
            } ?: getString(R.string.setup_pilot_sign_in_off))
            row(getString(R.string.setup_row_height_limit), getString(R.string.setup_height_m, p.legalMaxHeightM))
            row(getString(R.string.setup_row_checklist), if (p.checklist.isEmpty()) getString(R.string.setup_checklist_none) else resources.getQuantityString(R.plurals.setup_checklist_items, p.checklist.size, p.checklist.size))
            row(getString(R.string.setup_row_photos_go_to), p.imagery?.let { i -> i.service + (i.project?.let { " → $it" } ?: "") } ?: getString(R.string.setup_not_set))
            content.addView(text(17f, bold = true).apply { text = getString(R.string.setup_replace); setPadding(0, dp(20), 0, dp(2)) })
        }
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(label: String, hint: String, icon: Int, run: () -> Unit) = tiles.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            background = org.openutm.djiflight.app.ui.pressable(
                rounded(color(R.color.surface2), dp(10).toFloat()), rounded(color(R.color.surface3), dp(10).toFloat()),
            )
            setPadding(dp(8), dp(14), dp(8), dp(14))
            addView(android.widget.ImageView(this@SetupActivity).apply { setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.text)) }, LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(text(16f, bold = true).apply { text = label; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(2)) })
            addView(text(13f, R.color.text3).apply { text = hint; gravity = Gravity.CENTER })
            setOnClickListener { run() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (tiles.childCount > 0) marginStart = dp(10) })
        tile(getString(R.string.setup_tile_import), getString(R.string.setup_tile_import_hint), R.drawable.ic_file) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_PROVISION_FILE)
        }
        tile(getString(R.string.setup_tile_scan), getString(R.string.setup_tile_scan_hint), R.drawable.ic_qr) {
            IntentIntegrator(this).setCaptureActivity(ProvisioningQrActivity::class.java)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setPrompt(getString(R.string.setup_scan_prompt))
                .setBeepEnabled(false).setOrientationLocked(false).initiateScan()
        }
        tile(getString(R.string.setup_tile_paste), getString(R.string.setup_paste_hint), R.drawable.ic_paste) {
            val input = EditText(this).apply {
                hint = getString(R.string.setup_paste_hint); setTextColor(color(R.color.text)); setHintTextColor(color(R.color.text3))
                // A code, not words: no suggestions or autocorrect, and no full-screen keyboard (landscape) to tap into by mistake.
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                maxLines = 4
            }
            Sheet(this).title(getString(R.string.setup_paste_title)).body(input).button(getString(R.string.setup_cancel)).button(getString(R.string.setup_use), Kind.PRIMARY) { apply(input.text.toString()) }.show()
        }
        content.addView(tiles, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        if (graph.platform.debug) {
            content.addView(text(14f, R.color.accent).apply {
                text = getString(R.string.setup_debug_dev_stack, graph.platform.devBlenderUrl)
                setPadding(0, dp(18), 0, dp(8))
                isClickable = true
                setOnClickListener { apply(graph.devProvisioning()) }
            })
        }
    }

    /** Public server defaults plus private credentials entered by this device's administrator. */
    private fun panamaSetup() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun field(label: Int, initial: String = "", secret: Boolean = false, numeric: Boolean = false): EditText {
            form.addView(text(14f, R.color.text2).apply { text = getString(label); setPadding(0, dp(10), 0, dp(4)) })
            return EditText(this).apply {
                setText(initial)
                setTextColor(color(R.color.text)); setHintTextColor(color(R.color.text3)); textSize = 16f
                setSingleLine(true)
                inputType = when {
                    secret -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    numeric -> android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    else -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                isSaveEnabled = false
                if (Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                form.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
            }
        }
        val connected = (graph.service.status.value.link as? org.openutm.djiflight.core.adapter.LinkState.Connected)?.identity
        val device = field(R.string.setup_row_device, "${Build.MODEL}-${java.util.UUID.randomUUID().toString().take(6)}")
        val operator = field(R.string.setup_row_operator)
        val pilot = field(R.string.setup_panama_pilot)
        val clientId = field(R.string.setup_panama_client_id)
        val clientSecret = field(R.string.setup_panama_client_secret, secret = true)
        val limit = field(R.string.setup_panama_height, "120", numeric = true)
        form.addView(Sheet.note(this, getString(R.string.setup_panama_aircraft_note)))
        val serial = field(R.string.setup_panama_serial, connected?.serial ?: "")
        val model = field(R.string.setup_panama_model, connected?.model ?: "")
        val registration = field(R.string.setup_panama_registration)
        val status = text(14f, R.color.warn).apply {
            setPadding(0, dp(10), 0, 0)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        lateinit var connect: ActionButton
        var checking: kotlinx.coroutines.Job? = null
        val sheet = Sheet(this, wide = true).title(getString(R.string.setup_panama_manual))
            .message(getString(R.string.setup_panama_credentials_note))
            .body(Sheet.row(this, getString(R.string.setup_row_utm_server), PanamaDeployment.BLENDER_URL))
            .body(form).footer(status)
        sheet.button(getString(R.string.setup_cancel))
            .button(getString(R.string.setup_panama_connect), Kind.PRIMARY, dismiss = false, ref = { connect = it }) {
                val height = limit.text.toString().replace(',', '.').toDoubleOrNull()
                if (height == null) {
                    status.text = getString(R.string.setup_panama_invalid_height)
                    return@button
                }
                val sn = serial.text.toString().trim()
                if (sn.isEmpty() && (model.text.isNotBlank() || registration.text.isNotBlank())) {
                    status.text = getString(R.string.setup_panama_serial_required)
                    return@button
                }
                val candidate = runCatching {
                    PanamaDeployment.deviceSetup(
                        device.text.toString(), operator.text.toString(), pilot.text.toString(),
                        clientId.text.toString(), clientSecret.text.toString(),
                        aircraft = if (sn.isNotEmpty()) LabAircraft(sn, registration.text.toString().trim(), model.text.toString().trim()) else null,
                        legalMaxHeightM = height,
                    )
                }.getOrElse { status.text = getString(R.string.setup_panama_pilot_required); return@button }
                val problems = candidate.problems(allowDevAuth = false)
                if (problems.isNotEmpty()) { status.text = problems.joinToString("\n"); return@button }
                connect.isEnabled = false
                status.text = getString(R.string.setup_panama_checking)
                checking = ui.launch {
                    try {
                        val result = graph.checkProvisioning(candidate)
                        if (!sheet.isShowing) return@launch
                        when (result) {
                            is BackendResult.Ok -> {
                                val refused = graph.provision(candidate)
                                if (refused.isNotEmpty()) status.text = refused.joinToString("\n")
                                else {
                                    justSetUp = true
                                    sheet.dismiss()
                                    render()
                                    toast(getString(R.string.setup_panama_connected))
                                }
                            }
                            else -> {
                                val reason = when (result) {
                                    is BackendResult.Rejected -> result.message
                                    is BackendResult.Transient -> result.message
                                    is BackendResult.Unauthorized -> result.message
                                    is BackendResult.WindowClosed -> result.message
                                    is BackendResult.NotActivated -> result.message
                                    is BackendResult.Ok -> ""
                                }
                                status.text = getString(R.string.setup_panama_failed, reason)
                            }
                        }
                    } finally { if (sheet.isShowing) connect.isEnabled = true }
                }
            }.onDismiss { checking?.cancel(); clientSecret.text.clear() }.show()
    }

    private fun maps() {
        val p = graph.provisioning.value
        heading(getString(R.string.setup_section_maps))
        body(getString(R.string.maps_online_note))
        row(getString(R.string.maps_online_provider), graph.provisioning.value?.onlineMap?.name ?: "OpenStreetMap")
        heading(getString(R.string.maps_mode_title))
        for ((mode, label) in listOf(RegionPacks.Mode.AUTOMATIC to R.string.maps_mode_automatic, RegionPacks.Mode.ONLINE to R.string.maps_mode_online, RegionPacks.Mode.OFFLINE to R.string.maps_mode_offline)) {
            button(getString(label), R.drawable.ic_map, if (graph.maps.mode == mode) Kind.PRIMARY else Kind.SECONDARY) { graph.maps.mode = mode; render() }
        }
        body(getString(R.string.maps_coverage_note), R.color.text3)
        body(getString(R.string.maps_body))
        body(progress ?: graph.maps.describe(), R.color.text)
        val listed = p != null && (p.basemapUrl != null || p.imageryUrl != null || p.terrainUrl != null)
        if (p != null && !listed) body(getString(R.string.maps_no_files_listed), R.color.text3)
        button(getString(R.string.maps_download), R.drawable.ic_import, Kind.PRIMARY, enabled = listed) { downloadMaps() }
        button(getString(R.string.maps_import_basemap), R.drawable.ic_file) { pickMap(graph.maps.basemap) }
        button(getString(R.string.maps_import_imagery), R.drawable.ic_file) { pickMap(graph.maps.imagery) }
        button(getString(R.string.maps_import_terrain), R.drawable.ic_file) { pickMap(graph.maps.terrain) }
        body(getString(R.string.maps_terrain_note), R.color.text3)
    }

    private fun zones() {
        heading(getString(R.string.setup_section_zones))
        val idx = graph.zones.index.value
        body(getString(R.string.setup_zones_body))
        row(getString(R.string.setup_row_utm_zones), if (idx == null) getString(R.string.setup_zones_none) else getString(R.string.setup_zones_count, idx.zones.size, (graph.zones.ageMs() ?: 0) / 3.6e6))
        row(getString(R.string.setup_row_dji_zones), "${graph.aircraftZones.value.size}")
        button(getString(R.string.setup_zones_download), R.drawable.ic_sync, Kind.PRIMARY) { refreshZones() }
    }

    private fun updates() {
        val p = graph.provisioning.value
        heading(getString(R.string.setup_section_updates))
        row(getString(R.string.update_row_this_app), "${graph.platform.versionName} (${graph.platform.versionCode})")
        row(getString(R.string.update_row_android), Build.VERSION.RELEASE)
        update?.let { row(getString(R.string.update_row_available), it.versionName + (it.notes?.let { n -> " – $n" } ?: "")) }
        progress?.let { body(it, R.color.text) }
        if (p?.updateUrl == null) body(getString(R.string.update_no_channel), R.color.text3)
        val u = update
        if (u == null) button(getString(R.string.update_check), R.drawable.ic_sync, Kind.PRIMARY, enabled = p?.updateUrl != null) { checkUpdate() }
        else button(getString(R.string.update_download_install, u.versionName), R.drawable.ic_import, Kind.PRIMARY) { explainInstall() }
    }

    private fun apply(text: String) = runCatching { ProvisioningCodec.decode(text) }
        .onSuccess { apply(it) }
        .onFailure { Sheet(this).title(getString(R.string.setup_not_a_code_title)).message(it.message ?: getString(R.string.setup_not_a_code_message)).button(getString(R.string.setup_ok)).show() }

    private fun apply(p: DeviceProvisioning) {
        val problems = graph.provision(p)
        if (problems.isEmpty()) {
            justSetUp = true
            section = Section.DEVICE
        } else {
            Sheet(this).title(getString(R.string.setup_refused_title)).message(problems.joinToString("\n") { "• $it" }).button(getString(R.string.setup_ok)).show()
        }
        render()
    }

    private fun pickMap(target: File) {
        importTarget = target
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_MAP_FILE)
    }

    private fun downloadMaps() {
        val p = graph.provisioning.value ?: return
        ui.launch {
            for ((url, file) in listOfNotNull(p.basemapUrl?.let { it to graph.maps.basemap }, p.imageryUrl?.let { it to graph.maps.imagery }, p.terrainUrl?.let { it to graph.maps.terrain })) {
                runCatching {
                    graph.maps.download(url, file) { done, total ->
                        runOnUiThread { progress = if (total > 0) getString(R.string.maps_downloading_of, file.name, done / 1e6, total / 1e6) else getString(R.string.maps_downloading, file.name, done / 1e6); if (section == Section.MAPS) render() }
                    }
                }.onFailure { toast("${file.name}: ${it.message}") }
            }
            progress = null
            render()
        }
    }

    private fun refreshZones() {
        val here = graph.currentPosition() ?: return toast(getString(R.string.setup_no_position))
        ui.launch {
            when (val r = graph.zones.refresh(Geo.bbox(Geo.circle(here, 2_000.0)))) {
                is BackendResult.Ok -> toast(resources.getQuantityString(R.plurals.setup_zones_downloaded, r.value.zones.size, r.value.zones.size))
                is BackendResult.Transient -> toast(getString(R.string.setup_zones_offline, r.message))
                else -> toast(getString(R.string.setup_zones_refused, r))
            }
            render()
        }
    }

    private fun checkUpdate() {
        val url = graph.provisioning.value?.updateUrl ?: return
        ui.launch {
            runCatching { graph.updates.check(url) }
                .onSuccess { update = it; if (it == null) toast(getString(R.string.update_up_to_date)) }
                .onFailure { Sheet(this@SetupActivity).title(getString(R.string.update_check_failed)).message(it.message ?: getString(R.string.update_no_answer)).button(getString(R.string.setup_ok)).show() }
            render()
        }
    }

    /** Android asks once whether this app may install updates; say so before its page opens. */
    private fun explainInstall() {
        val needsPermission = Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()
        if (!needsPermission) return downloadAndInstall()
        Sheet(this).title(getString(R.string.update_permission_title))
            .message(getString(R.string.update_permission_message))
            .button(getString(R.string.update_not_now))
            .button(getString(R.string.update_continue), Kind.PRIMARY) { downloadAndInstall() }
            .show()
    }

    private fun downloadAndInstall() {
        val url = graph.provisioning.value?.updateUrl ?: return
        val pending = update ?: return
        ui.launch {
            runCatching {
                graph.updates.download(url, pending) { done, total -> runOnUiThread { progress = getString(R.string.update_downloading, done / 1e6, total / 1e6); if (section == Section.UPDATES) render() } }
            }.onSuccess { progress = getString(R.string.update_downloaded); graph.updates.install(this@SetupActivity, it) }
                .onFailure { progress = null; Sheet(this@SetupActivity).title(getString(R.string.update_download_failed)).message(it.message ?: "").button(getString(R.string.setup_ok)).show() }
            render()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        IntentIntegrator.parseActivityResult(requestCode, resultCode, data)?.let { r ->
            r.contents?.let(::apply)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_PROVISION_FILE -> runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
                .onSuccess {
                    apply(it)
                    if (graph.provisioning.value != null && justSetUp) {
                        Sheet(this).title(getString(R.string.setup_delete_file_title)).message(getString(R.string.setup_delete_file_message)).button(getString(R.string.setup_ok), Kind.PRIMARY).show()
                    }
                }
                .onFailure { Sheet(this).title(getString(R.string.setup_cant_read_file)).message(it.message ?: "").button(getString(R.string.setup_ok)).show() }
            REQ_MAP_FILE -> importTarget?.let { target ->
                ui.launch {
                    runCatching { graph.maps.import(uri, target) }.onSuccess { toast(getString(R.string.maps_imported)) }.onFailure { toast(getString(R.string.maps_not_imported, it.message)) }
                    render()
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    private companion object {
        const val REQ_PROVISION_FILE = 21
        const val REQ_MAP_FILE = 22
    }
}
