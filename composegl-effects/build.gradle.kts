plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

description = "The effects the toolkit ships: blur, outline, colour grade, dissolve. Public API only."

kotlin {
    jvm()
    linuxX64()

    // The iPhone, and a simulator on an Apple Silicon Mac. They compile here, on Linux —
    // Kotlin/Native cross-compiles the library itself, and only linking a finished app needs a
    // Mac. So the promise this module makes about iOS is checked by the ordinary build rather
    // than taken on trust.
    //
    // No iosX64: the Compose runtime stopped publishing for the Intel simulator, and a target we
    // cannot resolve a dependency for is not a target.
    iosArm64()
    iosSimulatorArm64()

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
 * These effects exist to prove that the shader API is enough to write a blur with. A dependency on
 * anything but the toolkit itself would mean it is not — and would mean this module knows something
 * a user's own effect cannot know, which is exactly what the owner asked us not to build.
 */
val confinement = tasks.register<DependencyConfinementCheck>("checkDependencyConfinement") {
    description = "Fails if composegl-effects grows anything the toolkit itself does not have."
    group = "verification"
    reason.set(
        "The effects we ship must be written against the same public API as anybody else's. If a " +
            "blur needs something a user cannot have, the API is wrong and this is where it shows.",
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
            "uk.wildware.composegl:composegl-effects",
            "uk.wildware.composegl:composegl-ui",
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
