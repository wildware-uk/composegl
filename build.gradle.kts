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

/**
 * The version comes from the git tag, so a release is `git tag v0.2.0 && git push --tags` and
 * nothing else. Off a tag you get a snapshot named after the last one, which is what you want
 * when you are testing a build against a real game.
 */
val projectVersion: String = run {
    val described = providers.exec {
        commandLine("git", "describe", "--tags", "--always", "--dirty")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()

    when {
        described.isEmpty() -> "0.1.0-SNAPSHOT"
        Regex("^v\\d+\\.\\d+\\.\\d+$").matches(described) -> described.removePrefix("v")
        described.startsWith("v") -> described.removePrefix("v").substringBefore("-") + "-SNAPSHOT"
        else -> "0.1.0-SNAPSHOT"
    }
}

subprojects {
    group = "uk.wildware.composegl"
    version = projectVersion

    extra["skikoTarget"] = skikoTarget
    extra["lwjglNatives"] = lwjglNatives
    extra["skikoRuntime"] = "org.jetbrains.skiko:skiko-awt-runtime-$skikoTarget:${rootProject.libs.versions.skiko.get()}"

    // Coverage on the modules that ship, so "how is the testing" has a number rather than an
    // opinion. Reported for the ordinary test task; the GL and no-AWT suites run in their own JVMs.
    if (name in setOf("composegl-core", "composegl-libgdx", "composegl-lwjgl3")) {
        apply(plugin = "jacoco")
        tasks.withType<Test>().configureEach {
            // noAwtTest re-runs the same tests on a stripped JVM; counting it would double-count.
            extensions.configure<JacocoTaskExtension> { isEnabled = name != "noAwtTest" }
        }
        tasks.register<JacocoReport>("coverage") {
            dependsOn(tasks.named("test"))
            // Whatever ran: the ordinary suite always, the GL suite too when there is a display.
            // Run `xvfb-run ./gradlew integrationTest coverage` for the honest number.
            executionData(fileTree(layout.buildDirectory.dir("jacoco")) { include("*.exec") })
            sourceDirectories.from(files("src/main/kotlin"))
            classDirectories.from(
                fileTree(layout.buildDirectory.dir("classes/kotlin/main")) {
                    // Generated Compose lambdas and the shim are not ours to cover.
                    exclude("**/ComposableSingletons*")
                },
            )
            reports { xml.required.set(true); html.required.set(true) }
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                allWarningsAsErrors.set(true)
                freeCompilerArgs.add("-jvm-default=no-compatibility")
            }
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging { showStandardStreams = true }
        }
    }
}
