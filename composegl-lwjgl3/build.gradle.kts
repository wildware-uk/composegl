plugins {
    alias(libs.plugins.kotlin.jvm)
    // For `@Preview` functions: the preview renderer calls them, and its tests write some.
    alias(libs.plugins.kotlin.compose)
}

description = "The raw OpenGL backend: a GLFW window, an LWJGL binding for the shared renderer, and stb_truetype. No engine."

dependencies {
    api(project(":composegl-ui"))
    // The renderer. This module is its binding: LWJGL's OpenGL, stb_truetype glyphs, and a window.
    api(project(":composegl-render"))
    api(libs.lwjgl)
    compileOnly(libs.jspecify)
    api(libs.lwjgl.glfw)
    api(libs.lwjgl.opengl)
    api(libs.lwjgl.stb)

    // LWJGL is a thin Java front on C libraries, so whichever machine runs this needs the matching
    // natives on its classpath. Desktop only, deliberately: this backend exists to keep the
    // toolkit honest, and a game ships on the LibGDX one.
    listOf(
        "natives-linux",
        "natives-linux-arm64",
        "natives-macos",
        "natives-macos-arm64",
        "natives-windows",
    ).forEach { platform ->
        runtimeOnly(variantOf(libs.lwjgl) { classifier(platform) })
        runtimeOnly(variantOf(libs.lwjgl.glfw) { classifier(platform) })
        runtimeOnly(variantOf(libs.lwjgl.opengl) { classifier(platform) })
        runtimeOnly(variantOf(libs.lwjgl.stb) { classifier(platform) })
    }

    testImplementation(project(":composegl-testing"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * No engine types, and none of the renderer this module used to carry: it draws through
 * composegl-render, so stb's font packer — which baked a whole atlas of its own — has no business here.
 */
val noEngineTypes = tasks.register<BytecodeReferenceCheck>("checkNoEngineTypes") {
    description = "Fails if the raw OpenGL backend names LibGDX or a renderer of its own."
    group = "verification"
    classDirectories.from(layout.buildDirectory.dir("classes/kotlin/main"))
    forbiddenPackages.set(listOf("com/badlogic/gdx", "org/lwjgl/stb/STBTTPackContext"))
    reason.set(
        "This backend is the reference raw-OpenGL wrapper round the shared renderer. It binds GL, " +
            "rasterises glyphs and runs a window; it does not name an engine or pack an atlas itself.",
    )
    dependsOn(tasks.named("classes"))
}

tasks.named("check") { dependsOn(noEngineTypes) }

// The renderer lives in composegl-render. Shaders and draw calls here would be a second one.
confineRenderer("LwjglGl.kt")

// The same tests on a GL 3.2 core context. The window here asks for no version, so Mesa is told to
// hand out a forward-compatible core one: what a game that brings its own GL 3 context would give
// this backend. A core context draws nothing without a vertex array object bound.
val testGl30 by tasks.registering(Test::class) {
    description = "Runs the tests on a GL 3.2 core context (Mesa)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    environment("MESA_GL_VERSION_OVERRIDE", "3.2FC")
}
