package composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import composegl.ui.backend.Clipboard
import composegl.ui.backend.SoftKeyboard
import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.graphics.Colour
import composegl.ui.graphics.UiCanvas
import composegl.ui.input.InteractionState
import composegl.ui.input.Key
import composegl.ui.input.KeyEventType
import composegl.ui.input.KeyHandler
import composegl.ui.input.PointerEvent
import composegl.ui.input.PointerHandler
import composegl.ui.input.TextHandler
import composegl.ui.layout.Box
import composegl.ui.layout.Constraints
import composegl.ui.layout.LeafLayout
import composegl.ui.layout.Measurable
import composegl.ui.layout.MeasurePolicy
import composegl.ui.layout.MeasureResult
import composegl.ui.layout.MeasureScope
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.clip
import composegl.ui.modifier.drawBehind
import composegl.ui.modifier.fillMaxWidth
import composegl.ui.modifier.focusable
import composegl.ui.modifier.interaction
import composegl.ui.modifier.onKeyEvent
import composegl.ui.modifier.onPointer
import composegl.ui.modifier.onTextEvent
import composegl.ui.modifier.styled as styledWith
import composegl.ui.skin.ResolvedStyle
import composegl.ui.skin.rememberStates
import composegl.ui.backend.TextInput
import composegl.ui.backend.TextInputSession
import composegl.ui.skin.rememberStyle
import composegl.ui.text.EditCommand
import composegl.ui.text.FontProvider
import composegl.ui.text.KeyboardEditor
import composegl.ui.text.TextFieldValue
import composegl.ui.text.TextGestures
import composegl.ui.text.TextLayout
import composegl.ui.text.TextRange
import composegl.ui.text.TextStyle
import composegl.ui.text.apply
import composegl.ui.text.graphemeAfter

/** The system clipboard, for the fields inside it. A game provides its backend's. */
val LocalClipboard: ProvidableCompositionLocal<Clipboard> = staticCompositionLocalOf { Clipboard.None }

@Composable
fun ProvideClipboard(clipboard: Clipboard, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalClipboard provides clipboard, content = content)

/**
 * The on-screen keyboard, for the fields inside it. A game provides its backend's.
 *
 * The default does nothing, which is the right answer on a desktop: the keyboard is already there.
 */
val LocalSoftKeyboard: ProvidableCompositionLocal<SoftKeyboard> =
    staticCompositionLocalOf { SoftKeyboard.None }

@Composable
fun ProvideSoftKeyboard(keyboard: SoftKeyboard, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalSoftKeyboard provides keyboard, content = content)

/**
 * The platform's input method, for the fields inside it. A game provides its backend's.
 *
 * The default is none, which is correct anywhere a key event is the whole story. See [TextInput]
 * for why that is not everywhere.
 */
val LocalTextInput: ProvidableCompositionLocal<TextInput> = staticCompositionLocalOf { TextInput.None }

@Composable
fun ProvideTextInput(input: TextInput, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalTextInput provides input, content = content)

/**
 * Somewhere to type.
 *
 * A name box, a seed, a server address, a search. Everything it is made of was built and tested
 * before it: the editing model, the movement rules, the keyboard and the pointer gestures. What
 * this adds is the part that has to be seen — a caret that blinks and stops blinking while you
 * type, the selection drawn behind the glyphs, and the field scrolling sideways so the caret is
 * never off the edge of it.
 *
 * It is usable with a keyboard alone, which is the point of the acceptance test: focus it with Tab,
 * type, move by word, select with shift, cut and paste, and leave with Tab. Nothing here needs a
 * mouse, and Tab is deliberately *not* the field's, so a pad or a keyboard can always get out.
 *
 * Five skin names. `"field"` is the box and the text colour, with its `focused` and `disabled`
 * states; `"field.placeholder"` is the colour of the hint; `"field.selection"` is the highlight;
 * `"field.caret"` is the caret; `"field.composition"` is the underline under text an input method
 * has not committed yet. A game restyles all of it without touching this file.
 *
 * @param value what the field says, and where the caret and selection are. The overload taking a
 *   plain [String] is the easy one; this is for a game that wants to move the caret itself.
 * @param placeholder what to show when it is empty. Not a label: it disappears the moment there is
 *   something to read, so anything a player needs to know while typing belongs beside the field.
 * @param maxLength how many characters it will hold, or zero for no limit. Typing at the limit does
 *   nothing and a paste fills up whatever room is left, rather than the whole paste being refused.
 * @param multiline whether Enter makes a line. A single-line field leaves Enter for whatever is
 *   around it, which is how a form's default button works.
 * @param onSubmit called by Enter in a single-line field, for that default button.
 */
@Composable
fun TextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    style: String = "field",
    placeholder: String? = null,
    enabled: Boolean = true,
    multiline: Boolean = false,
    maxLength: Int = 0,
    initialFocus: Boolean = false,
    onSubmit: (() -> Unit)? = null,
    clipboard: Clipboard = LocalClipboard.current,
    softKeyboard: SoftKeyboard = LocalSoftKeyboard.current,
    textInput: TextInput = LocalTextInput.current,
    interaction: InteractionState = remember { InteractionState() },
) {
    val resolved = rememberStyle(style, rememberStates(interaction, enabled))
    val hint = rememberStyle("$style.placeholder")
    val highlight = rememberStyle("$style.selection")
    val caret = rememberStyle("$style.caret")
    val composing = rememberStyle("$style.composition")
    val fonts = rememberFonts()

    val editor = remember(multiline, maxLength, clipboard) {
        KeyboardEditor(multiline, clipboard, maxLength)
    }
    val change by rememberUpdatedState(onValueChange)
    val submit by rememberUpdatedState(onSubmit)

    // What the next keystroke edits. Not the `value` parameter: several events arrive between two
    // compositions whenever a player types quickly or holds a key, and editing the composition's
    // value each time would keep only the last of them.
    val session = remember { EditSession(value) }
    session.sync(value)

    // Where the text has been scrolled to. Not snapshot state: it is worked out during layout, from
    // the room layout turned out to have, and writing snapshot state there would ask for another
    // composition every time the caret moved.
    val view = remember { FieldView() }

    val metrics = remember(value.text, resolved.textStyle, fonts, multiline) {
        FieldMetrics(fonts, resolved.textStyle, value.text, multiline)
    }

    // A phone's keyboard comes up with the field and goes away with it. On a desktop this is two
    // calls that do nothing, which is why a field never asks what it is running on.
    val wantsKeyboard = interaction.isFocused && enabled
    DisposableEffect(softKeyboard, wantsKeyboard) {
        if (wantsKeyboard) softKeyboard.show()
        // Whether it was raised is decided here rather than read back on the way out: by the time
        // this runs, focus has already gone, and a field leaving the screen while focused — a
        // dialogue closing over one — has to put the keyboard away on its way out too.
        onDispose { if (wantsKeyboard) softKeyboard.hide() }
    }

    // The platform's own input method, for the languages a key event cannot express and for a
    // phone's autocorrect. It is handed the same EditCommands the keyboard sends, so nothing below
    // here finds out that an input method exists.
    val ime = remember(session, multiline, maxLength, enabled) {
        object : TextInputSession {
            override val value: TextFieldValue get() = session.value
            override val multiline: Boolean get() = multiline

            override fun edit(commands: List<EditCommand>) {
                if (!enabled) return
                // Applied as one edit: an input method means a batch as a batch, and a field that
                // told the game about each half separately would report a value that never was.
                var after = session.value
                for (command in commands) {
                    val next = after.apply(command)
                    // At the limit, typing does nothing — the same rule the keyboard follows.
                    // Never refuse the whole batch: an input method that has its edit dropped
                    // silently goes on believing it happened.
                    if (maxLength > 0 && next.text.length > maxLength && next.text.length > after.text.length) continue
                    after = next
                }
                if (after != session.value) session.emit(after, change)
            }

            override fun submit() {
                submit?.invoke()
            }
        }
    }

    DisposableEffect(textInput, wantsKeyboard, ime) {
        if (wantsKeyboard) textInput.start(ime)
        onDispose { if (wantsKeyboard) textInput.stop(ime) }
    }

    // An input method keeps its own copy of the text, so anything that changed the field from
    // somewhere else — a key, a paste, a click, the game — has to be reported back to it.
    // Without this, a phone's autocorrect happily replaces a word that is no longer there.
    LaunchedEffect(textInput, wantsKeyboard, value) {
        if (wantsKeyboard) textInput.update(value)
    }

    // The caret is solid for a moment after every change, and blinks after that. Restarting the
    // effect on the value is what makes a field being typed into show a steady caret rather than
    // one that winks out mid-word.
    var blinking by remember { mutableStateOf(true) }
    LaunchedEffect(interaction.isFocused, value) {
        blinking = true
        if (!interaction.isFocused) return@LaunchedEffect
        var started = 0L
        while (true) {
            withFrameNanos { now ->
                if (started == 0L) started = now
                val phase = (now - started) / BlinkNanos
                blinking = phase % 2L == 0L
            }
        }
    }

    val painter = remember(
        metrics,
        resolved,
        hint,
        highlight,
        caret,
        composing,
        value.selection,
        value.composition,
        placeholder,
        blinking,
        interaction.isFocused,
        view,
    ) {
        FieldPainter(
            metrics = metrics,
            style = resolved,
            placeholder = placeholder,
            placeholderColour = hint.textColour,
            highlight = highlight,
            caretStyle = caret,
            composingStyle = composing,
            selection = value.selection,
            composition = value.composition,
            focused = interaction.isFocused,
            caretShowing = interaction.isFocused && blinking,
            view = view,
            multiline = multiline,
        )
    }

    val gestures = remember(view) { TextGestures(indexAt = { view.indexAt(it) }) }

    // Nothing is painted: this is how the widget learns where it is. First in the chain, because
    // that is the one place it is handed the whole widget rather than whatever a padding before it
    // has left — and remembered, so the chain still compares equal from one frame to the next.
    val locate = remember(view) { locator(view) }

    val keys = remember(editor, enabled, view, session) {
        KeyHandler { event ->
            // Every key says which modifiers were held, and a pointer event says nothing at all, so
            // this is where a shift-click learns that shift is down.
            view.shiftHeld = event.modifiers.shift
            if (!enabled || event.type != KeyEventType.Down) false
            else {
                val after = editor.onKey(event, session.value)
                when {
                    after != null -> {
                        if (after != session.value) session.emit(after, change)
                        true
                    }
                    event.key == Key.Enter && submit != null -> {
                        submit?.invoke()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    val typing = remember(editor, enabled, session) {
        TextHandler { event ->
            if (!enabled) false
            else editor.onText(event, session.value)?.also { session.emit(it, change) } != null
        }
    }

    val pointer = remember(gestures, enabled, view, session) {
        PointerHandler { event ->
            if (!enabled) false
            else {
                val wasDragging = gestures.isDragging
                gestures.onPointer(event, session.value, view.shiftHeld)?.let { session.emit(it, change) }
                event is PointerEvent.Press || wasDragging
            }
        }
    }

    Box(
        modifier = Modifier
            .drawBehind(locate)
            .then(modifier)
            .interaction(interaction)
            .focusable(interaction, enabled = enabled, initial = initialFocus)
            .onKeyEvent(keys)
            .onTextEvent(typing)
            .onPointer(pointer)
            .styledWith(resolved)
            .clip(),
    ) {
        // Wide, but only as tall as its lines. A field that filled the height it was offered would
        // eat a column, and a field is one line high unless it is a multi-line one.
        LeafLayout(Modifier.fillMaxWidth(), name = "field", measurePolicy = painter, draw = painter.draw)
    }
}

/** The easy one: a string in, a string out, and the caret looked after for you. */
@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: String = "field",
    placeholder: String? = null,
    enabled: Boolean = true,
    multiline: Boolean = false,
    maxLength: Int = 0,
    initialFocus: Boolean = false,
    onSubmit: (() -> Unit)? = null,
    clipboard: Clipboard = LocalClipboard.current,
    softKeyboard: SoftKeyboard = LocalSoftKeyboard.current,
    textInput: TextInput = LocalTextInput.current,
    interaction: InteractionState = remember { InteractionState() },
) {
    // The caret lives here, because a plain string cannot carry one. A game that sets the text from
    // outside gets the caret put at the end of whatever it set, which is where a player expects it.
    var held by remember { mutableStateOf(TextFieldValue(value)) }
    if (held.text != value) held = TextFieldValue(value, TextRange(value.length))

    TextField(
        value = held,
        onValueChange = {
            held = it
            if (it.text != value) onValueChange(it.text)
        },
        modifier = modifier,
        style = style,
        placeholder = placeholder,
        enabled = enabled,
        multiline = multiline,
        maxLength = maxLength,
        initialFocus = initialFocus,
        onSubmit = onSubmit,
        clipboard = clipboard,
        softKeyboard = softKeyboard,
        textInput = textInput,
        interaction = interaction,
    )
}

/** Records where the field is on the screen, every frame, without drawing anything. */
private fun locator(view: FieldView): UiCanvas.(Rect) -> Unit = { bounds -> view.node = bounds.topLeft }

/** Half a second on, half a second off, which is what every platform has settled on. */
private const val BlinkNanos = 500_000_000L

/** How thick the composing underline is, and how far above the bottom of the line it sits. */
private const val UnderlineThickness = 1.5f
private const val UnderlineInset = 2f

/** How wide the caret is drawn. Not from the skin: a caret is a hairline everywhere. */
private const val CaretWidth = 1.5f

/**
 * What the field is editing, between one composition and the next.
 *
 * A field is told its value by whatever owns it, and is told again only when that owner has had a
 * composition to react. Events do not wait for compositions: a player typing quickly, a held key
 * repeating, an input method committing a word — all deliver several events inside one frame. Each
 * of those has to edit the result of the one before it, or a burst of five characters leaves one.
 *
 * So this holds what was last sent out, and [sync] hands control back the moment the owner disagrees
 * with it: an owner that filters what a field may hold, or refuses a change outright, wins at its
 * next composition rather than being quietly overruled by the field's own copy.
 */
private class EditSession(private var current: TextFieldValue) {

    private var emitted: TextFieldValue? = null

    val value: TextFieldValue get() = current

    /** Called during composition with what the owner now says the value is. */
    fun sync(value: TextFieldValue) {
        if (value != emitted) {
            current = value
            emitted = null
        }
    }

    fun emit(value: TextFieldValue, change: (TextFieldValue) -> Unit) {
        current = value
        emitted = value
        change(value)
    }
}

/**
 * Where the text has been scrolled to, and how to turn a point into a character.
 *
 * It survives every recomposition, because it is what the *last* layout worked out and the pointer
 * needs it before the next one. Nothing here is snapshot state: layout writes it, drawing and the
 * pointer read it, and none of that should cause a composition.
 */
private class FieldView {

    var scrollX = 0f
    var scrollY = 0f

    /** Whether shift is down, learned from the keys, so a shift-click can extend a selection. */
    var shiftHeld = false

    /** Where the text starts on the screen, once the skin's padding is taken off. */
    var origin = Offset(0f, 0f)

    /** Where the widget itself starts on the screen. What a pointer's position is measured from. */
    var node = Offset(0f, 0f)

    var metrics: FieldMetrics? = null

    /** Which character a point in the widget is over. Clamped, so a drag off the edge still works. */
    fun indexAt(point: Offset): Int {
        val metrics = metrics ?: return 0
        // A handler is given a point in its own widget's coordinates and the text was drawn in the
        // screen's, so the widget's own corner is what turns one into the other. Without it a field
        // anywhere but the top-left corner of the screen puts every caret at the start of its text.
        val onScreen = point + node
        return metrics.indexAt(Offset(onScreen.x - origin.x + scrollX, onScreen.y - origin.y + scrollY))
    }
}

/**
 * Where every character is.
 *
 * Worked out by measuring prefixes rather than by asking the backend for glyph positions, because
 * the backend contract is deliberately two methods wide and every backend already keeps the promise
 * this needs: measuring the same text twice gives the same answer. The widths are cached per
 * character, so a field costs a handful of measurements when its text changes and none when it does
 * not.
 *
 * The one thing it gives up is kerning: where a backend draws `AV` closer together than it measures
 * `A` and `V` separately, the caret between them is a fraction out. No backend in this project does,
 * and the fix when one does is a wider `TextLayout`, not a different widget.
 *
 * A multi-line field breaks lines where the text says and nowhere else — there is no soft wrapping
 * in a field. A wrapping editor needs the line boxes only a shaper can give.
 */
internal class FieldMetrics(
    private val fonts: FontProvider,
    private val style: TextStyle,
    val text: String,
    multiline: Boolean,
) {

    /** Each line's stretch of the text, without its ending. One line when the field is single. */
    val lines: List<TextRange> = if (!multiline) listOf(TextRange(0, text.length)) else buildList {
        var start = 0
        text.forEachIndexed { index, character ->
            if (character == '\n') {
                add(TextRange(start, index))
                start = index + 1
            }
        }
        add(TextRange(start, text.length))
    }

    val lineHeight: Float = style.lineHeight

    private val layouts = HashMap<Int, TextLayout>()
    private val widths = HashMap<Int, Float>()

    private var hint: TextLayout? = null

    /** The hint, measured once. Drawn in place of the text when there is none. */
    fun hintLayout(placeholder: String): TextLayout =
        hint ?: fonts.measure(placeholder, style).also { hint = it }

    /** The whole of line [line], measured. What is drawn. */
    fun layoutOf(line: Int): TextLayout = layouts.getOrPut(line) {
        fonts.measure(text.substring(lines[line].start, lines[line].end), style)
    }

    val width: Float get() = lines.indices.maxOfOrNull { layoutOf(it).size.width } ?: 0f

    val height: Float get() = lines.size * lineHeight

    fun lineOf(index: Int): Int {
        val at = index.coerceIn(0, text.length)
        lines.forEachIndexed { line, range -> if (at <= range.end) return line }
        return lines.lastIndex
    }

    /** How far along its line character [index] is. */
    fun xOf(index: Int): Float = widths.getOrPut(index.coerceIn(0, text.length)) {
        val at = index.coerceIn(0, text.length)
        val line = lines[lineOf(at)]
        if (at <= line.start) 0f else fonts.measure(text.substring(line.start, at), style).size.width
    }

    /** Where the caret sits for [index], as a line and a distance along it. */
    fun caretAt(index: Int): Offset {
        val line = lineOf(index)
        return Offset(xOf(index), line * lineHeight)
    }

    /**
     * Which character is under a point in the text's own coordinates.
     *
     * Nearest boundary rather than nearest character: clicking the left half of a letter puts the
     * caret before it and the right half after it, which is what a player is aiming at.
     */
    fun indexAt(point: Offset): Int {
        val line = (point.y / lineHeight).toInt().coerceIn(0, lines.lastIndex)
        val range = lines[line]
        if (point.x <= 0f) return range.start

        var best = range.start
        var bestDistance = Float.MAX_VALUE
        var at = range.start
        while (true) {
            val distance = kotlin.math.abs(xOf(at) - point.x)
            if (distance < bestDistance) {
                bestDistance = distance
                best = at
            }
            if (at >= range.end) break
            at = text.graphemeAfter(at).coerceAtMost(range.end)
        }
        return best
    }
}

/**
 * The field's own drawing: the highlight, the words, the caret, and where they have scrolled to.
 *
 * Measuring is where the scrolling is decided, because measuring is the first time anything knows
 * how much room there is. Keeping the caret in view is two lines of arithmetic and the whole reason
 * a long name does not type itself off the right-hand edge.
 */
private class FieldPainter(
    private val metrics: FieldMetrics,
    private val style: ResolvedStyle,
    private val placeholder: String?,
    private val placeholderColour: Colour,
    private val highlight: ResolvedStyle,
    private val caretStyle: ResolvedStyle,
    private val composingStyle: ResolvedStyle,
    private val selection: TextRange,
    private val composition: TextRange?,
    private val focused: Boolean,
    private val caretShowing: Boolean,
    private val view: FieldView,
    private val multiline: Boolean,
) : MeasurePolicy {

    private var viewport = Rect(0f, 0f, 0f, 0f)

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val width = constraints.constrainWidth(
            if (constraints.hasBoundedWidth) constraints.maxWidth else metrics.width,
        )
        val height = constraints.constrainHeight(if (multiline) metrics.height else metrics.lineHeight)
        viewport = Rect(0f, 0f, width, height)

        view.metrics = metrics
        keepCaretInView(width, height)

        return layout(width, height) {}
    }

    /**
     * Scrolls as far as it has to and no further.
     *
     * Both extremes matter: typing at the end must leave the caret a caret's width inside the right
     * edge, and moving back to the start must show the first character rather than stopping one
     * letter short of it.
     */
    private fun keepCaretInView(width: Float, height: Float) {
        val caret = metrics.caretAt(selection.end)

        val longest = metrics.width
        val overflowX = (longest - width + CaretWidth).coerceAtLeast(0f)
        if (caret.x - view.scrollX > width - CaretWidth) view.scrollX = caret.x - width + CaretWidth
        if (caret.x - view.scrollX < 0f) view.scrollX = caret.x
        view.scrollX = view.scrollX.coerceIn(0f, overflowX)

        val overflowY = (metrics.height - height).coerceAtLeast(0f)
        if (caret.y - view.scrollY + metrics.lineHeight > height) {
            view.scrollY = caret.y + metrics.lineHeight - height
        }
        if (caret.y - view.scrollY < 0f) view.scrollY = caret.y
        view.scrollY = view.scrollY.coerceIn(0f, overflowY)
    }

    val draw: UiCanvas.(Rect) -> Unit = { bounds ->
        view.origin = bounds.topLeft
        val left = bounds.left - view.scrollX
        val top = bounds.top - view.scrollY

        if (metrics.text.isEmpty() && placeholder != null) {
            text(metrics.hintLayout(placeholder), Offset(bounds.left, bounds.top), placeholderColour)
        } else {
            if (!selection.collapsed) drawSelection(left, top)
            metrics.lines.indices.forEach { line ->
                text(metrics.layoutOf(line), Offset(left, top + line * metrics.lineHeight), style.textColour)
            }
            // Over the words rather than behind them: this is an underline, not a highlight.
            composition?.takeIf { !it.collapsed }?.let { drawComposition(it, left, top) }
        }

        if (caretShowing) {
            val caret = metrics.caretAt(selection.end)
            caretStyle.background.drawInto(
                this,
                Rect(
                    left + caret.x,
                    top + caret.y,
                    left + caret.x + CaretWidth,
                    top + caret.y + metrics.lineHeight,
                ),
                caretStyle.tint,
            )
        }
    }

    /**
     * The underline under text an input method has not committed yet.
     *
     * Typing Japanese is two steps: the letters you press become a run of provisional text, and
     * then you choose what it turns into. The underline is how a player can see which part of the
     * field is still provisional — without it, half-typed text looks exactly like text that is
     * already there, and there is no way to tell what the next Enter is going to replace.
     *
     * Drawn from the value's own `composition` range, so any backend that can report one gets this
     * for free.
     */
    private fun UiCanvas.drawComposition(range: TextRange, left: Float, top: Float) {
        eachLineOf(range) { line, startX, endX ->
            val baseline = top + line * metrics.lineHeight + metrics.lineHeight - UnderlineInset
            composingStyle.background.drawInto(
                this,
                Rect(left + startX, baseline, left + endX, baseline + UnderlineThickness),
                composingStyle.tint,
            )
        }
    }

    /** One rectangle per line the selection touches, drawn behind the words. */
    private fun UiCanvas.drawSelection(left: Float, top: Float) {
        eachLineOf(selection) { line, startX, endX ->
            highlight.background.drawInto(
                this,
                Rect(
                    left + startX,
                    top + line * metrics.lineHeight,
                    left + endX,
                    top + line * metrics.lineHeight + metrics.lineHeight,
                ),
                highlight.tint,
            )
        }
    }

    /**
     * The part of [range] that falls on each line it touches, as two distances along that line.
     *
     * Shared by the selection and the composing underline because a range that wraps is the same
     * arithmetic either way, and the second copy of it is always the one that forgets the last line.
     */
    private inline fun eachLineOf(range: TextRange, each: (Int, Float, Float) -> Unit) {
        val first = metrics.lineOf(range.min)
        val last = metrics.lineOf(range.max)
        for (line in first..last) {
            val onLine = metrics.lines[line]
            val from = maxOf(range.min, onLine.start)
            val to = minOf(range.max, onLine.end)
            each(line, metrics.xOf(from), metrics.xOf(to))
        }
    }
}
