plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
}

description = "LibGDX adapter: FBO, GL state firewall, blit, input bridge. gdx core only — no backend, so Android can reuse it."

dependencies {
    api(project(":composegl-core"))
    api(libs.gdx)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.compose.material3)
    testRuntimeOnly(libs.junit.platform.launcher)
    // The input bridge is tested against a real raster surface, so it needs Skia.
    testRuntimeOnly(project.extra["skikoRuntime"] as String)
    // Headless adapter tests (keycode table, modifiers, HDPI scaling) need a LibGDX application.
    testImplementation(libs.gdx.backend.lwjgl3)
    testRuntimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
}

/**
 * The adapter is allowed to know about LibGDX, and nothing else. No AWT, because the same adapter
 * should serve Android later; no LWJGL, because it must not be tied to one backend.
 */
val checkNoForbiddenReferences by tasks.registering(BytecodeReferenceCheck::class) {
    description = "Fails if composegl-libgdx references AWT, Swing or LWJGL."
    group = "verification"
    classDirectories.from(sourceSets.main.get().output.classesDirs)
    forbiddenPackages.set(listOf("java/awt", "javax/swing", "org/lwjgl"))
    reason.set(
        "composegl-libgdx depends on gdx core only. A backend-specific or AWT reference here " +
            "would stop the same adapter serving Android.",
    )
    dependsOn(tasks.named("classes"))
}

tasks.named("check") {
    dependsOn(checkNoForbiddenReferences)
}

/**
 * Tests that need a real GL context and a real driver. They boot an LWJGL3 window, so they are
 * skipped unless there is a display — on CI that is Xvfb with Mesa's llvmpipe. `./gradlew build`
 * does not run them; `./gradlew integrationTest` does.
 */
val integrationTest: SourceSet by sourceSets.creating

configurations[integrationTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(integrationTest.implementationConfigurationName, sourceSets.main.get().output)
    add(integrationTest.implementationConfigurationName, libs.compose.material3)
    add(integrationTest.runtimeOnlyConfigurationName, project.extra["skikoRuntime"] as String)
}

tasks.register<Test>("integrationTest") {
    description = "Runs the GL integration tests. Needs a display; use xvfb-run on a headless box."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
    // Each test owns the process's one GL window, so they cannot overlap.
    maxParallelForks = 1
    forkEvery = 1
    onlyIf {
        val hasDisplay = !System.getenv("DISPLAY").isNullOrEmpty()
        if (!hasDisplay) logger.lifecycle("Skipping integrationTest: no DISPLAY.")
        hasDisplay
    }
}
