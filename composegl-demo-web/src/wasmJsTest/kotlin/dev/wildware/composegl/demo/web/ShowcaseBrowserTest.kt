package dev.wildware.composegl.demo.web

import androidx.compose.runtime.mutableStateOf
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.webgl.BrowserUi
import dev.wildware.composegl.webgl.WebFonts
import dev.wildware.composegl.webgl.WebGlBackend
import dev.wildware.composegl.webgl.WebGlTexture
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.WebGLRenderingContext as GL
import org.khronos.webgl.get
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.EventTarget
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The whole showcase in headless Chromium, on a real WebGL canvas, driven with real DOM events: a
 * mouse on the section list, the keyboard's Page Down, a finger on a phone-sized page. Each page is
 * judged by what came out on the canvas — enough different colours that it is really a page, and
 * not a blank or a one-colour screen — and a picture of each is left in `build/screenshots`.
 */
class ShowcaseBrowserTest {

    private var nanos = 0L

    private class Page(val ui: BrowserUi, val state: ShowcaseState, val canvas: HTMLCanvasElement, val backend: WebGlBackend)

    private suspend fun page(cssWidth: Int, cssHeight: Int, test: suspend (Page) -> Unit) {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.style.cssText = "position:absolute;left:0;top:0;width:${cssWidth}px;height:${cssHeight}px;"
        canvas.width = cssWidth
        canvas.height = cssHeight
        document.body!!.appendChild(canvas)

        val backend = WebGlBackend(canvas, fonts(), preserveDrawingBuffer = true)
        val crest = backend.loadTexture("/composegl/resources/ui/ui.png").region(80, 0, 24, 24)
        val coins = WebGlTexture.rgba(backend.gl, CoinFrames * CoinSize, CoinSize, coinSheet(), smooth = false)
        val skins = ShowcaseSkins(showcaseAtlas(crest, coins) { texture, left, top, width, height -> texture.region(left, top, width, height) })

        val design = designFor(cssWidth.toDouble(), cssHeight.toDouble())
        val state = ShowcaseState().also { it.width = design.width }
        val budget = mutableStateOf<FrameBudget?>(null)
        val ui = BrowserUi(backend, design, input = { ShowcaseInput(state, it) }) { Showcase(state, skins, budget.value) }
        budget.value = ui.renderer.budget
        try {
            frames(ui, 8)
            test(Page(ui, state, canvas, backend))
        } finally {
            ui.close()
            backend.close()
            coins.close()
            canvas.remove()
        }
    }

    /** Enough frames at sixty a second for a page's crossfade to finish and its layout to settle. */
    private fun frames(ui: BrowserUi, count: Int = 16) = repeat(count) {
        nanos += 16_666_667L
        ui.frame(nanos)
    }

    /** Clicks the node tagged [tag] where it is on the canvas. One CSS pixel is one design unit here. */
    private fun Page.click(tag: String, pointerType: String = "mouse") {
        val node = checkNotNull(ui.host.root.findOrNull(tag)) { "nothing on the screen is tagged $tag" }
        val at = node.boundsInRoot.centre
        val scale = canvas.clientWidth.toDouble() / ui.design.width
        val x = at.x * scale
        val y = at.y * scale
        pointer(canvas, "pointermove", x, y, 0, pointerType)
        pointer(canvas, "pointerdown", x, y, 1, pointerType)
        pointer(canvas, "pointerup", x, y, 0, pointerType)
        frames(ui)
    }

    private fun Page.press(key: String) {
        keyboard(canvas, "keydown", key)
        keyboard(canvas, "keyup", key)
        frames(ui)
    }

    /** Fails unless the canvas holds a real page, and sends a picture of it to `build/screenshots`. */
    private suspend fun Page.assertRenders(name: String) {
        val width = canvas.width
        val height = canvas.height
        val bytes = Uint8Array(width * height * 4)
        backend.gl.readPixels(0, 0, width, height, GL.RGBA, GL.UNSIGNED_BYTE, bytes)
        val colours = HashSet<Int>()
        var lit = 0
        var at = 0
        while (at < bytes.length) {
            val r = bytes[at].toInt() and 0xFF
            val g = bytes[at + 1].toInt() and 0xFF
            val b = bytes[at + 2].toInt() and 0xFF
            colours += (r shr 3 shl 10) or (g shr 3 shl 5) or (b shr 3)
            if (r + g + b > 120) lit++
            at += 4
        }
        post("/composegl/pictures/$name", canvas.toDataURL("image/png")).await<JsAny?>()
        assertTrue(colours.size > 60, "$name drew only ${colours.size} colours; a page has text, panels and accents")
        assertTrue(lit > width * height / 200, "$name is nearly blank: $lit bright pixels")
    }

    @Test
    fun everySectionRendersWhenItsButtonIsClicked() = runTest(timeout = 10.minutes) {
        page(1280, 800) { page ->
            page.assertRenders("showcase-home")
            Section.entries.drop(1).forEach { section ->
                page.click("nav-${section.tag}")
                assertEquals(section, page.state.section)
                assertTrue(page.ui.host.root.findOrNull("page-${section.tag}") != null, "the ${section.title} page is not on the screen")
                page.assertRenders("showcase-${section.tag}")
            }
        }
    }

    @Test
    fun pageDownAndPageUpTurnThePageFromTheKeyboard() = runTest(timeout = 5.minutes) {
        page(1280, 800) { page ->
            page.press("PageDown")
            page.press("PageDown")
            assertEquals(Section.Tools, page.state.section)
            page.press("PageUp")
            assertEquals(Section.Widgets, page.state.section)
            page.assertRenders("showcase-keys")
        }
    }

    @Test
    fun aTapOnAPhoneSizedPageOpensASection() = runTest(timeout = 5.minutes) {
        page(400, 860) { page ->
            assertTrue(page.state.compact)
            page.click("nav-widgets", pointerType = "touch")
            assertEquals(Section.Widgets, page.state.section)
            page.assertRenders("showcase-phone")
        }
    }

    @Test
    fun theDialogOpensAndTheHighContrastSkinDraws() = runTest(timeout = 5.minutes) {
        page(1280, 2000) { page ->
            page.click("nav-widgets")
            page.click("open-dialog")
            frames(page.ui, 30)
            assertTrue(page.state.dialogOpen)
            page.assertRenders("showcase-dialog")
            page.press("Escape")
            frames(page.ui, 30)
            assertTrue(!page.state.dialogOpen, "Escape did not close the dialog")

            page.click("nav-settings")
            page.click("skin-HighContrast")
            assertEquals(SkinChoice.HighContrast, page.state.skin)
            page.assertRenders("showcase-high-contrast")
        }
    }

    private companion object {
        val Sizes = (8..72).toList()
        var loaded = false

        suspend fun fonts(): WebFonts {
            val fonts = WebFonts(pageSize = 2048)
            if (!loaded) {
                fonts.load("default", "/composegl/resources/fonts/DejaVuSans.ttf", Sizes)
                loaded = true
            } else {
                fonts.registerCss("default", "composegl-default", Sizes)
            }
            return fonts
        }
    }
}

private fun pointer(target: EventTarget, type: String, x: Double, y: Double, buttons: Int, pointerType: String): Unit = js(
    "{ const box = target.getBoundingClientRect(); target.dispatchEvent(new PointerEvent(type, { bubbles: true, cancelable: true, clientX: box.left + x, clientY: box.top + y, button: 0, buttons: buttons, pointerType: pointerType, pointerId: pointerType === 'touch' ? 7 : 1, isPrimary: true })); }",
)

private fun keyboard(target: EventTarget, type: String, key: String): Unit =
    js("{ target.dispatchEvent(new KeyboardEvent(type, { bubbles: true, cancelable: true, key: key, code: key })); }")

private fun post(url: String, body: String): Promise<JsAny?> = js("fetch(url, { method: 'POST', body: body }).then(r => r.text())")
