plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // `ComposeGlScene` takes the screen as a composable.
    alias(libs.plugins.kotlin.compose)
}

description = "The Kool frontend: the shared renderer drawn inside a Kool frame, with Kool's state handed back, Kool's textures and Kool's pointer."

/**
 * Multiplatform in shape, JVM only for now.
 *
 * Kool itself is multiplatform. The browser and Android are left out until they are built and tested,
 * not merely compiled: that is Udea issue #222. On the JVM, Kool's OpenGL is LWJGL on the context Kool
 * made current, so the GL binding and the glyphs are composegl-lwjgl3's.
 */
kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            api(project(":composegl-ui"))
            // The renderer. This module says where it draws inside Kool's frame, and hands Kool its state back.
            api(project(":composegl-render"))
            // The binding for Kool's desktop GL context, and stb_truetype glyphs.
            api(project(":composegl-lwjgl3"))
            // A plain dependency. Kool publishes no Gradle plugin a library would need.
            api(libs.kool.core)
            // Kool 0.19.0 asks for LWJGL 3.3.6 and composegl-lwjgl3 for a later one. Every LWJGL module
            // moves together, natives included, or Kool's own ones would be left a version behind.
            api(project.dependencies.platform(libs.lwjgl.bom))
        }

        jvmTest.dependencies {
            implementation(project(":composegl-testing"))
            // The shipped effects, drawn in the shared scenes.
            implementation(project(":composegl-effects"))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.withType<Test>().configureEach {
    // Where a test that renders a whole screen leaves the picture, for a person to look at.
    System.getenv("COMPOSEGL_KOOL_SHOTS")?.let { environment("COMPOSEGL_KOOL_SHOTS", it) }
}

/**
 * None of Kool's renderer: no KSL shader, no mesh and no vertex layout. The interface is drawn by
 * composegl-render on Kool's context; a Kool shader or mesh here would be a second renderer.
 */
val noRendererOfItsOwn = tasks.register<BytecodeReferenceCheck>("checkNoRendererOfItsOwn") {
    description = "Fails if the Kool frontend builds Kool shaders, meshes or vertex data of its own."
    group = "verification"
    classDirectories.from(layout.buildDirectory.dir("classes/kotlin/jvm/main"))
    forbiddenPackages.set(
        listOf(
            "de/fabmax/kool/modules/ksl",
            "de/fabmax/kool/scene/Mesh",
            "de/fabmax/kool/scene/geometry",
            "de/fabmax/kool/pipeline/DrawShader",
            "de/fabmax/kool/pipeline/VertexLayout",
            "de/fabmax/kool/pipeline/Attribute",
        ),
    )
    reason.set(
        "The interface is drawn by composegl-render on Kool's OpenGL context. A Kool shader, mesh or " +
            "vertex layout here would be a second renderer.",
    )
    dependsOn(tasks.named("jvmMainClasses"))
}

tasks.named("check") { dependsOn(noRendererOfItsOwn) }

// The renderer lives in composegl-render. Shaders and draw calls here would be a second one.
confineRenderer("KoolGl.kt")
