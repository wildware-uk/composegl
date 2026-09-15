plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
}

description = "Snake itself: rules, board, interface and input, with no backend anywhere in it."

dependencies {
    // The toolkit, and nothing else. Not a backend, not a window library, not an engine — which is
    // what lets the same classes run under GLFW on a desktop and under LibGDX on a phone.
    api(project(":composegl-ui"))
    // And its debug tools, for the frame budget a key puts up. Still no backend.
    implementation(project(":composegl-debug"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
