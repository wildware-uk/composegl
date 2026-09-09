plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
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
