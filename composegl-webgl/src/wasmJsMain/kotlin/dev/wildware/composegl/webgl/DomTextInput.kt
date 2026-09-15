package dev.wildware.composegl.webgl

import dev.wildware.composegl.ui.backend.SoftKeyboard
import dev.wildware.composegl.ui.backend.TextInput
import dev.wildware.composegl.ui.backend.TextInputSession
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.text.EditCommand
import dev.wildware.composegl.ui.text.TextFieldValue
import dev.wildware.composegl.ui.text.TextRange
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.CompositionEvent
import org.w3c.dom.events.Event

/**
 * The browser's input methods, driving a text field: composition on a desktop, and a phone's own
 * keyboard.
 *
 * A canvas is not somewhere a browser will compose text into, or raise a keyboard for. A text box is,
 * so while a field has focus a small invisible one does: it is focused, the input method composes
 * into it, and what it composes is handed to the field — the provisional run as a preedit, drawn
 * underlined by the field, and the chosen text as ordinary typing. The box is emptied after every
 * change, so it never holds anything a player could lose.
 *
 * Plain typing on a hardware keyboard does not come through here: [DomKeyboardInput] sends it and
 * prevents it from reaching the box. What arrives here is what a keyboard event could not say — an
 * input method's composition, and a phone keyboard's `input` events, which report a key code of 229
 * and put the real text only in the box.
 *
 * @param sink where committed text, and a phone keyboard's backspace, go.
 * @param box the invisible text box — see [hiddenTextBox].
 * @param home what gets focus back when the field lets go, normally the canvas.
 */
class DomTextInput(
    private val sink: InputSink,
    val box: HTMLTextAreaElement,
    private val home: HTMLElement,
) : TextInput {

    private var current: TextInputSession? = null

    /** Whether an input method is part way through something. */
    var composing = false
        private set

    private val listeners = Listeners()

    fun attach() {
        listeners.add(box, "compositionstart") { composing = true }
        listeners.add(box, "compositionupdate") { composed(it.unsafeCast<CompositionEvent>().data) }
        listeners.add(box, "compositionend") { committed(it.unsafeCast<CompositionEvent>().data) }
        listeners.add(box, "input") { inputEvent(it) }
    }

    fun detach() = listeners.clear()

    override fun start(session: TextInputSession) {
        current = session
        box.value = ""
        focusQuietly(box)
    }

    override fun update(value: TextFieldValue) = Unit

    override fun stop(session: TextInputSession) {
        // Focus went straight from one field to the next, and the new one started first.
        if (current !== session) return
        current = null
        composing = false
        box.value = ""
        if (document.activeElement === box) focusQuietly(home)
    }

    /** The input method's provisional run changed. It replaces the last one whole. */
    fun composed(text: String) {
        composing = true
        val session = current ?: return
        session.edit(Composition.compose(text, codePointCount(text), session.value))
    }

    /** The input method made up its mind, or gave up: the preedit goes and what was chosen is typed. */
    fun committed(text: String) {
        composing = false
        box.value = ""
        val session = current
        if (session != null) session.edit(Composition.clear(session.value))
        if (text.isNotEmpty()) sink.onText(TextEvent(text))
    }

    private fun inputEvent(event: Event) {
        val type = inputType(event)
        val data = inputData(event)
        if (inputIsComposing(event)) return
        when (type) {
            // A phone keyboard's letter, which its keydown did not carry.
            "insertText", "insertReplacementText" -> if (!data.isNullOrEmpty()) sink.onText(TextEvent(data))
            // And its backspace, which is not a key at all as far as the page is told.
            "deleteContentBackward" -> tap(Key.Backspace)
            "deleteContentForward" -> tap(Key.Delete)
            "insertLineBreak" -> tap(Key.Enter)
        }
        box.value = ""
    }

    private fun tap(key: Key) {
        sink.onKey(KeyEvent(key, KeyEventType.Down))
        sink.onKey(KeyEvent(key, KeyEventType.Up))
    }

    companion object {

        /**
         * A text box nobody can see, laid over [near] so an input method's candidate window opens
         * beside the game rather than in a corner of the page.
         */
        fun hiddenTextBox(near: HTMLElement): HTMLTextAreaElement {
            val box = document.createElement("textarea") as HTMLTextAreaElement
            box.setAttribute("aria-hidden", "true")
            box.setAttribute("autocapitalize", "off")
            box.setAttribute("autocomplete", "off")
            box.setAttribute("spellcheck", "false")
            box.tabIndex = -1
            box.style.cssText = "position:absolute;opacity:0;width:1px;height:1px;padding:0;border:0;" +
                "margin:0;resize:none;overflow:hidden;pointer-events:none;"
            val bounds = near.getBoundingClientRect()
            box.style.left = "${bounds.left + window.scrollX}px"
            box.style.top = "${bounds.top + window.scrollY}px"
            (near.parentElement ?: document.body)?.appendChild(box)
            return box
        }
    }
}

/**
 * The on-screen keyboard, raised by giving the invisible text box focus — the only way a page has.
 *
 * [heightPixels] is how much of the page the keyboard covers, where the browser says: the visual
 * viewport shrinks when a keyboard comes up over it. Zero where it does not.
 */
class DomSoftKeyboard(private val box: HTMLTextAreaElement, private val home: HTMLElement) : SoftKeyboard {

    override var isVisible: Boolean = false
        private set

    override val heightPixels: Float
        get() = if (isVisible) (coveredCssPixels() * window.devicePixelRatio).toFloat() else 0f

    override fun show() {
        isVisible = true
        if (document.activeElement !== box) focusQuietly(box)
    }

    override fun hide() {
        isVisible = false
        if (document.activeElement === box) focusQuietly(home)
    }
}

/**
 * Turning what an input method is currently thinking into edits to a field.
 *
 * The browser's copy of the desktop backend's, deliberately not shared. A preedit is the provisional
 * run under the caret; the browser hands it over whole every time it changes, so each replaces the
 * last. Positions are code points, and turned into `Char`s here so a caret never lands inside a
 * surrogate pair.
 */
internal object Composition {

    fun compose(text: String, caretInCodePoints: Int, value: TextFieldValue): List<EditCommand> {
        if (text.isEmpty()) return clear(value)
        val target = value.composition ?: value.selection
        val start = target.min
        val caret = start + charOffset(text, caretInCodePoints)
        return listOf(
            EditCommand.Replace(target, text),
            EditCommand.SetSelection(TextRange(caret)),
            EditCommand.SetComposition(TextRange(start, start + text.length)),
        )
    }

    /** The provisional run goes. What was chosen arrives separately, as typing. */
    fun clear(value: TextFieldValue): List<EditCommand> {
        val composing = value.composition ?: return emptyList()
        return listOf(
            EditCommand.Replace(composing, ""),
            EditCommand.SetSelection(TextRange(composing.min)),
            EditCommand.SetComposition(null),
        )
    }

    private fun charOffset(text: String, codePoints: Int): Int {
        var at = 0
        var counted = 0
        while (at < text.length && counted < codePoints) {
            at += if (codePointAt(text, at) > 0xFFFF) 2 else 1
            counted++
        }
        return at
    }
}

internal fun codePointCount(text: String): Int {
    var count = 0
    text.forEachCodePoint { count++ }
    return count
}

/** Focus without scrolling the page to bring [element] into view. */
internal fun focusQuietly(element: HTMLElement): Unit = js("element.focus({ preventScroll: true })")

private fun inputType(event: Event): String = js("event.inputType || ''")

private fun inputData(event: Event): String? = js("event.data")

private fun inputIsComposing(event: Event): Boolean = js("event.isComposing === true")

private fun coveredCssPixels(): Double =
    js("window.visualViewport ? Math.max(0, window.innerHeight - window.visualViewport.height) : 0")
