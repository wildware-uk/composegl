package dev.wildware.composegl.lwjgl3.preview

import dev.wildware.composegl.lwjgl3.GlfwWindow
import dev.wildware.composegl.lwjgl3.Lwjgl3Backend
import dev.wildware.composegl.lwjgl3.StbFonts
import dev.wildware.composegl.ui.preview.PreviewFunction
import dev.wildware.composegl.ui.preview.Previews
import java.io.File
import java.net.URLClassLoader
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * A font for the previews to draw text with: a `.ttf` registered as [family] at each of [sizes].
 *
 * Written on the command line as `family=path/to/font.ttf`, or `family=path/to/font.ttf@12,16` to
 * bake only some sizes.
 */
class PreviewFont(val family: String, val file: File, val sizes: List<Int> = DefaultSizes) {

    companion object {
        /** Every size the toolkit's own skin asks for, and a heading. */
        val DefaultSizes = listOf(12, 13, 14, 16, 18, 20, 22, 26, 34)

        fun parse(text: String): PreviewFont {
            val family = text.substringBefore('=', missingDelimiterValue = "")
            val rest = text.substringAfter('=', missingDelimiterValue = "")
            require(family.isNotEmpty() && rest.isNotEmpty()) {
                "a font is family=path/to/font.ttf, or family=path@12,16; got \"$text\""
            }
            val path = rest.substringBefore('@')
            val sizes = if ('@' in rest) {
                rest.substringAfter('@').split(',').map {
                    requireNotNull(it.trim().toIntOrNull()) { "\"$it\" is not a font size, in \"$text\"" }
                }
            } else {
                DefaultSizes
            }
            return PreviewFont(family, File(path), sizes)
        }
    }
}

/**
 * What `renderPreviews` was asked to do.
 *
 * @param classes the compiled classes to look for previews in — a module's own output.
 * @param out where the pictures go, one `<name>.png` each.
 * @param fonts what text is drawn with. A preview with text and no font registered for its family
 *   fails with the family's name.
 * @param packageName only previews in this package or below it; empty for all of them.
 */
class PreviewOptions(
    val classes: List<File>,
    val out: File,
    val fonts: List<PreviewFont> = emptyList(),
    val packageName: String = "",
) {
    companion object {

        /**
         * `--classes dir` (repeatable), `--out dir`, `--font family=file.ttf[@sizes]` (repeatable)
         * and `--package name`.
         */
        fun parse(args: List<String>): PreviewOptions {
            val classes = mutableListOf<File>()
            val fonts = mutableListOf<PreviewFont>()
            var out: File? = null
            var packageName = ""

            val each = args.iterator()
            while (each.hasNext()) {
                val flag = each.next()
                fun value(): String {
                    require(each.hasNext()) { "$flag needs a value after it" }
                    return each.next()
                }
                when (flag) {
                    "--classes" -> value().split(File.pathSeparatorChar).filter { it.isNotEmpty() }.mapTo(classes, ::File)
                    "--out" -> out = File(value())
                    "--font" -> fonts += PreviewFont.parse(value())
                    "--package" -> packageName = value()
                    else -> throw IllegalArgumentException(
                        "renderPreviews does not know \"$flag\"; it takes --classes, --out, --font and --package",
                    )
                }
            }
            require(classes.isNotEmpty()) { "renderPreviews needs --classes: where to look for @Preview functions" }
            return PreviewOptions(classes, requireNotNull(out) { "renderPreviews needs --out: where to write the pictures" }, fonts, packageName)
        }
    }
}

/** What a run did: the pictures it wrote, and each preview that could not be drawn with why. */
class PreviewReport(val written: List<File>, val failed: Map<String, Throwable>)

/**
 * Finds every `@Preview` under [options]' classes and writes each as a PNG.
 *
 * One preview going wrong does not stop the rest: its failure is in the report and the others are
 * still drawn, so one broken screen does not hide what happened to twenty working ones.
 *
 * Opens a hidden window for its GL context and closes it again.
 */
fun renderPreviews(options: PreviewOptions, log: (String) -> Unit = ::println): PreviewReport {
    // Asked for before anything is drawn. This backend takes its solid colour from a corner of the
    // glyph atlas, so with no font it cannot draw even a rectangle, and every preview would fail
    // one at a time with a message about text in a picture that has none.
    require(options.fonts.isNotEmpty()) {
        "renderPreviews needs at least one --font, such as --font default=fonts/DejaVuSans.ttf; " +
            "the default skin draws text in the family \"default\""
    }
    // Before the window too: a mistyped path is a mistake in the command, not in a preview.
    options.fonts.forEach { font ->
        require(font.file.isFile) { "there is no font at ${font.file} for \"${font.family}\"" }
    }

    return URLClassLoader(
        options.classes.map { it.toURI().toURL() }.toTypedArray(),
        Thread.currentThread().contextClassLoader ?: PreviewRenderer::class.java.classLoader,
    ).use { loader ->
        val previews = Previews.find(options.classes, loader, options.packageName)
        check(previews.isNotEmpty()) {
            "found no @Preview functions under ${options.classes.joinToString()}" +
                if (options.packageName.isEmpty()) "" else " in ${options.packageName}"
        }
        options.out.mkdirs()
        GlfwWindow("composegl previews", 1, 1, visible = false, vsync = false).use { window ->
            draw(previews, window, options, log)
        }
    }
}

/** [previews] into [options]' folder, through a backend on [window] that is closed again after. */
private fun draw(
    previews: List<PreviewFunction>,
    window: GlfwWindow,
    options: PreviewOptions,
    log: (String) -> Unit,
): PreviewReport {
    val fonts = StbFonts(pageSize = 2048)
    val backend = try {
        options.fonts.forEach { font -> fonts.register(font.family, font.file.readBytes(), font.sizes) }
        Lwjgl3Backend(window, fonts)
    } catch (failure: Throwable) {
        // A file that is there but is not a font: nothing else will close what was baked so far.
        runCatching { fonts.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
        throw failure
    }

    val written = mutableListOf<File>()
    val failed = linkedMapOf<String, Throwable>()
    try {
        val renderer = PreviewRenderer(backend)
        previews.forEach { preview ->
            try {
                val file = File(options.out, "${preview.name}.png")
                ImageIO.write(renderer.render(preview), "png", file)
                written += file
                log("wrote ${file.path}")
            } catch (failure: Throwable) {
                // Errors too: a preview left as TODO(), or a screen that never stops animating,
                // throws one, and is still only one broken preview. Running out of the machine is not.
                if (failure is VirtualMachineError) throw failure
                failed[preview.name] = failure
                log("could not draw $preview: $failure")
            }
        }
    } finally {
        backend.close()
    }
    return PreviewReport(written, failed)
}

/**
 * `renderPreviews --classes build/classes/kotlin/main --out build/previews --font default=font.ttf`
 *
 * What a module's `renderPreviews` Gradle task runs. Fails the build when a preview could not be
 * drawn, so a broken one is noticed rather than leaving last week's picture in place.
 */
fun main(args: Array<String>) {
    val report = renderPreviews(PreviewOptions.parse(args.toList()))
    if (report.failed.isNotEmpty()) {
        System.err.println("${report.failed.size} preview(s) could not be drawn: ${report.failed.keys.joinToString()}")
        exitProcess(1)
    }
}
