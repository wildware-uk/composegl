package dev.wildware.composegl.demo.web

import androidx.compose.runtime.mutableStateOf
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.webgl.BrowserUi
import dev.wildware.composegl.webgl.WebFonts
import dev.wildware.composegl.webgl.WebGlBackend
import dev.wildware.composegl.webgl.WebGlTexture
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLCanvasElement

/**
 * The showcase, in a browser tab.
 *
 * Everything in `commonMain` is ordinary toolkit code, the same a desktop game writes. What makes it
 * a web page is here: fonts and pictures from URLs next to the page, a backend on the canvas, and
 * [BrowserUi] to run it, laid out to whatever shape the window is.
 *
 * `?shot` keeps each frame readable after it is shown, for the script that photographs the page, and
 * a `#section` in the address opens that section.
 */
fun main() {
    MainScope().launch {
        try {
            start()
        } catch (failure: Throwable) {
            fail(failure.message ?: failure.toString())
            throw failure
        }
    }
}

private suspend fun start() {
    val fonts = WebFonts(pageSize = 2048)
    fonts.load("default", "fonts/DejaVuSans.ttf", FontSizes)
    // Small cuts holding only what the text page says; a failure here only costs those lines.
    runCatching {
        fonts.load("cjk", "fonts/NotoSansSC-Subset.ttf", FontSizes)
        fonts.load("korean", "fonts/NotoSansKR-Subset.ttf", FontSizes)
        fonts.fallBackTo(listOf("cjk", "korean"))
    }

    val element = document.getElementById("game") as HTMLCanvasElement
    val backend = WebGlBackend(element, fonts, preserveDrawingBuffer = "shot" in window.location.search)
    val sheet = backend.loadTexture("ui/ui.png")
    val coins = WebGlTexture.rgba(backend.gl, CoinFrames * CoinSize, CoinSize, coinSheet(), smooth = false)
    val atlas = showcaseAtlas(sheet.region(80, 0, 24, 24), coins) { texture, left, top, width, height -> texture.region(left, top, width, height) }

    val state = ShowcaseState()
    val skins = ShowcaseSkins(atlas)
    val budget = mutableStateOf<FrameBudget?>(null)
    sectionFromAddress()?.let { state.goTo(it) }

    val ui = BrowserUi(
        backend,
        designFor(element.clientWidth.toDouble(), element.clientHeight.toDouble()),
        background = Colour.rgb(0x0B0E13),
        input = { screen -> ShowcaseInput(state, screen) },
    ) {
        Showcase(state, skins, budget.value, openLink = { url -> window.open(url, "_blank", "noopener") })
    }
    budget.value = ui.renderer.budget

    window.addEventListener("hashchange", { sectionFromAddress()?.let { state.goTo(it) } })

    var shownSection = state.section
    var ready = false
    fun loop(millis: Double) {
        val design = designFor(element.clientWidth.toDouble(), element.clientHeight.toDouble())
        if (design != ui.design) ui.design = design
        if (state.width != design.width) state.width = design.width
        ui.frame((millis * 1_000_000.0).toLong())
        if (state.section != shownSection) {
            shownSection = state.section
            replaceHash(state.section.tag)
        }
        if (!ready) {
            ready = true
            document.body?.setAttribute("data-ready", "true")
        }
        window.requestAnimationFrame(::loop)
    }
    window.requestAnimationFrame(::loop)
}

/** Every size the skins, the text scale and the landing page's title can ask for. */
private val FontSizes = (8..72).toList()

private fun sectionFromAddress(): Section? {
    val tag = window.location.hash.removePrefix("#")
    return Section.entries.firstOrNull { it.tag == tag }
}

private fun replaceHash(tag: String): Unit = js("history.replaceState(null, '', '#' + tag)")

private fun fail(message: String) {
    document.body?.setAttribute("data-error", "true")
    document.getElementById("loading")?.textContent = "ComposeGL could not start: $message"
}
