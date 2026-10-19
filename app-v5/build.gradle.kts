import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun prop(name: String): String? = (findProperty(name) as String?) ?: System.getenv(name)

// Never committed: set these in ~/.gradle/gradle.properties or the environment (docs/12, Distribution).
// A DJI app key is tied to one package name, so v5 has its own (org.openutm.djiflight.v5).
val djiApiKey: String = prop("DJI_V5_API_KEY") ?: ""
// Debug builds only: the local stack for the "Use local dev stack" setup button. 10.0.2.2 = the host, from an emulator.
val devBlenderUrl: String = prop("BLENDER_URL") ?: "http://10.0.2.2:8000"

android {
    namespace = "org.openutm.djiflight.app.v5"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.openutm.djiflight.v5"
        // MSDK v5.18: minSdk 24, 64-bit ARM only (DJI's sample: abiFilters arm64-v8a). Every native
        // library is 16 KB-aligned. The DJI RC Pro Enterprise and RC Plus 2 run Android, arm64.
        minSdk = 24
        targetSdk = 34
        versionCode = 9
        versionName = "0.6.0"
        manifestPlaceholders["djiApiKey"] = djiApiKey
        buildConfigField("String", "DEV_BLENDER_URL", "\"$devBlenderUrl\"")
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // The same OpenUTM release key as app-v4; updates only install over an APK signed with it.
        val store = prop("OPENUTM_RELEASE_STORE_FILE")
        if (store != null) {
            create("release") {
                storeFile = file(store)
                storePassword = prop("OPENUTM_RELEASE_STORE_PASSWORD")
                keyAlias = prop("OPENUTM_RELEASE_KEY_ALIAS")
                keyPassword = prop("OPENUTM_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Debug: Settings → System → Languages offers English (XA) (accented, 30 % longer) and (XB)
        // (right to left), to check every screen with translated-length text.
        debug {
            isPseudoLocalesEnabled = true
        }
        release {
            // The SDK's own consumer rules (in its aar) switch off shrinking and keep dji.**, com.dji.**,
            // org.**, com.google.**: R8 only obfuscates. Resource shrinking needs code shrinking, so off.
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // The aar asks for extractNativeLibs=true; DJI's sample leaves these unstripped and picks one libc++_shared.
            useLegacyPackaging = true
            pickFirsts += listOf("lib/arm64-v8a/libc++_shared.so")
            keepDebugSymbols += listOf(
                "libconstants.so", "libdji_innertools.so", "libdjibase.so", "libDJICSDKCommon.so", "libDJIFlySafeCore-CSDK.so",
                "libdjifs_jni-CSDK.so", "libDJIRegister.so", "libdjisdk_jni.so", "libDJIUpgradeCore.so", "libDJIUpgradeJNI.so",
                "libDJIWaypointV2Core-CSDK.so", "libdjiwpv2-CSDK.so", "libFlightRecordEngine.so", "libvideo-framing.so", "libwaes.so",
                "libagora-rtsa-sdk.so", "libc++.so", "libc++_shared.so", "libmrtc_28181.so", "libmrtc_agora.so", "libmrtc_core.so",
                "libmrtc_core_jni.so", "libmrtc_data.so", "libmrtc_log.so", "libmrtc_onvif.so", "libmrtc_rtmp.so", "libmrtc_rtsp.so",
            ).map { "*/*/$it" }
            // Opt-in, until checked on an aircraft: leave out the SDK's live-streaming protocols (Agora,
            // ONVIF, RTSP, GB/T 28181, WebRTC, RTMP, NDI), which this app never starts. About 45 MB less.
            //   ./gradlew -Popenutm.slimNative=true :app-v5:assembleRelease
            if (providers.gradleProperty("openutm.slimNative").orNull == "true") {
                excludes += listOf(
                    "libagora-rtc-sdk.so", "libagora-ffmpeg.so", "libagora-fdkaac.so", "libagora-soundtouch.so", "libaosl.so",
                    "libmrtc_onvif_client.so", "libmrtc_rtsp.so", "libmrtc_28181_server.so", "libmrtc_28181.so",
                    "libmrtc_webrtc.so", "libmrtc_webrtc_recv.so", "libmrtc_rtmp.so", "libmrtc_agora.so", "libmrtc_volc.so", "libndi.so",
                ).map { "lib/*/$it" }
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":app-common")) // the screens, services, maps; core comes with it
    implementation(project(":adapter-dji-v5"))
    // DJI's UI Library v5 (vendored, MIT): top bar, camera view, settings and status panels (DjiV5Platform).
    implementation(project(":uxsdk-v5"))
    implementation(libs.androidx.appcompat) // DjiPanelsActivity hosts DJI's settings fragments
    implementation(libs.dji.sdk.v5)
    compileOnly(libs.dji.sdk.v5.provided)
    runtimeOnly(libs.dji.sdk.v5.network)
    // Debug-only WPML self-test screen (src/debug) calls DJI's validator directly.
    debugCompileOnly(libs.dji.wpmzsdk)
}

/**
 * `./gradlew :app-v5:assembleRelease :app-v5:releaseChannel` copies the release APK to
 * build/release-channel/ and writes latest.json for the in-app update check. v5 controllers get
 * their own channel URL (`update_url` in their device setup), separate from the v4 devices'.
 */
tasks.register("releaseChannel") {
    group = "distribution"
    // Collects what assembleRelease made: when both are asked for, run after it, never before.
    mustRunAfter("assembleRelease")
    description = "Collects the release APK and writes latest.json for the in-app update check."
    doLast {
        // Only this build's APKs: an older one left here would be published with the channel.
        val outDir = layout.buildDirectory.dir("release-channel").get().asFile.apply { deleteRecursively(); mkdirs() }
        val vc = android.defaultConfig.versionCode!!
        val vn = android.defaultConfig.versionName!!
        val appId = android.defaultConfig.applicationId!!
        val apk = layout.buildDirectory.dir("outputs/apk/release").get().asFile.listFiles { f -> f.extension == "apk" }?.firstOrNull()
            ?: error("no release APK found: run assembleRelease first")
        val name = "$appId-$vn.apk"
        apk.copyTo(File(outDir, name), overwrite = true)
        val sha = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
        File(outDir, "latest.json").writeText(
            "{\n  \"releases\": [\n    {\"application_id\": \"$appId\", \"version_code\": $vc, \"version_name\": \"$vn\", \"url\": \"$name\", \"sha256\": \"$sha\", \"min_sdk\": 24}\n  ]\n}\n",
        )
        println("wrote ${File(outDir, "latest.json")}")
    }
}
