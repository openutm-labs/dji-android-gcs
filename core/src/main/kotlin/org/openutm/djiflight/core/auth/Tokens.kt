package org.openutm.djiflight.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.openutm.djiflight.core.http.HttpRequest
import org.openutm.djiflight.core.http.HttpTransport
import org.openutm.djiflight.core.time.Clock
import java.io.IOException
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Supplies bearer tokens for Flight Blender. Called at sync time, never by a person. */
interface TokenProvider {
    /** @throws IOException if no token can be obtained now (e.g. offline). */
    fun token(forceRefresh: Boolean = false): String
}

class AuthException(message: String) : IOException(message)

/**
 * The unsigned-in-effect JWT Flight Blender accepts only with BYPASS_AUTH_TOKEN_VERIFICATION=1,
 * the same one `tools/sitl-bridge/blender_api.py` mints. Local development and tests only.
 */
@OptIn(ExperimentalEncodingApi::class)
class DevTokenProvider(
    private val clock: Clock = Clock.SYSTEM,
    private val audience: String = "testflight.flightblender.com",
    private val scopes: String = "flightblender.read flightblender.write",
    private val subject: String = "dji-flight-dev",
) : TokenProvider {
    override fun token(forceRefresh: Boolean): String {
        val now = clock.nowMs() / 1000
        val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val header = b64.encode("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val claims = buildJsonObject {
            put("iss", "dummy"); put("sub", subject); put("aud", audience); put("scope", scopes)
            put("iat", now); put("exp", now + 3600)
        }
        val body = b64.encode(claims.toString().toByteArray())
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("local-dev-only".toByteArray(), "HmacSHA256")) }
        return "$header.$body.${b64.encode(mac.doFinal("$header.$body".toByteArray()))}"
    }
}

/**
 * OAuth2 client credentials (one credential per device, provisioned by QR; docs/12, Auth).
 * The token is cached until shortly before it expires.
 */
@OptIn(ExperimentalEncodingApi::class)
class ClientCredentialsTokenProvider(
    private val transport: HttpTransport,
    private val tokenUrl: String,
    private val clientId: String,
    private val clientSecret: String,
    private val scope: String,
    private val audience: String? = null,
    private val clock: Clock = Clock.SYSTEM,
    private val skewMs: Long = 60_000,
) : TokenProvider {
    private var cached: String? = null
    private var expiresAtMs = 0L

    @Synchronized
    override fun token(forceRefresh: Boolean): String {
        val now = clock.nowMs()
        cached?.let { if (!forceRefresh && now < expiresAtMs - skewMs) return it }
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val form = buildString {
            append("grant_type=client_credentials&scope=").append(enc(scope))
            audience?.let { append("&audience=").append(enc(it)) }
        }
        val basic = Base64.encode("$clientId:$clientSecret".toByteArray())
        val resp = transport.execute(
            HttpRequest(
                "POST", tokenUrl,
                mapOf("Authorization" to "Basic $basic", "Content-Type" to "application/x-www-form-urlencoded"),
                form.toByteArray(),
            ),
        )
        if (resp.status != 200) throw AuthException("token endpoint returned HTTP ${resp.status}")
        val json = runCatching { Json.parseToJsonElement(resp.body) as JsonObject }.getOrNull()
            ?: throw AuthException("token endpoint returned no JSON")
        val token = json["access_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw AuthException("token response has no access_token")
        val ttl = json["expires_in"]?.jsonPrimitive?.long ?: 3600
        cached = token
        expiresAtMs = now + ttl * 1000
        return token
    }
}
