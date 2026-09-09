plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

description = "A showcase: game-style interfaces — holograms, particles, tracking panels — built in Compose inside a 3D scene."

dependencies {
    implementation(project(":composegl-libgdx"))
    implementation(libs.compose.material3)
    implementation(libs.gdx.backend.lwjgl3)
    runtimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
    runtimeOnly(project.extra["skikoRuntime"] as String)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Share the bundled font rather than commit the binary a third time.
sourceSets.main {
    resources.srcDir("../composegl-demo-libgdx/src/main/resources")
}

application {
    mainClass.set("composegl.showcase.MainKt")
}

tasks.withType<AbstractArchiveTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
