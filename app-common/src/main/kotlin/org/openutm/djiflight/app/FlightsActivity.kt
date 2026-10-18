package org.openutm.djiflight.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
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
import org.openutm.djiflight.app.ui.text
import org.openutm.djiflight.app.ui.topBar
import org.openutm.djiflight.core.PhotoStats
import org.openutm.djiflight.core.export.FlightExport
import org.openutm.djiflight.core.imagery.ImageryHandoff
import org.openutm.djiflight.core.model.OperationKind
import org.openutm.djiflight.core.provisioning.ImageryService
import org.openutm.djiflight.core.session.FlightSnapshot
import org.openutm.djiflight.core.session.Phase
import java.text.DateFormat
import java.util.Date

/**
 * Every flight on the device: what UTM has of it, its photos, and export (the copy of record for
 * flights Blender couldn't take). The list on the left, the selected flight on the right.
 */
class FlightsActivity : Activity() {
    private val graph get() = (application as FlightApp).graph
    private val scope = MainScope()
    private var flights: List<FlightSnapshot> = emptyList()
    private var photos: Map<String, PhotoStats> = emptyMap()
    private var selected: String? = null
    private var pendingExport: Pair<String, String>? = null // flightId to "geojson" | "csv"
    private lateinit var list: ListView
    private lateinit var detail: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flights)
        graph.start(this)
        val bar = topBar(getString(R.string.flights_title)) { finish() }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(ActionButton(this).set(getString(R.string.flights_remove_old), R.drawable.ic_trash, Kind.SECONDARY).apply {
            setOnClickListener {
                Sheet(this@FlightsActivity).title(getString(R.string.flights_remove_title))
                    .message(getString(R.string.flights_remove_message))
                    .button(getString(R.string.flights_cancel))
                    .button(getString(R.string.flights_remove), Kind.DANGER) {
                        Toast.makeText(this@FlightsActivity, graph.service.prune().let { resources.getQuantityString(R.plurals.flights_removed, it, it) }, Toast.LENGTH_SHORT).show()
                        refresh()
                    }.show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        findViewById<LinearLayout>(R.id.topBar).addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        list = findViewById(R.id.list)
        detail = findViewById(R.id.detail)
        list.setOnItemClickListener { _, _, pos, _ -> flights.getOrNull(pos)?.let { selected = it.flightId; renderDetail(); (list.adapter as BaseAdapter).notifyDataSetChanged() } }
        refresh()
    }

    private fun refresh() {
        flights = graph.service.snapshots().filter { it.plan != null }.sortedByDescending { it.lastEventAt }
        photos = flights.associate { it.flightId to graph.service.photoStats(it.flightId) }
        if (selected == null || flights.none { it.flightId == selected }) selected = flights.firstOrNull()?.flightId
        list.adapter = Rows()
        renderDetail()
    }

    /** What UTM has of the flight, in the pilot's words. */
    private fun status(f: FlightSnapshot): Pair<String, Pill.Tone> {
        val pending = graph.service.pendingSamples(f.flightId)
        return when (f.phase) {
            Phase.CLOSED -> getString(R.string.flights_status_in_utm) to Pill.Tone.OK
            Phase.PLANNED -> getString(R.string.flights_status_not_declared) to Pill.Tone.WARN
            Phase.DECLARED -> getString(R.string.flights_status_declared) to Pill.Tone.INFO
            Phase.ACTIVE -> getString(R.string.flights_status_in_flight) to Pill.Tone.INFO
            Phase.LANDED -> if (pending > 0) getString(R.string.flights_status_uploading) to Pill.Tone.WARN else getString(R.string.flights_status_landed) to Pill.Tone.INFO
            Phase.EXPIRED_UNSYNCED -> getString(R.string.flights_status_export_it) to Pill.Tone.CRIT
            Phase.UNDECLARED -> getString(R.string.flights_status_not_in_utm) to Pill.Tone.CRIT
            Phase.CANCELLED -> getString(R.string.flights_status_cancelled) to Pill.Tone.NEUTRAL
        }
    }

    private fun title(f: FlightSnapshot) = f.plan?.siteName?.takeIf { it.isNotBlank() } ?: getString(if (f.plan?.kind == OperationKind.MISSION) R.string.flights_kind_mission else R.string.flights_kind_manual)

    private inner class Rows : BaseAdapter() {
        override fun getCount() = flights.size
        override fun getItem(position: Int) = flights[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val f = flights[position]
            val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            val n = photos[f.flightId]?.count ?: 0
            val (st, tone) = status(f)
            return LinearLayout(this@FlightsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(68)
                setPadding(dp(16), dp(8), dp(12), dp(8))
                setBackgroundColor(color(if (f.flightId == selected) R.color.surface2 else R.color.bg))
                val texts = LinearLayout(this@FlightsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(17f, bold = true).apply { text = title(f); maxLines = 1 })
                    addView(text(14f, R.color.text2).apply {
                        text = listOfNotNull(
                            fmt.format(Date(f.plan!!.plannedStartMs)),
                            getString(if (f.plan?.kind == OperationKind.MISSION) R.string.flights_kind_mission_short else R.string.flights_kind_manual_short),
                            if (n > 0) resources.getQuantityString(R.plurals.flights_photos, n, n) else null,
                        ).joinToString(" · ")
                        maxLines = 1
                    })
                }
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Pill(this@FlightsActivity, 13f).apply { set(st, tone) })
            }
        }
    }

    private fun renderDetail() {
        detail.removeAllViews()
        val f = flights.firstOrNull { it.flightId == selected }
        if (f == null) {
            detail.addView(text(17f, R.color.text2).apply { text = getString(R.string.flights_empty) })
            return
        }
        val plan = f.plan!!
        val samples = graph.service.samples(f.flightId)
        val flown = samples.filter { it.flying }
        val pending = graph.service.pendingSamples(f.flightId)
        val st = photos[f.flightId] ?: PhotoStats(0, null, null)
        val service = graph.provisioning.value?.imagery
        val t = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        detail.addView(text(20f, bold = true).apply { text = title(f) })
        detail.addView(text(14f, R.color.text2).apply { text = t.format(Date(plan.plannedStartMs)); setPadding(0, dp(2), 0, dp(10)) })
        fun row(l: String, v: String) = detail.addView(Sheet.row(this, l, v))
        row(getString(R.string.flights_row_utm), status(f).first)
        row(getString(R.string.flights_row_kind), getString(if (plan.kind == OperationKind.MISSION) R.string.flights_kind_mission else R.string.flights_kind_manual))
        if (flown.size >= 2) row(getString(R.string.flights_row_time_in_air), getString(R.string.flights_minutes, (flown.last().t - flown.first().t) / 60_000.0))
        row(getString(R.string.flights_row_samples), if (pending > 0) getString(R.string.flights_samples_pending, samples.size, pending) else getString(R.string.flights_samples_all, samples.size))
        row(getString(R.string.flights_row_photos), when {
            st.count == 0 -> getString(R.string.flights_photos_none)
            f.imageryHandoff?.service == "none" -> getString(R.string.flights_photos_not_uploading, st.count)
            f.imageryHandoff != null -> getString(R.string.flights_photos_in, st.count, f.imageryHandoff!!.service)
            service != null && f.recordingClosed -> getString(R.string.flights_photos_to_upload, st.count, service.service)
            else -> "${st.count}"
        })
        row(getString(R.string.flights_row_exported), getString(if (graph.service.isExported(f.flightId)) R.string.flights_yes else R.string.flights_not_yet))
        detail.addView(Sheet.note(this, getString(R.string.flights_note, f.declarationId?.take(13) ?: getString(R.string.flights_declaration_none), f.flightId.take(8))))

        fun action(label: String, icon: Int, kind: Kind = Kind.SECONDARY, run: () -> Unit) =
            detail.addView(ActionButton(this).set(label, icon, kind).apply { setOnClickListener { run() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) })
        if (service != null && st.count > 0 && f.recordingClosed) action(getString(R.string.flights_photos_to, service.service), R.drawable.ic_photo, if (f.imageryHandoff == null) Kind.PRIMARY else Kind.SECONDARY) { handoff(f, service, st) }
        action(getString(R.string.flights_export_track), R.drawable.ic_upload) { export(f, "geojson") }
        action(getString(R.string.flights_export_samples), R.drawable.ic_upload) { export(f, "csv") }
        action(getString(R.string.flights_delete_action), R.drawable.ic_trash) { askDelete(f) }
    }

    private fun askDelete(f: FlightSnapshot) {
        val canDelete = f.recordingClosed || f.phase == Phase.CANCELLED
        if (!canDelete) {
            Sheet(this).title(getString(R.string.flights_cant_delete_title)).message(getString(R.string.flights_cant_delete_message)).button(getString(R.string.flights_ok)).show()
            return
        }
        val exported = graph.service.isExported(f.flightId)
        Sheet(this).title(getString(R.string.flights_delete_title))
            .message(getString(R.string.flights_delete_message) + if (!exported) getString(R.string.flights_delete_not_exported) else "")
            .button(getString(R.string.flights_keep))
            .button(getString(R.string.flights_delete), Kind.DANGER, R.drawable.ic_trash) {
                if (!graph.service.delete(f.flightId)) Toast.makeText(this, getString(R.string.flights_delete_refused), Toast.LENGTH_LONG).show()
                selected = null
                refresh()
            }.show()
    }

    /** The same hand-off as on the Fly screen, for an earlier flight. */
    private fun handoff(f: FlightSnapshot, service: ImageryService, stats: PhotoStats) {
        val name = ImageryHandoff.mapName(f)
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val span = if (stats.firstMs != null && stats.lastMs != null) getString(R.string.flights_handoff_span, time.format(Date(stats.firstMs!!)), time.format(Date(stats.lastMs!!))) else ""
        val where = service.project?.let { " → $it" } ?: ""
        body.addView(text(15f, R.color.text2).apply { text = getString(R.string.flights_handoff_text, stats.count, span, service.service, where, name) })
        body.addView(ActionButton(this).set(getString(R.string.flights_copy_map_name), null, Kind.SECONDARY).apply {
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(getString(R.string.flights_clip_label), name))
                Toast.makeText(this@FlightsActivity, getString(R.string.flights_copied, name), Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
        Sheet(this).title(getString(R.string.flights_photos_to, service.service)).body(body)
            .button(getString(R.string.flights_close))
            .button(getString(R.string.flights_not_uploading)) { scope.launch { graph.service.markImageryHandedOff(f.flightId, "none", name, stats.count); refresh() } }
            .button(getString(R.string.flights_uploaded), Kind.PRIMARY, R.drawable.ic_check) { scope.launch { graph.service.markImageryHandedOff(f.flightId, service.service, name, stats.count); refresh() } }
            .show()
    }

    private fun export(f: FlightSnapshot, kind: String) {
        pendingExport = f.flightId to kind
        val name = FlightExport.fileName(f, kind)
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(if (kind == "csv") "text/csv" else "application/geo+json").putExtra(Intent.EXTRA_TITLE, name),
            REQ_EXPORT,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val (id, kind) = pendingExport ?: return
        pendingExport = null
        val uri = data?.data
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK || uri == null) return
        val f = graph.service.snapshot(id) ?: return
        runCatching {
            val samples = graph.service.samples(id)
            val text = if (kind == "csv") FlightExport.csv(f, samples) else FlightExport.geoJson(f, samples)
            contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
            graph.service.markExported(id)
        }.onSuccess { Toast.makeText(this, getString(R.string.flights_exported), Toast.LENGTH_SHORT).show(); refresh() }
            .onFailure { Sheet(this).title(getString(R.string.flights_export_failed)).message(it.message ?: getString(R.string.flights_unknown_error)).button(getString(R.string.flights_ok)).show() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val REQ_EXPORT = 11
    }
}
