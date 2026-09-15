package dev.wildware.composegl.webgl

import dev.wildware.composegl.ui.backend.Clipboard
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.input.TextEvent
import org.w3c.dom.clipboard.ClipboardEvent
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.KeyboardEvent

/**
 * The page's keyboard, translated into the toolkit's.
 *
 * Two streams, kept apart for the reason the desktop backends give: a **key** is a physical thing
 * going down and coming up — a shortcut, an arrow, Escape — and **text** is what the platform decided
 * was typed. A browser hands both over on the same `keydown`: `code` says which key, and `key` says
 * what it typed, after Shift and the layout. So one event can become a [KeyEvent] and a [TextEvent].
 *
 * Keys are named by `code`, the physical position, so WASD is WASD on a French keyboard too; text
 * comes from `key`, so the French player still types an `a` where the A is printed.
 *
 * A key the toolkit used, and every key that typed something, has its default prevented, so Tab
 * does not leave the canvas, Space does not scroll the page, and a hidden text box does not type the
 * character a second time. Everything else — reload, the developer tools — still reaches the browser.
 *
 * Paste is the one key that has to wait. A page cannot read the clipboard when it likes; it is handed
 * the contents in a `paste` event, which comes *after* the key. So Ctrl+V is held back until that
 * event has filled [DomClipboard] in, and only then sent, so the field reading the clipboard reads
 * what was just copied rather than what was there before.
 *
 * Composition — Japanese, Chinese, Korean, and a phone's own keyboard — is [DomTextInput]'s. A key
 * that arrives while an input method is composing is its business and is left alone here.
 *
 * @param sink where the translated events go.
 * @param clipboard where a paste's contents are put before Ctrl+V is sent. Null sends it at once.
 */
class DomKeyboardInput(
    private val sink: InputSink,
    private val clipboard: DomClipboard? = null,
    isMac: Boolean = platformIsMac(),
) {

    init {
        // The difference between Command-C and Control-C, which only the page can know.
        Modifiers.isMac = isMac
    }

    private val listeners = Listeners()

    /** A Ctrl+V waiting for the browser to say what was pasted. */
    private var waitingPaste: KeyEvent? = null

    /** Registers this on [target] — the canvas, and the hidden text box, which can each have focus. */
    fun attachTo(target: EventTarget) {
        listeners.add(target, "keydown") { keyDown(it.unsafeCast<KeyboardEvent>()) }
        listeners.add(target, "keyup") { keyUp(it.unsafeCast<KeyboardEvent>()) }
    }

    /** Registers the paste half on [target], normally the document, where a `paste` always arrives. */
    fun listenForPaste(target: EventTarget) {
        listeners.add(target, "paste") { pasted(it.unsafeCast<ClipboardEvent>()) }
    }

    fun detach() = listeners.clear()

    fun keyDown(event: KeyboardEvent): Boolean {
        // An input method is composing, and the key belongs to it.
        if (event.isComposing || event.keyCode == Composing) return false
        val modifiers = modifiers(event)
        val down = KeyEvent(named(event.code), KeyEventType.Down, modifiers, event.repeat)

        if (clipboard != null && modifiers.isPrimary && down.key == Key.V && !event.repeat) {
            // Not prevented: preventing it is what stops the browser firing the paste.
            waitingPaste = down
            return false
        }

        var used = sink.onKey(down)
        val typed = typedText(event, modifiers)
        if (typed != null) used = sink.onText(TextEvent(typed)) || used
        if (used || typed != null) event.preventDefault()
        return used
    }

    fun keyUp(event: KeyboardEvent): Boolean {
        if (event.isComposing || event.keyCode == Composing) return false
        val key = named(event.code)
        // No paste came — nothing on the page could take one. The key goes anyway, a little late.
        if (key == Key.V) sendWaitingPaste()
        val used = sink.onKey(KeyEvent(key, KeyEventType.Up, modifiers(event)))
        if (used) event.preventDefault()
        return used
    }

    /** The browser has said what was pasted: remember it, then send the Ctrl+V that was waiting. */
    fun pasted(event: ClipboardEvent) {
        val text = pastedText(event)
        if (text != null) clipboard?.remember(text)
        if (waitingPaste != null) {
            event.preventDefault()
            sendWaitingPaste()
        }
    }

    private fun sendWaitingPaste() {
        val waiting = waitingPaste ?: return
        waitingPaste = null
        sink.onKey(waiting)
    }

    /**
     * What a key typed, if it typed anything.
     *
     * One character, or one character outside the basic plane: a named key like `ArrowLeft` or
     * `Dead` is longer, and is not text. Control and Command make a shortcut rather than a letter —
     * except Control with Alt, which is how Windows spells AltGr and how a Polish player types ą.
     */
    private fun typedText(event: KeyboardEvent, modifiers: Modifiers): String? {
        val key = event.key
        val single = key.length == 1 || (key.length == 2 && key[0].isHighSurrogate() && key[1].isLowSurrogate())
        if (!single) return null
        if (key[0].code < 0x20 || key[0].code == 0x7F) return null
        val altGr = modifiers.control && modifiers.alt
        if ((modifiers.control || modifiers.meta) && !altGr) return null
        return key
    }

    private fun modifiers(event: KeyboardEvent): Modifiers {
        var bits = 0
        if (event.shiftKey) bits = bits or Modifiers.SHIFT
        if (event.ctrlKey) bits = bits or Modifiers.CONTROL
        if (event.altKey) bits = bits or Modifiers.ALT
        if (event.metaKey) bits = bits or Modifiers.META
        return Modifiers(bits)
    }

    private companion object {

        /** What `keyCode` is for every key an input method is holding on to. */
        const val Composing = 229

        /** A key by its `code`. Anything not listed is [Key.Unknown], so a strange key is still an event. */
        fun named(code: String): Key {
            keys[code]?.let { return it }
            if (code.length == 4 && code.startsWith("Key")) {
                val letter = code[3]
                if (letter in 'A'..'Z') return Key(letter - 'A' + 1)
            }
            if (code.length == 6 && code.startsWith("Digit")) digit(code[5])?.let { return it }
            // The number pad types the same digits, and nothing in an interface wants to know which.
            if (code.length == 7 && code.startsWith("Numpad")) digit(code[6])?.let { return it }
            if (code.startsWith("F")) code.drop(1).toIntOrNull()?.takeIf { it in 1..12 }?.let { return Key(49 + it) }
            return Key.Unknown
        }

        private fun digit(character: Char): Key? = if (character in '0'..'9') Key(30 + (character - '0')) else null

        val keys: Map<String, Key> = mapOf(
            "ArrowLeft" to Key.Left, "ArrowRight" to Key.Right, "ArrowUp" to Key.Up, "ArrowDown" to Key.Down,
            "Home" to Key.Home, "End" to Key.End, "PageUp" to Key.PageUp, "PageDown" to Key.PageDown,
            "Enter" to Key.Enter, "NumpadEnter" to Key.Enter, "Escape" to Key.Escape, "Tab" to Key.Tab,
            "Space" to Key.Space, "Backspace" to Key.Backspace, "Delete" to Key.Delete, "Insert" to Key.Insert,
            "ShiftLeft" to Key.Shift, "ShiftRight" to Key.Shift,
            "ControlLeft" to Key.Control, "ControlRight" to Key.Control,
            "AltLeft" to Key.Alt, "AltRight" to Key.Alt,
            "MetaLeft" to Key.Meta, "MetaRight" to Key.Meta, "OSLeft" to Key.Meta, "OSRight" to Key.Meta,
            "CapsLock" to Key.CapsLock,
            "Minus" to Key.Minus, "Equal" to Key.Equals, "BracketLeft" to Key.LeftBracket,
            "BracketRight" to Key.RightBracket, "Backslash" to Key.Backslash, "Semicolon" to Key.Semicolon,
            "Quote" to Key.Apostrophe, "Backquote" to Key.Grave, "Comma" to Key.Comma,
            "Period" to Key.Period, "Slash" to Key.Slash,
            "BrowserBack" to Key.Back,
        )
    }
}

/**
 * The clipboard, as far as a page may have one.
 *
 * A page cannot read the system clipboard whenever it likes — only when the player pastes, when it is
 * handed the text in an event. So reading answers with the last text this page knows was on it: what
 * the player pasted most recently, or what the interface copied. [DomKeyboardInput] makes sure a paste
 * is known before the Ctrl+V that asked for it is sent, so a field pasting reads the right thing.
 *
 * Writing goes to the real clipboard as well, when the browser allows it — a copy made on a key
 * press does — so what was copied in the game can be pasted in another tab.
 */
class DomClipboard(private var contents: String? = null) : Clipboard {

    override fun read(): String? = contents?.takeIf { it.isNotEmpty() }

    override fun write(text: String) {
        contents = text
        writeSystemClipboard(text)
    }

    /** What a paste event said was on the clipboard. */
    fun remember(text: String) {
        contents = text
    }
}

private fun pastedText(event: ClipboardEvent): String? = js("event.clipboardData ? event.clipboardData.getData('text/plain') : null")

private fun writeSystemClipboard(text: String): JsAny? =
    js("(navigator.clipboard && navigator.clipboard.writeText) ? navigator.clipboard.writeText(text).catch(() => null) : null")

internal fun platformIsMac(): Boolean = js("/Mac|iPhone|iPad/.test(navigator.platform || '')")
