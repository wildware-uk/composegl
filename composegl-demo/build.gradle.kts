plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

description = "The example: a game interface built with the toolkit, run on the LibGDX backend."

dependencies {
    implementation(project(":composegl-gdx"))
    // The frame budget and redraw overlays the example turns on with a key.
    implementation(project(":composegl-debug"))

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
 *
 * A picture wider or taller than the 640 window is skipped and says so; take it on its own with
 * `COMPOSEGL_DOC_WINDOW=1280 COMPOSEGL_DOC_ONLY=<name>`, which leaves every other picture alone.
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
 * The live preview window's own classpath: composegl-preview, which the example never runs on.
 *
 * A configuration of its own rather than a dependency of the example, so the window and Gradle's
 * client never end up in what the game ships. Resolved for the JVM, like the runtime classpath.
 */
val previewLive: Configuration by configurations.creating {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
}

dependencies {
    previewLive(project(":composegl-preview"))
}

/**
 * What the window asks Gradle for after a save: the example's classes, and every library jar it runs
 * on, so a change in another module rebuilds that jar and the window can see it has to restart.
 */
val previewClasses = tasks.register("previewClasses") {
    group = "documentation"
    description = "Compiles what the live preview window reloads."
    dependsOn(sourceSets["main"].output, sourceSets["main"].runtimeClasspath)
}

/**
 * Every `@Preview` in the example, live, in a window: `./gradlew :composegl-demo:previewLive`.
 *
 * Saving a source file compiles the example through Gradle and redraws the previews from the new
 * classes. The example's own classes are left off the classpath on purpose: the window loads them
 * itself, and loads them again after every compile. Everything else — composegl, the Compose runtime,
 * LibGDX — is loaded once. See the wiki's Live previews page.
 */
tasks.register<JavaExec>("previewLive") {
    group = "documentation"
    description = "Opens a window showing every @Preview, redrawn when a source file is saved."
    mainClass.set("dev.wildware.composegl.preview.PreviewLiveKt")
    dependsOn(previewClasses)

    val main = sourceSets["main"]
    classpath = previewLive + (main.runtimeClasspath - main.output)

    val classes = main.output.classesDirs
    val resources = files(main.output.resourcesDir)
    val sources = files(main.allSource.srcDirs)
    val root = rootDir
    val gradleHome = gradle.gradleHomeDir
    val task = "${project.path}:previewClasses"
    val buildFiles = files(
        "build.gradle.kts",
        rootProject.file("build.gradle.kts"),
        rootProject.file("settings.gradle.kts"),
        rootProject.file("gradle/libs.versions.toml"),
        rootProject.file("gradle.properties"),
        rootProject.file("buildSrc/src"),
        rootProject.file("buildSrc/build.gradle.kts"),
    )
    val font = layout.projectDirectory.file("src/main/resources/fonts/DejaVuSans.ttf")
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "--classes", classes.asPath,
                "--resources", resources.asPath,
                "--sources", sources.asPath,
                "--project-dir", root.path,
                "--task", task,
                "--gradle-home", gradleHome?.path.orEmpty(),
                "--build-file", buildFiles.asPath,
            ) + listOf("default", "body").flatMap { listOf("--font", "$it=${font.asFile.path}") }
        },
    )
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
