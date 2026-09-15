import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // `BrowserUi` takes the screen as a composable.
    alias(libs.plugins.kotlin.compose)
}

description = "The browser backend: WebGL, the page's own fonts, and the DOM's input. WebAssembly."

/**
 * WebAssembly only, and in a browser only.
 *
 * Everything the toolkit needs from a platform — a canvas, fonts, a clipboard, a keyboard, a
 * pointer, a pad — the page already has, so this module is a translation layer and nothing else.
 * It shares no code with either desktop backend, for the reason those two share none with each
 * other: it has to reach the same pictures by its own route, or the goldens prove nothing.
 */
kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        // Talking to the page is what this module is, so the interop opt-in is taken once, here.
        all { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }

        wasmJsMain.dependencies {
            api(project(":composegl-ui"))
            api(libs.kotlinx.browser)
        }

        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":composegl-testing"))
            implementation(libs.coroutines.test)
        }
    }
}

/**
 * Where the tests find their goldens and the font they draw with, and where a picture they send
 * back is written. A browser cannot open a file, so the Karma server does it for them — see
 * `karma.config.d/composegl.js`, which reads both paths from here.
 */
val testResources = layout.projectDirectory.dir("src/wasmJsTest/resources")
val screenshots = layout.buildDirectory.dir("screenshots")
val desktopGoldens = rootProject.layout.projectDirectory.dir("composegl-lwjgl3/src/test/resources/goldens")

tasks.withType<KotlinJsTest>().configureEach {
    environment("COMPOSEGL_WEB_RESOURCES", testResources.asFile.absolutePath)
    environment("COMPOSEGL_WEB_SCREENSHOTS", screenshots.get().asFile.absolutePath)
    // The raw OpenGL backend's goldens, which the shapes drawn here are held to as well.
    environment("COMPOSEGL_DESKTOP_GOLDENS", desktopGoldens.asFile.absolutePath)
    System.getenv("COMPOSEGL_UPDATE_GOLDENS")?.let { environment("COMPOSEGL_UPDATE_GOLDENS", it) }
    inputs.dir(testResources)
    // Which Chromium runs them is decided once, for every browser test, in the root build file.
}
