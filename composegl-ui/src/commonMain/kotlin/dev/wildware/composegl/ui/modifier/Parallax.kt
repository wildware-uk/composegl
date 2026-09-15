package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerType
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.widget.ScrollState

/**
 * Something a layer can drift against: how far it has moved from rest, in the interface's units.
 *
 * Compose state underneath, so reading [position] while composing subscribes to it and the screen
 * recomposes the moment it moves. Zero is rest — the pointer in the middle, the stick let go, the
 * list at its top — so a layer under a still source sits exactly where layout put it.
 *
 * Three come with the toolkit: [PointerParallax], [StickParallax] and [ScrollParallax]. Anything
 * else that moves — a camera, a ship's heading — is one state-backed property away.
 */
@Stable
interface ParallaxSource {
    val position: Offset
}

/**
 * How far the pointer is from [centre].
 *
 * Fed with [saw], or by wrapping a sink in [ParallaxAware]. The mouse leaving the window puts it
 * back at rest, and so does a finger lifting: neither has a place to be any more, and a background
 * left leaning towards where the cursor last was looks stuck rather than deep.
 *
 * @param centre the point that counts as rest, in the same coordinates the pointer arrives in —
 *   usually the middle of the design resolution. Settable, for a window that changes shape.
 */
@Stable
class PointerParallax(centre: Offset) : ParallaxSource {

    var centre: Offset by mutableStateOf(centre)

    private var pointer: Offset? by mutableStateOf(null)

    /**
     * The pointer that last moved. With two fingers down, lifting the other one is not a reason to
     * send the scenery home while this one is still pressing.
     */
    private var following: PointerId? = null

    override val position: Offset
        get() = pointer?.let { it - centre } ?: Offset.Zero

    fun saw(event: PointerEvent) {
        when (event) {
            is PointerEvent.Move, is PointerEvent.Press -> {
                pointer = event.position
                following = event.pointerId
            }
            // A mouse still has a place after it lets go. A finger does not.
            is PointerEvent.Release -> if (event.pointerId == following) {
                pointer = if (event.type.hovers) event.position else null
            }
            is PointerEvent.Exit -> if (event.pointerId == following) pointer = null
            // A cancel takes the gesture away, not the pointer: the mouse is still where it was.
            is PointerEvent.Cancel, is PointerEvent.Scroll -> Unit
        }
    }

    private val PointerType.hovers: Boolean
        get() = this == PointerType.Mouse || this == PointerType.Ray
}

/**
 * How far a stick is pushed, as a distance.
 *
 * A stick reports -1 to 1, which is no use as an offset, so [reach] is what full deflection is
 * worth: a stick pushed all the way right is `reach` units right of rest. That keeps a single
 * `factor` meaning the same thing against a stick as against a pointer — with [reach] set to half
 * the screen, a stick at the edge moves a layer exactly as far as a mouse at the edge.
 *
 * The right stick by default, because on a menu the left one is already moving focus.
 *
 * @param reach what full deflection is worth, in the interface's units.
 */
@Stable
class StickParallax(
    val reach: Offset,
    val x: GamepadAxis = GamepadAxis.RightX,
    val y: GamepadAxis = GamepadAxis.RightY,
) : ParallaxSource {

    /** The same reach on both axes, for a square screen or a caller who does not mind. */
    constructor(reach: Float, x: GamepadAxis = GamepadAxis.RightX, y: GamepadAxis = GamepadAxis.RightY) :
        this(Offset(reach, reach), x, y)

    private var deflection: Offset by mutableStateOf(Offset.Zero)

    /** The pad that last pushed this stick, so a second pad being unplugged does not undo its lean. */
    private var leaning: GamepadId? = null

    override val position: Offset
        get() = Offset(deflection.x * reach.x, deflection.y * reach.y)

    fun saw(event: GamepadEvent) {
        when (event) {
            is GamepadEvent.Axis -> when (event.axis) {
                x -> {
                    deflection = Offset(event.value, deflection.y)
                    leaning = event.gamepadId
                }
                y -> {
                    deflection = Offset(deflection.x, event.value)
                    leaning = event.gamepadId
                }
                else -> Unit
            }
            // A pad pulled out mid-lean would otherwise leave the menu leaning for good.
            is GamepadEvent.Disconnected -> if (event.gamepadId == leaning) deflection = Offset.Zero
            else -> Unit
        }
    }
}

/**
 * How far the contents of a [dev.wildware.composegl.ui.widget.ScrollArea] have moved.
 *
 * Negative as the list goes down, because the contents move *up*: a layer at factor one travels
 * with the rows, and one at a half travels at half their speed — the far hills behind a long page.
 */
@Stable
class ScrollParallax(private val state: ScrollState) : ParallaxSource {
    // Subtracted from zero rather than negated: a list at its top is 0, and -0 would compare unequal
    // to it, costing a still screen a frame.
    override val position: Offset get() = Offset(0f - state.x, 0f - state.y)
}

/**
 * Everything passing through [sink] also moves [pointer] and [stick].
 *
 * Wraps rather than intercepts, the way [dev.wildware.composegl.ui.input.SourceAware] does: the
 * sources see every event, including the ones the interface uses, and the answers are the sink's,
 * unchanged. A background should drift while the cursor is over a button as well as between them.
 */
class ParallaxAware(
    private val sink: InputSink,
    private val pointer: PointerParallax? = null,
    private val stick: StickParallax? = null,
) : InputSink {

    override fun onPointer(event: PointerEvent): Boolean {
        pointer?.saw(event)
        return sink.onPointer(event)
    }

    override fun onKey(event: KeyEvent): Boolean = sink.onKey(event)

    override fun onText(event: TextEvent): Boolean = sink.onText(event)

    override fun onGamepad(event: GamepadEvent): Boolean {
        stick?.saw(event)
        return sink.onGamepad(event)
    }
}

/**
 * Moves this node by [factor] times how far [source] has moved from rest.
 *
 * Cheap depth: the far layer at a small factor, the near one at a bigger one, and a main menu that
 * follows the mouse or the stick looks like it has something behind it.
 *
 * ```kotlin
 * val pointer = remember { PointerParallax(centre = Offset(640f, 360f)) }
 * Image(sky, Modifier.fillMaxSize().parallax(pointer, factor = -0.02f))
 * Image(hills, Modifier.fillMaxSize().parallax(pointer, factor = -0.06f))
 * ```
 *
 * An [offset] and nothing more, so layout does not move: siblings keep their places and the node
 * keeps its slot. What does move is where it is drawn and where it is clicked, together, so a
 * button riding a foreground layer is pressed where the player sees it. A positive factor follows
 * the source; a negative one leans away from it, which is what a camera looking past the pointer
 * does. Two on one node add, so a pointer and a stick can both steer the same layer.
 *
 * The source is read here, while composing, so call this inside the composable that draws the
 * layer. Every move of the source recomposes that scope — nothing for a menu, but put the layers in
 * a small composable of their own rather than at the top of a screen with a hundred widgets in it.
 * A still source costs nothing: the element compares equal and the frame is free.
 *
 * Give a layer that fills the screen a margin to drift into — a little bigger than the screen, or a
 * [clip] on its parent — or its edge shows when it moves.
 *
 * @throws IllegalArgumentException if [factor] is not a number or is infinite.
 */
fun Modifier.parallax(source: ParallaxSource, factor: Float): Modifier {
    require(!factor.isNaN()) { "a parallax factor cannot be NaN" }
    require(!factor.isInfinite()) { "a parallax factor cannot be infinite, was $factor" }
    val moved = source.position * factor
    // Plus zero turns a -0 (rest, times a negative factor) into 0, so rest always compares equal.
    return then(OffsetElement(moved.x + 0f, moved.y + 0f))
}
