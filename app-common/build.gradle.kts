plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The screens, background services, maps and provisioning shared by app-v4 (dji, sim) and app-v5.
// No SDK code: each app supplies a Platform (adapter, video, build facts).
android {
    namespace = "org.openutm.djiflight.app"
    compileSdk = 34
    defaultConfig {
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core) // FileProvider for installing updates
    implementation(libs.maplibre.android)
    implementation(libs.zxing.android.embedded) { isTransitive = false }
    implementation(libs.zxing.core)
}
