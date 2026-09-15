import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

description = "The showcase in a browser tab: WebAssembly, drawn with WebGL. What GitHub Pages serves."

/**
 * The browser showcase: a tour of the toolkit anybody can click round, published to GitHub Pages.
 *
 * The screens are common code, so their logic is tested on the JVM with `uiTest` in a second. The
 * page — fonts, pictures, the canvas and the loop — is `wasmJsMain`, and its tests load the whole
 * showcase in headless Chromium and drive it with real DOM events.
 *
 * `./gradlew :composegl-demo-web:wasmJsBrowserDevelopmentRun` opens it on a local server, and
 * `:composegl-demo-web:wasmJsBrowserDistribution` writes the folder the Pages workflow publishes —
 * an `index.html`, the `.wasm`, the script that loads it and the fonts.
 */
kotlin {
    jvm()

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "composegl-demo-web.js" }
        }
        binaries.executable()
    }

    sourceSets {
        // Talking to the page is wasm only; the marker does not exist on the JVM.
        matching { it.name.startsWith("wasmJs") }.configureEach { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }

        commonMain.dependencies {
            implementation(project(":composegl-ui"))
            implementation(project(":composegl-effects"))
        }

        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }

        wasmJsMain {
            dependencies {
                implementation(project(":composegl-webgl"))
            }
            // The example's fonts and art; ship one copy, not three.
            resources.srcDir("../composegl-demo/src/main/resources")
        }

        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

/** Where the browser tests find the fonts and art: the Karma server serves them, see `karma.config.d`. */
tasks.withType<KotlinJsTest>().configureEach {
    environment("COMPOSEGL_WEB_RESOURCES", rootProject.layout.projectDirectory.dir("composegl-demo/src/main/resources").asFile.absolutePath)
    environment("COMPOSEGL_WEB_SCREENSHOTS", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}
