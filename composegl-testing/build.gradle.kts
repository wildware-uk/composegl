plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

description = "Shared test material: the scenes every backend must draw the same, and golden comparison."

/**
 * Multiplatform because one of the backends is not on a JVM.
 *
 * The scenes and the arithmetic that says whether two pictures agree are common, so the browser's
 * WebGL backend draws exactly the list the two desktop backends draw and is judged by exactly the
 * same rule. Reading and writing PNG files is the JVM half; the browser reads its goldens its own
 * way.
 */
kotlin {
    jvm()

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":composegl-ui"))

            // The effects the toolkit ships, so every backend draws the same blur, outline, grade
            // and dissolve and the goldens can be compared side by side.
            api(project(":composegl-effects"))
        }
    }
}
