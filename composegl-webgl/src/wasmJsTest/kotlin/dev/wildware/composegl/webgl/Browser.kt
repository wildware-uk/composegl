package dev.wildware.composegl.webgl

import dev.wildware.composegl.testing.comparePictures
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.text.TextStyle
import kotlinx.browser.document
import kotlinx.coroutines.await
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.Uint8ClampedArray
import org.khronos.webgl.WebGLRenderingContext as GL
import org.khronos.webgl.get
import org.khronos.webgl.set
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.EventTarget
import kotlin.js.Promise
import kotlin.test.fail

/**
 * What the browser tests share: a canvas on the page, the test font, real DOM events, reading a frame
 * back, and the goldens — which a page cannot open or write, so the Karma server does it for it. See
 * `karma.config.d/composegl.js`.
 */

/** Every size a test or a default skin might ask for. The browser draws any; the registry wants them named. */
val TestSizes = (8..48).toList()

private var fontLoaded = false

/** DejaVu Sans, the font the desktop goldens were drawn in, as `body` and as the toolkit's default family. */
suspend fun testFonts(): WebFonts {
    val fonts = WebFonts()
    if (!fontLoaded) {
        fonts.load("body", "/composegl/resources/fonts/DejaVuSans.ttf", TestSizes)
        fontLoaded = true
    } else {
        fonts.registerCss("body", "composegl-body", TestSizes)
    }
    if (TextStyle.Default.family != "body") fonts.registerCss(TextStyle.Default.family, "composegl-body", TestSizes)
    return fonts
}

/** A canvas [width] by [height] CSS pixels, on the page, with a backing store the same size. */
fun pageCanvas(width: Int, height: Int): HTMLCanvasElement {
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.width = width
    canvas.height = height
    canvas.style.cssText = "position:absolute;left:10px;top:20px;width:${width}px;height:${height}px;"
    document.body!!.appendChild(canvas)
    return canvas
}

/** The whole drawing buffer as `0xRRGGBB`, the top row first. */
fun readFrame(gl: GL, width: Int, height: Int): IntArray {
    val bytes = Uint8Array(width * height * 4)
    gl.readPixels(0, 0, width, height, GL.RGBA, GL.UNSIGNED_BYTE, bytes)
    return IntArray(width * height) { at ->
        val from = ((height - 1 - at / width) * width + at % width) * 4
        (bytes[from].toInt() and 0xFF shl 16) or (bytes[from + 1].toInt() and 0xFF shl 8) or (bytes[from + 2].toInt() and 0xFF)
    }
}

fun red(rgb: Int) = rgb shr 16 and 0xFF
fun green(rgb: Int) = rgb shr 8 and 0xFF
fun blue(rgb: Int) = rgb and 0xFF

/**
 * Fails unless [actual] matches the golden called [name], by the rule every backend's goldens are held
 * to. With `COMPOSEGL_UPDATE_GOLDENS=1` it writes the golden instead. A failure leaves the actual and
 * a difference map in `build/screenshots`.
 *
 * @param desktop compare with the raw OpenGL backend's golden rather than this backend's own.
 */
suspend fun assertMatchesGolden(name: String, width: Int, height: Int, actual: IntArray, desktop: Boolean = false) {
    if (!desktop && fetchText("/composegl/updating").await<JsString>().toString() == "1") {
        post("/composegl/pictures/golden/$name", pngDataUrl(width, height, actual)).await<JsAny?>()
        return
    }
    val url = if (desktop) "/composegl/desktop-goldens/$name.png" else "/composegl/resources/goldens/$name.png"
    val decoded = decodePng(url).await<Uint8ClampedArray?>()
        ?: fail("there is no golden at $url; run the tests with COMPOSEGL_UPDATE_GOLDENS=1 to write one")
    val expected = IntArray(width * height) { at ->
        (decoded[at * 4].toInt() and 0xFF shl 16) or (decoded[at * 4 + 1].toInt() and 0xFF shl 8) or (decoded[at * 4 + 2].toInt() and 0xFF)
    }
    val comparison = comparePictures(width, height, expected, actual)
    if (comparison.passes) return
    val label = if (desktop) "$name-desktop" else name
    post("/composegl/pictures/actual/$label", pngDataUrl(width, height, actual)).await<JsAny?>()
    post("/composegl/pictures/difference/$label", pngDataUrl(width, height, comparison.difference)).await<JsAny?>()
    fail("\"$label\" does not match its golden: ${comparison.summary}. See build/screenshots")
}

private fun pngDataUrl(width: Int, height: Int, pixels: IntArray): String {
    val rgba = Uint8ClampedArray(width * height * 4)
    // Whole numbers rather than bytes: a clamped array turns a byte over 127, read as negative, into 0.
    for (at in 0 until width * height) setOpaque(rgba, at * 4, pixels[at])
    return encodePng(width, height, rgba)
}

private fun setOpaque(rgba: Uint8ClampedArray, at: Int, rgb: Int): Unit =
    js("{ rgba[at] = (rgb >> 16) & 255; rgba[at + 1] = (rgb >> 8) & 255; rgba[at + 2] = rgb & 255; rgba[at + 3] = 255; }")

private fun encodePng(width: Int, height: Int, rgba: Uint8ClampedArray): String = js(
    """(() => { const c = document.createElement('canvas'); c.width = width; c.height = height; c.getContext('2d').putImageData(new ImageData(rgba, width, height), 0, 0); return c.toDataURL('image/png'); })()""",
)

private fun decodePng(url: String): Promise<Uint8ClampedArray?> = js(
    """fetch(url).then(r => r.ok ? r.blob().then(b => createImageBitmap(b, { premultiplyAlpha: 'none', colorSpaceConversion: 'none' })).then(i => { const c = document.createElement('canvas'); c.width = i.width; c.height = i.height; const x = c.getContext('2d'); x.drawImage(i, 0, 0); return x.getImageData(0, 0, i.width, i.height).data; }) : null)""",
)

private fun fetchText(url: String): Promise<JsString> = js("fetch(url).then(r => r.text())")

private fun post(url: String, body: String): Promise<JsAny?> = js("fetch(url, { method: 'POST', body: body }).then(r => r.text())")

// --- real DOM events, dispatched the way the browser dispatches its own -----------------------------

/** A pointer event at [x], [y] inside [canvas]'s box. Returns false when a listener prevented it. */
fun pointer(
    canvas: HTMLCanvasElement,
    type: String,
    x: Double,
    y: Double,
    button: Int = 0,
    buttons: Int = 0,
    pointerType: String = "mouse",
    pointerId: Int = 1,
): Boolean {
    val box = canvas.getBoundingClientRect()
    return dispatchPointer(canvas, type, box.left + x, box.top + y, button, buttons, pointerType, pointerId)
}

private fun dispatchPointer(
    target: EventTarget,
    type: String,
    clientX: Double,
    clientY: Double,
    button: Int,
    buttons: Int,
    pointerType: String,
    pointerId: Int,
): Boolean = js(
    "target.dispatchEvent(new PointerEvent(type, { bubbles: true, cancelable: true, clientX: clientX, clientY: clientY, button: button, buttons: buttons, pointerType: pointerType, pointerId: pointerId, isPrimary: true }))",
)

/** A mouse click at [x], [y]: down, then up. */
fun click(canvas: HTMLCanvasElement, x: Double, y: Double, pointerType: String = "mouse") {
    pointer(canvas, "pointermove", x, y, pointerType = pointerType)
    pointer(canvas, "pointerdown", x, y, button = 0, buttons = 1, pointerType = pointerType)
    pointer(canvas, "pointerup", x, y, button = 0, buttons = 0, pointerType = pointerType)
}

fun wheel(canvas: HTMLCanvasElement, x: Double, y: Double, deltaY: Double, deltaMode: Int = 0): Boolean {
    val box = canvas.getBoundingClientRect()
    return dispatchWheel(canvas, box.left + x, box.top + y, deltaY, deltaMode)
}

private fun dispatchWheel(target: EventTarget, clientX: Double, clientY: Double, deltaY: Double, deltaMode: Int): Boolean = js(
    "target.dispatchEvent(new WheelEvent('wheel', { bubbles: true, cancelable: true, clientX: clientX, clientY: clientY, deltaY: deltaY, deltaMode: deltaMode }))",
)

/** A keyboard event. [key] is what it typed or its name; [code] is the physical key. */
fun key(
    target: EventTarget,
    type: String,
    key: String,
    code: String,
    shift: Boolean = false,
    control: Boolean = false,
    alt: Boolean = false,
    meta: Boolean = false,
    repeat: Boolean = false,
    composing: Boolean = false,
): Boolean = js(
    "target.dispatchEvent(new KeyboardEvent(type, { bubbles: true, cancelable: true, key: key, code: code, shiftKey: shift, ctrlKey: control, altKey: alt, metaKey: meta, repeat: repeat, isComposing: composing }))",
)

/** A key pressed and let go. */
fun tap(target: EventTarget, key: String, code: String, shift: Boolean = false, control: Boolean = false) {
    key(target, "keydown", key, code, shift = shift, control = control)
    key(target, "keyup", key, code, shift = shift, control = control)
}

/** The browser's paste event, with [text] on the clipboard. */
fun paste(target: EventTarget, text: String): Boolean = js(
    "(() => { const d = new DataTransfer(); d.setData('text/plain', text); return target.dispatchEvent(new ClipboardEvent('paste', { bubbles: true, cancelable: true, clipboardData: d })); })()",
)

fun composition(target: EventTarget, type: String, data: String): Boolean =
    js("target.dispatchEvent(new CompositionEvent(type, { bubbles: true, data: data }))")

fun input(target: EventTarget, inputType: String, data: String?): Boolean =
    js("target.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: inputType, data: data }))")

fun activeElement(): JsAny? = js("document.activeElement")

/** The window losing focus: the player switched tab or clicked another application. */
fun blurWindow(): Unit = js("{ window.dispatchEvent(new FocusEvent('blur')); }")

// --- pads -----------------------------------------------------------------------------------------

/**
 * A pad as the Gamepad API reports one, with the standard layout's seventeen buttons. [pressed] names
 * the buttons held by index; [values] overrides a button's analogue value, for a trigger.
 */
fun pad(
    index: Int = 0,
    pressed: Set<Int> = emptySet(),
    values: Map<Int, Double> = emptyMap(),
    axes: List<Double> = listOf(0.0, 0.0, 0.0, 0.0),
    mapping: String = "standard",
): JsGamepad {
    val buttons = (0 until 17).joinToString(",") { at ->
        val value = values[at] ?: if (at in pressed) 1.0 else 0.0
        "{\"pressed\":${at in pressed},\"value\":$value}"
    }
    return parsePad("{\"index\":$index,\"mapping\":\"$mapping\",\"connected\":true,\"buttons\":[$buttons],\"axes\":[${axes.joinToString(",")}]}")
}

private fun parsePad(json: String): JsGamepad = js("JSON.parse(json)")

fun padSlots(vararg pads: JsGamepad?): JsArray<JsGamepad?> {
    val slots = JsArray<JsGamepad?>()
    pads.forEachIndexed { at, pad -> slots[at] = pad }
    return slots
}

/** Everything a translator sent, in order, answering [answer] to each. */
class RecordingSink(var answer: Boolean = true) : InputSink {
    val events = mutableListOf<Any>()
    override fun onPointer(event: PointerEvent) = answer.also { events += event }
    override fun onKey(event: KeyEvent) = answer.also { events += event }
    override fun onText(event: TextEvent) = answer.also { events += event }
    override fun onGamepad(event: GamepadEvent) = answer.also { events += event }
}
