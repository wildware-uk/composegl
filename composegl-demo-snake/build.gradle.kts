plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

description = "Snake. The game is OpenGL; every pixel of UI — menu, HUD, pause, game over — is Compose."

dependencies {
    implementation(project(":composegl-libgdx"))
    implementation(libs.compose.material3)
    implementation(libs.gdx.backend.lwjgl3)
    runtimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
    runtimeOnly(project.extra["skikoRuntime"] as String)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The bundled font lives with the other demo; share it rather than commit the binary twice.
sourceSets.main {
    resources.srcDir("../composegl-demo-libgdx/src/main/resources")
}

application {
    mainClass.set("composegl.snake.MainKt")
}

tasks.withType<AbstractArchiveTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
