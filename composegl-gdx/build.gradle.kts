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

    // Pads. A separate LibGDX project rather than part of the core, and the reason a game gets
    // hot-plugging and a name-to-layout database for free instead of parsing HID reports.
    api(libs.gdx.controllers)

    // Enough of LibGDX to run without a window: the tests that need real glyph shapes but no GPU.
    testImplementation(libs.gdx.backend.headless)

    // And a real window with a real GL context, for the handful of tests that need one. They skip
    // themselves when there is no display; CI gives them one with Xvfb.
    testImplementation(libs.gdx.backend.lwjgl3)
    testRuntimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
    testRuntimeOnly(variantOf(libs.gdx.freetype.platform) { classifier("natives-desktop") })

    testImplementation(project(":composegl-testing"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The same GL suite on a GL 3.2 core context, which is what a game on OpenGL ES 3 or desktop GL 3
// runs on. LibGDX asks for a core profile only on a Mac, so on Linux Mesa is told to hand out a
// forward-compatible core one; `Gl` checks it got one rather than passing on a compatibility context.
val testGl30 by tasks.registering(Test::class) {
    description = "Runs the tests on a GL 3.2 core context instead of GL 2."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("composegl.gl", "gl30")
    environment("MESA_GL_VERSION_OVERRIDE", "3.2FC")
}
