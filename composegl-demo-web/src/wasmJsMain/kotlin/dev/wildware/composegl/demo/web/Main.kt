package dev.wildware.composegl.demo.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.game.Bar
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Checkbox
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import dev.wildware.composegl.ui.widget.Toggle
import dev.wildware.composegl.webgl.BrowserUi
import dev.wildware.composegl.webgl.WebFonts
import dev.wildware.composegl.webgl.WebGlBackend
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLCanvasElement

/**
 * The example, in a browser tab.
 *
 * Everything below `main` is ordinary toolkit code — the same `Column`, `TextField` and `Button` a
 * desktop game writes. What makes it a web page is the four lines in `main`: fonts from a URL, a
 * backend on a canvas, and [BrowserUi] to run it.
 *
 * `?shot` keeps each frame readable after it is shown, for the script that photographs the page.
 */
fun main() {
    MainScope().launch {
        val fonts = WebFonts()
        fonts.load("default", "fonts/DejaVuSans.ttf", (10..40).toList())
        val element = document.getElementById("game") as HTMLCanvasElement
        val backend = WebGlBackend(element, fonts, preserveDrawingBuffer = "shot" in window.location.search)
        BrowserUi(backend, Design, background = Backdrop) { WebDemo() }.start()
        document.body?.setAttribute("data-ready", "true")
    }
}

private val Design = Size(960f, 600f)

private val Backdrop = Colour.rgb(0x0B0E13)
private val Card = Colour.rgb(0x161D27)
private val Muted = Colour.rgb(0x8A98A8)

/** A launch screen: a name, a volume, two switches and a button, every one of them keyboard, mouse, touch and pad driven. */
@Composable
fun WebDemo() {
    var callsign by remember { mutableStateOf("") }
    var volume by remember { mutableFloatStateOf(0.6f) }
    var subtitles by remember { mutableStateOf(true) }
    var hints by remember { mutableStateOf(false) }
    var launches by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().background(Backdrop)) {
        Column(
            Modifier.align(Alignment.Centre).width(560f).background(Card, corner = 16f).padding(32f),
            verticalArrangement = Arrangement.spacedBy(16f),
        ) {
            Text("ComposeGL, in a browser tab", textStyle = TextStyle(size = 28f))
            Text(
                "The same toolkit a desktop game uses, compiled to WebAssembly and drawn with WebGL. " +
                    "Click, type, tab around, or pick up a pad.",
                colour = Muted,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16f), verticalAlignment = VerticalAlignment.Centre) {
                Text("Callsign", modifier = Modifier.width(90f))
                TextField(
                    callsign,
                    { callsign = it },
                    Modifier.width(300f).testTag("callsign"),
                    placeholder = "type a name",
                    maxLength = 16,
                    initialFocus = true,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16f), verticalAlignment = VerticalAlignment.Centre) {
                Text("Volume", modifier = Modifier.width(90f))
                Slider(volume, { volume = it }, Modifier.testTag("volume"), step = 0.05f, length = 300f)
                Text("${(volume * 100).toInt()}%")
            }
            Bar(volume, Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(32f)) {
                Toggle(subtitles, { subtitles = it }, label = "Subtitles")
                Checkbox(hints, { hints = it }, label = "Show hints")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12f), verticalAlignment = VerticalAlignment.Centre) {
                Button("Launch", { launches++ }, Modifier.testTag("launch"))
                Button("Reset", { callsign = ""; volume = 0.6f; launches = 0 })
            }

            Text(
                when (launches) {
                    0 -> "Ready when you are."
                    else -> "Launched $launches time${if (launches == 1) "" else "s"} as ${callsign.ifEmpty { "nobody" }}" +
                        (if (subtitles) ", with subtitles." else ".")
                },
                colour = if (launches == 0) Muted else Colour.rgb(0x4CC2FF),
            )
        }
    }
}
