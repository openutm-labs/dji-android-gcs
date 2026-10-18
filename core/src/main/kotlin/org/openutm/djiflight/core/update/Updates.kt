package org.openutm.djiflight.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** One entry of the release channel's `latest.json` (a static file next to the APKs; no server). */
@Serializable
data class Release(
    @SerialName("application_id") val applicationId: String,
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    /** Absolute, or relative to the `latest.json` URL. */
    val url: String,
    val sha256: String,
    @SerialName("min_sdk") val minSdk: Int? = null,
    val notes: String? = null,
)

@Serializable
data class ReleaseChannel(val releases: List<Release>)

object Updates {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

    fun parse(text: String): ReleaseChannel = json.decodeFromString(ReleaseChannel.serializer(), text)

    fun toJson(channel: ReleaseChannel): String = json.encodeToString(ReleaseChannel.serializer(), channel)

    /** The newest release for this app that this device can install, if it is newer than [currentVersionCode]. */
    fun available(channel: ReleaseChannel, applicationId: String, currentVersionCode: Int, sdkInt: Int): Release? =
        channel.releases
            .filter { it.applicationId == applicationId && it.versionCode > currentVersionCode && (it.minSdk ?: 0) <= sdkInt }
            .maxByOrNull { it.versionCode }

    fun resolveUrl(channelUrl: String, releaseUrl: String): String = when {
        releaseUrl.startsWith("https://") || releaseUrl.startsWith("http://") -> releaseUrl
        else -> channelUrl.substringBeforeLast('/') + "/" + releaseUrl.trimStart('/')
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** A downloaded APK is installed only if its SHA-256 matches the channel's. */
    fun verify(file: File, release: Release): Boolean = sha256(file).equals(release.sha256.trim(), ignoreCase = true)
}
