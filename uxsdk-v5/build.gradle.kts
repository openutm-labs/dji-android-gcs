// DJI's UI Library for MSDK v5 (android-sdk-v5-uxsdk), vendored from dji-sdk/Mobile-SDK-Android-V5
// @ a48aa4e (2026-06-03), MIT (LICENSE-DJI.txt). Used by app-v5 only. Changes from DJI's module (README.md):
// no map kit providers (Google Maps, MapLibre 10), no MapWidget, no sample activities, no CJK font.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dji.v5.ux"
    compileSdk = 34
    resourcePrefix = "uxsdk_"
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { viewBinding = true }
    // DJI's own code: its warnings are not ours to fix.
    lint { abortOnError = false; checkReleaseBuilds = false }
}

kotlin {
    jvmToolchain(17)
    compilerOptions { freeCompilerArgs.add("-Xjvm-default=all") }
}

dependencies {
    // MSDK 5.18 itself brings RxJava3/RxAndroid, okio, wire, gson and Room; app-v5 packages it.
    compileOnly(libs.dji.sdk.v5)
    compileOnly(libs.dji.sdk.v5.provided)
    // The widgets extend ConstraintLayout and AppCompat views: part of this module's API.
    api(libs.androidx.appcompat)
    api(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.legacy.support)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.rxkotlin3)
    implementation(libs.rxandroid3)
    // RTK guidance animations.
    implementation(libs.lottie)
    // Obstacle-avoidance settings (TabLayout).
    implementation(libs.material)
    // Settings list cells: IconicsImageView with Google Material icons ("gmd-close"). Artifacts only, as DJI's
    // `@aar` deps: the 3.x typefaces would otherwise pull in iconics-core 3.x.
    implementation(libs.iconics.core) { isTransitive = false }
    implementation(libs.iconics.views) { isTransitive = false }
    implementation(libs.iconics.google.material) { isTransitive = false }
    implementation(libs.iconics.community.material) { isTransitive = false }
    implementation(libs.iconics.ionicons) { isTransitive = false }
}
