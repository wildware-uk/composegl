package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.backend.Haptic
import dev.wildware.composegl.ui.backend.Haptics
import dev.wildware.composegl.ui.focus.FocusDirection
import dev.wildware.composegl.ui.focus.FocusWithinHandler
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.Hsv
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.DirectionHandler
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadHandler
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.LocalUiSounds
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.input.UiSounds
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.FlowRow
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onFocusDirection
import dev.wildware.composegl.ui.modifier.onFocusWithin
import dev.wildware.composegl.ui.modifier.onGamepadEvent
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.styled
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.skin.ResolvedStyle
import dev.wildware.composegl.ui.skin.WidgetState
import dev.wildware.composegl.ui.skin.flatColour
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.skin.styled
import kotlin.math.abs
import kotlin.math.min

/**
 * Somewhere to choose a colour: a character's hair, a team's colour, a crosshair, a tint being
 * tweaked in a debug window.
 *
 * ```kotlin
 * var tint by remember { mutableStateOf(Colour.rgb(0x4CC2FF)) }
 * ColourPicker(colour = tint, onColourChange = { tint = it }, alpha = true, presets = teamColours)
 * ```
 *
 * It is laid out along the three things a person means by a colour (see [Hsv]):
 *
 * - **A square** of saturation across and brightness up, for the hue that is chosen. Grey is at the
 *   start edge, the strongest colour at the end edge, black along the bottom.
 * - **A hue strip** beside it, running once round the colour wheel from red at the top to red again
 *   at the bottom.
 * - **An alpha strip** when [alpha] is true, solid at the top and gone at the bottom, over a
 *   checkerboard so a see-through colour looks see-through.
 * - **A hex field** under them when [hexField] is true, with a swatch of the colour beside it. It is
 *   an ordinary [TextField], so a pad player gets the on-screen keyboard. It takes `#RRGGBB`, or
 *   `#AARRGGBB` with [alpha], and the short `#RGB`; the colour changes as soon as what is typed is
 *   one, and the text tidies itself up when focus leaves.
 * - **Preset swatches** under that, one per colour in [presets]. A click, Enter or South on one
 *   picks it.
 *
 * Every part works from every device:
 *
 * - **The mouse or a finger** presses or drags anywhere on the square or a strip. A press takes the
 *   pointer, so a drag that wanders off the edge carries on and holds the marker at the edge.
 * - **Arrow keys or the d-pad** move the marker on whichever of the three has focus, a twentieth of
 *   the way each press. At an edge they stop taking the press, so the next one moves focus on, the
 *   way a [Slider] lets go of focus at its end.
 * - **The left stick** on the focused square moves the marker smoothly in any direction, faster the
 *   further it is pushed, with no virtual cursor. A fresh push against an edge the marker is already
 *   on is left to move focus instead, so a stick alone can leave the square.
 * - **The shoulder buttons** turn the hue from anywhere inside the picker: left bumper back round the
 *   wheel, right bumper forwards, repeating while held and wrapping past red.
 *
 * On a right-to-left screen the square mirrors, as a slider does: grey is on the right. The strips
 * go the other side of it with the row.
 *
 * Like [Slider], it draws and reports and the screen holds the answer: [onColourChange] is told the
 * colour the player asked for, and nothing moves until [colour] says so. It remembers the hue itself,
 * so a colour dragged down to black or across to grey keeps its hue for when it comes back.
 *
 * Everything it looks like is the skin's: `"<style>"` is the panel round it, `"<style>.area"` the
 * frame round the square and each strip in its hovered, focused and disabled states,
 * `"<style>.marker"` the markers (its text colour the ring, its fill the outline), and
 * `"colourpicker.checker"` the two squares of the checkerboard (its fill and its text colour). The
 * swatches are `"colourswatch"` and the hex field `"field"`.
 *
 * @param colour the colour shown.
 * @param onColourChange called with the new colour when the player changes it.
 * @param alpha whether the player may choose how see-through it is. Without it, the colour's alpha
 *   is kept as it came in, and the hex field says only the red, green and blue.
 * @param presets colours offered as swatches to pick with one press. None by default.
 * @param hexField whether to show the hex field and the swatch beside it.
 * @param size how big the square is, which is also how tall the strips are.
 * @param stripWidth how wide each strip is.
 * @param initialFocus true to start with focus on the square.
 */
@Composable
fun ColourPicker(
    colour: Colour,
    onColourChange: (Colour) -> Unit,
    modifier: Modifier = Modifier,
    alpha: Boolean = false,
    presets: List<Colour> = emptyList(),
    hexField: Boolean = true,
    size: Float = 160f,
    stripWidth: Float = 18f,
    style: String = "colourpicker",
    enabled: Boolean = true,
    initialFocus: Boolean = false,
) = PickerBody(colour, onColourChange, modifier, alpha, presets, hexField, size, stripWidth, style, enabled, initialFocus, inPopup = false)

/**
 * A small square of colour: a preset to pick, the colour a setting is set to, or the button that
 * opens a picker.
 *
 * ```kotlin
 * ColourSwatch(colour = tint, onClick = { editing = true })
 * ```
 *
 * A see-through colour is drawn over a checkerboard, so it looks see-through. With [onClick] it is a
 * button: focusable, and pressed by a click, Enter or the pad's South. Without, it only shows.
 *
 * [ColourPickerButton] is a swatch that opens a [ColourPicker] under itself when pressed.
 *
 * The frame round it is the skin's `"<style>"`, in its hovered, focused, pressed and disabled
 * states; the checkerboard is `"colourpicker.checker"`.
 *
 * @param size how wide and tall it is.
 */
@Composable
fun ColourSwatch(
    colour: Colour,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    size: Float = 24f,
    style: String = "colourswatch",
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
) = SwatchNode(colour, modifier, onClick, size, style, enabled, initialFocus, interaction, anchor = null, name = "colourswatch")

/**
 * A [ColourSwatch] that opens a [ColourPicker] under itself: the compact way to put a colour in a
 * settings list or a debug window.
 *
 * ```kotlin
 * ColourPickerButton(colour = crosshair, onColourChange = { crosshair = it }, alpha = true)
 * ```
 *
 * Pressed — by a click, Enter or the pad's South — it opens the picker over everything else, with
 * focus on the square. The colour changes live while the picker is open. Escape, the pad's East or
 * Back, a press outside the picker or another press on the swatch closes it, and focus goes back to
 * the swatch.
 *
 * The picker is drawn by the screen's [PopupHost], so one has to be round the screen. It opens below
 * the swatch, or above it when there is more room there.
 *
 * @param style the skin style for the swatch.
 * @param pickerStyle the skin style for the picker it opens; see [ColourPicker].
 */
@Composable
fun ColourPickerButton(
    colour: Colour,
    onColourChange: (Colour) -> Unit,
    modifier: Modifier = Modifier,
    alpha: Boolean = false,
    presets: List<Colour> = emptyList(),
    hexField: Boolean = true,
    size: Float = 24f,
    style: String = "colourswatch",
    pickerStyle: String = "colourpicker",
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
) {
    requirePopups()
    var expanded by remember { mutableStateOf(false) }
    val open = expanded && enabled
    // Turned off while open, it closes for good, as a dropdown does.
    if (expanded && !enabled) SideEffect { expanded = false }
    val anchor = remember { PopupAnchor() }
    val toggle = remember { { expanded = !expanded } }

    SwatchNode(colour, modifier, toggle, size, style, enabled, initialFocus, interaction, anchor, name = "colourpickerbutton")

    if (!open) return

    OnBack { expanded = false }

    Popup(anchor, onDismiss = { expanded = false }, position = PopupPosition.BelowStart) {
        PickerBody(
            colour, onColourChange, Modifier, alpha, presets, hexField, DefaultSize, DefaultStripWidth,
            pickerStyle, enabled = true, initialFocus = true, inPopup = true,
        )
    }
}

/** The gap between the parts of a picker. */
private const val Gap = 8f

/** How far in from the edge of the square or a strip the colour starts, leaving room for the frame's border. */
private const val Frame = 2f

/** How far one arrow press or pad nudge moves a marker, as a fraction of the way. */
private const val Nudge = 0.05f

/** How far one shoulder press turns the hue, in degrees. */
private const val HueTurn = 10f

/** How far a fully pushed stick moves the marker across the square in a second, as a fraction of the way. */
private const val StickSpeed = 0.8f

/** How far the stick must be pushed before it moves the marker. */
private const val StickDeadZone = 0.2f

private const val FirstRepeatNanos = 400_000_000L
private const val RepeatNanos = 60_000_000L

private const val DefaultSize = 160f
private const val DefaultStripWidth = 18f
private const val SwatchSize = 28f
private const val PresetSize = 20f

/** The side of one square of the checkerboard. */
private const val CheckerCell = 5f

@Composable
private fun PickerBody(
    colour: Colour,
    onColourChange: (Colour) -> Unit,
    modifier: Modifier,
    alpha: Boolean,
    presets: List<Colour>,
    hexField: Boolean,
    size: Float,
    stripWidth: Float,
    style: String,
    enabled: Boolean,
    initialFocus: Boolean,
    inPopup: Boolean,
) {
    require(size > 0f) { "a colour square cannot be $size across" }
    require(stripWidth > 0f) { "a strip cannot be $stripWidth wide" }

    // One object the pointer, the keys, the pad and drawing all share, as a slider's is, so a drag
    // works out the next colour without waiting to be recomposed.
    val picker = remember { PickerLogic() }
    picker.take(colour)
    picker.alpha = alpha
    picker.enabled = enabled
    picker.mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    picker.squareSide = size
    picker.stripLength = size
    picker.report = onColourChange
    picker.sounds = LocalUiSounds.current
    picker.haptics = LocalHaptics.current
    if (!enabled) {
        picker.spinning = 0
        picker.letGo()
    }
    val hsv = picker.hsv

    val shoulders = remember(picker) { GamepadHandler { picker.shoulder(it) } }
    val within = remember(picker) { FocusWithinHandler { picker.focusInside = it } }
    // Focus gone from the picker while a shoulder was held: its release goes to wherever focus went,
    // and the picker would turn the hue for ever waiting for it. Decided here rather than in the
    // handler, because a step from the square to a strip leaves one before it reaches the other.
    if (picker.spinning != 0 && !picker.focusInside) SideEffect { picker.spinning = 0 }
    // Hold to repeat, for a shoulder. A button held down says nothing more after it went down, so the
    // frame clock keeps the hue turning.
    val spinning = picker.spinning
    LaunchedEffect(picker, spinning) {
        if (spinning == 0) return@LaunchedEffect
        var next = withFrameNanos { it } + FirstRepeatNanos
        while (true) {
            val now = withFrameNanos { it }
            if (picker.spinning != spinning) break
            if (now < next) continue
            picker.spin(spinning)
            next = now + RepeatNanos
        }
    }

    // A press on the panel between the parts is inside the picker, and inside a popup that is not a
    // reason to close it.
    val inside = remember { PointerHandler { it is PointerEvent.Press } }
    val body = modifier.onGamepadEvent(shoulders).onFocusWithin(within).then(if (inPopup) Modifier.onPointer(inside) else Modifier).styled(style)
    val across = size + (if (alpha) 2 else 1) * (Gap + stripWidth)

    Column(body, verticalArrangement = Arrangement.spacedBy(Gap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
            ColourSquare(picker, hsv, size, style, enabled, initialFocus)
            Strip(picker, StripKind.Hue, hsv, stripWidth, size, style, enabled)
            if (alpha) Strip(picker, StripKind.Alpha, hsv, stripWidth, size, style, enabled)
        }
        if (hexField) {
            Row(Modifier.width(across), horizontalArrangement = Arrangement.spacedBy(Gap), verticalAlignment = VerticalAlignment.Centre) {
                ColourSwatch(hsv.toColour(), size = SwatchSize)
                HexField(picker, colour, alpha, enabled, Modifier.weight(1f))
            }
        }
        if (presets.isNotEmpty()) {
            FlowRow(Modifier.width(across), horizontalSpacing = 4f, verticalSpacing = 4f) {
                presets.forEach { preset ->
                    ColourSwatch(preset, onClick = { picker.pick(preset) }, size = PresetSize, enabled = enabled)
                }
            }
        }
    }
}

/** The saturation and brightness square. */
@Composable
private fun ColourSquare(picker: PickerLogic, hsv: Hsv, size: Float, style: String, enabled: Boolean, initialFocus: Boolean) {
    val interaction = remember { InteractionState() }
    val states = rememberStates(interaction, enabled)
    val marker = rememberStyle("$style.marker", states)
    val input = remember(picker) { PieceInput(picker, StripKind.Square) }
    val pointer = remember(input) { PointerHandler { input.pointer(it) } }
    val directions = remember(input) { DirectionHandler { input.nudge(it) } }
    val stick = remember(picker) { GamepadHandler { picker.stick(it) } }

    // Focus gone while the stick was pushed: that push is over as far as the square is concerned, and
    // it will not hear the stick come back to rest.
    if (WidgetState.Focused !in states) SideEffect { picker.letGo() }
    val steering = picker.steering
    LaunchedEffect(picker, steering) {
        if (!steering) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                picker.steer((now - last) / 1_000_000_000f)
                last = now
            }
        }
    }

    val mirrored = picker.mirrored
    val draw = remember(hsv, mirrored, marker) { squarePainter(hsv, mirrored, marker) }
    LeafLayout(
        Modifier.size(size)
            .interaction(interaction)
            .focusable(interaction, enabled = enabled, initial = initialFocus)
            .onPointer(pointer)
            .onFocusDirection(directions)
            .onGamepadEvent(stick)
            .styled("$style.area", states),
        name = "colourpicker.square",
        draw = draw,
    )
}

/** The hue strip, or the alpha strip. */
@Composable
private fun Strip(picker: PickerLogic, kind: StripKind, hsv: Hsv, width: Float, length: Float, style: String, enabled: Boolean) {
    val interaction = remember { InteractionState() }
    val states = rememberStates(interaction, enabled)
    val marker = rememberStyle("$style.marker", states)
    val checker = rememberStyle("colourpicker.checker")
    val input = remember(picker, kind) { PieceInput(picker, kind) }
    val pointer = remember(input) { PointerHandler { input.pointer(it) } }
    val directions = remember(input) { DirectionHandler { input.nudge(it) } }

    val draw = remember(kind, hsv, marker, checker) {
        if (kind == StripKind.Hue) huePainter(hsv, marker) else alphaPainter(hsv, marker, checker)
    }
    LeafLayout(
        Modifier.size(width, length)
            .interaction(interaction)
            .focusable(interaction, enabled = enabled)
            .onPointer(pointer)
            .onFocusDirection(directions)
            .styled("$style.area", states),
        name = if (kind == StripKind.Hue) "colourpicker.hue" else "colourpicker.alpha",
        draw = draw,
    )
}

/** The hex field: what is typed becomes the colour as soon as it is one. */
@Composable
private fun HexField(picker: PickerLogic, colour: Colour, alpha: Boolean, enabled: Boolean, modifier: Modifier) {
    val written = colour.toHex(alpha)
    var text by remember { mutableStateOf(written) }
    val interaction = remember { InteractionState() }
    val states = rememberStates(interaction, enabled)

    // While the player is typing, half a colour stays as it is typed. Otherwise the field says the
    // colour the way a skin file writes it — and says a colour changed from elsewhere at once.
    val holder = remember { arrayOf(colour) }
    if (holder[0] != colour) {
        holder[0] = colour
        if (picker.parse(text) != colour) text = written
    }
    // Leaving the field tidies it — but the pad keyboard takes focus while a pad player types into
    // the field, so focus going there is not leaving. It is tidied once the keyboard closes too.
    val keyboard = LocalGamepadKeyboard.current
    val editing = remember { booleanArrayOf(false) }
    if (WidgetState.Focused in states) {
        editing[0] = true
    } else if (editing[0] && keyboard?.isOpen != true) {
        editing[0] = false
        if (text != written) text = written
    }

    TextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            picker.parse(typed)?.let { picker.pick(it) }
        },
        modifier = modifier,
        enabled = enabled,
        maxLength = if (alpha) 9 else 7,
        onSubmit = { text = picker.hsv.toColour().toHex(alpha) },
        interaction = interaction,
    )
}

/** A swatch's node, which a [ColourPickerButton] also opens its popup next to. */
@Composable
private fun SwatchNode(
    colour: Colour,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    size: Float,
    style: String,
    enabled: Boolean,
    initialFocus: Boolean,
    interaction: InteractionState,
    anchor: PopupAnchor?,
    name: String,
) {
    val resolved = rememberStyle(style, rememberStates(interaction, enabled))
    val checker = rememberStyle("colourpicker.checker")
    val draw = remember(colour, checker) { swatchPainter(colour, checker) }
    val sounds = LocalUiSounds.current
    val direction = LocalLayoutDirection.current

    var chain = modifier.size(size).interaction(interaction)
    if (onClick != null) {
        chain = chain
            .focusable(interaction, enabled = enabled, initial = initialFocus)
            .clickable(enabled = enabled, onClick = rememberTapped(onClick))
    }
    chain = chain.styled(resolved)

    ComposeNode<UiNode, UiApplier>(
        factory = { UiNode(name).also { anchor?.node = it } },
        update = {
            set(sounds) { this.sounds = it }
            set(direction) { this.layoutDirection = it }
            set(chain) { this.modifier = it }
            set(MeasurePolicy.Empty) { this.measurePolicy = it }
            set(draw) { this.content = it }
        },
    )
}

/** Which part of a picker a piece of input is on. */
private enum class StripKind { Square, Hue, Alpha }

/**
 * The pointer and the direction keys on one part of a picker.
 *
 * The same shape for all three parts, as [Slider]'s is: a press or a drag puts the marker under the
 * pointer, and a direction moves it a step, or is left alone at an edge so focus can move on.
 */
private class PieceInput(private val picker: PickerLogic, private val kind: StripKind) {

    /** Whether this drag has changed the colour yet, so letting go sounds once for it. */
    private var dragged = false

    fun pointer(event: PointerEvent): Boolean {
        if (!picker.enabled) return false
        return when (event) {
            // The press takes the pointer, so the rest of the drag arrives here even off the edge.
            is PointerEvent.Press -> { dragged = picker.change(at(event.position)); true }
            is PointerEvent.Move -> if (event.pressed.isEmpty()) false else {
                if (picker.change(at(event.position))) dragged = true
                true
            }
            is PointerEvent.Release -> {
                if (dragged) picker.sounds.change()
                dragged = false
                true
            }
            is PointerEvent.Cancel -> { dragged = false; true }
            is PointerEvent.Scroll, is PointerEvent.Exit -> false
        }
    }

    fun nudge(direction: FocusDirection): Boolean {
        if (!picker.enabled) return false
        val now = picker.hsv
        val wanted = when (kind) {
            StripKind.Square -> {
                // The marker moves the way the arrow points; mirrored, grey is on the right.
                val across = when (direction) {
                    FocusDirection.Left -> if (picker.mirrored) Nudge else -Nudge
                    FocusDirection.Right -> if (picker.mirrored) -Nudge else Nudge
                    else -> 0f
                }
                val up = when (direction) {
                    FocusDirection.Up -> Nudge
                    FocusDirection.Down -> -Nudge
                    else -> 0f
                }
                if (across == 0f && up == 0f) return false
                now.copy(saturation = (now.saturation + across).coerceIn(0f, 1f), value = (now.value + up).coerceIn(0f, 1f))
            }
            // Red is at the top of the hue strip and solid at the top of the alpha strip.
            StripKind.Hue -> when (direction) {
                FocusDirection.Up -> now.copy(hue = (now.hue - 360f * Nudge).coerceIn(0f, 360f))
                FocusDirection.Down -> now.copy(hue = (now.hue + 360f * Nudge).coerceIn(0f, 360f))
                else -> return false
            }
            StripKind.Alpha -> when (direction) {
                FocusDirection.Up -> now.copy(alpha = (now.alpha + Nudge).coerceIn(0f, 1f))
                FocusDirection.Down -> now.copy(alpha = (now.alpha - Nudge).coerceIn(0f, 1f))
                else -> return false
            }
        }
        // At the edge, the direction is not used. That is how a player leaves the part.
        if (!picker.change(wanted)) return false
        picker.sounds.change()
        picker.haptics.perform(Haptic.Tick)
        return true
    }

    /** What the colour is with the marker at [position], in this part's own coordinates. */
    private fun at(position: Offset): Hsv {
        val now = picker.hsv
        return when (kind) {
            StripKind.Square -> {
                val inner = (picker.squareSide - 2f * Frame).coerceAtLeast(1f)
                val across = ((position.x - Frame) / inner).coerceIn(0f, 1f)
                val down = ((position.y - Frame) / inner).coerceIn(0f, 1f)
                now.copy(saturation = if (picker.mirrored) 1f - across else across, value = 1f - down)
            }
            StripKind.Hue -> now.copy(hue = 360f * fractionDown(position))
            StripKind.Alpha -> now.copy(alpha = 1f - fractionDown(position))
        }
    }

    private fun fractionDown(position: Offset): Float {
        val inner = (picker.stripLength - 2f * Frame).coerceAtLeast(1f)
        return ((position.y - Frame) / inner).coerceIn(0f, 1f)
    }
}

/**
 * What a picker knows, in one mutable object that outlives a recomposition.
 *
 * The colour is held as [Hsv] rather than as a [Colour], because a [Colour] forgets the hue of a
 * grey: a player who drags to the black edge and back would find the hue gone. It is only taken
 * again from the colour the screen passes in when that is a different colour.
 */
private class PickerLogic {

    /**
     * The colour as it is held. A plain field, so taking the screen's colour while composing changes
     * no state the picker has just read, which would compose it a second time for nothing.
     */
    private var held = Hsv(0f, 0f, 0f)

    /** Counts the player's changes: what recomposes the picker when input moves the colour. */
    private var moved by mutableIntStateOf(0)

    var hsv: Hsv
        get() {
            moved
            return held
        }
        set(value) {
            if (value == held) return
            held = value
            moved++
        }

    var alpha = false
    var enabled = true
    var mirrored = false
    var squareSide = 0f
    var stripLength = 0f
    var report: (Colour) -> Unit = {}
    var sounds: UiSounds = UiSounds.None
    var haptics: Haptics = Haptics.None

    /** Which way the hue is turning while a shoulder is held: -1, 0 or 1. */
    var spinning by mutableStateOf(0)

    /** The pad holding the shoulder down, so another pad being unplugged does not stop it. */
    private var spinningPad: GamepadId? = null

    /** Whether focus is somewhere inside the picker, which is the only place a shoulder's release is heard. */
    var focusInside by mutableStateOf(false)

    /** Whether the stick is moving the marker on the square. */
    var steering by mutableStateOf(false)

    private var stickX = 0f
    private var stickY = 0f

    /** The pad whose stick was last pushed. */
    private var stickPad: GamepadId? = null

    /** Whether the stick is pushed, whoever took the push. */
    private var pushing = false

    /** Whether this push has changed the colour, so letting go sounds once for it. */
    private var steered = false

    /** The screen's colour, unless it is the one already held. */
    fun take(colour: Colour) {
        val now = held
        if (now.toColour() == colour) return
        held = Hsv.of(colour, hue = now.hue, saturation = now.saturation)
    }

    /**
     * Moves to [wanted], and reports the colour if that changed it. False when it is where it was.
     *
     * The hue alone can change without the colour changing — a grey's — and is kept all the same.
     */
    fun change(wanted: Hsv): Boolean {
        val now = hsv
        val settled = if (alpha) wanted else wanted.copy(alpha = now.alpha)
        if (settled == now) return false
        val before = now.toColour()
        hsv = settled
        val after = settled.toColour()
        if (after != before) report(after)
        return true
    }

    /** A whole colour at once: a preset, or a hex typed in. */
    fun pick(colour: Colour) {
        if (!enabled) return
        val now = hsv
        val wanted = if (alpha) colour else colour.withAlpha(now.toColour().alpha)
        if (wanted == now.toColour()) return
        hsv = Hsv.of(wanted, hue = now.hue, saturation = now.saturation)
        report(wanted)
    }

    /** What a player typed as a colour this picker can take, or null. */
    fun parse(text: String): Colour? {
        val digits = text.trim().removePrefix("#").length
        if (!alpha && digits == 8) return null
        val colour = Colour.fromHex(text) ?: return null
        return if (alpha) colour else colour.withAlpha(hsv.toColour().alpha)
    }

    // --- the shoulders -------------------------------------------------------------------------------

    fun shoulder(event: GamepadEvent): Boolean {
        val by = when (event) {
            is GamepadEvent.ButtonDown -> bumper(event.button)
            is GamepadEvent.ButtonUp -> {
                val let = bumper(event.button)
                if (let == 0 || spinning != let) return false
                spinning = 0
                return true
            }
            // The pad went with the shoulder still down. Its release will never come.
            is GamepadEvent.Disconnected -> {
                if (event.gamepadId == spinningPad) spinning = 0
                return false
            }
            else -> 0
        }
        if (by == 0 || !enabled) return false
        spin(by)
        spinning = by
        spinningPad = event.gamepadId
        return true
    }

    private fun bumper(button: GamepadButton) = when (button) {
        GamepadButton.LeftBumper -> -1
        GamepadButton.RightBumper -> 1
        else -> 0
    }

    /** Turns the hue a notch, round past red rather than stopping at it: the wheel has no end. */
    fun spin(by: Int) {
        val now = hsv
        val turned = ((now.hue + by * HueTurn) % 360f + 360f) % 360f
        change(now.copy(hue = turned))
        sounds.change()
        haptics.perform(Haptic.Tick)
    }

    // --- the stick ----------------------------------------------------------------------------------

    /**
     * The left stick, offered while the square has focus.
     *
     * A push is the square's from the moment it starts until the stick is back at rest, unless it
     * starts pushing against an edge the marker is already on: that push is left to move focus, which
     * is how a player with only a stick leaves the square. Coming back to rest is never taken, so
     * whatever moves focus hears the stick let go.
     */
    fun stick(event: GamepadEvent): Boolean {
        // The pad went mid-push. The stick will never be heard coming back to rest, so it is at rest.
        if (event is GamepadEvent.Disconnected) {
            if (event.gamepadId == stickPad) {
                stickX = 0f
                stickY = 0f
                letGo()
            }
            return false
        }
        if (event !is GamepadEvent.Axis) return false
        when (event.axis) {
            GamepadAxis.LeftX -> stickX = event.value
            GamepadAxis.LeftY -> stickY = event.value
            // Another stick or a trigger, maybe on another pad: it must not take the left stick over.
            else -> return false
        }
        stickPad = event.gamepadId
        if (!enabled || (abs(stickX) < StickDeadZone && abs(stickY) < StickDeadZone)) {
            letGo()
            return false
        }
        if (!pushing) {
            pushing = true
            steering = !againstEdge()
        }
        return steering
    }

    fun letGo() {
        if (!pushing && !steering) return
        if (steered) sounds.change()
        steered = false
        pushing = false
        steering = false
    }

    /** One frame of a pushed stick: the marker moves, faster the further it is pushed. */
    fun steer(seconds: Float) {
        if (!steering || !enabled) return
        val x = if (abs(stickX) < StickDeadZone) 0f else stickX
        val y = if (abs(stickY) < StickDeadZone) 0f else stickY
        val now = hsv
        val across = (if (mirrored) -x else x) * StickSpeed * seconds
        val up = -y * StickSpeed * seconds
        val wanted = now.copy(
            saturation = (now.saturation + across).coerceIn(0f, 1f),
            value = (now.value + up).coerceIn(0f, 1f),
        )
        if (change(wanted)) steered = true
    }

    /** Whether the stick's main direction points off an edge the marker is already at. */
    private fun againstEdge(): Boolean {
        val now = hsv
        return if (abs(stickX) >= abs(stickY)) {
            val stronger = (stickX > 0f) != mirrored
            if (stronger) now.saturation >= 1f else now.saturation <= 0f
        } else {
            if (stickY < 0f) now.value >= 1f else now.value <= 0f
        }
    }
}

// --- drawing ------------------------------------------------------------------------------------------

private fun squarePainter(hsv: Hsv, mirrored: Boolean, marker: ResolvedStyle): UiCanvas.(Rect) -> Unit = { bounds ->
    val box = bounds.inset(Frame)
    if (drawsGradients) {
        // White to the pure hue across, then clear to black down over it: the whole square in two.
        val pure = Hsv(hsv.hue, 1f, 1f).toColour()
        rect(box, if (mirrored) Brush.horizontal(pure, Colour.White) else Brush.horizontal(Colour.White, pure))
        rect(box, Brush.vertical(Colour.Transparent, Colour.Black))
    } else {
        // A canvas with no gradients gets a grid of flat cells, which still reads as the square.
        val cells = 16
        val w = box.width / cells
        val h = box.height / cells
        for (row in 0 until cells) {
            for (column in 0 until cells) {
                val across = (column + 0.5f) / cells
                val colour = Hsv(hsv.hue, if (mirrored) 1f - across else across, 1f - (row + 0.5f) / cells).toColour()
                rect(Rect(box.left + column * w, box.top + row * h, box.left + (column + 1) * w, box.top + (row + 1) * h), colour)
            }
        }
    }
    val x = box.left + box.width * (if (mirrored) 1f - hsv.saturation else hsv.saturation)
    val y = box.top + box.height * (1f - hsv.value)
    ring(Offset(x, y), marker)
}

private fun huePainter(hsv: Hsv, marker: ResolvedStyle): UiCanvas.(Rect) -> Unit = { bounds ->
    val box = bounds.inset(Frame)
    // Six gradients, one between each pair of the wheel's corners, or bands where there are none.
    val bands = if (drawsGradients) 6 else 36
    val each = box.height / bands
    for (band in 0 until bands) {
        val from = Hsv(360f * band / bands, 1f, 1f).toColour()
        val to = Hsv(360f * (band + 1) / bands, 1f, 1f).toColour()
        val slice = Rect(box.left, box.top + band * each, box.right, box.top + (band + 1) * each)
        if (drawsGradients) rect(slice, Brush.vertical(from, to)) else rect(slice, from)
    }
    bar(box, box.top + box.height * (hsv.hue / 360f), marker)
}

private fun alphaPainter(hsv: Hsv, marker: ResolvedStyle, checker: ResolvedStyle): UiCanvas.(Rect) -> Unit = { bounds ->
    val box = bounds.inset(Frame)
    checkerboard(box, checker)
    val solid = hsv.copy(alpha = 1f).toColour()
    if (drawsGradients) {
        rect(box, Brush.vertical(solid, solid.withAlpha(0)))
    } else {
        val bands = 16
        val each = box.height / bands
        for (band in 0 until bands) {
            val slice = Rect(box.left, box.top + band * each, box.right, box.top + (band + 1) * each)
            rect(slice, solid.withAlpha((255 * (1f - (band + 0.5f) / bands)).toInt()))
        }
    }
    bar(box, box.top + box.height * (1f - hsv.alpha), marker)
}

private fun swatchPainter(colour: Colour, checker: ResolvedStyle): UiCanvas.(Rect) -> Unit = { box ->
    if (colour.alpha != 0xFF) checkerboard(box, checker)
    rect(box, colour)
}

/** Two alternating greys, so a see-through colour drawn over them shows it is see-through. */
private fun UiCanvas.checkerboard(box: Rect, style: ResolvedStyle) {
    rect(box, style.background.flatColour ?: Colour.LightGrey)
    val dark = style.textColour
    var row = 0
    var y = box.top
    while (y < box.bottom) {
        var x = box.left + if (row % 2 == 0) 0f else CheckerCell
        while (x < box.right) {
            rect(Rect(x, y, min(x + CheckerCell, box.right), min(y + CheckerCell, box.bottom)), dark)
            x += 2f * CheckerCell
        }
        y += CheckerCell
        row++
    }
}

/** The square's marker: a ring in the marker's text colour, outlined so it shows on white and on black. */
private fun UiCanvas.ring(centre: Offset, style: ResolvedStyle) {
    val outline = style.background.flatColour ?: Colour.Black
    border(Rect(centre.x - 6f, centre.y - 6f, centre.x + 6f, centre.y + 6f), outline, 4f, corner = 6f)
    border(Rect(centre.x - 5f, centre.y - 5f, centre.x + 5f, centre.y + 5f), style.textColour, 2f, corner = 5f)
}

/** A strip's marker: a bar across it, outlined the same way. */
private fun UiCanvas.bar(box: Rect, y: Float, style: ResolvedStyle) {
    val outline = style.background.flatColour ?: Colour.Black
    border(Rect(box.left - 2f, y - 4f, box.right + 2f, y + 4f), outline, 4f, corner = 2f)
    border(Rect(box.left - 1f, y - 3f, box.right + 1f, y + 3f), style.textColour, 2f, corner = 2f)
}
