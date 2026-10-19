package org.openutm.djiflight.core.sync

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Fingerprint the API destination without persisting its URL, query or credentials in flights. */
object ServerIdentity {
    fun of(baseUrl: String): String {
        val uri = URI(baseUrl.trim()).normalize()
        val scheme = requireNotNull(uri.scheme).lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "unsupported server scheme" }
        val host = requireNotNull(uri.host).lowercase(Locale.ROOT)
        require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "server URL must not contain credentials, query or fragment" }
        val port = when {
            uri.port < 0 || (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80) -> ""
            else -> ":${uri.port}"
        }
        val canonical = "$scheme://$host$port${uri.rawPath.orEmpty().trimEnd('/')}"
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
