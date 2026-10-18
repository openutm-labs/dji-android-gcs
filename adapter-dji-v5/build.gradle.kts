plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.openutm.djiflight.dji.v5"
    compileSdk = 34
    defaultConfig {
        // DJI MSDK v5.18: minSdk 24 (the aar's own manifest); its sample targets 35.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
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
    implementation(libs.kotlinx.coroutines.android)
    // The app module packages the SDK; this module only compiles against it.
    compileOnly(libs.dji.sdk.v5)
    compileOnly(libs.dji.sdk.v5.provided)
    // DJI's WPML library, a dependency of the SDK itself: used only for its KMZ validator.
    compileOnly(libs.dji.wpmzsdk)
}
