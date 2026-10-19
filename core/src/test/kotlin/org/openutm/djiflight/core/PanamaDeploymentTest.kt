package org.openutm.djiflight.core

import kotlinx.coroutines.test.runTest
import org.openutm.djiflight.core.auth.TokenProvider
import org.openutm.djiflight.core.blender.BlenderBackend
import org.openutm.djiflight.core.http.HttpRequest
import org.openutm.djiflight.core.http.HttpResponse
import org.openutm.djiflight.core.http.HttpTransport
import org.openutm.djiflight.core.provisioning.AuthConfig
import org.openutm.djiflight.core.provisioning.LabAircraft
import org.openutm.djiflight.core.provisioning.PanamaDeployment
import org.openutm.djiflight.core.provisioning.ProvisioningCodec
import org.openutm.djiflight.core.sync.BackendResult
import kotlin.test.*

class PanamaDeploymentTest {
    @Test fun `Panama setup supplies public endpoints and a distinct pilot identity per device`() {
        fun setup() = PanamaDeployment.deviceSetup(" Phone ", " OPR-TEST ", " Test Pilot ", " test-client ", "unit-test-secret")
        val p = setup()
        val a = assertIs<AuthConfig.ClientCredentials>(p.auth)
        assertEquals("https://blender.panama.openutm.net", p.blenderUrl)
        assertEquals("https://auth.panama.openutm.net/oauth/token/", a.tokenUrl)
        assertEquals("blender.panama.openutm.net", a.audience)
        assertEquals("flightblender.read flightblender.write", a.scope)
        assertEquals("OPR-TEST", p.operatorId)
        assertEquals("Test Pilot", p.pilots.single().name)
        assertNotEquals(p.pilots.single().id, setup().pilots.single().id)
        assertTrue(p.aircraft.isEmpty())
        assertTrue(p.problems(allowDevAuth = false).isEmpty())
        assertEquals(p, ProvisioningCodec.decode(ProvisioningCodec.toJson(p)))
    }

    @Test fun `setup never invents aircraft registration and requires supplied identity and credentials`() {
        val p = PanamaDeployment.deviceSetup("Phone", "", "Pilot", "", "", LabAircraft("test-serial", "", "Mini 2"))
        assertEquals("", p.aircraft.single().registration)
        assertTrue(p.problems(false).any { "operator_id" in it })
        assertTrue(p.problems(false).any { "client_id" in it })
        assertFailsWith<IllegalArgumentException> { PanamaDeployment.deviceSetup("Phone", "OPR-TEST", " ", "test-client", "unit-test-secret") }
    }

    @Test fun `connection check performs only a protected GET and refuses a login HTML page`() = runTest {
        val requests = mutableListOf<HttpRequest>()
        var body = """{"count":0,"results":[]}"""
        val transport = HttpTransport { request -> requests += request; HttpResponse(200, body) }
        val tokens = object : TokenProvider { override fun token(forceRefresh: Boolean) = "unit-test-token" }
        val backend = BlenderBackend(PanamaDeployment.BLENDER_URL, tokens, transport, "connection-check")
        assertIs<BackendResult.Ok<Unit>>(backend.checkConnection())
        body = "<html>Sign in</html>"
        assertIs<BackendResult.Rejected>(backend.checkConnection())
        assertTrue(requests.all { it.method == "GET" && it.body == null })
        assertTrue(requests.all { it.url == PanamaDeployment.BLENDER_URL + "/flight_declaration_ops/flight_declaration?page_size=1" })
    }

    @Test fun `connection check refreshes a rejected token then reports access refusal without writing`() = runTest {
        val methods = mutableListOf<String>()
        val refreshed = mutableListOf<Boolean>()
        val tokens = object : TokenProvider {
            override fun token(forceRefresh: Boolean): String { refreshed += forceRefresh; return "unit-test-token" }
        }
        val backend = BlenderBackend(PanamaDeployment.BLENDER_URL, tokens, HttpTransport {
            methods += it.method; HttpResponse(401, "unauthorized")
        }, "connection-check")
        assertIs<BackendResult.Unauthorized>(backend.checkConnection())
        assertEquals(listOf(false, true), refreshed)
        assertEquals(listOf("GET", "GET"), methods)
    }
}
