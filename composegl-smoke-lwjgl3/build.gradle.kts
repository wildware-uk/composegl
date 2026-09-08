plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

application {
    mainClass.set("composegl.smoke.MainKt")
}

description = "Raw LWJGL3 host, no LibGDX. Proves the core/adapter seam and hosts the GL integration tests."

dependencies {
    implementation(project(":composegl-core"))
    implementation(libs.compose.material3)
    implementation(platform("org.lwjgl:lwjgl-bom:${libs.versions.lwjgl.get()}"))
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.glfw)
    implementation(libs.lwjgl.opengl)
    val natives = project.extra["lwjglNatives"] as String
    runtimeOnly(variantOf(libs.lwjgl) { classifier(natives) })
    runtimeOnly(variantOf(libs.lwjgl.glfw) { classifier(natives) })
    runtimeOnly(variantOf(libs.lwjgl.opengl) { classifier(natives) })
    runtimeOnly(project.extra["skikoRuntime"] as String)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * GL integration tests. They open a real window with a real driver, so they are skipped unless
 * there is a display — on CI that is Xvfb with Mesa's llvmpipe. `./gradlew build` does not run
 * them; `xvfb-run ./gradlew integrationTest` does.
 */
val integrationTest: SourceSet by sourceSets.creating

configurations[integrationTest.implementationConfigurationName]
    .extendsFrom(configurations.implementation.get(), configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.runtimeOnly.get(), configurations.testRuntimeOnly.get())

dependencies {
    add(integrationTest.implementationConfigurationName, sourceSets.main.get().output)
}

tasks.register<Test>("integrationTest") {
    description = "Runs the GL integration tests. Needs a display; use xvfb-run on a headless box."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
    maxParallelForks = 1
    forkEvery = 1
    onlyIf {
        val hasDisplay = !System.getenv("DISPLAY").isNullOrEmpty()
        if (!hasDisplay) logger.lifecycle("Skipping integrationTest: no DISPLAY.")
        hasDisplay
    }
}

// Compose pulls a few artifacts in through two coordinates, which collide in the distribution
// archives. The sample is run with `./gradlew :composegl-smoke-lwjgl3:run`.
tasks.withType<AbstractArchiveTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
