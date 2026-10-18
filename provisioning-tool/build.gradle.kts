plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.zxing.core)
}

application {
    mainClass.set("org.openutm.djiflight.provisioning.MainKt")
}

tasks.named<JavaExec>("run") {
    // Paths given on the command line are relative to where ./gradlew was started.
    workingDir = rootProject.projectDir
}
