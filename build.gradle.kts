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
 * The version: what the release button asked for, and failing that, the git tag.
 *
 * `-PcomposeglVersion=0.2.0` is how the Release workflow names a build. It needs to say, for two
 * reasons: a snapshot is published off no tag at all, and a release is compiled and signed
 * *before* it is tagged, so that a commit which cannot be built leaves no tag behind. The workflow
 * makes the tag out of the same number it passed in and then checks the two agree, so the old
 * promise still holds — a published artifact and its tag cannot disagree.
 *
 * With no property the tag decides, and releasing by hand is still `git tag v0.2.0 && git push
 * --tags`.
 *
 * Off a tag you get a snapshot of the *next* minor rather than the last one. `0.1.0-SNAPSHOT`
 * after 0.1.0 has been released is a mutable version wearing the name of an immutable one that
 * already exists on Central, and whoever depends on it gets whichever they happened to fetch.
 */
val projectVersion: String = run {
    val asked = providers.gradleProperty("composeglVersion").orNull?.trim()
    if (!asked.isNullOrEmpty()) return@run asked

    val described = providers.exec {
        commandLine("git", "describe", "--tags", "--always", "--dirty")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()

    /** 0.1.0 becomes 0.2.0-SNAPSHOT: what the next release off this commit would most likely be. */
    fun nextMinorSnapshot(release: String): String {
        val parts = release.split(".")
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return "0.1.0-SNAPSHOT"
        return "${parts[0]}.${minor + 1}.0-SNAPSHOT"
    }

    when {
        described.isEmpty() -> "0.1.0-SNAPSHOT"
        Regex("^v\\d+\\.\\d+\\.\\d+$").matches(described) -> described.removePrefix("v")
        described.startsWith("v") -> nextMinorSnapshot(described.removePrefix("v").substringBefore("-"))
        else -> "0.1.0-SNAPSHOT"
    }
}

subprojects {
    group = "dev.wildware.composegl"
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
 * The browser build's tools come from the repositories `settings.gradle.kts` declares.
 *
 * Left alone, the Kotlin plugin adds a repository of its own for each of them, and this build
 * refuses repositories added by a project — so the first wasm task fails before it starts. A null
 * base URL tells the plugin the repository is already there.
 */
allprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec>().downloadBaseUrl.set(null as String?)
    }
    plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec>().downloadBaseUrl.set(null as String?)
    }
    plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec>().downloadBaseUrl.set(null as String?)
    }
    plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec>().downloadBaseUrl.set(null as String?)
    }

    // Every browser test runs in Chromium. CI has Chrome installed and says where; a machine with
    // Playwright has a Chromium of its own, and naming it here saves everybody an environment variable.
    tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
        if (System.getenv("CHROME_BIN") == null) {
            playwrightChromium()?.let { environment("CHROME_BIN", it) }
        }
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
    "composegl-render",
    "composegl-effects",
    "composegl-game",
    "composegl-debug",
    "composegl-gdx",
    "composegl-korge",
    "composegl-kool",
    "composegl-lwjgl3",
    "composegl-webgl",
    "composegl-android",
    "composegl-robovm",
    "composegl-testing",
    // Development only: the live @Preview window. A game's build runs it through `previewLive` and
    // never ships it.
    "composegl-preview",
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

/** The newest Chromium Playwright has downloaded, if it has. */
fun playwrightChromium(): String? {
    val cache = File(System.getProperty("user.home"), ".cache/ms-playwright")
    return cache.listFiles { file -> file.name.startsWith("chromium-") }
        ?.sortedByDescending { it.name.removePrefix("chromium-").toIntOrNull() ?: 0 }
        ?.firstNotNullOfOrNull { dir ->
            listOf("chrome-linux64/chrome", "chrome-linux/chrome").map { File(dir, it) }.firstOrNull { it.canExecute() }
        }
        ?.absolutePath
}

/** Every module's tests are JUnit 5, and a test that prints says why it printed. */
fun Project.tests() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { showStandardStreams = true }
    }
}
