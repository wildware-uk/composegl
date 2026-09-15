package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.graphics.textRun
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.IntrinsicMeasurable
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.focusableByPointer
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.skin.ResolvedStyle
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.text.FontProvider
import dev.wildware.composegl.ui.text.Paragraph
import dev.wildware.composegl.ui.text.TextAnchor
import dev.wildware.composegl.ui.text.TextDecoration
import dev.wildware.composegl.ui.text.TextGestures
import dev.wildware.composegl.ui.text.TextLayout
import dev.wildware.composegl.ui.text.TextOutline
import dev.wildware.composegl.ui.text.TextRange
import dev.wildware.composegl.ui.text.TextRun
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.text.paragraph

/**
 * A piece of text.
 *
 * The leaf every other widget ends up containing. It takes its font, size and colour from the
 * skin's style called [style], so a label written in a game says what it says rather than what
 * colour it is.
 *
 * Measuring and drawing use the same layout object, never two. That is the one rule text has to
 * keep: measure twice and a line can break in one of them and not the other, and the result is
 * text drawn a word away from the space that was reserved for it.
 *
 * @param style the skin style to take the font and colour from. Null takes them from whatever
 *   widget this is inside — the label in a button is the button's colour, in whichever state the
 *   button is in — and falls back to `"label"` when nothing is wrapping it.
 * @param textStyle overrides the style's font, for the rare place that needs one.
 * @param colour overrides the style's colour.
 * @param align where the text sits when it was given more room than it needs. This aligns the
 *   *block*: a centred paragraph is a centred block of ragged lines, because where a line breaks
 *   is the backend's business and the toolkit never sees the lines.
 * @param softWrap false to let the text run on past the width it was given rather than wrap.
 *   Explicit newlines still break lines.
 * @param maxLines cuts the text off after this many lines, ending with the ellipsis. Zero is no
 *   limit. A shortcut for `textStyle = someStyle.copy(maxLines = n)`.
 * @param ellipsis what a cut-off line ends with. Null keeps whatever the style says, which is "…";
 *   an empty string cuts the line off cleanly instead.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: String? = null,
    textStyle: TextStyle? = null,
    colour: Colour? = null,
    align: HorizontalAlignment = HorizontalAlignment.Start,
    softWrap: Boolean = true,
    maxLines: Int = 0,
    ellipsis: String? = null,
) = Text(text, modifier, style, textStyle, colour, align, softWrap, maxLines, ellipsis, LocalTextOutline.current)

/**
 * The same, with a ring of [outline] round the letters. Null is no ring, whatever the surroundings
 * say — the overload without this parameter is the one that takes [LocalTextOutline].
 *
 * The ring is painted outside the box and **the box does not grow for it**, exactly as
 * `Modifier.outline`'s bleed already reaches past the node it wraps. So an outlined label lays out
 * byte for byte like the same label unoutlined: turning the ring on cannot move its neighbours,
 * cannot rewrap a paragraph, and cannot push the words off the baseline a [PromptGlyph] beside them
 * is sitting on. Where the ring needs room of its own — a background that must cover it, an
 * ancestor clip that would trim it — `Modifier.padding` of the outline width says so out loud.
 *
 * @see dev.wildware.composegl.ui.text.TextOutline for what a stamped ring can and cannot do.
 */
// A separate function rather than a tenth parameter with a default on the one above, and it has to
// stay that way: adding a defaulted parameter to a published function changes its signature, so
// every game compiled against 0.1.0 would fail to link against the "tidier" version. `outline` has
// no default here for the same reason the two can coexist at all — give it one and `Text("hi")`
// matches both.
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: String? = null,
    textStyle: TextStyle? = null,
    colour: Colour? = null,
    align: HorizontalAlignment = HorizontalAlignment.Start,
    softWrap: Boolean = true,
    maxLines: Int = 0,
    ellipsis: String? = null,
    outline: TextOutline?,
) = Text(text, modifier, style, textStyle, colour, align, softWrap, maxLines, ellipsis, outline, TextAnchor.LineBox)

/**
 * The same, placed by [anchor] rather than by the top of its line box.
 *
 * A text node's box is a *line* box: its top sits at the tallest glyph's ascent, so text placed by
 * a coordinate that means "the top of the capitals" — which is what almost every ported coordinate
 * means — draws [dev.wildware.composegl.ui.text.FontMetrics.capInset] low. That error is silent,
 * nothing clips or overflows, and it scales with the font, so a screen with three text sizes is
 * wrong by three different amounts.
 *
 * This moves the node, not the glyphs inside it: the box is the same size, wraps the same way, and
 * its background, border and clicks all move with it. It is exactly
 * `Modifier.offset(y = -anchor.lift(metrics))`, worked out from the style this label actually
 * resolved rather than from one the caller had to look up and keep in step.
 *
 * @param anchor which part of the text lands on the y this node was placed at.
 */
// The fourth overload rather than a parameter with a default on the third, for the reason the note
// above gives: a defaulted parameter added to a published function changes its signature, and every
// game compiled against the version before it would fail to link. `outline` has a default here
// because `anchor` does not, which is what keeps this one distinguishable from the two above.
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: String? = null,
    textStyle: TextStyle? = null,
    colour: Colour? = null,
    align: HorizontalAlignment = HorizontalAlignment.Start,
    softWrap: Boolean = true,
    maxLines: Int = 0,
    ellipsis: String? = null,
    outline: TextOutline? = LocalTextOutline.current,
    anchor: TextAnchor,
) {
    // A selection has to know where every character is, and only the overload that breaks its own
    // lines does. So a label inside a SelectionContainer is that one, with nothing styled.
    if (LocalSelection.current != null) {
        Text(text, modifier, style, textStyle, colour, align, softWrap, maxLines, ellipsis, outline, anchor, runs = emptyList())
        return
    }
    val named = rememberStyle(style ?: "label")
    val inherited = LocalContentStyle.current
    val resolved = if (style == null && inherited != null) inherited else named
    val fonts = rememberFonts()
    val ink = colour ?: resolved.textColour
    // Scaled here, before anything is measured, so the backend lays the text out and bakes its
    // glyphs at the size the player asked for rather than measuring small and being stretched.
    val scale = LocalTextScale.current
    val face = remember(resolved.textStyle, textStyle, maxLines, ellipsis, scale) {
        val base = (textStyle ?: resolved.textStyle).scaled(scale)
        base.copy(
            maxLines = if (maxLines > 0) maxLines else base.maxLines,
            ellipsis = ellipsis ?: base.ellipsis,
        )
    }

    // One object measures and draws, and it is remembered on everything it was built from. Same
    // text under the same style: the same object, so the node sees nothing change and the frame is
    // not redrawn. Different text: a different object, so it is.
    val painter = remember(text, face, ink, align, softWrap, fonts, outline) {
        TextPainter(text, face, ink, align, softWrap, fonts, outline)
    }

    // Off the face rather than off the measured string: where the cap top and the baseline sit
    // inside a line box is a property of the font at that size, the same for every label in a
    // style, and known before a word has been measured. Nothing at all for the default anchor,
    // which is the one nearly every label uses.
    val lift = if (anchor == TextAnchor.LineBox) 0f else remember(anchor, face, fonts) {
        anchor.lift(fonts.metrics(face))
    }
    // The caller's modifier last, so their own offset adds to this rather than being replaced by it.
    val placed = if (lift == 0f) modifier else Modifier.offset(y = -lift).then(modifier)

    LeafLayout(modifier = placed, name = "text", measurePolicy = painter, draw = painter.draw, ink = painter.ink)
}

/**
 * The measuring and the drawing of one run of text, together, because they must agree.
 *
 * The layout the backend handed back at measure time is kept and drawn. Nothing re-measures at
 * draw time, and nothing can: that is what makes "it measures exactly as it draws" a property of
 * the design rather than a thing to be careful about.
 */
private class TextPainter(
    private val text: String,
    private val style: TextStyle,
    private val colour: Colour,
    private val align: HorizontalAlignment,
    private val softWrap: Boolean,
    private val fonts: FontProvider,
    private val outline: TextOutline?,
) : MeasurePolicy {

    private var measured: TextLayout? = null

    /**
     * The width the kept layout was measured against.
     *
     * This object already stands for one run of text in one style — it is remembered on both — so
     * the only thing that can change between one frame and the next is how much room it was
     * offered. When that is the same too, last frame's answer is still the answer, and measuring
     * again would lay the same words out into the same lines to arrive at the same numbers.
     */
    private var measuredFor = Float.NaN

    /** How far right the measured block sits inside the width layout settled on. */
    private var shift = 0f

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val room = if (softWrap) constraints.maxWidth else Float.POSITIVE_INFINITY
        val kept = measured
        val block = if (kept != null && room == measuredFor) kept else fonts.measure(text, style, room)
        measured = block
        measuredFor = room

        val width = constraints.constrainWidth(block.size.width)
        shift = when (align) {
            HorizontalAlignment.Start -> 0f
            HorizontalAlignment.Centre -> (width - block.size.width) / 2f
            HorizontalAlignment.End -> width - block.size.width
        }.coerceAtLeast(0f)

        // Where the letters stand, for a row lining this label up with its neighbours. The same
        // arithmetic `ink` below does: every line after the first is a line height further down.
        val last = block.firstBaseline + (block.lineCount - 1).coerceAtLeast(0) * style.lineHeight
        return layout(width, constraints.constrainHeight(block.size.height), block.firstBaseline, last) {}
    }

    private val intrinsics = TextIntrinsics(text, softWrap) { piece, room -> fonts.measure(piece, style, room).size }

    override fun MeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) =
        intrinsics.minWidth()

    override fun MeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) =
        intrinsics.maxWidth()

    override fun MeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) =
        intrinsics.height(width)

    override fun MeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) =
        intrinsics.height(width)

    /**
     * The glyphs, rather than the line boxes they sit in.
     *
     * A line box is the ascent, the descent and whatever leading the style asks for; the letters
     * occupy `ascent + descent` of that, starting at the first baseline less the ascent. Over
     * several lines the last line's descent is the bottom, so the leading under it is not counted.
     *
     * Horizontally it is the measured width, shifted the same way the drawing is — which for a
     * centred or end-aligned block is not the left edge of the box.
     *
     * Asked for only by [dev.wildware.composegl.ui.node.UiNode.paintedInRoot], never per frame.
     */
    val ink: (Rect) -> Rect? = { box ->
        measured?.let { block ->
            val metrics = fonts.metrics(style)
            val lastBaseline = block.firstBaseline + (block.lineCount - 1) * style.lineHeight
            Rect(
                left = box.left + shift,
                top = box.top + block.firstBaseline - metrics.ascent,
                right = box.left + shift + block.size.width,
                bottom = box.top + lastBaseline + metrics.descent,
            )
        }
    }

    val draw: UiCanvas.(Rect) -> Unit = { bounds ->
        // Null only if a frame is drawn before anything measured, which the passes do not do.
        // The two-float call, not the Offset one: this runs for every run of text every frame.
        // The outline goes to the canvas rather than being stacked here, so an outlined label is
        // still one node, and it is painted outside these bounds rather than inside a bigger box —
        // which is what lets measuring ignore it entirely.
        measured?.let { textRun(it, bounds.left + shift, bounds.top, colour, outline) }
    }
}

/**
 * The same, with parts of it styled differently — an underlined term, a struck word, a value in
 * another colour — and, if it wants them, told which run the pointer is over.
 *
 * The one thing that changes underneath is who breaks the lines. Every other overload hands the
 * whole string to the backend and never sees where it wrapped, which is the right bargain for a
 * label and an impossible one here: a widget cannot underline the fifth word without knowing where
 * the fifth word is. So this one breaks the lines itself, through [paragraph], and draws each run
 * in its own colour. Two things follow from that and are worth knowing before reaching for it:
 *
 * - **Lines are aligned as well as the block.** A centred paragraph here is centred line by line,
 *   because the lines are known. Every other overload centres the block and leaves the lines ragged
 *   inside it, because they are not.
 * - **It costs more.** A run of text is measured per line and per run boundary instead of once.
 *   Everything is cached on the text, the style and the width, so a paragraph that has not changed
 *   costs nothing to draw again — but a label that has no runs should stay on an overload that has
 *   no runs.
 *
 * ```kotlin
 * val term = TextRange(4, 12)
 * Text(
 *     "The tincture wears off at dawn.",
 *     runs = listOf(TextRun(term, colour = Colour.Orange, decoration = TextDecoration.Underline, tag = "tincture")),
 *     onRunClick = { explain(it.tag as String) },
 * )
 * ```
 *
 * @param runs which parts look different, and what the pointer gets back for them. An empty list
 *   draws exactly what the other overloads draw, by the toolkit's own breaking rather than the
 *   backend's, which can put a line break in a different place.
 * @param onRunHover called with the run under the pointer whenever that changes, and with null when
 *   the pointer is over plain text or has left. This is the run-aware hit test: a term is hoverable
 *   without the paragraph being split into one node per word.
 * @param onRunClick called when a run is pressed and released without the pointer leaving it.
 */
// A fifth overload rather than a parameter with a default on the fourth, for the reason the notes
// above give: a defaulted parameter added to a published function changes its signature, and every
// game compiled against the version before it would fail to link. `runs` has no default, which is
// what keeps this one distinguishable from the four above; the two callbacks after it may have
// defaults because `runs` does not.
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: String? = null,
    textStyle: TextStyle? = null,
    colour: Colour? = null,
    align: HorizontalAlignment = HorizontalAlignment.Start,
    softWrap: Boolean = true,
    maxLines: Int = 0,
    ellipsis: String? = null,
    outline: TextOutline? = LocalTextOutline.current,
    anchor: TextAnchor = TextAnchor.LineBox,
    runs: List<TextRun>,
    onRunHover: ((TextRun?) -> Unit)? = null,
    onRunClick: ((TextRun) -> Unit)? = null,
) {
    val named = rememberStyle(style ?: "label")
    val inherited = LocalContentStyle.current
    val resolved = if (style == null && inherited != null) inherited else named
    val fonts = rememberFonts()
    val ink = colour ?: resolved.textColour
    // Scaled here, before anything is measured, so the backend lays the text out and bakes its
    // glyphs at the size the player asked for rather than measuring small and being stretched.
    val scale = LocalTextScale.current
    val face = remember(resolved.textStyle, textStyle, maxLines, ellipsis, scale) {
        val base = (textStyle ?: resolved.textStyle).scaled(scale)
        base.copy(
            maxLines = if (maxLines > 0) maxLines else base.maxLines,
            ellipsis = ellipsis ?: base.ellipsis,
        )
    }

    val view = remember { RunView() }
    val painter = remember(text, face, ink, align, softWrap, fonts, outline, runs, view) {
        RunPainter(text, face, ink, align, softWrap, fonts, outline, runs, view)
    }

    val lift = if (anchor == TextAnchor.LineBox) 0f else remember(anchor, face, fonts) {
        anchor.lift(fonts.metrics(face))
    }

    // Nothing is painted: this is how the widget learns where it is, so a pointer position in its
    // own coordinates can be turned into one inside the text. Only when somebody is asking, because
    // a label that answers no questions should carry no handlers at all.
    val watched = onRunHover != null || onRunClick != null
    val locate = remember(view) { runLocator(view) }
    val pointer = remember(view, painter, onRunHover, onRunClick) {
        RunPointer(view, painter, onRunHover, onRunClick)
    }

    val interactions = remember { InteractionState() }

    // Inside a SelectionContainer: the text field's own gestures, pointed at this paragraph.
    val selecting = LocalSelection.current
    val selected = selecting?.state?.rangeIn(view)
    val focusState = remember { InteractionState() }
    val gestures = remember(view, painter) { TextGestures(indexAt = { painter.indexAt(view.textPoint(it)) }) }
    val selector = remember(selecting, gestures, text) {
        selecting?.let { SelectionPointer(it.state, view, gestures, text) }
    }

    var placed = if (lift == 0f) Modifier else Modifier.offset(y = -lift)
    if (watched || selector != null) placed = placed.drawBehind(locate)
    if (watched) placed = placed.interaction(interactions).onPointer(pointer)
    // After the run handler, so a clickable term inside a selectable label still gets its click.
    if (selector != null) placed = placed.focusableByPointer(focusState).onPointer(selector)
    val chain = placed.then(modifier)

    // A pointer that has left this label altogether sends it nothing — the router delivers a move
    // to what is under it and to nothing else — so without this a term stays lit after the pointer
    // has gone somewhere else. Whether the pointer is on the node at all is the one thing the node
    // knows and a handler on it cannot.
    val over = watched && interactions.isHovered
    SideEffect { if (watched && !over) pointer.left() }

    if (selecting != null) {
        val state = selecting.state
        // Read here so losing focus recomposes this label. Only the moment it is lost clears the
        // selection: a game whose pointer router has no focus manager never focuses a label at all,
        // and its selections should still stay where the player dragged them.
        val focused = focusState.isFocused
        SideEffect {
            state.textChanged(view, text)
            if (view.wasFocused && !focused) {
                state.forget(view)
                // A shift let go of while focus was elsewhere never reaches the container, so it
                // must not turn the next plain click back here into an extend.
                state.shiftHeld = false
            }
            view.wasFocused = focused
        }
        DisposableEffect(state, view) { onDispose { state.forget(view) } }
    }

    // The painter is kept while the selection moves, so a drag re-measures nothing; only the little
    // lambda that puts the highlight behind it is new each time the range is.
    val highlight = selecting?.highlight
    val draw = if (selected == null || selected.collapsed || highlight == null) painter.draw else {
        remember(painter, selected, highlight) { painter.drawSelected(selected, highlight) }
    }

    LeafLayout(modifier = chain, name = "text", measurePolicy = painter, draw = draw, ink = painter.ink)
}

/**
 * The pointer half of a selectable label: [TextGestures], exactly as a [TextField] uses them, with
 * the answer handed to the container rather than to an `onValueChange`.
 *
 * It takes the press — that is what brings focus, and so Ctrl+C, to this label — and every move of
 * the drag that follows, wherever the pointer goes. Anything but the primary button is left alone,
 * so a right-click still reaches whatever a game has put underneath.
 */
private class SelectionPointer(
    private val state: SelectionState,
    private val view: RunView,
    private val gestures: TextGestures,
    private val text: String,
) : PointerHandler {

    override fun onPointer(event: PointerEvent): Boolean {
        val wasDragging = gestures.isDragging
        gestures.onPointer(event, state.valueFor(view, text), state.shiftHeld)?.let { state.select(view, it) }
        return (event is PointerEvent.Press && event.button == PointerButton.Primary) || wasDragging
    }
}

/** Records where the label is on the screen, every frame, without drawing anything. */
private fun runLocator(view: RunView): UiCanvas.(Rect) -> Unit = { bounds -> view.node = bounds.topLeft }

/**
 * Where the text ended up, so a pointer position can be turned into a position in the paragraph.
 *
 * Two corners rather than one, because they are not the same corner: [node] is the whole widget,
 * which is what a pointer event is measured from, and [origin] is the text inside it, which is what
 * the paragraph is measured from. A label with padding on it has them a padding apart.
 *
 * Nothing here is snapshot state: drawing writes it, the pointer reads it, and neither should cause
 * a composition.
 */
private class RunView {
    var node = Offset.Zero
    var origin = Offset.Zero

    /** Whether this label had focus at its last composition, so the moment it loses it is seen. */
    var wasFocused = false

    /** [point], given in the widget's own coordinates, moved into the paragraph's. */
    fun textPoint(point: Offset) = point + node - origin
}

/**
 * The pointer half of a styled label: which run is under the pointer, and which one was clicked.
 *
 * A click is a press and a release on the same run, which is the same rule `clickable` follows and
 * the same one a player expects — pressing a term and sliding off it does not follow the link.
 */
private class RunPointer(
    private val view: RunView,
    private val painter: RunPainter,
    private val onHover: ((TextRun?) -> Unit)?,
    private val onClick: ((TextRun) -> Unit)?,
) : PointerHandler {

    private var hovered: TextRun? = null
    private var pressed: TextRun? = null

    override fun onPointer(event: PointerEvent): Boolean {
        when (event) {
            is PointerEvent.Move -> hover(painter.runAt(view.textPoint(event.position)))
            is PointerEvent.Exit -> hover(null)
            is PointerEvent.Cancel -> {
                pressed = null
                hover(null)
            }
            is PointerEvent.Press -> {
                pressed = painter.runAt(view.textPoint(event.position))
                hover(pressed)
                // Consumed only over a run somebody is listening for. Everywhere else the click
                // carries on to whatever is underneath, so a term inside a clickable panel does not
                // stop the rest of the sentence pressing the panel.
                if (pressed != null && onClick != null) return true
            }
            is PointerEvent.Release -> {
                val over = painter.runAt(view.textPoint(event.position))
                val was = pressed
                pressed = null
                hover(over)
                if (over != null && over == was && onClick != null) {
                    onClick.invoke(over)
                    return true
                }
            }
            else -> Unit
        }
        return false
    }

    /**
     * The pointer is no longer on the label at all, which only the node itself finds out.
     *
     * Not while a press is being held: the router stops hovering the moment a gesture starts, and
     * a term that unlit itself on press and lit itself again on release would flicker under every
     * click of it.
     */
    fun left() {
        if (pressed == null) hover(null)
    }

    /** Told only when it changes, so a mouse crossing a term reports it once rather than per frame. */
    private fun hover(run: TextRun?) {
        if (run == hovered) return
        hovered = run
        onHover?.invoke(run)
    }
}

/**
 * The measuring and the drawing of a styled run of text, together, because they must agree.
 *
 * Unlike [TextPainter] this owns the line breaking, through [paragraph]. That is the whole
 * difference and the whole cost: it knows where every line starts and every run sits, which is what
 * lets it draw four words in one colour and the fifth underlined in another, and it pays for that
 * in measurements. All of them are cached — the paragraph on the width it was broken at, each piece
 * on the characters it covers — so a screen standing still measures nothing at all.
 */
private class RunPainter(
    private val text: String,
    private val style: TextStyle,
    private val colour: Colour,
    private val align: HorizontalAlignment,
    private val softWrap: Boolean,
    private val fonts: FontProvider,
    private val outline: TextOutline?,
    private val runs: List<TextRun>,
    private val view: RunView,
) : MeasurePolicy {

    /** The style with its line limit off. See [Paragraph]: a piece is known to fit on one line. */
    private val flat = style.copy(maxLines = 0)

    /** Every position a run starts or ends at, in order. Where one drawn piece becomes the next. */
    private val boundaries: IntArray =
        runs.flatMap { listOf(it.range.min, it.range.max) }.distinct().sorted().toIntArray()

    private val pieces = HashMap<Long, TextLayout>()

    private var measured: Paragraph? = null
    private var measuredFor = Float.NaN
    private var shift = 0f

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val room = if (softWrap) constraints.maxWidth else Float.POSITIVE_INFINITY
        val kept = measured
        val block = if (kept != null && room == measuredFor) kept else fonts.paragraph(text, style, room, align)
        return placed(block, room, constraints)
    }

    private val intrinsics = TextIntrinsics(text, softWrap) { piece, room -> fonts.paragraph(piece, style, room, align).size }

    override fun MeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) =
        intrinsics.minWidth()

    override fun MeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) =
        intrinsics.maxWidth()

    override fun MeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) =
        intrinsics.height(width)

    override fun MeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) =
        intrinsics.height(width)

    private fun MeasureScope.placed(block: Paragraph, room: Float, constraints: Constraints): MeasureResult {
        measured = block
        measuredFor = room

        val width = constraints.constrainWidth(block.size.width)
        // The block inside the node; the lines inside the block are already placed by `align`.
        shift = when (align) {
            HorizontalAlignment.Start -> 0f
            HorizontalAlignment.Centre -> (width - block.size.width) / 2f
            HorizontalAlignment.End -> width - block.size.width
        }.coerceAtLeast(0f)

        // The paragraph already knows where every line stands. No lines, no baseline.
        val lines = block.lines
        val height = constraints.constrainHeight(block.size.height)
        return if (lines.isEmpty()) layout(width, height) {}
        else layout(width, height, lines.first().baseline, lines.last().baseline) {}
    }

    /**
     * Which character boundary is nearest [point], in the paragraph's own coordinates.
     *
     * Clamped the way a selection drag needs: above the first line is the first line and below the
     * last is the last, so a drag that leaves the label keeps selecting instead of stopping dead.
     */
    fun indexAt(point: Offset): Int = measured?.indexAt(point) ?: 0

    /**
     * The same drawing, with [range] highlighted behind the glyphs in [highlight]'s background.
     *
     * One rectangle per line the range touches, off [Paragraph.boxesOf] — the same boxes a run's
     * hit test uses, so what lights up is exactly what a click would have landed on.
     */
    fun drawSelected(range: TextRange, highlight: ResolvedStyle): UiCanvas.(Rect) -> Unit = { bounds ->
        measured?.let { block ->
            val left = bounds.left + shift
            for (box in block.boxesOf(range)) {
                highlight.background.drawInto(this, box.translate(Offset(left, bounds.top)), highlight.tint)
            }
        }
        draw(bounds)
    }

    /** Which run [point] is in, in the paragraph's own coordinates. The later run wins an overlap. */
    fun runAt(point: Offset): TextRun? {
        val block = measured ?: return null
        for (index in runs.indices.reversed()) {
            val run = runs[index]
            val boxes = block.boxesOf(run.range)
            for (box in boxes) if (point in box) return run
        }
        return null
    }

    /**
     * What this label really paints inside the box it was given.
     *
     * A line box is taller than the glyphs in it, and a paragraph's box is taller still. What a
     * decoration adds is a reach the glyphs do not have: a line is drawn off the baseline rather
     * than inside the letters, so on a face with shallow descenders an underline is the lowest
     * thing the label paints.
     */
    val ink: (Rect) -> Rect? = { box ->
        val block = measured
        if (block == null || block.lines.isEmpty()) null else {
            val metrics = block.metrics
            val first = block.lines.first()
            val last = block.lines.last()
            var left = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            for (line in block.lines) {
                val ellipsis = if (line.ellipsised) pieceOf(-1, -1).size.width else 0f
                if (line.left < left) left = line.left
                if (line.left + line.width + ellipsis > right) right = line.left + line.width + ellipsis
            }
            var bottom = last.baseline + metrics.descent
            for (run in runs) {
                if (run.decoration == TextDecoration.None) continue
                val reach = last.baseline + run.decoration.offsetFrom(metrics) +
                    run.decoration.thicknessFor(metrics)
                if (reach > bottom) bottom = reach
            }
            Rect(
                left = box.left + shift + left,
                top = box.top + first.baseline - metrics.ascent,
                right = box.left + shift + right,
                bottom = box.top + bottom,
            ).takeIf { !it.isEmpty }
        }
    }

    val draw: UiCanvas.(Rect) -> Unit = { bounds ->
        val block = measured
        if (block != null) {
            view.origin = Offset(bounds.left + shift, bounds.top)
            val metrics = block.metrics
            val lines = block.lines
            for (index in lines.indices) {
                val line = lines[index]
                val top = bounds.top + line.top
                val left = bounds.left + shift + line.left
                var at = line.range.min
                var x = 0f
                while (at < line.range.max) {
                    val end = boundaryAfter(at, line.range.max)
                    val piece = pieceOf(at, end)
                    val tint = colourAt(at) ?: colour
                    textRun(piece, left + x, top, tint, outline)
                    val decoration = decorationAt(at)
                    if (decoration != TextDecoration.None) {
                        val thickness = decoration.thicknessFor(metrics)
                        val y = bounds.top + line.baseline + decoration.offsetFrom(metrics)
                        rect(Rect(left + x, y, left + x + piece.size.width, y + thickness), tint)
                    }
                    x += piece.size.width
                    at = end
                }
                // The ellipsis belongs to the label rather than to whatever run happened to be cut
                // in half by the limit, so it is drawn in the label's own colour and undecorated.
                if (line.ellipsised && style.ellipsis.isNotEmpty()) {
                    textRun(pieceOf(-1, -1), left + x, top, colour, outline)
                }
            }
        }
    }

    /** The next place the drawing has to stop, which is the next run edge or the end of the line. */
    private fun boundaryAfter(at: Int, limit: Int): Int {
        for (boundary in boundaries) if (boundary > at) return minOf(boundary, limit)
        return limit
    }

    /**
     * One piece of the text, measured once and kept.
     *
     * `-1, -1` is the ellipsis, which is the one piece that is not a stretch of the text.
     */
    private fun pieceOf(from: Int, to: Int): TextLayout =
        pieces.getOrPut(from.toLong() shl 32 or (to.toLong() and 0xFFFFFFFFL)) {
            fonts.measure(if (from < 0) style.ellipsis else text.substring(from, to), flat)
        }

    private fun colourAt(index: Int): Colour? {
        var found: Colour? = null
        for (run in runs) if (index >= run.range.min && index < run.range.max && run.colour != null) {
            found = run.colour
        }
        return found
    }

    private fun decorationAt(index: Int): TextDecoration {
        var found = TextDecoration.None
        for (run in runs) if (index >= run.range.min && index < run.range.max) {
            if (run.decoration != TextDecoration.None) found = run.decoration
        }
        return found
    }
}

/**
 * A run of text's intrinsic sizes, worked out when first asked and kept.
 *
 * Kept apart from the layout the painter draws, because asking must not change what is drawn: a
 * question about the text on one line, asked in the middle of a frame, would otherwise replace the
 * wrapped layout the label is about to paint.
 *
 * The widest is the text on one line. The narrowest is its longest word, since that is where
 * wrapping stops helping — measured word by word, because measuring the whole text at no width at
 * all is a question each backend answers differently. Text that does not wrap is one line either
 * way.
 */
private class TextIntrinsics(
    private val text: String,
    private val softWrap: Boolean,
    private val sizeOf: (String, Float) -> Size,
) {
    private var natural = Float.NaN
    private var longestWord = Float.NaN
    private var heightFor = Float.NaN
    private var height = 0f

    fun maxWidth(): Float {
        if (natural.isNaN()) natural = sizeOf(text, Float.POSITIVE_INFINITY).width
        return natural
    }

    fun minWidth(): Float {
        if (!softWrap) return maxWidth()
        if (longestWord.isNaN()) {
            var widest = 0f
            for (word in text.split(' ', '\n', '\t')) {
                if (word.isEmpty()) continue
                val width = sizeOf(word, Float.POSITIVE_INFINITY).width
                if (width > widest) widest = width
            }
            longestWord = widest
        }
        return longestWord
    }

    fun height(width: Float): Float {
        val room = if (softWrap) width else Float.POSITIVE_INFINITY
        if (room != heightFor) {
            height = sizeOf(text, room).height
            heightFor = room
        }
        return height
    }
}
