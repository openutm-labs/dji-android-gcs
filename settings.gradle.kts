pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dji-flight"

// DJI-free modules: build and test on any JVM.
include(":core", ":adapter-fake", ":provisioning-tool")

// Android modules only when an SDK is configured (ANDROID_HOME or sdk.dir in local.properties),
// so `./gradlew :core:test` works on a machine or CI runner without Android tooling.
val localSdkDir = java.util.Properties().apply {
    val f = file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}.getProperty("sdk.dir")
val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: localSdkDir
if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":app-common", ":adapter-dji-v4", ":app-v4", ":adapter-dji-v5", ":uxsdk-v5", ":app-v5")
} else {
    logger.lifecycle("No Android SDK found: building only :core and :adapter-fake")
}
