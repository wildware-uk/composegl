plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

description = "The example: a game interface built with the toolkit, run on the LibGDX backend."

dependencies {
    implementation(project(":composegl-gdx"))

    // The same interface, drawn twice. The example carries both backends because that is the
    // proof: `Screen` and the widgets are shared between the two mains without a line changing.
    implementation(project(":composegl-lwjgl3"))
    implementation(libs.gdx.backend.lwjgl3)
    runtimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
    runtimeOnly(variantOf(libs.gdx.freetype.platform) { classifier("natives-desktop") })

    // gdx-controllers ships its core as an interface and its drivers per platform. The toolkit
    // module depends on the interface; picking the driver is the application's job, and this is a
    // desktop application.
    runtimeOnly(libs.gdx.controllers.desktop)
}

application {
    mainClass.set("composegl.demo.MainKt")
}

/** The same example on the raw OpenGL backend. `run` is the LibGDX one. */
tasks.register<JavaExec>("runGl") {
    group = "application"
    description = "Runs the example on the raw OpenGL backend."
    mainClass.set("composegl.demo.GlMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}

/**
 * Every picture in the wiki, regenerated.
 *
 * Writes `docs/wiki/images`. Needs a display, so on a headless machine it is
 * `xvfb-run -a ./gradlew :composegl-demo:docShots`.
 */
tasks.register<JavaExec>("docShots") {
    group = "documentation"
    description = "Takes the screenshots the wiki uses."
    mainClass.set("composegl.demo.docs.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
}

/**
 * No tarball. The example is run with `./gradlew :composegl-demo:run`, never shipped.
 *
 * The application plugin's distribution copies every dependency into one directory by file name,
 * and the Compose runtime ships two different jars that are both called `runtime-desktop`. There
 * is no way to build that archive without quietly dropping one of them, and an example that is
 * never distributed does not need it.
 */
tasks.named("distTar") { enabled = false }
tasks.named("distZip") { enabled = false }
