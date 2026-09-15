plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
}

description = "The LibGDX backend: a Gdx.gl binding for the shared renderer, FreeType glyphs, input, clipboard, keyboard."

dependencies {
    api(project(":composegl-ui"))
    // The renderer. This module is its binding: Gdx.gl, FreeType glyphs, and LibGDX's input.
    api(project(":composegl-render"))
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
    // The debug overlays, drawn through this backend with a real GL context.
    testImplementation(project(":composegl-debug"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * None of the renderer this module used to carry: it draws through composegl-render, so LibGDX's
 * meshes, shader programs and pixmap packer have no business here.
 */
val noOwnRenderer = tasks.register<BytecodeReferenceCheck>("checkNoOwnRenderer") {
    description = "Fails if the LibGDX backend builds meshes, shaders or an atlas of its own."
    group = "verification"
    classDirectories.from(layout.buildDirectory.dir("classes/kotlin/main"))
    forbiddenPackages.set(
        listOf(
            "com/badlogic/gdx/graphics/Mesh",
            "com/badlogic/gdx/graphics/glutils/ShaderProgram",
            "com/badlogic/gdx/graphics/g2d/PixmapPacker",
        ),
    )
    reason.set(
        "This backend is a thin wrapper round the shared renderer. It binds Gdx.gl, rasterises glyphs " +
            "with FreeType and translates input; it does not batch, compile shaders or pack an atlas.",
    )
    dependsOn(tasks.named("classes"))
}

tasks.named("check") { dependsOn(noOwnRenderer) }

// The renderer lives in composegl-render. Shaders and draw calls here would be a second one.
confineRenderer("GdxGl.kt")

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
