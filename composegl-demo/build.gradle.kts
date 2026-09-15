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

    // The HUD's game widgets, which live in their own module.
    implementation(project(":composegl-game"))
    implementation(libs.gdx.backend.lwjgl3)
    runtimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
    runtimeOnly(variantOf(libs.gdx.freetype.platform) { classifier("natives-desktop") })

    // gdx-controllers ships its core as an interface and its drivers per platform. The toolkit
    // module depends on the interface; picking the driver is the application's job, and this is a
    // desktop application.
    runtimeOnly(libs.gdx.controllers.desktop)
}

application {
    mainClass.set("dev.wildware.composegl.demo.MainKt")
}

/** The same example on the raw OpenGL backend. `run` is the LibGDX one. */
tasks.register<JavaExec>("runGl") {
    group = "application"
    description = "Runs the example on the raw OpenGL backend."
    mainClass.set("dev.wildware.composegl.demo.GlMainKt")
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
    mainClass.set("dev.wildware.composegl.demo.docs.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
}

/**
 * Every `@Preview` in the example, as a PNG in `build/previews`.
 *
 * No demo launched, nothing clicked: the functions are found in the compiled classes and drawn on
 * the raw OpenGL backend, off the window, each at its own size. Needs a display for the GL context,
 * so on a headless machine it is `xvfb-run -a ./gradlew :composegl-demo:renderPreviews`.
 *
 * The folder is emptied first, so a preview that was renamed or deleted does not leave its old
 * picture behind looking current.
 */
tasks.register<JavaExec>("renderPreviews") {
    group = "documentation"
    description = "Draws every @Preview function to a PNG."
    mainClass.set("dev.wildware.composegl.lwjgl3.preview.RenderPreviewsKt")
    classpath = sourceSets["main"].runtimeClasspath

    val classes = sourceSets["main"].output.classesDirs
    val out = layout.buildDirectory.dir("previews")
    val font = layout.projectDirectory.file("src/main/resources/fonts/DejaVuSans.ttf")
    outputs.dir(out)
    // Pictures depend on the GPU's driver as much as on the code, so they are always redrawn.
    outputs.upToDateWhen { false }

    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf("--classes", classes.asPath, "--out", out.get().asFile.path) +
                listOf("default", "body").flatMap { listOf("--font", "$it=${font.asFile.path}") }
        },
    )
    doFirst { out.get().asFile.deleteRecursively() }
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
