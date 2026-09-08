plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
}

description = "Compose scene, frame driving, input and platform services. No engine, no GL calls, no AWT."

dependencies {
    api(libs.compose.runtime)
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.skiko)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.compose.material3)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Raster-target tests draw with real Skia, so they need the native library.
    testRuntimeOnly(project.extra["skikoRuntime"] as String)
}
