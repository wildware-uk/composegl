plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
}

description = "LibGDX adapter: FBO, GL state firewall, blit, input bridge. gdx core only — no backend, so Android can reuse it."

dependencies {
    api(project(":composegl-core"))
    api(libs.gdx)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Headless adapter tests (keycode table, modifiers, HDPI scaling) need a LibGDX application.
    testImplementation(libs.gdx.backend.lwjgl3)
    testRuntimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
}
