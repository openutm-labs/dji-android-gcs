package org.openutm.djiflight.core.blender

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.openutm.djiflight.core.altitude.AltitudeCandidate
import org.openutm.djiflight.core.auth.TokenProvider
import org.openutm.djiflight.core.declaration.DeclarationPlan
import org.openutm.djiflight.core.geo.BBox
import org.openutm.djiflight.core.http.HttpRequest
import org.openutm.djiflight.core.http.HttpResponse
import org.openutm.djiflight.core.http.HttpTransport
import org.openutm.djiflight.core.model.TelemetrySample
import org.openutm.djiflight.core.rid.FlightIdentity
import org.openutm.djiflight.core.rid.RidMapper
import org.openutm.djiflight.core.sync.BackendResult
import org.openutm.djiflight.core.sync.OperationState
import org.openutm.djiflight.core.sync.UtmBackend
import org.openutm.djiflight.core.time.Rfc3339
import java.io.IOException

/**
 * Flight Blender over HTTPS, using the same endpoints and payloads as `tools/sitl-bridge/blender_api.py`
 * (checked against Blender `54636aa`):
 *   POST /flight_declaration_ops/set_flight_declaration
 *   GET  /flight_declaration_ops/flight_declaration          (duplicate-safe declare lookup)
 *   PUT  /flight_declaration_ops/flight_declaration_state/{id}
 *   PUT  /flight_stream/set_telemetry                         (one observation, many current_states)
 *   GET  /geo_fence_ops/geo_fence
 */
class BlenderBackend(
    baseUrl: String,
    private val tokens: TokenProvider,
    private val transport: HttpTransport,
    private val submittedBy: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : UtmBackend {
    override val serverId = org.openutm.djiflight.core.sync.ServerIdentity.of(baseUrl)
    private val base = baseUrl.trimEnd('/')

    override suspend fun declare(plan: DeclarationPlan): BackendResult<String> {
        val (status, body) = when (val r = call("POST", "/flight_declaration_ops/set_flight_declaration", declarationBody(plan))) {
            is Raw.Fail -> return r.result
            is Raw.Resp -> r
        }
        if (status !in 200..201) return classify(status, body)
        val id = parse(body)?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?: return BackendResult.Transient("declaration response had no id: ${body.take(200)}")
        return BackendResult.Ok(id)
    }

    override suspend fun findDeclaration(clientRef: String, startMs: Long, endMs: Long): BackendResult<String?> {
        val day = 86_400_000L
        var page = 1
        while (true) {
            val path = "/flight_declaration_ops/flight_declaration?start_date=${Rfc3339.date(startMs - day)}" +
                "&end_date=${Rfc3339.date(endMs + day)}&page=$page&page_size=100"
            val (status, body) = when (val r = call("GET", path, null)) {
                is Raw.Fail -> return r.result
                is Raw.Resp -> r
            }
            if (status != 200) return classify(status, body)
            val root = parse(body)?.jsonObject ?: return BackendResult.Transient("declaration list was not JSON")
            val results = root["results"]?.jsonArray ?: JsonArray(emptyList())
            for (item in results) {
                val o = item.jsonObject
                if (clientRefOf(o["flight_declaration_raw_geojson"]) == clientRef) {
                    return BackendResult.Ok(o["id"]?.jsonPrimitive?.contentOrNull)
                }
            }
            val count = root["count"]?.jsonPrimitive?.int ?: 0
            if (page * 100 >= count || results.isEmpty()) return BackendResult.Ok(null)
            page++
        }
    }

    override suspend fun setState(declarationId: String, state: OperationState): BackendResult<Unit> {
        val body = buildJsonObject {
            put("state", state.blenderCode)
            put("submitted_by", submittedBy)
        }
        val (status, text) = when (val r = call("PUT", "/flight_declaration_ops/flight_declaration_state/$declarationId", body)) {
            is Raw.Fail -> return r.result
            is Raw.Resp -> r
        }
        return if (status == 200) BackendResult.Ok(Unit) else classify(status, text)
    }

    override suspend fun sendTelemetry(
        flight: FlightIdentity,
        samples: List<TelemetrySample>,
        reference: AltitudeCandidate,
    ): BackendResult<Unit> {
        require(samples.isNotEmpty())
        val pilot = samples.lastOrNull { it.pilotLat != null && it.pilotLng != null }
        val body = buildJsonObject {
            putJsonArray("observations") {
                add(buildJsonObject {
                    put("current_states", JsonArray(samples.map { RidMapper.state(it, reference.ellipsoidM, reference.sigmaM) }))
                    put("flight_details", RidMapper.flightDetails(flight, pilot?.pilotLat, pilot?.pilotLng))
                })
            }
        }
        val (status, text) = when (val r = call("PUT", "/flight_stream/set_telemetry", body)) {
            is Raw.Fail -> return r.result
            is Raw.Resp -> r
        }
        return when {
            status == 201 -> BackendResult.Ok(Unit)
            status == 400 && "does not match any current operation" in text -> BackendResult.WindowClosed(message(text))
            status == 400 && "not one of Activated" in text -> BackendResult.NotActivated(message(text))
            else -> classify(status, text)
        }
    }

    /**
     * Restriction zones valid in the window, filtered to [area] on the device.
     *
     * Not via Blender's `view` parameter: `54636aa` indexes geofences as lat,lng but stores and returns
     * `bounds` as lng,lat, so its viewport filter only matches a lat,lng view. Relying on that would
     * silently return no zones if it changes. Pages through the list instead (at most [MAX_ZONES]).
     */
    override suspend fun zones(area: BBox, startMs: Long, endMs: Long): BackendResult<String> {
        val kept = ArrayList<JsonElement>()
        var offset = 0
        while (offset < MAX_ZONES) {
            val path = "/geo_fence_ops/geo_fence?start_date=${Rfc3339.date(startMs)}&end_date=${Rfc3339.date(endMs)}&limit=$ZONE_PAGE&offset=$offset"
            val (status, text) = when (val r = call("GET", path, null)) {
                is Raw.Fail -> return r.result
                is Raw.Resp -> r
            }
            if (status != 200) return classify(status, text)
            val root = parse(text) as? JsonObject ?: return BackendResult.Transient("zone list was not JSON")
            val results = root["results"] as? JsonArray ?: JsonArray(emptyList())
            results.filterTo(kept) { boundsOf(it)?.intersects(area) ?: true }
            val count = (root["count"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            offset += ZONE_PAGE
            if (results.isEmpty() || offset >= count) break
        }
        return BackendResult.Ok(buildJsonObject { put("count", kept.size); put("results", JsonArray(kept)) }.toString())
    }

    /** Blender's `bounds`: "minlng,minlat,maxlng,maxlat". */
    private fun boundsOf(e: JsonElement): BBox? {
        val b = ((e as? JsonObject)?.get("bounds") as? JsonPrimitive)?.contentOrNull ?: return null
        val v = b.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        return if (v.size == 4) BBox(minLat = v[1], minLng = v[0], maxLat = v[3], maxLng = v[2]) else null
    }

    fun declarationBody(plan: DeclarationPlan): JsonObject = buildJsonObject {
        put("originating_party", plan.originatingParty)
        put("start_datetime", Rfc3339.format(plan.startMs))
        put("end_datetime", Rfc3339.format(plan.endMs))
        putJsonObject("flight_declaration_geo_json") {
            put("type", "FeatureCollection")
            putJsonArray("features") {
                add(buildJsonObject {
                    put("type", "Feature")
                    putJsonObject("properties") {
                        putJsonObject("min_altitude") { put("meters", round1(plan.minAltW84M)); put("datum", "W84") }
                        putJsonObject("max_altitude") { put("meters", round1(plan.maxAltW84M)); put("datum", "W84") }
                        put(CLIENT_REF, plan.clientRef)
                        put("source", "openutm-dji-flight")
                    }
                    putJsonObject("geometry") {
                        put("type", "Polygon")
                        putJsonArray("coordinates") {
                            addJsonArray { plan.ring.forEach { p -> add(buildJsonArray { add(p.lng); add(p.lat) }) } }
                        }
                    }
                })
            }
        }
        put("type_of_operation", if (plan.vlos) 1 else 2)
        put("aircraft_id", plan.aircraftSerial)
        put("expect_telemetry", true)
        put("submitted_by", plan.submittedBy)
    }

    private sealed interface Raw {
        data class Resp(val status: Int, val body: String) : Raw
        data class Fail(val result: BackendResult<Nothing>) : Raw
    }

    private suspend fun call(method: String, path: String, body: JsonElement?): Raw =
        withContext(io) {
            try {
                var resp = send(method, path, body, refresh = false)
                if (resp.status == 401) resp = send(method, path, body, refresh = true)
                if (resp.status == 401 || resp.status == 403) {
                    Raw.Fail(BackendResult.Unauthorized("HTTP ${resp.status}: ${resp.body.take(200)}"))
                } else {
                    Raw.Resp(resp.status, resp.body)
                }
            } catch (e: IOException) {
                Raw.Fail(BackendResult.Transient(e.message ?: e.javaClass.simpleName))
            }
        }

    private fun send(method: String, path: String, body: JsonElement?, refresh: Boolean): HttpResponse =
        transport.execute(
            HttpRequest(
                method, base + path,
                mapOf("Authorization" to "Bearer ${tokens.token(refresh)}", "Content-Type" to "application/json", "Accept" to "application/json"),
                body?.toString()?.toByteArray(Charsets.UTF_8),
            ),
        )

    private fun classify(status: Int, body: String): BackendResult<Nothing> = when {
        status >= 500 || status == 408 || status == 429 -> BackendResult.Transient("HTTP $status: ${message(body)}")
        status == 401 || status == 403 -> BackendResult.Unauthorized("HTTP $status")
        else -> BackendResult.Rejected(status, message(body))
    }

    private fun message(body: String): String =
        (parse(body) as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: body.take(300)

    private fun parse(body: String): JsonElement? = runCatching { Json.parseToJsonElement(body) }.getOrNull()

    private fun clientRefOf(raw: JsonElement?): String? {
        val fc = when (raw) {
            is JsonObject -> raw
            is JsonPrimitive -> raw.contentOrNull?.let { parse(it) as? JsonObject }
            else -> null
        } ?: return null
        val features = fc["features"] as? JsonArray ?: return null
        return features.firstNotNullOfOrNull { f ->
            ((f as? JsonObject)?.get("properties") as? JsonObject)?.get(CLIENT_REF)?.let { (it as? JsonPrimitive)?.contentOrNull }
        }
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    companion object {
        const val CLIENT_REF = "client_ref"
        private const val ZONE_PAGE = 200
        private const val MAX_ZONES = 5_000
    }
}
