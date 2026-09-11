plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

description = "The showcase: a 3D scene with the game-widget tier over it, and a panel inside it."

dependencies {
    implementation(project(":composegl-gdx"))

    // The shipped effects, so the showcase runs the same blur a game would.
    implementation(project(":composegl-effects"))
    implementation(libs.gdx.backend.lwjgl3)
    runtimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
    runtimeOnly(variantOf(libs.gdx.freetype.platform) { classifier("natives-desktop") })

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The font is the example's; ship one copy, not three.
sourceSets.main {
    resources.srcDir("../composegl-demo/src/main/resources")
}

application {
    mainClass.set("dev.wildware.composegl.showcase.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

/** Never shipped, only run — the same reason the example has none. */
tasks.named("distTar") { enabled = false }
tasks.named("distZip") { enabled = false }
