plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/**
 * Skiko ships its native library as one artifact per host. Compose pulls in `skiko-awt`, which is
 * the JVM bindings only, so anything that actually renders needs the matching runtime artifact.
 * Spec §13 promises this exact coordinate in the "Skiko native library not found" message.
 */
val skikoTarget: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val cpu = if (arch == "aarch64" || arch.startsWith("arm")) "arm64" else "x64"
    when {
        os.contains("mac") || os.contains("darwin") -> "macos-$cpu"
        os.contains("win") -> "windows-$cpu"
        else -> "linux-$cpu"
    }
}

/** LWJGL uses its own classifier spelling for host natives. */
val lwjglNatives: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val arm = arch == "aarch64" || arch.startsWith("arm")
    when {
        os.contains("mac") || os.contains("darwin") -> if (arm) "natives-macos-arm64" else "natives-macos"
        os.contains("win") -> "natives-windows"
        else -> if (arm) "natives-linux-arm64" else "natives-linux"
    }
}

subprojects {
    group = "uk.wildware.composegl"
    version = "0.1.0-SNAPSHOT"

    extra["skikoTarget"] = skikoTarget
    extra["lwjglNatives"] = lwjglNatives
    extra["skikoRuntime"] = "org.jetbrains.skiko:skiko-awt-runtime-$skikoTarget:${rootProject.libs.versions.skiko.get()}"

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                allWarningsAsErrors.set(true)
                freeCompilerArgs.add("-Xjvm-default=all")
            }
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging { showStandardStreams = true }
        }
    }
}
