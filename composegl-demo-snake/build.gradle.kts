plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

description = "Snake on the raw OpenGL backend: a window, fonts, a canvas and nothing else."

dependencies {
    implementation(project(":composegl-demo-snake-core"))

    // The raw OpenGL backend, and nothing else. The whole game runs without LibGDX on the
    // classpath, which is the part of the port worth saying out loud.
    implementation(project(":composegl-lwjgl3"))
}

// The font is the example's; ship one copy, not two.
sourceSets.main {
    resources.srcDir("../composegl-demo/src/main/resources")
}

application {
    mainClass.set("dev.wildware.composegl.snake.MainKt")
}

/** Never shipped, only run — the same reason the example has none. */
tasks.named("distTar") { enabled = false }
tasks.named("distZip") { enabled = false }
