package org.openutm.djiflight.core.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.openutm.djiflight.core.http.HttpRequest
import org.openutm.djiflight.core.http.HttpTransport
import java.io.IOException
import java.math.BigInteger
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Per-pilot sign-in (docs/12, Auth): OpenID Connect, authorization code with PKCE, against the lab's
 * identity server (Flight Passport). It says who flew; it is not how the device talks to Blender,
 * which stays the device's own client credential, so a flight still syncs days later with no pilot
 * token. A sign-in counts for [PilotLoginConfig.validHours] after it, online or not.
 */
@Serializable
data class PilotLoginConfig(
    /** OIDC issuer; its discovery document is at `<issuer>/.well-known/openid-configuration`. */
    val issuer: String,
    /** A public client (no secret) registered for this app, with PKCE. */
    @SerialName("client_id") val clientId: String,
    /** Flight Passport offers only `openid` and `profile` (asking for `email` is refused); the email comes from userinfo. */
    val scope: String = "openid profile",
    /**
     * Registered with the client. The in-app sign-in page catches it before it loads, so nothing needs
     * to answer there; the default loopback form passes stock Passport, which refuses custom schemes.
     */
    @SerialName("redirect_uri") val redirectUri: String = DEFAULT_REDIRECT_URI,
    /** How long a sign-in counts, offline included. */
    @SerialName("valid_hours") val validHours: Int = 72,
    /** Plans need a signed-in pilot; otherwise the roster pick stands, recorded as not signed in. */
    val required: Boolean = false,
) {
    companion object {
        const val DEFAULT_REDIRECT_URI = "http://127.0.0.1/openutm-signin"
    }

    fun problems(): List<String> = buildList {
        if (!issuer.startsWith("https://") && !issuer.startsWith("http://")) add("pilot_login.issuer must be a URL")
        if (clientId.isBlank()) add("pilot_login.client_id is empty")
        if (validHours !in 1..24 * 31) add("pilot_login.valid_hours must be 1..744")
        if ("openid" !in scope.split(' ')) add("pilot_login.scope must include openid")
    }
}

/** Who signed in, from a verified ID token; what a flight records about its pilot. */
@Serializable
data class PilotSignIn(
    val issuer: String,
    /** The identity server's stable id for the pilot (`sub`). */
    val subject: String,
    val name: String,
    /** Also used to match the lab's roster; kept on the device only, never sent to Blender. */
    val email: String? = null,
    val signedInAtMs: Long,
    val validUntilMs: Long,
) {
    fun valid(nowMs: Long) = nowMs in signedInAtMs - CLOCK_SKEW_MS..validUntilMs

    companion object {
        const val CLOCK_SKEW_MS = 5 * 60_000L
    }
}

/** What the app keeps between opening the sign-in page and the redirect coming back. */
@Serializable
data class PendingSignIn(
    val state: String,
    val nonce: String,
    val verifier: String,
    val redirectUri: String,
    val tokenEndpoint: String,
    val jwksUri: String,
    val issuer: String,
    val startedAtMs: Long,
    val userinfo: String? = null,
)

data class OidcEndpoints(val issuer: String, val authorization: String, val token: String, val jwksUri: String, val userinfo: String? = null)

class SignInException(message: String) : IOException(message)

/** RFC 7636: a random verifier and its S256 challenge. */
@OptIn(ExperimentalEncodingApi::class)
object Pkce {
    private val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val random = SecureRandom()

    fun random(bytes: Int = 32): String = b64.encode(ByteArray(bytes).also(random::nextBytes))

    fun challenge(verifier: String): String = b64.encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

@OptIn(ExperimentalEncodingApi::class)
class OidcClient(private val transport: HttpTransport, private val config: PilotLoginConfig) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Online: the identity server's endpoints from its discovery document. */
    fun discover(): OidcEndpoints {
        val url = config.issuer.trimEnd('/') + "/.well-known/openid-configuration"
        val r = transport.execute(HttpRequest("GET", url, mapOf("Accept" to "application/json")))
        if (r.status != 200) throw SignInException("identity server: HTTP ${r.status} for $url")
        val d = json.parseToJsonElement(r.body).jsonObject
        fun s(k: String) = d[k]?.jsonPrimitive?.contentOrNull ?: throw SignInException("identity server: discovery has no $k")
        return OidcEndpoints(s("issuer"), s("authorization_endpoint"), s("token_endpoint"), s("jwks_uri"), d["userinfo_endpoint"]?.jsonPrimitive?.contentOrNull)
    }

    /** The page the pilot signs in on, and what to keep until the redirect comes back. */
    fun start(ep: OidcEndpoints, redirectUri: String, nowMs: Long): Pair<String, PendingSignIn> {
        val pending = PendingSignIn(Pkce.random(16), Pkce.random(16), Pkce.random(48), redirectUri, ep.token, ep.jwksUri, ep.issuer, nowMs, ep.userinfo)
        val q = listOf(
            "response_type" to "code", "client_id" to config.clientId, "redirect_uri" to redirectUri, "scope" to config.scope,
            "state" to pending.state, "nonce" to pending.nonce, "code_challenge" to Pkce.challenge(pending.verifier), "code_challenge_method" to "S256",
            "prompt" to "login",
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val sep = if ('?' in ep.authorization) "&" else "?"
        return "${ep.authorization}$sep$q" to pending
    }

    /**
     * The redirect's query: checks the state, swaps the code for tokens (PKCE, no client secret),
     * and verifies the ID token's signature (RS256, from the server's JWKS), issuer, audience,
     * expiry and nonce. Returns who signed in.
     */
    fun finish(pending: PendingSignIn, redirectQuery: Map<String, String>, nowMs: Long): PilotSignIn {
        redirectQuery["error"]?.let { throw SignInException("sign-in refused: $it ${redirectQuery["error_description"].orEmpty()}".trim()) }
        if (redirectQuery["state"] != pending.state) throw SignInException("sign-in answer doesn't match this request (state)")
        val code = redirectQuery["code"] ?: throw SignInException("sign-in answer has no code")
        val form = listOf(
            "grant_type" to "authorization_code", "code" to code, "redirect_uri" to pending.redirectUri,
            "client_id" to config.clientId, "code_verifier" to pending.verifier,
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val r = transport.execute(HttpRequest("POST", pending.tokenEndpoint, mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"), form.toByteArray()))
        if (r.status != 200) throw SignInException("token endpoint: HTTP ${r.status} ${r.body.take(200)}")
        val tokens = json.parseToJsonElement(r.body).jsonObject
        val idToken = tokens["id_token"]?.jsonPrimitive?.contentOrNull
            ?: throw SignInException("token endpoint returned no id_token (is openid in the scope?)")
        val idClaims = verify(idToken, pending, nowMs)
        val sub = idClaims["sub"]?.jsonPrimitive?.contentOrNull ?: throw SignInException("ID token has no sub")
        // Passport's ID token carries no name or email: ask userinfo, for the same subject only.
        val extra = tokens["access_token"]?.jsonPrimitive?.contentOrNull?.let { at -> pending.userinfo?.let { userinfo(it, at, sub) } }
        val claims = JsonObject((extra ?: JsonObject(emptyMap())) + idClaims)
        fun c(k: String) = claims[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val name = c("name") ?: listOfNotNull(c("given_name"), c("family_name")).joinToString(" ").ifBlank { null } ?: c("preferred_username") ?: c("email") ?: sub
        return PilotSignIn(pending.issuer, sub, name, c("email"), nowMs, nowMs + config.validHours * 3_600_000L)
    }

    /** Userinfo claims, if the endpoint answers for [sub]; best effort (the ID token already proved who it is). */
    private fun userinfo(url: String, accessToken: String, sub: String): JsonObject? = runCatching {
        val r = transport.execute(HttpRequest("GET", url, mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")))
        if (r.status != 200) return null
        json.parseToJsonElement(r.body).jsonObject.takeIf { it["sub"]?.jsonPrimitive?.contentOrNull == sub }
    }.getOrNull()

    /** The ID token's claims, once its signature and contents check out. */
    fun verify(idToken: String, pending: PendingSignIn, nowMs: Long): JsonObject {
        val parts = idToken.split('.')
        if (parts.size != 3) throw SignInException("ID token is not a JWT")
        val header = json.parseToJsonElement(b64(parts[0]).decodeToString()).jsonObject
        val alg = header["alg"]?.jsonPrimitive?.contentOrNull
        if (alg != "RS256") throw SignInException("ID token signed with $alg; only RS256 is accepted")
        val kid = header["kid"]?.jsonPrimitive?.contentOrNull
        val r = transport.execute(HttpRequest("GET", pending.jwksUri, mapOf("Accept" to "application/json")))
        if (r.status != 200) throw SignInException("JWKS: HTTP ${r.status}")
        val keys = json.parseToJsonElement(r.body).jsonObject["keys"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            .filter { it["kty"]?.jsonPrimitive?.contentOrNull == "RSA" && (it["use"]?.jsonPrimitive?.contentOrNull ?: "sig") == "sig" }
        val key = (if (kid != null) keys.firstOrNull { it["kid"]?.jsonPrimitive?.contentOrNull == kid } else null) ?: keys.singleOrNull()
            ?: throw SignInException("no JWKS key for this ID token")
        val pub = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(1, b64(key["n"]!!.jsonPrimitive.content)), BigInteger(1, b64(key["e"]!!.jsonPrimitive.content))),
        )
        val ok = Signature.getInstance("SHA256withRSA").run {
            initVerify(pub)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            verify(b64(parts[2]))
        }
        if (!ok) throw SignInException("ID token signature is wrong")
        val claims = json.parseToJsonElement(b64(parts[1]).decodeToString()).jsonObject
        val iss = claims["iss"]?.jsonPrimitive?.contentOrNull
        if (iss?.trimEnd('/') != pending.issuer.trimEnd('/')) throw SignInException("ID token is from $iss, not ${pending.issuer}")
        val aud = claims["aud"]
        val audiences = when {
            aud == null -> emptyList()
            aud is kotlinx.serialization.json.JsonArray -> aud.map { it.jsonPrimitive.content }
            else -> listOf(aud.jsonPrimitive.content)
        }
        if (config.clientId !in audiences) throw SignInException("ID token is not for this app (aud)")
        val exp = claims["exp"]?.jsonPrimitive?.longOrNull ?: throw SignInException("ID token has no expiry")
        if (nowMs > exp * 1000 + PilotSignIn.CLOCK_SKEW_MS) throw SignInException("ID token has expired: check this controller's clock")
        claims["iat"]?.jsonPrimitive?.longOrNull?.let { if (it * 1000 > nowMs + PilotSignIn.CLOCK_SKEW_MS) throw SignInException("ID token issued in the future: check this controller's clock") }
        if (claims["nonce"]?.jsonPrimitive?.contentOrNull != pending.nonce) throw SignInException("ID token doesn't match this sign-in (nonce)")
        return claims
    }

    private fun b64(s: String): ByteArray = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(s)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        /** `a=1&b=x%20y` → map, for the redirect's query. */
        fun query(q: String?): Map<String, String> = q.orEmpty().split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }
    }
}
