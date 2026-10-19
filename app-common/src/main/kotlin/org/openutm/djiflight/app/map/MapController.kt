package org.openutm.djiflight.app.map

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import org.maplibre.android.tile.TileOperation
import org.maplibre.android.camera.CameraPosition
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.openutm.djiflight.app.R
import org.openutm.djiflight.app.RegionPacks
import org.openutm.djiflight.core.provisioning.OnlineBasemap
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.zones.Zone
import java.io.File
import kotlin.math.hypot
import org.maplibre.android.geometry.LatLng as MlLatLng

/**
 * One MapLibre map with the app's overlays. Texture mode, so it can sit over (or under) the video.
 * All overlay setters are cheap and may be called at the UI's refresh rate.
 *
 * On the Plan screen it also edits a polygon ([edit]): drag a corner to move it, drag an edge's
 * "+" handle to add a corner there; a tap elsewhere goes to [onMapClick].
 */
class MapController(
    private val context: Context,
    private val packs: RegionPacks,
    private val bottomClearanceDp: Int = 68,
    private val provider: () -> OnlineBasemap? = { null },
) {
    init {
        MapLibre.getInstance(context.applicationContext)
        MapNetwork.install(context.applicationContext)
    }

    private val density = context.resources.displayMetrics.density
    /** The letter on the home point marker (the Fly screen's map key names it). */
    private val homeLetter = context.getString(R.string.map_home_letter)
    private val mapView = MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true).attributionEnabled(false).logoEnabled(false).compassEnabled(true))
    private fun dp(value: Int) = (value * density).toInt()
    val view = FrameLayout(context)
    private val credits = TextView(context).apply {
        id = R.id.mapCredits
        textSize = 10f; setTextColor(Color.BLACK); setBackgroundColor(0xE6FFFFFF.toInt())
        setPadding(dp(4), dp(2), dp(4), dp(2))
        setOnClickListener {
            val link = if (packs.usesOnline()) (provider() ?: OnlineBasemap()).attributionUrl else "https://www.openstreetmap.org/copyright"
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
        }
    }
    private val status = TextView(context).apply {
        id = R.id.mapStatus
        textSize = 11f; setTextColor(Color.BLACK); setBackgroundColor(0xE6FFFFFF.toInt())
        setPadding(dp(5), dp(3), dp(5), dp(3))
        setOnClickListener {
            if (!packs.usesOnline() && !packs.basemap.isFile && !packs.imagery.isFile) {
                context.startActivity(Intent(context, org.openutm.djiflight.app.SetupActivity::class.java).putExtra("section", "maps"))
            } else reloadStyle()
        }
    }
    private var loaded = false
    private var tileError = false
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var observingNetwork = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { view.post { updateStatus() } }
        override fun onLost(network: Network) { view.post { updateStatus() } }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { view.post { updateStatus() } }
    }
    init {
        view.addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.addView(credits, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        view.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            credits.maxWidth = (view.width - dp(8)).coerceAtLeast(1)
            status.maxWidth = (view.width - dp(8)).coerceAtLeast(1)
            val small = view.height < dp(180)
            val bottom = dp(if (small) 4 else bottomClearanceDp)
            val top = dp(if (small) 4 else 58)
            (credits.layoutParams as FrameLayout.LayoutParams).let { p ->
                if (p.bottomMargin != bottom) credits.layoutParams = p.apply { bottomMargin = bottom; marginEnd = dp(4) }
            }
            (status.layoutParams as FrameLayout.LayoutParams).let { p ->
                if (p.topMargin != top) status.layoutParams = p.apply { topMargin = top; marginStart = dp(4) }
            }
        }
        mapView.addOnTileActionListener { operation, _, _, _, _, _, source ->
            if (operation == TileOperation.Error && source == "online-streets") view.post { tileError = true; updateStatus() }
        }
        mapView.addOnDidFinishRenderingFrameListener(MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
            if (fully && !loaded) view.post { loaded = true; updateStatus() }
        })
        mapView.addOnDidFailLoadingMapListener { view.post { tileError = true; updateStatus() } }
    }
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var pendingCenter: Pair<LatLng, Double>? = null
    private var clickListener: ((LatLng) -> Unit)? = null
    private var movedListener: (() -> Unit)? = null
    private val last = HashMap<String, String>()

    /** Layer id → GeoJSON source id, bottom to top. */
    private val overlays = listOf("zones", "aircraftZones", "area", "survey", "mission", "track", "midpoints", "vertices", "start", "home", "pilot", "aircraft")

    fun attachTo(parent: ViewGroup, savedState: Bundle?) {
        (view.parent as? ViewGroup)?.removeView(view)
        parent.addView(view, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (map == null) {
            mapView.onCreate(savedState)
            mapView.getMapAsync { m ->
                map = m
                // Public OSM service: viewport requests only, no lower-zoom or offline prefetch.
                m.setPrefetchesTiles(false)
                m.setPrefetchZoomDelta(0)
                if (savedState == null) m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(MlLatLng(0.0, 0.0)).zoom(1.5).build()))
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                // Drawing corners: quick taps in a row must add corners, not zoom (pinch still zooms).
                if (editor != null) m.uiSettings.isDoubleTapGesturesEnabled = false
                m.addOnMapClickListener { p -> clickListener?.invoke(LatLng(p.latitude, p.longitude)); clickListener != null }
                m.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) movedListener?.invoke() }
                reloadStyle()
            }
            installEditing()
        }
    }

    /** Moves the map between containers (swapping with the video) without recreating it. */
    fun moveTo(parent: ViewGroup) {
        (view.parent as? ViewGroup)?.removeView(view)
        parent.addView(view, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun reloadStyle() {
        val m = map ?: return
        style = null
        loaded = false
        tileError = false
        credits.text = if (packs.usesOnline()) (listOf((provider() ?: OnlineBasemap()).attribution) + packs.attributions(listOf(packs.terrain))).distinct().joinToString(" · ")
            else packs.attributions().joinToString(" · ").ifBlank { if (packs.basemap.isFile) "© OpenStreetMap contributors" else "" }
        credits.visibility = if (credits.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        updateStatus()
        m.setStyle(Style.Builder().fromJson(MapStyle.json(packs.basemap, packs.imagery, packs.terrain, packs.mode, provider() ?: OnlineBasemap()))) { st ->
            addImages(st)
            addOverlays(st)
            style = st
            last.forEach { (id, json) -> st.getSourceAs<GeoJsonSource>(id)?.setGeoJson(json) }
            pendingCenter?.let { (p, z) -> center(p, z) }
        }
    }

    private fun updateStatus() {
        val online = packs.usesOnline()
        val connected = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        status.text = context.getString(when {
            online && !connected -> R.string.map_online_unavailable
            tileError -> R.string.map_tiles_unavailable
            online && !loaded -> R.string.map_loading
            online -> R.string.map_online_streets
            !packs.basemap.isFile && !packs.imagery.isFile -> R.string.map_offline_missing
            else -> R.string.map_offline_ready
        })
    }

    private fun addOverlays(st: Style) {
        overlays.forEach { st.addSource(GeoJsonSource(it, EMPTY)) }
        st.addLayer(FillLayer("zones-fill", "zones").withProperties(fillColor("#E53935"), fillOpacity(0.25f)))
        st.addLayer(LineLayer("zones-line", "zones").withProperties(lineColor("#B71C1C"), lineWidth(2f)))
        st.addLayer(FillLayer("aircraftZones-fill", "aircraftZones").withProperties(fillColor("#FB8C00"), fillOpacity(0.2f)))
        st.addLayer(LineLayer("aircraftZones-line", "aircraftZones").withProperties(lineColor("#E65100"), lineWidth(1.5f), lineDasharray(arrayOf(2f, 2f))))
        st.addLayer(FillLayer("area-fill", "area").withProperties(fillColor("#1E88E5"), fillOpacity(0.12f)))
        st.addLayer(LineLayer("area-line", "area").withProperties(lineColor("#1565C0"), lineWidth(2.5f)))
        st.addLayer(LineLayer("survey-line", "survey").withProperties(lineColor("#FFFFFF"), lineWidth(2.5f)))
        st.addLayer(LineLayer("mission-line", "mission").withProperties(lineColor("#26C6DA"), lineWidth(2f)))
        st.addLayer(LineLayer("track-line", "track").withProperties(lineColor("#FDD835"), lineWidth(3f)))
        st.addLayer(CircleLayer("midpoints-point", "midpoints").withProperties(circleRadius(8f), circleColor("#FFFFFF"), circleOpacity(0.8f), circleStrokeColor("#1A2129"), circleStrokeWidth(1.5f)))
        st.addLayer(SymbolLayer("midpoints-plus", "midpoints").withProperties(iconImage(IMG_PLUS), iconAllowOverlap(true), iconIgnorePlacement(true)))
        st.addLayer(CircleLayer("vertices-point", "vertices").withProperties(circleRadius(11f), circleColor("#FFFFFF"), circleStrokeColor("#1A2129"), circleStrokeWidth(2f)))
        st.addLayer(CircleLayer("start-point", "start").withProperties(circleRadius(7f), circleColor("#00C853"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
        st.addLayer(SymbolLayer("home-point", "home").withProperties(iconImage(IMG_HOME), iconAllowOverlap(true), iconIgnorePlacement(true)))
        st.addLayer(CircleLayer("pilot-point", "pilot").withProperties(circleRadius(6f), circleColor("#00ACC1"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
        st.addLayer(
            SymbolLayer("aircraft-point", "aircraft").withProperties(
                iconImage(IMG_AIRCRAFT), iconRotate(Expression.get("yaw")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconAllowOverlap(true), iconIgnorePlacement(true),
            ),
        )
    }

    /** Markers drawn once: an arrow for the aircraft (its heading), "H" for home, "+" on the edge handles. */
    private fun addImages(st: Style) {
        val s = (36 * density).toInt()
        st.addImage(IMG_AIRCRAFT, Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            val p = Path().apply {
                moveTo(s / 2f, s * 0.06f); lineTo(s * 0.86f, s * 0.9f); lineTo(s / 2f, s * 0.7f); lineTo(s * 0.14f, s * 0.9f); close()
            }
            c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F4511E") })
            c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.2f * density; strokeJoin = Paint.Join.ROUND })
        })
        val h = (26 * density).toInt()
        st.addImage(IMG_HOME, Bitmap.createBitmap(h, h, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            c.drawCircle(h / 2f, h / 2f, h / 2f - density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2E9E4F") })
            c.drawCircle(h / 2f, h / 2f, h / 2f - density * 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * density })
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = h * 0.6f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
            c.drawText(homeLetter, h / 2f, h / 2f - (t.descent() + t.ascent()) / 2, t)
        })
        val q = (16 * density).toInt()
        st.addImage(IMG_PLUS, Bitmap.createBitmap(q, q, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A2129"); strokeWidth = 2 * density; strokeCap = Paint.Cap.ROUND }
            c.drawLine(q * 0.2f, q / 2f, q * 0.8f, q / 2f, pen)
            c.drawLine(q / 2f, q * 0.2f, q / 2f, q * 0.8f, pen)
        })
    }

    fun onMapClick(listener: ((LatLng) -> Unit)?) {
        clickListener = listener
    }

    /** Called when the pilot pans or zooms the map by hand (the screen then offers Recenter). */
    fun onUserMove(listener: (() -> Unit)?) {
        movedListener = listener
    }

    fun setArea(ring: List<LatLng>?) = set("area", ring?.let { polygons(listOf(it)) } ?: EMPTY)
    fun setSurvey(ring: List<LatLng>?) = set("survey", ring?.takeIf { it.size >= 2 }?.let { line(if (it.size >= 3) it + it.first() else it) } ?: EMPTY)
    fun setMission(points: List<LatLng>?) = set("mission", points?.takeIf { it.size >= 2 }?.let { line(it) } ?: EMPTY)
    fun setTrack(points: List<LatLng>) = set("track", if (points.size >= 2) line(points) else EMPTY)
    fun setZones(zones: List<Zone>) = set("zones", polygons(zones.flatMap { it.rings }))
    fun setAircraftZones(zones: List<Zone>) = set("aircraftZones", polygons(zones.flatMap { it.rings }))
    fun setAircraft(p: LatLng?, yawDeg: Double = 0.0) =
        set("aircraft", p?.takeIf { it.isValid }?.let { point(it, JSONObject().put("yaw", if (yawDeg.isFinite()) yawDeg else 0.0)) } ?: EMPTY)
    fun setHome(p: LatLng?) = set("home", p?.takeIf { it.isValid }?.let { point(it) } ?: EMPTY)
    fun setPilot(p: LatLng?) = set("pilot", p?.takeIf { it.isValid }?.let { point(it) } ?: EMPTY)
    fun setStart(p: LatLng?) = set("start", p?.takeIf { it.isValid }?.let { point(it) } ?: EMPTY)

    /** The polygon's corner handles, and with [midpoints] a "+" handle on each edge. */
    fun setHandles(corners: List<LatLng>?, midpoints: Boolean, closed: Boolean = true) {
        val c = corners.orEmpty()
        set("vertices", if (c.isEmpty()) EMPTY else fc(c.map { feature(pointGeometry(it)) }))
        set("midpoints", if (!midpoints || c.size < 2) EMPTY else fc(edges(c, closed).map { (a, b) -> feature(pointGeometry(LatLng((a.lat + b.lat) / 2, (a.lng + b.lng) / 2))) }))
    }

    fun center(p: LatLng, zoom: Double = 15.5) {
        val m = map
        if (m == null || style == null) {
            pendingCenter = p to zoom
            return
        }
        pendingCenter = null
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(MlLatLng(p.lat, p.lng), zoom))
    }

    /** Fits [points], keeping them clear of the screen's own overlays (padding in px: left, top, right, bottom). */
    fun fit(points: List<LatLng>, left: Int = (48 * density).toInt(), top: Int = left, right: Int = left, bottom: Int = left) {
        val m = map ?: return
        if (points.size < 2) return points.firstOrNull()?.let { center(it) } ?: Unit
        val b = LatLngBounds.from(points.maxOf { it.lat }, points.maxOf { it.lng }, points.minOf { it.lat }, points.minOf { it.lng })
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, left, top, right, bottom))
    }

    val ready: Boolean get() = style != null

    private fun set(id: String, geojson: String) {
        if (last[id] == geojson) return
        last[id] = geojson
        style?.getSourceAs<GeoJsonSource>(id)?.setGeoJson(geojson)
    }

    // --- polygon editing (Plan screen)

    interface Editor {
        /** The corners, in order. */
        fun corners(): List<LatLng>
        /** Edge "+" handles: insert a corner after index [afterIndex]. */
        val insertable: Boolean
        val closed: Boolean get() = true
        fun insert(afterIndex: Int, p: LatLng)
        /** A corner is being dragged ([done] = finger lifted). */
        fun move(index: Int, p: LatLng, done: Boolean)
    }

    private var editor: Editor? = null
    private var dragging = -1

    fun edit(e: Editor?) {
        editor = e
        dragging = -1
        map?.uiSettings?.isDoubleTapGesturesEnabled = e == null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installEditing() {
        mapView.setOnTouchListener { _, ev ->
            val m = map ?: return@setOnTouchListener false
            val ed = editor ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val corners = ed.corners()
                    val hit = nearest(m, corners, ev.x, ev.y, 30 * density)
                    dragging = when {
                        hit >= 0 -> hit
                        ed.insertable && corners.size >= 2 -> {
                            val mids = edges(corners, ed.closed).map { (a, b) -> LatLng((a.lat + b.lat) / 2, (a.lng + b.lng) / 2) }
                            val e = nearest(m, mids, ev.x, ev.y, 24 * density)
                            if (e >= 0) { ed.insert(e, mids[e]); e + 1 } else -1
                        }
                        else -> -1
                    }
                    dragging >= 0
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging < 0) return@setOnTouchListener false
                    val p = m.projection.fromScreenLocation(PointF(ev.x, ev.y))
                    ed.move(dragging, LatLng(p.latitude, p.longitude), done = false)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging < 0) return@setOnTouchListener false
                    val p = m.projection.fromScreenLocation(PointF(ev.x, ev.y))
                    ed.move(dragging, LatLng(p.latitude, p.longitude), done = true)
                    dragging = -1
                    true
                }
                else -> dragging >= 0
            }
        }
    }

    private fun nearest(m: MapLibreMap, points: List<LatLng>, x: Float, y: Float, radiusPx: Float): Int {
        var best = -1
        var bestD = radiusPx
        points.forEachIndexed { i, p ->
            val s = m.projection.toScreenLocation(MlLatLng(p.lat, p.lng))
            val d = hypot(s.x - x, s.y - y)
            if (d <= bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun edges(c: List<LatLng>, closed: Boolean = true): List<Pair<LatLng, LatLng>> =
        if (!closed || c.size < 3) c.zipWithNext() else c.indices.map { c[it] to c[(it + 1) % c.size] }

    fun onStart() {
        mapView.onStart()
        if (!observingNetwork) {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback)
            observingNetwork = true
        }
    }
    fun onResume() { mapView.onResume(); updateStatus() }
    fun onPause() = mapView.onPause()
    fun onStop() {
        if (observingNetwork) { connectivity.unregisterNetworkCallback(networkCallback); observingNetwork = false }
        mapView.onStop()
    }
    fun onLowMemory() = mapView.onLowMemory()
    fun onDestroy() {
        if (observingNetwork) { connectivity.unregisterNetworkCallback(networkCallback); observingNetwork = false }
        mapView.onDestroy()
    }
    fun onSaveInstanceState(out: Bundle) = mapView.onSaveInstanceState(out)

    private companion object {
        const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
        const val IMG_AIRCRAFT = "aircraft-arrow"
        const val IMG_HOME = "home-h"
        const val IMG_PLUS = "edge-plus"

        fun coords(ps: List<LatLng>) = JSONArray(ps.map { JSONArray(listOf(it.lng, it.lat)) })
        fun fc(features: List<JSONObject>) = JSONObject().put("type", "FeatureCollection").put("features", JSONArray(features)).toString()
        fun feature(geometry: JSONObject, props: JSONObject = JSONObject()) = JSONObject().put("type", "Feature").put("properties", props).put("geometry", geometry)
        fun pointGeometry(p: LatLng) = JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(p.lng, p.lat)))
        fun polygons(rings: List<List<LatLng>>) = fc(rings.filter { it.size >= 4 }.map { feature(JSONObject().put("type", "Polygon").put("coordinates", JSONArray(listOf(coords(it))))) })
        fun line(ps: List<LatLng>) = fc(listOf(feature(JSONObject().put("type", "LineString").put("coordinates", coords(ps)))))
        fun point(p: LatLng, props: JSONObject = JSONObject()) = fc(listOf(feature(pointGeometry(p), props)))
    }
}
