plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.openutm.djiflight.dji.v4"
    compileSdk = 34
    defaultConfig {
        // DJI MSDK v4.18's sample uses minSdk 23; the Smart Controller runs Android 7.x.
        minSdk = 23
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
    compileOnly(libs.dji.sdk.v4)
    compileOnly(libs.dji.sdk.v4.provided)
}
