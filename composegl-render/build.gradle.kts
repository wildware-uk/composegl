plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

description = "The one renderer every backend draws with: canvas, batch, layers, glyph atlas and the OpenGL device."

/**
 * Multiplatform, with every line in `commonMain`, on exactly `composegl-ui`'s targets.
 *
 * The renderer is the part of this library that draws, and every backend now shares it. It talks
 * to the GPU through [dev.wildware.composegl.render.GpuDevice], and the one device there is today
 * talks to OpenGL through [dev.wildware.composegl.render.gl.Gl], an interface each backend
 * implements in a few hundred lines of one-liners. Nothing here names an engine.
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
 * The renderer may depend on the toolkit and on nothing else: the same list `composegl-ui` keeps,
 * plus this module. An engine arriving here would arrive in every backend at once.
 */
val confinement = tasks.register<DependencyConfinementCheck>("checkDependencyConfinement") {
    description = "Fails if composegl-render's runtime classpath grows anything but composegl-ui's own."
    group = "verification"
    reason.set(
        "composegl-render is shared by every backend, on Android, iOS and the browser as well as " +
            "the desktop. It is allowed exactly what composegl-ui is allowed. No engine.",
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
            "dev.wildware.composegl:composegl-ui",
            "dev.wildware.composegl:composegl-render",
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

/** No engine, no window toolkit, no GL binding: the renderer calls its own `Gl` interface. */
val noEngineTypes = tasks.register<BytecodeReferenceCheck>("checkNoEngineTypes") {
    description = "Fails if composegl-render names an engine, a GL binding or a window toolkit."
    group = "verification"
    classDirectories.from(layout.buildDirectory.dir("classes/kotlin/jvm/main"))
    forbiddenPackages.set(
        listOf(
            "org/lwjgl",
            "com/badlogic",
            "korlibs",
            "org/khronos",
            "java/awt",
            "javax/swing",
            "org/jetbrains/skiko",
            "androidx/compose/ui",
        ),
    )
    reason.set(
        "The renderer reaches the GPU through GpuDevice and OpenGL through its own Gl interface, " +
            "which each backend implements. A binding named here would tie every backend to it.",
    )
    dependsOn(tasks.named("jvmMainClasses"))
}

/**
 * The device seam, held: nothing above it may know OpenGL exists.
 *
 * A Vulkan, Metal or DirectX device is only possible later if the canvas, the batch, the layers and
 * the fonts never reach into `render/gl` now.
 */
val deviceNeutral = tasks.register<BytecodeReferenceCheck>("checkDeviceNeutral") {
    description = "Fails if the device-neutral renderer names the OpenGL device."
    group = "verification"
    classDirectories.from(layout.buildDirectory.dir("classes/kotlin/jvm/main"))
    includeClasses.set(listOf("dev/wildware/composegl/render/"))
    excludeClasses.set(listOf("dev/wildware/composegl/render/gl/"))
    forbiddenPackages.set(listOf("dev/wildware/composegl/render/gl"))
    reason.set(
        "Everything above GpuDevice is shared with devices that are not OpenGL. Put GL knowledge in " +
            "render/gl, behind the device.",
    )
    dependsOn(tasks.named("jvmMainClasses"))
}

tasks.named("check") { dependsOn(confinement, noEngineTypes, deviceNeutral) }
