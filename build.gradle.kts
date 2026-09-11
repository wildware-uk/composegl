plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
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

    // The same settings for both shapes of Kotlin module. A multiplatform module compiles the
    // same source for a JVM and for something that is not a JVM, so the JVM-only flags are set on
    // the JVM target rather than on everything.
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                allWarningsAsErrors.set(true)
                freeCompilerArgs.add("-jvm-default=no-compatibility")
            }
        }
        tests()
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
            jvmToolchain(21)
            compilerOptions { allWarningsAsErrors.set(true) }
            targets.withType<org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget>().configureEach {
                compilerOptions { freeCompilerArgs.add("-jvm-default=no-compatibility") }
            }
        }
        tests()
    }
}

/** Every module's tests are JUnit 5, and a test that prints says why it printed. */
fun Project.tests() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { showStandardStreams = true }
    }
}
