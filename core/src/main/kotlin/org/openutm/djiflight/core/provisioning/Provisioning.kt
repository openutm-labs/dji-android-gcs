package org.openutm.djiflight.core.provisioning

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.openutm.djiflight.core.auth.PilotLoginConfig
import org.openutm.djiflight.core.auth.PilotSignIn
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
data class Pilot(val id: String, val name: String)

@Serializable
data class LabAircraft(val serial: String, val registration: String, val model: String = "")

/** How the device gets Flight Blender tokens. */
@Serializable
sealed class AuthConfig {
    /** OAuth2 client credentials issued to this one device (a Passport application per device). */
    @Serializable
    @SerialName("client_credentials")
    data class ClientCredentials(
        @SerialName("token_url") val tokenUrl: String,
        @SerialName("client_id") val clientId: String,
        @SerialName("client_secret") val clientSecret: String,
        val scope: String = "flightblender.read flightblender.write",
        val audience: String? = null,
    ) : AuthConfig()

    /** The dummy JWT Blender accepts only with BYPASS_AUTH_TOKEN_VERIFICATION=1. Debug builds only. */
    @Serializable
    @SerialName("dev")
    data object Dev : AuthConfig()
}

/**
 * Everything a lab admin gives one device (docs/12, Auth and provisioning). Delivered as a QR code
 * (phones) or a file / pasted text (the Smart Controller has no camera). Pilot entries hold only an
 * id and a display name until the data sharing agreement covers more.
 */
@Serializable
data class DeviceProvisioning(
    val v: Int = 1,
    @SerialName("device_name") val deviceName: String,
    @SerialName("blender_url") val blenderUrl: String,
    val auth: AuthConfig,
    val lab: String,
    @SerialName("operator_id") val operatorId: String,
    val country: String? = null,
    @SerialName("legal_max_height_m") val legalMaxHeightM: Double = 120.0,
    val pilots: List<Pilot> = emptyList(),
    val aircraft: List<LabAircraft> = emptyList(),
    /** Vector basemap for the lab's region (PMTiles, Protomaps schema). */
    @SerialName("basemap_url") val basemapUrl: String? = null,
    /** Raster imagery for the lab's region (PMTiles), e.g. its own orthomosaics. */
    @SerialName("imagery_url") val imageryUrl: String? = null,
    /** Terrain for the lab's region (PMTiles of Terrarium tiles, e.g. cut from Mapterhorn): ground heights, clearance, hillshade. */
    @SerialName("terrain_url") val terrainUrl: String? = null,
    /** `latest.json` of the app's release channel. */
    @SerialName("update_url") val updateUrl: String? = null,
    /** The lab's own pre-flight checklist; ticked once per flight before take-off. Empty = none. */
    val checklist: List<String> = emptyList(),
    /** Where the lab processes its photos; the app shows a hand-off card after mapping flights. */
    val imagery: ImageryService? = null,
    /** Per-pilot sign-in through the lab's identity server (OIDC + PKCE); none = the roster pick only. */
    @SerialName("pilot_login") val pilotLogin: PilotLoginConfig? = null,
) {
    /** The roster entry a signed-in pilot is, by id or email; null if the roster doesn't list them. */
    fun rosterEntry(s: PilotSignIn): Pilot? =
        pilots.firstOrNull { it.id == s.subject } ?: s.email?.let { e -> pilots.firstOrNull { it.id.equals(e, ignoreCase = true) } }

    fun problems(allowDevAuth: Boolean): List<String> = buildList {
        if (v != 1) add("unsupported provisioning version $v")
        if (!blenderUrl.startsWith("https://") && !(allowDevAuth && blenderUrl.startsWith("http://"))) add("blender_url must be https")
        if (runCatching { org.openutm.djiflight.core.sync.ServerIdentity.of(blenderUrl) }.isFailure) add("blender_url must be a valid server URL without credentials, query or fragment")
        if (deviceName.isBlank()) add("device_name is empty")
        if (lab.isBlank() || operatorId.isBlank()) add("lab and operator_id are required")
        when (auth) {
            is AuthConfig.Dev -> if (!allowDevAuth) add("dev auth is only accepted by debug builds")
            is AuthConfig.ClientCredentials -> {
                if (!auth.tokenUrl.startsWith("https://") && !(allowDevAuth && auth.tokenUrl.startsWith("http://"))) add("token_url must be https")
                if (auth.clientId.isBlank() || auth.clientSecret.isBlank()) add("client_id and client_secret are required")
            }
        }
        if (legalMaxHeightM !in 1.0..500.0) add("legal_max_height_m out of range")
        if (pilots.map { it.id }.toSet().size != pilots.size) add("duplicate pilot ids")
        if (checklist.any { it.isBlank() }) add("checklist has an empty item")
        if (checklist.size > 40) add("checklist has more than 40 items")
        imagery?.let { if (it.service.isBlank()) add("imagery.service is empty") }
        pilotLogin?.let { l ->
            addAll(l.problems())
            if (!l.issuer.startsWith("https://") && !allowDevAuth) add("pilot_login.issuer must be https")
        }
    }
}

/** The lab's photo processing service, e.g. `{"service": "DroneDeploy", "project": "Ngong forest 2026"}`. */
@Serializable
data class ImageryService(
    val service: String = "DroneDeploy",
    /** The project (or folder) the lab's maps go into, shown to the pilot. */
    val project: String? = null,
)

/**
 * Compact text form for QR codes: `openutm-device:1:` + base64url(gzip(json)).
 * Plain JSON (a file on a microSD card, or pasted) is accepted too.
 */
@OptIn(ExperimentalEncodingApi::class)
object ProvisioningCodec {
    const val PREFIX = "openutm-device:1:"
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        classDiscriminator = "type"
    }
    private val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    fun toJson(p: DeviceProvisioning): String = json.encodeToString(DeviceProvisioning.serializer(), p)

    fun encode(p: DeviceProvisioning): String {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(toJson(p).toByteArray(Charsets.UTF_8)) }
        return PREFIX + b64.encode(out.toByteArray())
    }

    /** @throws IllegalArgumentException with a readable reason. */
    fun decode(text: String): DeviceProvisioning {
        val t = text.trim()
        val jsonText = when {
            t.startsWith(PREFIX) -> runCatching {
                // Codes pasted from a message often come wrapped: spaces and line breaks are not part of base64url.
                GZIPInputStream(ByteArrayInputStream(b64.decode(t.removePrefix(PREFIX).filterNot { it.isWhitespace() }))).use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrElse { throw IllegalArgumentException("provisioning code is damaged (${it.message})") }
            t.startsWith("{") -> t
            else -> throw IllegalArgumentException("not an OpenUTM device provisioning code")
        }
        return runCatching { json.decodeFromString(DeviceProvisioning.serializer(), jsonText) }
            .getOrElse { throw IllegalArgumentException("provisioning JSON is invalid: ${it.message?.lineSequence()?.firstOrNull()}") }
    }
}
