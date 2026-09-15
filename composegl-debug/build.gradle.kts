plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

description = "Debug tooling for development builds: the inspector and the layout, focus, redraw, " +
    "overdraw, text and frame budget overlays. Public API only."

/**
 * The same targets as `composegl-ui`, so a debug build of a game on any of them can take it.
 *
 * Its own module because debugging tools belong in a development build and not in a shipped one: a
 * game adds this as a debug-only dependency, or leaves it out of its release build, and the release
 * carries none of it.
 */
kotlin {
    jvm()
    linuxX64()
    iosArm64()
    iosSimulatorArm64()

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":composegl-ui"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

/**
 * The module rule, checked rather than promised: the toolkit and what it brings, and nothing else.
 *
 * Not even the other add-on, `composegl-game`. A debug tool that needs something the toolkit does not
 * offer publicly means the toolkit grows a public API for it, which is what keeps a game's own tools
 * on the same footing as these.
 */
val confinement = tasks.register<DependencyConfinementCheck>("checkDependencyConfinement") {
    description = "Fails if composegl-debug grows anything the toolkit itself does not have."
    group = "verification"
    reason.set(
        "The debug tools are written against the same public API as anybody else's. If one needs " +
            "something a game cannot have, the toolkit grows a public API; it does not get a dependency.",
    )
    allowed.set(
        listOf(
            "androidx.annotation:annotation",
            "androidx.annotation:annotation-jvm",
            "androidx.collection:collection",
            "androidx.collection:collection-jvm",
            "androidx.compose.runtime:runtime",
            "androidx.compose.runtime:runtime-annotation",
            "androidx.compose.runtime:runtime-annotation-jvm",
            "androidx.compose.runtime:runtime-desktop",
            "org.jetbrains:annotations",
            "org.jetbrains.compose.runtime:runtime",
            "org.jetbrains.compose.runtime:runtime-desktop",
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlinx:kotlinx-coroutines-bom",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
            "dev.wildware.composegl:composegl-debug",
            "dev.wildware.composegl:composegl-ui",
        ),
    )
    resolved.set(
        provider {
            DependencyConfinementCheck.modulesOf(
                configurations.named("jvmRuntimeClasspath").get().incoming.resolutionResult.root,
            )
        },
    )
}

tasks.named("check") { dependsOn(confinement) }
