package dev.wildware.composegl.preview

import dev.wildware.composegl.lwjgl3.preview.PreviewFont
import java.io.File

/**
 * What `previewLive` was asked to show, and how to keep it up to date.
 *
 * @param classes the module's compiled class folders: the reloaded layer.
 * @param resources the module's resource folders, reloaded with the classes.
 * @param sources the module's source folders. Saving a file in one starts a compile.
 * @param fonts what text is drawn with, loaded once. The default skin's text is the family `default`.
 * @param packageName only previews in this package or below it; empty for all of them.
 * @param projectDir the Gradle build's root folder. Null watches [classes] for somebody else's compile.
 * @param tasks what to ask Gradle to run after a save, such as `:my-game:previewClasses`.
 * @param gradleHome the Gradle installation to compile with, so nothing is downloaded.
 * @param buildFiles build scripts and the like. Changing one asks for a restart.
 */
class PreviewLiveOptions(
    val classes: List<File>,
    val resources: List<File> = emptyList(),
    val sources: List<File> = emptyList(),
    val fonts: List<PreviewFont> = emptyList(),
    val packageName: String = "",
    val projectDir: File? = null,
    val tasks: List<String> = emptyList(),
    val gradleHome: File? = null,
    val buildFiles: List<File> = emptyList(),
) {

    /** What to run by hand when Gradle is not compiling for the window. */
    val advice: String get() = "./gradlew -t " + tasks.joinToString(" ").ifEmpty { "classes" }

    /** Whether there is a Gradle build to ask for compiles at all. */
    val compiles: Boolean get() = projectDir != null && tasks.isNotEmpty()

    companion object {

        /**
         * `--classes`, `--resources` and `--sources` (path lists, repeatable), `--font family=file.ttf[@sizes]`
         * (repeatable), `--package`, `--project-dir`, `--task` (repeatable), `--gradle-home` and
         * `--build-file` (repeatable).
         */
        fun parse(args: List<String>): PreviewLiveOptions {
            val classes = mutableListOf<File>()
            val resources = mutableListOf<File>()
            val sources = mutableListOf<File>()
            val fonts = mutableListOf<PreviewFont>()
            val tasks = mutableListOf<String>()
            val buildFiles = mutableListOf<File>()
            var packageName = ""
            var projectDir: File? = null
            var gradleHome: File? = null

            val each = args.iterator()
            while (each.hasNext()) {
                val flag = each.next()
                fun value(): String {
                    require(each.hasNext()) { "$flag needs a value after it" }
                    return each.next()
                }
                fun paths() = value().split(File.pathSeparatorChar).filter { it.isNotEmpty() }.map(::File)
                when (flag) {
                    "--classes" -> classes += paths()
                    "--resources" -> resources += paths()
                    "--sources" -> sources += paths()
                    "--font" -> fonts += PreviewFont.parse(value())
                    "--package" -> packageName = value()
                    "--project-dir" -> projectDir = File(value())
                    "--task" -> tasks += value()
                    "--gradle-home" -> gradleHome = value().takeIf { it.isNotEmpty() }?.let(::File)
                    "--build-file" -> buildFiles += paths()
                    else -> throw IllegalArgumentException(
                        "previewLive does not know \"$flag\"; it takes --classes, --resources, --sources, --font, " +
                            "--package, --project-dir, --task, --gradle-home and --build-file",
                    )
                }
            }
            require(classes.isNotEmpty()) { "previewLive needs --classes: the module's compiled classes" }
            require(fonts.isNotEmpty()) {
                "previewLive needs at least one --font, such as --font default=fonts/DejaVuSans.ttf; " +
                    "the default skin draws text in the family \"default\""
            }
            return PreviewLiveOptions(classes, resources, sources, fonts, packageName, projectDir, tasks, gradleHome, buildFiles)
        }
    }
}
