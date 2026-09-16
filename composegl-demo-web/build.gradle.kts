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
/**
 * The toolkit's list of every style name a widget asks for, copied out of composegl-ui's tests.
 *
 * `ShowcaseSkinsTest` holds the demo's look to it. Copied rather than depended on: it is test code
 * in another module, and a copy made by the build cannot drift from the original.
 */
val toolkitStyleNames = tasks.register<Sync>("toolkitStyleNames") {
    description = "Copies composegl-ui's list of style names for the demo's skin test."
    from(rootProject.layout.projectDirectory.dir("composegl-ui/src/commonTest/kotlin")) {
        include("dev/wildware/composegl/ui/skin/StyleNames.kt")
    }
    into(layout.buildDirectory.dir("generated/style-names"))
}

/** The demo's skin files, embedded as Kotlin source so the browser needs nothing loaded to wear them. */
val embeddedSkins = listOf(
    Triple("embedShowcaseLook", "showcase.json", "SHOWCASE_LOOK_JSON"),
    Triple("embedTourExtras", "extras.json", "TOUR_EXTRAS_JSON"),
    Triple("embedHighContrastTourExtras", "high-contrast-extras.json", "HIGH_CONTRAST_TOUR_EXTRAS_JSON"),
).map { (task, file, property) ->
    tasks.register<EmbedTextAsSource>(task) {
        description = "Turns the demo's $file into a Kotlin source file."
        group = "build"
        source.set(layout.projectDirectory.file("src/commonMain/skins/$file"))
        packageName.set("dev.wildware.composegl.demo.web")
        propertyName.set(property)
        // A directory each: two tasks writing into one would each count the other's file as stale.
        outputDirectory.set(layout.buildDirectory.dir("generated/skins/$task"))
    }
}

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

        commonMain {
            embeddedSkins.forEach { kotlin.srcDir(it) }
        }

        commonMain.dependencies {
            implementation(project(":composegl-ui"))
            implementation(project(":composegl-effects"))
            implementation(project(":composegl-game"))
            implementation(project(":composegl-debug"))
        }

        jvmTest {
            kotlin.srcDir(toolkitStyleNames)
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
