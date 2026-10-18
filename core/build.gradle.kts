plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures` // TestData + BlenderDb, shared with adapter-fake's pipeline test
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Keep this list short: core runs on Android 6+ and on the JVM, beside the DJI SDK's own libraries.
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testFixturesImplementation(kotlin("test"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Integration tests talk to a local Flight Blender; they skip themselves when it is down.
    systemProperty("blender.url", System.getenv("BLENDER_URL") ?: "http://localhost:8000")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
