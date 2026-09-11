plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

description = "The example: a game interface built with the toolkit, run on the LibGDX backend."

dependencies {
    implementation(project(":composegl-gdx"))
    implementation(libs.gdx.backend.lwjgl3)
    runtimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
    runtimeOnly(variantOf(libs.gdx.freetype.platform) { classifier("natives-desktop") })
}

application {
    mainClass.set("composegl.demo.MainKt")
}
