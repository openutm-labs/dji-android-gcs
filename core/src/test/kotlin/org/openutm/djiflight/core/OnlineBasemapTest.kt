package org.openutm.djiflight.core

import org.openutm.djiflight.core.provisioning.OnlineBasemap
import org.openutm.djiflight.core.provisioning.PanamaDeployment
import org.openutm.djiflight.core.provisioning.ProvisioningCodec
import kotlin.test.*

class OnlineBasemapTest {
    @Test fun `default public map works without a provider key or device credentials`() {
        val map = OnlineBasemap()
        assertTrue(map.problems().isEmpty())
        assertEquals("https://tile.openstreetmap.org/{z}/{x}/{y}.png", map.tileUrl)
        assertEquals(19, map.maxZoom)
        assertTrue(map.attribution.contains("OpenStreetMap contributors"))
    }

    @Test fun `custom provider survives device setup interchange while old profiles keep defaults`() {
        val p = PanamaDeployment.deviceSetup("test-device", "test-operator", "Test", "test-client", "test-secret")
        assertNull(ProvisioningCodec.decode(ProvisioningCodec.toJson(p)).onlineMap)
        val custom = OnlineBasemap("Lab streets", "https://maps.example.org/{z}/{x}/{y}.png", "Lab map credits", "https://maps.example.org/credits", 18)
        assertEquals(custom, ProvisioningCodec.decode(ProvisioningCodec.toJson(p.copy(onlineMap = custom))).onlineMap)
        assertTrue(p.copy(onlineMap = custom).problems(false).isEmpty())
    }

    @Test fun `invalid tile templates unsafe links and missing attribution are refused`() {
        for (url in listOf("http://example.org/{z}/{x}/{y}.png", "https://user:pass@example.org/{z}/{x}/{y}.png", "https://example.org/{z}/{x}.png", "https://example.org/{z}/{x}/{y}/{x}.png", "https://example.org/{z}/{x}/{y}.png#fragment")) {
            assertTrue(OnlineBasemap(tileUrl = url).problems().isNotEmpty(), url)
        }
        assertTrue(OnlineBasemap(attribution = " ").problems().isNotEmpty())
        assertTrue(OnlineBasemap(attributionUrl = "javascript:alert(1)").problems().isNotEmpty())
        assertTrue(OnlineBasemap(maxZoom = 23).problems().isNotEmpty())
        assertTrue(OnlineBasemap(maxZoom = -1).problems().isNotEmpty())
    }
}
