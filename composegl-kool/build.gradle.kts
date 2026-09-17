plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // `ComposeGlScene` takes the screen as a composable.
    alias(libs.plugins.kotlin.compose)
}

description = "The Kool frontend: the shared renderer drawn inside a Kool frame, with Kool's state handed back, Kool's textures and Kool's pointer."

/**
 * The desktop and Android: the two places Kool 0.19.0 draws with OpenGL.
 *
 * Everything but the GL binding and the glyphs is shared, in `jvmAndAndroidMain`: the scene, the
 * canvas, the state hand-back, the textures and the pointer are Kool's common API on both. On the
 * desktop Kool's OpenGL is LWJGL on the context Kool made current, so the binding and the glyphs are
 * composegl-lwjgl3's. On Android it is OpenGL ES 3 on Kool's `GLSurfaceView`, so the binding is
 * `android.opengl` and the glyphs are Android's own text drawing.
 *
 * Not the browser: Kool 0.19.0 publishes no WebAssembly build (Udea issue #222), only Kotlin/JS,
 * and the toolkit is WebAssembly in the browser.
 */
kotlin {
    jvm()

    // `ContextGl` is one expected object with an actual on each platform: the binding the shared code
    // draws through. Kotlin still calls expected classes beta and warns, and warnings fail this build.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    android {
        namespace = "dev.wildware.composegl.kool"
        compileSdk = 36
        // Kool 0.19.0's own floor.
        minSdk = 26
        // Kool's OpenGL ES exists only on a device, so that is where the tests run.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndAndroid") {
                withJvm()
                // AGP's multiplatform library target, which `withAndroidTarget()` does not match.
                withCompilations { it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm }
            }
        }
    }

    sourceSets {
        val jvmAndAndroidMain by getting {
            dependencies {
                api(project(":composegl-ui"))
                // The renderer. This module says where it draws inside Kool's frame, and hands Kool its state back.
                api(project(":composegl-render"))
                // A plain dependency. Kool publishes no Gradle plugin a library would need.
                api(libs.kool.core)
            }
        }

        jvmMain.dependencies {
            // The binding for Kool's desktop GL context, and stb_truetype glyphs.
            api(project(":composegl-lwjgl3"))
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

        getByName("androidDeviceTest").dependencies {
            implementation(project(":composegl-testing"))
            implementation(project(":composegl-effects"))
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.junit)
        }
        // The raw OpenGL frontend's goldens and the font they were drawn with, read on the device.
        getByName("androidDeviceTest").resources.srcDir("../composegl-lwjgl3/src/test/resources")
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
    classDirectories.from(layout.buildDirectory.dir("classes/kotlin/jvm/main"), layout.buildDirectory.dir("classes/kotlin/android/main"))
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
    dependsOn(tasks.named("jvmMainClasses"), tasks.named("compileAndroidMain"))
}

tasks.named("check") { dependsOn(noRendererOfItsOwn) }

// The renderer lives in composegl-render. Shaders and draw calls here would be a second one.
confineRenderer("ContextGl.kt")
