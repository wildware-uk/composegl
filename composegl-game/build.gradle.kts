plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

description = "The in-play widgets: bars, reticle, hotbar, cooldowns, minimap, damage numbers, particles. Public API only."

kotlin {
    jvm()
    linuxX64()

    // The same targets as composegl-ui, so a game that can draw the toolkit somewhere can draw its
    // health bar there too. iOS compiles here on Linux, as it does for the toolkit.
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
 * The point of this module, checked rather than promised.
 *
 * The game widgets are an opt-in layer on the toolkit, built from the same public API a game's own
 * widget is. A dependency on anything but the toolkit would mean they are not — and `internal` in
 * composegl-ui is already out of reach from here, so the compiler checks the other half.
 */
val confinement = tasks.register<DependencyConfinementCheck>("checkDependencyConfinement") {
    description = "Fails if composegl-game grows anything the toolkit itself does not have."
    group = "verification"
    reason.set(
        "The game widgets are written against the same public API as anybody else's. If a health " +
            "bar needs something a user cannot have, the API is wrong and this is where it shows. " +
            "composegl-game depends on composegl-ui alone, and never on composegl-debug.",
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
            "dev.wildware.composegl:composegl-game",
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
