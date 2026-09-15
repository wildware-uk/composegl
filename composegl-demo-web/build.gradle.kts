plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

description = "The example in a browser tab: WebAssembly, drawn with WebGL. What GitHub Pages serves."

/**
 * A page and nothing else.
 *
 * `./gradlew :composegl-demo-web:wasmJsBrowserDevelopmentRun` opens it on a local server, and
 * `:composegl-demo-web:wasmJsBrowserDistribution` writes the folder the Pages workflow publishes —
 * an `index.html`, the `.wasm`, the script that loads it and the font.
 */
kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "composegl-demo-web.js" }
        }
        binaries.executable()
    }

    sourceSets {
        all { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }

        wasmJsMain {
            dependencies {
                implementation(project(":composegl-webgl"))
            }
            // The example's font; ship one copy, not three.
            resources.srcDir("../composegl-demo/src/main/resources")
        }
    }
}
