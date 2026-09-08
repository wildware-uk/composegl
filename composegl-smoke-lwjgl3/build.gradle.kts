plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
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
