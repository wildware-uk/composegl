plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.maven.publish) apply false
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

/**
 * The modules that go to Maven Central, and nothing else.
 *
 * A list rather than "everything that is not a demo", because the cost of getting this wrong is
 * permanent: a version published to Central can never be deleted or replaced. A demo published by
 * accident is a demo somebody can depend on forever.
 */
val published = setOf(
    "composegl-ui",
    "composegl-effects",
    "composegl-gdx",
    "composegl-lwjgl3",
    "composegl-android",
    "composegl-testing",
)

configure(subprojects.filter { it.name in published }) {
    apply(plugin = "com.vanniktech.maven.publish")

    /**
     * What Central insists on before it will take a release: a name, a description, a home, a
     * licence, a human, and where the source is.
     *
     * Signing and the upload itself are configured by properties rather than here, so that the key
     * and the token live in CI's secrets and never in the repository. Without them this still
     * builds — `publishToMavenLocal` works on any machine — it just cannot publish.
     */
    afterEvaluate {
        // Read out here: inside the pom block, `name` and `description` are the POM's own.
        val moduleName = name
        val moduleDescription = description
            ?: error("$moduleName has no description, and Central requires one")

        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = false)
            if (project.hasProperty("signingInMemoryKey")) signAllPublications()

            coordinates(group.toString(), name, version.toString())

            pom {
                name.set(moduleName)
                description.set(moduleDescription)
                url.set("https://github.com/wildware-uk/composegl")
                inceptionYear.set("2026")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("shaun-wild")
                        name.set("Shaun Wild")
                        url.set("https://github.com/shaun-wild")
                    }
                }

                scm {
                    url.set("https://github.com/wildware-uk/composegl")
                    connection.set("scm:git:git://github.com/wildware-uk/composegl.git")
                    developerConnection.set("scm:git:ssh://git@github.com/wildware-uk/composegl.git")
                }
            }
        }
    }
}

/** Every module's tests are JUnit 5, and a test that prints says why it printed. */
fun Project.tests() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { showStandardStreams = true }
    }
}
