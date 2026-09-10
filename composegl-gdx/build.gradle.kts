plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
}

description = "The LibGDX backend: renderer, input translation, fonts, clipboard, keyboard."

dependencies {
    api(project(":composegl-ui"))
    api(libs.gdx)

    // FreeType is how a game turns a .ttf into glyphs. It ships natives for desktop, Android and
    // iOS, which is a large part of why LibGDX is still the reference backend.
    api(libs.gdx.freetype)

    // Enough of LibGDX to run without a window: the tests that need real glyph shapes but no GPU.
    testImplementation(libs.gdx.backend.headless)

    // And a real window with a real GL context, for the handful of tests that need one. They skip
    // themselves when there is no display; CI gives them one with Xvfb.
    testImplementation(libs.gdx.backend.lwjgl3)
    testRuntimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
    testRuntimeOnly(variantOf(libs.gdx.freetype.platform) { classifier("natives-desktop") })

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
