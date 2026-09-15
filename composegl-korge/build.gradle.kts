plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // `ComposeGlView` takes the screen as a composable.
    alias(libs.plugins.kotlin.compose)
}

description = "The KorGE backend: a KmlGl binding for the shared renderer, KorGE's TrueType glyphs, clipboard and cursor, inside a KorGE game's own stage."

/**
 * Multiplatform in shape, JVM only for now.
 *
 * KorGE itself is multiplatform, and nothing drawn here names a JVM class except where a platform
 * has to be asked something — so the browser and the phones are a target away rather than a
 * rewrite. They are left out until they are built and tested, not merely compiled: see
 * docs/superpowers/specs/2026-09-15-korge-backend.md for what is in the way.
 */
kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            api(project(":composegl-ui"))
            // The renderer. This module is its binding: KorGE's OpenGL, KorGE's glyphs, and the stage.
            api(project(":composegl-render"))
            // A plain dependency. The KorGE Gradle plugin is not applied: it wants to own the whole
            // build, and a library only needs the engine on its classpath.
            api(libs.korge)
        }

        jvmTest.dependencies {
            implementation(project(":composegl-testing"))
            // The shipped effects, drawn through this backend's shaders.
            implementation(project(":composegl-effects"))
            // The debug overlays, drawn through this backend too.
            implementation(project(":composegl-debug"))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

/**
 * Where KorGE draws when a test asks it to.
 *
 * KorGE's desktop window is AWT with an OpenGL context reached through reflection, which Java 17
 * and later refuse unless these packages are opened. With `KORGE_HEADLESS=true` it makes an
 * offscreen window instead and still renders with a real driver — llvmpipe on a machine with no
 * GPU — so the pixel tests run with no display at all. Under Xvfb they run the ordinary way.
 */
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=java.desktop/sun.java2d.opengl=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
    )
    System.getenv("KORGE_HEADLESS")?.let { environment("KORGE_HEADLESS", it) }
    // Where a test that renders a whole screen leaves the picture, for a person to look at.
    System.getenv("COMPOSEGL_KORGE_SHOTS")?.let { environment("COMPOSEGL_KORGE_SHOTS", it) }
}

/**
 * None of the renderer this module used to carry: no KorGE shader program and no vertex layout of its
 * own. It draws through composegl-render, over KorGE's OpenGL.
 */
val noRendererOfItsOwn = tasks.register<BytecodeReferenceCheck>("checkNoRendererOfItsOwn") {
    description = "Fails if the KorGE backend builds KorGE shader programs or vertex data of its own."
    group = "verification"
    classDirectories.from(layout.buildDirectory.dir("classes/kotlin/jvm/main"))
    // Not all of korlibs/graphics/shader: KorgeRenderTargetView draws through KorGE's own batch, whose
    // default program it names. What a renderer of its own would need is attributes and a layout.
    forbiddenPackages.set(
        listOf(
            "korlibs/graphics/shader/Attribute",
            "korlibs/graphics/shader/Varying",
            "korlibs/graphics/shader/VertexLayout",
            "korlibs/graphics/AGVertexData",
            "korlibs/graphics/AGVertexArrayObject",
        ),
    )
    reason.set(
        "The interface is drawn by composegl-render through KorGE's KmlGl. A KorGE Program or vertex " +
            "layout here would be a second renderer.",
    )
    dependsOn(tasks.named("jvmMainClasses"))
}

tasks.named("check") { dependsOn(noRendererOfItsOwn) }

// The renderer lives in composegl-render. Shaders and draw calls here would be a second one.
confineRenderer("KorgeKmlGl.kt")
