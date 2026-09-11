package uk.wildware.composegl.robovm

import org.robovm.apple.coregraphics.CGRect
import org.robovm.apple.uikit.UIControl
import org.robovm.apple.uikit.UIControlEvents
import org.robovm.apple.uikit.UIReturnKeyType
import org.robovm.apple.uikit.UITextAutocapitalizationType
import org.robovm.apple.uikit.UITextAutocorrectionType
import org.robovm.apple.uikit.UITextField
import org.robovm.apple.uikit.UITextSpellCheckingType
import org.robovm.apple.uikit.UIView
import uk.wildware.composegl.ui.backend.TextInput
import uk.wildware.composegl.ui.backend.TextInputSession
import uk.wildware.composegl.ui.text.TextFieldValue
import uk.wildware.composegl.ui.text.TextRange

/**
 * The iPhone's own keyboard, driving a text field.
 *
 * The same gap `AndroidTextInput` fills, on the other phone. An engine hands the toolkit one
 * character per key press, which is enough for English and wrong for everything else — autocorrect
 * never fires, a swiped word arrives as nothing, dictation is impossible, and Japanese cannot be
 * typed at all.
 *
 * The approach here is the opposite of Android's, on purpose. Android gives you an
 * `InputConnection` — the keyboard asks, and the toolkit answers, and the toolkit keeps the only
 * real copy of the text. iOS gives you a whole `UITextField`, so the cheapest correct thing is to
 * put a real one on screen at zero size, let UIKit do the editing in it, and copy what it says
 * into the toolkit's field. Everything UIKit already knows how to do — autocorrect, swipe,
 * dictation, and the marked text that assembling a kanji is made of — then works because it is
 * genuinely UIKit doing it.
 *
 * ```kotlin
 * val input = UiKitTextInput(controller.view)
 * ProvideTextInput(input) { Hud() }
 * ```
 *
 * @param parent the view to hang the invisible field off — the engine's GL view, or the view
 *   controller's own.
 * @param onRenderThread how to get back onto the thread the interface is composed on. UIKit calls
 *   in on the main thread; a game drawing on its own thread **must** pass this, or two threads
 *   edit one field. The default runs the work where it arrived, which is right only for a game
 *   whose loop is the main thread.
 */
class UiKitTextInput(
    private val parent: UIView,
    private val onRenderThread: (Runnable) -> Unit = { it.run() },
) : TextInput {

    /** The field the keyboard is pointed at, or null. Written and read on the main thread. */
    private var current: TextInputSession? = null

    /**
     * What this last pushed into UIKit.
     *
     * Setting `text` on a `UITextField` makes it report a change, which would come straight back
     * here and be applied again. Remembering what was pushed is how that loop is cut.
     */
    private var pushed: String? = null

    private val field: UITextField by lazy {
        UITextField(CGRect.Zero()).also { made ->
            // On, because they are the whole reason for going through the platform rather than
            // reading key presses.
            made.autocorrectionType = UITextAutocorrectionType.Yes
            made.spellCheckingType = UITextSpellCheckingType.Yes
            made.autocapitalizationType = UITextAutocapitalizationType.Sentences
            made.isHidden = true
            made.addOnEditingChangedListener { _ -> onChanged() }
            made.addOnEditingDidEndOnExitListener { _ -> onReturn() }
            parent.addSubview(made)
        }
    }

    override fun start(session: TextInputSession) {
        current = session
        field.returnKeyType = if (session.multiline) UIReturnKeyType.Default else UIReturnKeyType.Done
        push(session.value)
        field.isHidden = false
        field.becomeFirstResponder()
    }

    override fun update(value: TextFieldValue) {
        // Something other than the keyboard changed the text — a paste, the game, a tap that moved
        // the caret. UIKit is holding the old string and would edit by offsets into it.
        if (current == null) return
        if (value.text != field.text) push(value)
    }

    override fun stop(session: TextInputSession) {
        // Not ours: focus went straight from one field to the next, and the new field started
        // before the old one stopped. Resigning here would close the new field's keyboard.
        if (current !== session) return
        current = null
        pushed = null
        field.resignFirstResponder()
        field.isHidden = true
    }

    /** Puts the toolkit's text into UIKit's field, without reading the echo back. */
    private fun push(value: TextFieldValue) {
        pushed = value.text
        field.text = value.text
    }

    /** UIKit edited its field. Work out what changed and tell the toolkit's. */
    private fun onChanged() {
        val session = current ?: return
        val text = field.text.orEmpty()
        // Our own push coming back. Applying it would be harmless and pointless; ignoring it keeps
        // the field from recomposing for nothing.
        if (text == pushed) {
            pushed = null
            return
        }
        pushed = null

        val commands = Mirror.commands(
            value = session.value,
            text = text,
            selection = caret(text),
            composition = marked(),
        )
        if (commands.isNotEmpty()) onRenderThread(Runnable { session.edit(commands) })
    }

    private fun onReturn() {
        val session = current ?: return
        onRenderThread(Runnable { session.submit() })
    }

    /**
     * Where UIKit's caret is, in characters from the start.
     *
     * `UITextPosition` is deliberately opaque — it is not a number and cannot be treated as one —
     * so the offset has to be asked for rather than read.
     */
    private fun caret(text: String): TextRange {
        val range = field.selectedTextRange ?: return TextRange(text.length)
        val start = field.getOffset(field.beginningOfDocument, range.start)
        val end = field.getOffset(field.beginningOfDocument, range.end)
        return TextRange(start.toInt(), end.toInt())
    }

    /** The provisional run, if UIKit is in the middle of assembling something. */
    private fun marked(): TextRange? {
        val range = field.markedTextRange ?: return null
        val start = field.getOffset(field.beginningOfDocument, range.start)
        val end = field.getOffset(field.beginningOfDocument, range.end)
        return if (start == end) null else TextRange(start.toInt(), end.toInt())
    }
}
