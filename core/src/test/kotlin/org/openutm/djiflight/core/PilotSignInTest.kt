package org.openutm.djiflight.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openutm.djiflight.core.auth.OidcClient
import org.openutm.djiflight.core.auth.PilotLoginConfig
import org.openutm.djiflight.core.auth.Pkce
import org.openutm.djiflight.core.auth.SignInException
import org.openutm.djiflight.core.http.HttpResponse
import org.openutm.djiflight.core.http.HttpTransport
import org.openutm.djiflight.core.provisioning.AuthConfig
import org.openutm.djiflight.core.provisioning.DeviceProvisioning
import org.openutm.djiflight.core.provisioning.Pilot
import java.math.BigInteger
import java.net.URI
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class PilotSignInTest {
    private val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val issuer = "http://10.0.2.2:9000/o"
    private val config = PilotLoginConfig(issuer, "app-client", validHours = 72)
    private val now = 1_792_142_000_000L
    private val key: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val otherKey: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun BigInteger.b64u(): String = b64.encode(toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it })

    /** A fake identity server: discovery, JWKS, and a token endpoint that checks the PKCE verifier. */
    private inner class FakeServer(
        val signWith: KeyPair = key,
        val claims: (nonce: String) -> Map<String, Any> = { n -> mapOf("iss" to issuer, "aud" to "app-client", "sub" to "u-42", "name" to "Amina Test", "email" to "pilot1@example.org", "nonce" to n, "iat" to now / 1000, "exp" to now / 1000 + 600) },
        val userinfo: String? = null,
    ) : HttpTransport {
        var challenge: String? = null
        var nonce: String? = null
        override fun execute(request: org.openutm.djiflight.core.http.HttpRequest): HttpResponse = when {
            request.url.endsWith("/.well-known/openid-configuration") -> HttpResponse(200, """{"issuer":"$issuer","authorization_endpoint":"$issuer/authorize/","token_endpoint":"$issuer/token/","jwks_uri":"$issuer/.well-known/jwks.json","userinfo_endpoint":"$issuer/userinfo/"}""")
            request.url.endsWith("/userinfo/") -> if (userinfo != null && request.headers["Authorization"] == "Bearer a") HttpResponse(200, userinfo) else HttpResponse(401, "")
            request.url.endsWith("jwks.json") -> {
                val pub = key.public as RSAPublicKey
                HttpResponse(200, """{"keys":[{"kty":"RSA","kid":"k1","use":"sig","alg":"RS256","n":"${pub.modulus.b64u()}","e":"${pub.publicExponent.b64u()}"}]}""")
            }
            request.url.endsWith("/token/") -> {
                val form = OidcClient.query(String(request.body!!))
                if (form["client_id"] != "app-client" || Pkce.challenge(form["code_verifier"]!!) != challenge || form["code"] != "the-code") HttpResponse(400, """{"error":"invalid_grant"}""")
                else HttpResponse(200, """{"access_token":"a","token_type":"Bearer","id_token":"${jwt(claims(nonce!!), signWith)}"}""")
            }
            else -> HttpResponse(404, "")
        }

        fun jwt(c: Map<String, Any>, k: KeyPair): String {
            val h = b64.encode("""{"alg":"RS256","typ":"JWT","kid":"k1"}""".toByteArray())
            val body = buildJsonObject { c.forEach { (n, v) -> if (v is Long) put(n, v) else put(n, v.toString()) } }.toString()
            val p = b64.encode(body.toByteArray())
            val sig = Signature.getInstance("SHA256withRSA").run { initSign(k.private); update("$h.$p".toByteArray()); sign() }
            return "$h.$p.${b64.encode(sig)}"
        }
    }

    private fun signIn(server: FakeServer, tamper: (Map<String, String>) -> Map<String, String> = { it }) = run {
        val c = OidcClient(server, config)
        val ep = c.discover()
        val (url, pending) = c.start(ep, "org.openutm.djiflight.v4.sim:/oauth2redirect", now)
        val q = OidcClient.query(URI(url).rawQuery)
        server.challenge = q["code_challenge"]
        server.nonce = q["nonce"]
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("$issuer/authorize/", url.substringBefore('?'))
        c.finish(pending, tamper(mapOf("code" to "the-code", "state" to q["state"]!!)), now + 20_000)
    }

    @Test
    fun `PKCE challenge matches RFC 7636's example`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertTrue(Pkce.random(48).length >= 43)
    }

    @Test
    fun `a pilot signs in with code and PKCE and the verified ID token says who`() {
        val s = signIn(FakeServer())
        assertEquals("u-42", s.subject)
        assertEquals("Amina Test", s.name)
        assertEquals(issuer, s.issuer)
        assertTrue(s.valid(now + 71 * 3_600_000L))
        assertFalse(s.valid(now + 73 * 3_600_000L))
        // The lab's roster is matched by id, then by email.
        val p = DeviceProvisioning(deviceName = "d", blenderUrl = "https://b", auth = AuthConfig.Dev, lab = "L", operatorId = "O",
            pilots = listOf(Pilot("pilot1@example.org", "Amina (roster)")), pilotLogin = config)
        assertEquals("Amina (roster)", p.rosterEntry(s)?.name)
    }

    @Test
    fun `Passport style - no name in the ID token, the email from userinfo finds the roster entry`() {
        val passportClaims: (String) -> Map<String, Any> = { n -> mapOf("iss" to issuer, "aud" to "app-client", "sub" to "1", "nonce" to n, "iat" to now / 1000, "exp" to now / 1000 + 600) }
        val s = signIn(FakeServer(claims = passportClaims, userinfo = """{"sub":"1","email":"pilot1@example.org","role":""}"""))
        assertEquals("1", s.subject)
        assertEquals("pilot1@example.org", s.email)
        val p = DeviceProvisioning(deviceName = "d", blenderUrl = "https://b", auth = AuthConfig.Dev, lab = "L", operatorId = "O",
            pilots = listOf(Pilot("pilot1@example.org", "Amina (roster)")), pilotLogin = config)
        assertEquals("Amina (roster)", p.rosterEntry(s)?.name)
        // Userinfo for another subject is ignored.
        assertEquals(null, signIn(FakeServer(claims = passportClaims, userinfo = """{"sub":"2","email":"someone@example.org"}""")).email)
        assertEquals("openid profile", PilotLoginConfig("https://p", "c").scope)
    }

    @Test
    fun `forged, foreign, stale or replayed tokens are refused`() {
        assertFailsWith<SignInException> { signIn(FakeServer(signWith = otherKey)) }.also { assertTrue("signature" in it.message!!) }
        assertFailsWith<SignInException> { signIn(FakeServer(claims = { n -> mapOf("iss" to issuer, "aud" to "someone-else", "sub" to "u", "nonce" to n, "exp" to now / 1000 + 600) })) }
        assertFailsWith<SignInException> { signIn(FakeServer(claims = { n -> mapOf("iss" to "https://evil", "aud" to "app-client", "sub" to "u", "nonce" to n, "exp" to now / 1000 + 600) })) }
        assertFailsWith<SignInException> { signIn(FakeServer(claims = { n -> mapOf("iss" to issuer, "aud" to "app-client", "sub" to "u", "nonce" to n, "exp" to now / 1000 - 3600) })) }
        assertFailsWith<SignInException> { signIn(FakeServer(claims = { _ -> mapOf("iss" to issuer, "aud" to "app-client", "sub" to "u", "nonce" to "old", "exp" to now / 1000 + 600) })) }
        assertFailsWith<SignInException> { signIn(FakeServer()) { it + ("state" to "another") } }
        assertFailsWith<SignInException> { signIn(FakeServer()) { mapOf("error" to "access_denied") } }
    }

    @Test
    fun `a setup code survives being wrapped over lines and spaces when pasted`() {
        val p = DeviceProvisioning(deviceName = "d", blenderUrl = "https://b", auth = AuthConfig.Dev, lab = "L", operatorId = "O", pilotLogin = config)
        val code = org.openutm.djiflight.core.provisioning.ProvisioningCodec.encode(p)
        val wrapped = code.chunked(37).joinToString("\n ")
        assertEquals(p, org.openutm.djiflight.core.provisioning.ProvisioningCodec.decode(wrapped))
    }

    @Test
    fun `device setup checks the pilot login block`() {
        val base = DeviceProvisioning(deviceName = "d", blenderUrl = "https://b", auth = AuthConfig.Dev, lab = "L", operatorId = "O")
        assertTrue(base.copy(pilotLogin = PilotLoginConfig("https://passport.example.org/o", "c")).problems(allowDevAuth = true).isEmpty())
        assertTrue(base.copy(pilotLogin = PilotLoginConfig("http://10.0.2.2:9000/o", "c")).problems(allowDevAuth = false).any { "https" in it })
        assertTrue(base.copy(pilotLogin = PilotLoginConfig("https://p", "", scope = "profile")).problems(allowDevAuth = true).size >= 2)
    }
}
