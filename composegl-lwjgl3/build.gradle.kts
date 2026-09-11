plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "The raw OpenGL backend: a GLFW window, one shader and stb_truetype. No engine."

dependencies {
    api(project(":composegl-ui"))
    api(libs.lwjgl)
    compileOnly(libs.jspecify)
    api(libs.lwjgl.glfw)
    api(libs.lwjgl.opengl)
    api(libs.lwjgl.stb)

    // LWJGL is a thin Java front on C libraries, so whichever machine runs this needs the matching
    // natives on its classpath. Desktop only, deliberately: this backend exists to keep the
    // toolkit honest, and a game ships on the LibGDX one.
    listOf(
        "natives-linux",
        "natives-linux-arm64",
        "natives-macos",
        "natives-macos-arm64",
        "natives-windows",
    ).forEach { platform ->
        runtimeOnly(variantOf(libs.lwjgl) { classifier(platform) })
        runtimeOnly(variantOf(libs.lwjgl.glfw) { classifier(platform) })
        runtimeOnly(variantOf(libs.lwjgl.opengl) { classifier(platform) })
        runtimeOnly(variantOf(libs.lwjgl.stb) { classifier(platform) })
    }

    testImplementation(project(":composegl-testing"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * The promise this module exists to keep, checked rather than remembered.
 *
 * A second backend is only evidence that the toolkit's seams are real if it genuinely shares
 * nothing with the first. One convenient import of a LibGDX class — a `Color`, a `Matrix4`, a
 * texture region — and this stops being a second opinion and becomes a second voice agreeing with
 * itself.
 */
val noEngineTypes = tasks.register<BytecodeReferenceCheck>("checkNoEngineTypes") {
    description = "Fails if the raw OpenGL backend names LibGDX."
    group = "verification"
    classDirectories.from(layout.buildDirectory.dir("classes/kotlin/main"))
    forbiddenPackages.set(listOf("com/badlogic/gdx"))
    reason.set(
        "This backend is the toolkit's second opinion. It has to reach the same pictures by its " +
            "own route, or it proves nothing.",
    )
    dependsOn(tasks.named("classes"))
}

tasks.named("check") { dependsOn(noEngineTypes) }
