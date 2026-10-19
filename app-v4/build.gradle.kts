import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun prop(name: String): String? = (findProperty(name) as String?) ?: System.getenv(name)

// Never committed: set these in ~/.gradle/gradle.properties or the environment (docs/12, Distribution).
val djiApiKey: String = prop("DJI_API_KEY") ?: ""
// Debug builds only: the local stack for the "Use local dev stack" setup button. 10.0.2.2 = the host, from an emulator.
val devBlenderUrl: String = prop("BLENDER_URL") ?: "http://10.0.2.2:8000"

android {
    namespace = "org.openutm.djiflight.app.v4"
    compileSdk = 34
    if (providers.gradleProperty("openutm.releaseSmoke").orNull == "true") testBuildType = "release"

    defaultConfig {
        applicationId = "org.openutm.djiflight.v4"
        // MSDK v4.18: minSdk 23, targetSdk 34, 32- and 64-bit ARM. Not eligible for Google Play (4 KB pages).
        minSdk = 23
        targetSdk = 34
        versionCode = 6
        versionName = "0.5.1"
        multiDexEnabled = true
        testInstrumentationRunner = "org.openutm.djiflight.test.PanamaSetupSmoke"
        buildConfigField("String", "DEV_BLENDER_URL", "\"$devBlenderUrl\"")
    }

    signingConfigs {
        // One release key held by OpenUTM; updates only install over an APK signed with the same key.
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

    flavorDimensions += "sdk"
    productFlavors {
        create("dji") {
            dimension = "sdk"
            manifestPlaceholders["djiApiKey"] = djiApiKey
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        }
        create("sim") {
            // No DJI code at all: replays recorded tracks. Emulators, any phone, pilot training.
            dimension = "sdk"
            applicationIdSuffix = ".sim"
            versionNameSuffix = "-sim"
            manifestPlaceholders["djiApiKey"] = ""
        }
    }

    buildTypes {
        // Debug: Settings → System → Languages offers English (XA) (accented, 30 % longer) and (XB)
        // (right to left), to check every screen with translated-length text.
        debug {
            isPseudoLocalesEnabled = true
            buildConfigField("boolean", "MINI2_SURVEY_EXPERIMENTAL", ((findProperty("openutm.experimentalMini2") as String?) == "true").toString())
        }
        release {
            buildConfigField("boolean", "MINI2_SURVEY_EXPERIMENTAL", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            // proguard-rules.pro carries DJI's own 4.18 keep rules (from its sample app) and ours.
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
            // DJI's sample: extractNativeLibs=true and these libraries left unstripped.
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "libdjivideo.so", "libSDKRelativeJNI.so", "libFlyForbid.so", "libduml_vision_bokeh.so", "libyuv2.so",
                "libGroudStation.so", "libFRCorkscrew.so", "libUpgradeVerify.so", "libFR.so", "libDJIFlySafeCore.so",
                "libdjifs_jni.so", "libsfjni.so", "libDJICommonJNI.so", "libDJICSDKCommon.so", "libDJIUpgradeCore.so",
                "libDJIUpgradeJNI.so", "libDJIWaypointV2Core.so", "libAMapSDK_MAP_v6_9_2.so", "libDJIMOP.so", "libDJISDKLOGJNI.so",
            ).map { "*/*/$it" }
        }
        resources {
            excludes += listOf("META-INF/rxjava.properties", "assets/location_map_gps_locked.png", "assets/location_map_gps_3d.png")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":app-common")) // the screens, services, maps; core comes with it
    implementation(libs.androidx.multidex)

    "djiImplementation"(project(":adapter-dji-v4"))
    "djiImplementation"(libs.dji.sdk.v4) {
        exclude(module = "library-anti-distortion") // as DJI's sample does
    }
    "djiCompileOnly"(libs.dji.sdk.v4.provided)
    // DJI's UI Library: camera view, camera settings, status, dashboard, take-off/RTH, pre-flight checklist.
    // Its POM drags in its own SDK (4.16.2) and three map SDKs for its MapWidget, which we don't use
    // (our map is offline MapLibre): Google Play services (absent on the Smart Controller), AMap, Mapbox.
    "djiImplementation"(libs.dji.uxsdk.v4) {
        exclude(group = "com.dji", module = "dji-sdk")
        exclude(group = "com.dji", module = "library-anti-distortion")
        exclude(group = "com.google.android.gms")
        exclude(group = "com.amap.api")
        exclude(group = "com.mapbox.mapboxsdk")
        exclude(group = "org.jetbrains.kotlin")
    }
    // Used by the UX SDK's camera settings panels, undeclared in its POM (found by inflating them on an emulator).
    "djiImplementation"(libs.androidx.recyclerview)
    // The SDK's own layouts use these attributes but its POM doesn't declare them (DJI's sample adds them too).
    "djiImplementation"(libs.androidx.appcompat)
    "djiImplementation"(libs.androidx.constraintlayout)

    "simImplementation"(project(":adapter-fake"))
}

/**
 * `./gradlew :app-v4:assembleRelease :app-v4:releaseChannel` copies the release APKs to
 * build/release-channel/ and writes latest.json (version, SHA-256) for the in-app update check.
 * Publish the folder as static files (GitHub Releases, the lab's web host).
 */
tasks.register("releaseChannel") {
    group = "distribution"
    // Collects what assembleRelease made: when both are asked for, run after it, never before.
    mustRunAfter("assembleRelease")
    description = "Collects release APKs and writes latest.json for the in-app update check."
    doLast {
        // Only this build's APKs: an older one left here would be published with the channel.
        val outDir = layout.buildDirectory.dir("release-channel").get().asFile.apply { deleteRecursively(); mkdirs() }
        val vc = android.defaultConfig.versionCode!!
        val vn = android.defaultConfig.versionName!!
        val entries = listOf("dji" to "org.openutm.djiflight.v4", "sim" to "org.openutm.djiflight.v4.sim").mapNotNull { (flavor, appId) ->
            val apk = layout.buildDirectory.dir("outputs/apk/$flavor/release").get().asFile.listFiles { f -> f.extension == "apk" }?.firstOrNull()
                ?: return@mapNotNull null
            val name = "$appId-$vn.apk"
            apk.copyTo(File(outDir, name), overwrite = true)
            val sha = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
            val suffix = if (flavor == "sim") "-sim" else ""
            """    {"application_id": "$appId", "version_code": $vc, "version_name": "$vn$suffix", "url": "$name", "sha256": "$sha", "min_sdk": 23}"""
        }
        check(entries.isNotEmpty()) { "no release APKs found: run assembleRelease first" }
        File(outDir, "latest.json").writeText("{\n  \"releases\": [\n" + entries.joinToString(",\n") + "\n  ]\n}\n")
        println("wrote ${File(outDir, "latest.json")} (${entries.size} APKs)")
    }
}
