package dev.wildware.composegl.ui.preview

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.preview.broken.Empty
import dev.wildware.composegl.ui.preview.broken.InsideAClass
import dev.wildware.composegl.ui.preview.broken.NotComposable
import dev.wildware.composegl.ui.preview.broken.TakesArguments
import dev.wildware.composegl.ui.preview.broken.Throws
import dev.wildware.composegl.ui.preview.samples.ObjectPreviews
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Finding functions marked `@Preview`, and composing them as the screens they describe.
 *
 * The previews are real ones, compiled by the Compose compiler in this source set, so a change in
 * what the compiler makes of a composable shows up here rather than as an empty picture.
 */
class PreviewsTest {

    /** This source set's compiled classes: the directory a Gradle task would hand over. */
    private val classes = File(PreviewsTest::class.java.protectionDomain.codeSource.location.toURI())

    private fun samples() = Previews.find(listOf(classes), packageName = "dev.wildware.composegl.ui.preview.samples")

    @Test
    fun `every marked function is found and nothing else`() {
        assertEquals(
            listOf("ClearPreview", "CounterPreview", "InACompanion", "InsideAnObject", "StaticInACompanion", "square-on-blue"),
            samples().map { it.name },
        )
    }

    @Test
    fun `the annotation's size name and background are what a preview carries`() {
        val square = samples().single { it.name == "square-on-blue" }

        assertEquals(64, square.width)
        assertEquals(48, square.height)
        assertEquals(Colour.rgb(0x0000FF), square.background)
        assertEquals("dev.wildware.composegl.ui.preview.samples.SamplePreviewsKt.SquarePreview", square.function)

        val counter = samples().single { it.name == "CounterPreview" }
        assertEquals(300 to 120, counter.width to counter.height)
        assertEquals(Colour.rgb(0x000000), counter.background, "opaque black when nobody says")
    }

    @Test
    fun `a preview is laid out at its own size and clicks like any screen`() {
        uiTest(samples().single { it.name == "CounterPreview" }).use { ui ->
            assertEquals(300f to 120f, ui.size.width to ui.size.height)
            ui.assertText("counter", "PRESSED 0")

            ui.click("counter")
            ui.click("counter")

            ui.assertText("counter", "PRESSED 2")
        }
    }

    @Test
    fun `the background fills the picture behind the content`() {
        uiTest(samples().single { it.name == "square-on-blue" }).use { ui ->
            val canvas = ui.backend.canvas as RecordingCanvas
            canvas.clear()
            ui.render()

            val rectangles = canvas.calls.filterIsInstance<DrawCall.Rectangle>()
            assertEquals(Rect.of(0f, 0f, 64f, 48f), rectangles.first().rect)
            assertEquals(Colour.rgb(0x0000FF), rectangles.first().colour)
            assertEquals(Rect.of(8f, 8f, 16f, 16f), ui.node("square").boundsInRoot)
            assertEquals(Colour.rgb(0xFF0000), rectangles.last().colour)
        }
    }

    @Test
    fun `a transparent background draws nothing behind`() {
        uiTest(samples().single { it.name == "ClearPreview" }).use { ui ->
            val canvas = ui.backend.canvas as RecordingCanvas
            canvas.clear()
            ui.render()

            assertEquals(
                listOf(Colour.rgb(0x00FF00)),
                canvas.calls.filterIsInstance<DrawCall.Rectangle>().map { it.colour },
            )
        }
    }

    @Test
    fun `a preview in an object is called on the object`() {
        val preview = Previews.of(ObjectPreviews::class.java).single()

        uiTest(preview).use { ui -> ui.assertText("label", "FROM AN OBJECT") }
    }

    @Test
    fun `a preview in a companion object is called on the companion and clicks`() {
        uiTest(samples().single { it.name == "InACompanion" }).use { ui ->
            ui.assertText("companion", "COMPANION 0")
            ui.click("companion")
            ui.assertText("companion", "COMPANION 1")
        }
    }

    @Test
    fun `a JvmStatic preview in a companion is one preview not two`() {
        uiTest(samples().single { it.name == "StaticInACompanion" }).use { ui -> ui.assertText("static", "STATIC") }
    }

    @Test
    fun `previews are found inside a jar too`() {
        val jar = File(createTempDirectory("previews").toFile(), "previews.jar")
        val folder = "dev/wildware/composegl/ui/preview/samples"
        JarOutputStream(jar.outputStream()).use { out ->
            File(classes, folder).listFiles()!!.filter { it.isFile }.forEach { file ->
                out.putNextEntry(JarEntry("$folder/${file.name}"))
                out.write(file.readBytes())
                out.closeEntry()
            }
        }

        assertEquals(samples().map { it.name }, Previews.find(listOf(jar)).map { it.name })
    }

    @Test
    fun `a preview that is not composable is refused by name`() {
        val failure = assertFailsWith<IllegalArgumentException> { Previews.of(NotComposable::class.java) }
        assertTrue("NotComposable.Plain is marked @Preview but is not @Composable" in failure.message!!, failure.message)
    }

    @Test
    fun `a preview that takes arguments is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> { Previews.of(TakesArguments::class.java) }
        assertTrue("takes arguments" in failure.message!!, failure.message)
    }

    @Test
    fun `a preview inside a class is refused because nothing can make the instance`() {
        val failure = assertFailsWith<IllegalArgumentException> { Previews.of(InsideAClass::class.java) }
        assertTrue("inside a class" in failure.message!!, failure.message)
    }

    @Test
    fun `a preview with no area is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> { Previews.of(Empty::class.java) }
        assertTrue("0x10" in failure.message!!, failure.message)
    }

    @Test
    fun `two previews writing the same file are refused rather than one overwriting the other`() {
        val failure = assertFailsWith<IllegalStateException> {
            Previews.find(listOf(classes), packageName = "dev.wildware.composegl.ui.preview.clash")
        }
        assertTrue("menu.png" in failure.message!!, failure.message)
    }

    @Test
    fun `what a preview throws comes out as itself`() {
        val preview = Previews.of(Throws::class.java).single()

        val failure = assertFailsWith<IllegalStateException> { uiTest(preview) }
        assertEquals("the preview itself went wrong", failure.message)
    }
}
