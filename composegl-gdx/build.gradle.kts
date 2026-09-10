plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
}

description = "The LibGDX backend: renderer, input translation, fonts, clipboard, keyboard."

dependencies {
    api(project(":composegl-ui"))
    api(libs.gdx)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
