package org.openutm.djiflight.core.provisioning

import java.util.UUID

/** Public deployment defaults only. Credentials and operator identities are supplied per device. */
object PanamaDeployment {
    const val BLENDER_URL = "https://blender.panama.openutm.net"
    const val TOKEN_URL = "https://auth.panama.openutm.net/oauth/token/"
    const val AUDIENCE = "blender.panama.openutm.net"
    const val LAB = "Panama Flying Labs"
    const val SCOPE = "flightblender.read flightblender.write"

    fun deviceSetup(
        deviceName: String,
        operatorId: String,
        pilotName: String,
        clientId: String,
        clientSecret: String,
        aircraft: LabAircraft? = null,
        legalMaxHeightM: Double = 120.0,
    ): DeviceProvisioning {
        require(pilotName.isNotBlank()) { "Pilot name is required" }
        return DeviceProvisioning(
            deviceName = deviceName.trim(), blenderUrl = BLENDER_URL,
            auth = AuthConfig.ClientCredentials(TOKEN_URL, clientId.trim(), clientSecret, SCOPE, AUDIENCE),
            lab = LAB, operatorId = operatorId.trim(), country = "PA", legalMaxHeightM = legalMaxHeightM,
            pilots = listOf(Pilot("pilot-${UUID.randomUUID()}", pilotName.trim())),
            aircraft = listOfNotNull(aircraft),
        )
    }
}
