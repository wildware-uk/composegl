plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

description = "Snake. The board is OpenGL; every pixel you can click is the toolkit."

dependencies {
    // The raw OpenGL backend, and nothing else. The whole game runs without LibGDX on the
    // classpath, which is the part of the port worth saying out loud.
    implementation(project(":composegl-lwjgl3"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The font is the example's; ship one copy, not two.
sourceSets.main {
    resources.srcDir("../composegl-demo/src/main/resources")
}

application {
    mainClass.set("composegl.snake.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

/** Never shipped, only run — the same reason the example has none. */
tasks.named("distTar") { enabled = false }
tasks.named("distZip") { enabled = false }
