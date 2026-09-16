plugins {
    alias(libs.plugins.kotlin.jvm)
    // The window's own interface is composed, and so are the test fixtures.
    alias(libs.plugins.kotlin.compose)
}

description = "The live @Preview window: every preview in a module on the raw OpenGL backend, " +
    "recompiled through Gradle and reloaded when a source file is saved. Development only."

dependencies {
    api(project(":composegl-ui"))
    // The window, the fonts and the GL context. Nothing depends on this module, so a game's build
    // pulls it in for `previewLive` alone and a shipped game carries none of it.
    api(project(":composegl-lwjgl3"))
    compileOnly(libs.jspecify)
    implementation(libs.gradle.tooling.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * Tiny modules of previews, compiled into folders of their own and never put on the test classpath.
 *
 * A reload test needs two builds of the same class: that is the whole thing being tested. Each
 * fixture is one build. `v1` and `v2` are the same previews with different text, `v3` has one of
 * them deleted. The tests copy a fixture's output into a folder standing in for a module's
 * `build/classes`, the way a compile overwrites it, and load it in a class loader of its own.
 */
val fixtures = listOf("fixtureV1", "fixtureV2", "fixtureV3")
fixtures.forEach { name ->
    val set = sourceSets.create(name)
    dependencies.add(set.implementationConfigurationName, project(":composegl-ui"))
}

tasks.test {
    // The real-GL test draws text, and the example's font is the one already in the repository.
    systemProperty("composegl.preview.font", rootProject.file("composegl-demo/src/main/resources/fonts/DejaVuSans.ttf").path)
    fixtures.forEach { name ->
        val output = sourceSets[name].output
        dependsOn(output)
        inputs.files(output).withPropertyName(name)
        val classes = output.classesDirs.files.map { it.path }
        val resources = listOfNotNull(output.resourcesDir?.path)
        systemProperty("composegl.preview.$name.classes", classes.joinToString(File.pathSeparator))
        systemProperty("composegl.preview.$name.resources", resources.joinToString(File.pathSeparator))
    }
}

/**
 * The end-to-end test: a real Gradle build of a small sample project, driven through the Tooling
 * API, edited on disk and recompiled, and the previews reloaded from what it wrote.
 *
 * Not part of `check`. It starts a Gradle daemon of its own and takes a minute, so it runs when asked:
 * `./gradlew :composegl-preview:integrationTest`. It is compiled on every build, so it cannot rot.
 */
val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
dependencies {
    add(integrationTest.implementationConfigurationName, libs.junit.jupiter)
    add(integrationTest.runtimeOnlyConfigurationName, libs.junit.platform.launcher)
}

tasks.register<Test>("integrationTest") {
    description = "Edits a sample Gradle project, compiles it through the Tooling API and reloads its previews."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)

    // What the sample project compiles its previews against: composegl-ui and the Compose runtime,
    // as files, so the sample needs no access to this build.
    val uiClasspath = configurations[integrationTest.runtimeClasspathConfigurationName]
    inputs.files(uiClasspath).withPropertyName("uiClasspath")
    val gradleHome = gradle.gradleHomeDir?.path.orEmpty()
    val kotlinVersion = libs.versions.kotlin.get()
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dcomposegl.preview.it.classpath=${uiClasspath.asPath}",
                "-Dcomposegl.preview.it.gradleHome=$gradleHome",
                "-Dcomposegl.preview.it.kotlin=$kotlinVersion",
            )
        },
    )
}

// Compiled with everything else, run only when asked.
tasks.named("assemble") { dependsOn(integrationTest.classesTaskName) }
