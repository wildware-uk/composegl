package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.focus.FocusDirection
import dev.wildware.composegl.ui.focus.FocusWithinHandler
import dev.wildware.composegl.ui.focus.RevealHandler
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.DirectionHandler
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadHandler
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.IntrinsicMeasurable
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.worldPosition
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.draggable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onFocusDirection
import dev.wildware.composegl.ui.modifier.onFocusWithin
import dev.wildware.composegl.ui.modifier.onGamepadEvent
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.onReveal
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.node.CameraElement
import dev.wildware.composegl.ui.node.ContentCamera
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.skin.WidgetState
import dev.wildware.composegl.ui.skin.styled
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Where a child of a [PanZoomCanvas] sits in its world. See [worldPosition].
 */
data class WorldPositionElement(
    val x: Float,
    val y: Float,
    val anchor: Alignment,
    val scaleWithZoom: Boolean,
) : Modifier.Element

/**
 * Puts this child of a [PanZoomCanvas] at the world point ([x], [y]), at its natural size.
 *
 * [anchor] says which point of the child sits there: [Alignment.TopStart] puts its top-left corner
 * on it, which is what a tile board wants, and [Alignment.Centre] its middle, which is what a node
 * in a skill tree wants. Start is the child's left whichever way the screen reads, because a world
 * is a picture rather than a line of text: a map is not mirrored for a right-to-left language.
 *
 * With [scaleWithZoom] false the child follows the camera but stays its own size on screen — a
 * label, a pin, a player marker readable at any zoom. Its [anchor] point is what stays on the world
 * point.
 *
 * Only a [PanZoomCanvas] reads this. Anywhere else it does nothing.
 */
fun Modifier.worldPosition(
    x: Float,
    y: Float,
    anchor: Alignment = Alignment.TopStart,
    scaleWithZoom: Boolean = true,
) = then(WorldPositionElement(x, y, anchor, scaleWithZoom))

/** What a double click or the pad's reset button does to a [PanZoomCanvas]. */
enum class PanZoomReset {
    /** Eases back to the zoom and centre the state started with. See [PanZoomState.reset]. */
    Initial,

    /** Eases so the whole world is in view. See [PanZoomState.fit]. */
    Fit,

    /** Nothing: the screen has its own button for it, or no reason to. */
    None,
}

/**
 * A plane of UI that the player drags around and zooms: a world map, a skill tree, a tile board, a
 * node editor, a diagram bigger than the screen.
 *
 * ```kotlin
 * val camera = rememberPanZoomState(
 *     zoom = 1f, minZoom = 0.25f, maxZoom = 3f,
 *     bounds = Rect.of(0f, 0f, 4000f, 3000f),
 * )
 *
 * PanZoomCanvas(
 *     state = camera,
 *     modifier = Modifier.fillMaxSize(),
 *     background = remember(links) { { visible -> drawLinks(links, visible) } },
 * ) {
 *     skills.forEach { skill ->
 *         key(skill.id) {
 *             SkillNode(skill, Modifier.worldPosition(skill.x, skill.y, anchor = Alignment.Centre))
 *         }
 *     }
 * }
 *
 * camera.animateTo(centre = skill.position, zoom = 1.5f)
 * ```
 *
 * The children are laid out once, in world units, where [worldPosition] puts them — a child without
 * one sits at world (0, 0). Pan and zoom are a transform the canvas draws them through and the
 * pointer finds them through, so moving the camera measures and recomposes nothing, and a child is
 * as sharp at three times its size as at its own: every edge and letter is drawn at the size it
 * appears, not stretched from a picture. Clicks, hover, drags, focus rings and `boundsInRoot` all
 * work inside the plane with nothing special written for them.
 *
 * Text is made again at the nearest of a few [dev.wildware.composegl.ui.graphics.TextZoom] steps
 * once a gesture settles, and stretched between steps while it moves.
 *
 * [background] draws under the children, in world units, handed the part of the world in view: the
 * lines between skill nodes, a grid, terrain. One batched pass rather than a node per line, and it
 * can skip what is out of view. Remember it on the data it draws — `remember(links) { ... }`, as
 * every widget here remembers its own draw — because the canvas redraws when the lambda it was
 * given changes, and a fresh one each recomposition asks for a redraw each recomposition.
 *
 * A child entirely outside the view is neither drawn nor hit-tested, so a board of thousands draws
 * the few dozen on screen. Its subtree is still composed and laid out; for a world too big to
 * compose at all, [LazyPanZoomCanvas] composes only what is near the view.
 *
 * How it is moved:
 *
 * - **Drag** on empty space pans, and a fast one flings on when let go. A drag that starts on a
 *   button inside pans once it has moved further than a click would, and the button is not clicked;
 *   a slider inside keeps its own drag.
 * - **The wheel** zooms about the pointer, so what is under it stays under it. A sideways wheel pans.
 * - **Pinch** with two fingers zooms about their middle and pans with it.
 * - **Double click** on empty space does [reset].
 * - **The pad**, while focus is on the canvas or inside it: the left stick pans, the right trigger
 *   zooms in and the left one out, and [resetButton] does [reset]. The d-pad moves focus from node
 *   to node, and the camera eases to keep the focused node in view. With the canvas itself focused,
 *   a direction goes to the nearest node that way, or pans a step when there is none.
 * - **Keys**, the same way: `=` zooms in, `-` zooms out, `0` does [reset], and the arrows move focus
 *   as the d-pad does.
 *
 * [focusable] makes the canvas itself somewhere focus can be, which is how a pad reaches a map with
 * nothing focusable on it. Its look is the skin's `"<style>"`, plain and `focused`; the plane is
 * clipped to the canvas.
 *
 * A canvas that cannot transform — one whose [UiCanvas.transforms] is false — still pans, but draws
 * the world at its own size with no background, and the pointer agrees with it.
 */
@Composable
fun PanZoomCanvas(
    state: PanZoomState = rememberPanZoomState(),
    modifier: Modifier = Modifier,
    background: (UiCanvas.(visible: Rect) -> Unit)? = null,
    reset: PanZoomReset = PanZoomReset.Initial,
    resetButton: GamepadButton? = GamepadButton.RightStick,
    focusable: Boolean = true,
    style: String = "panzoom",
    interaction: InteractionState = remember { InteractionState() },
    content: @Composable () -> Unit,
) {
    val camera = remember(state) { PanZoomCamera(state) }
    camera.background = background

    val input = remember(state) { PanZoomInput(state) }
    input.reset = reset
    input.resetButton = resetButton

    val clocks = LocalClocks.current
    state.clocks = clocks
    DisposableEffect(state) { onDispose { state.detach() } }
    // Every frame, like a scrolling list's fling: waking only when something starts would cost the
    // first frames of it, which are the ones a player feels.
    LaunchedEffect(state, clocks) {
        var last = clocks.time(Clock.Ui)
        while (true) {
            withFrameNanos {
                val now = clocks.time(Clock.Ui)
                state.advance(now - last)
                last = now
            }
        }
    }

    val states = if (interaction.isFocused) FocusedOnly else emptySet()
    val handlers = remember(input) { input.handlers() }

    Layout(
        modifier = modifier
            .then(handlers.camera(camera))
            .onPlaced(handlers.placed)
            .interaction(interaction)
            .then(if (focusable) Modifier.focusable(interaction) else Modifier)
            .onFocusDirection(handlers.directions)
            .onFocusWithin(handlers.within)
            .onKeyEvent(handlers.keys)
            .onGamepadEvent(handlers.pad)
            .onReveal(handlers.reveal)
            .onPointer(handlers.pointer)
            // Clickable for the double click, and so a press on empty space is the canvas's and does
            // not fall through to whatever is behind it.
            .clickable(onDoubleClick = handlers.doubleClick, onClick = NoClick)
            .draggable(
                onDragStart = handlers.dragStart,
                onDragEnd = handlers.dragEnd,
                onDragCancel = handlers.dragCancel,
                onDrag = handlers.drag,
            )
            .styled(style, states)
            .clip(),
        name = "panzoom",
        content = content,
        measurePolicy = PanZoomPolicy(state),
    )
}

/**
 * A [PanZoomCanvas] for a world too big to compose: only the items whose [area] is in view, or
 * within [margin] of it on screen, exist at all.
 *
 * ```kotlin
 * LazyPanZoomCanvas(
 *     items = tiles,                                   // 40,000 of them
 *     area = { Rect.of(it.col * 64f, it.row * 64f, 64f, 64f) },
 *     state = camera,
 *     key = { it.id },
 * ) { tile -> Tile(tile) }
 * ```
 *
 * Each item is composed in a box of exactly its [area], so a 200 × 200 board shows a few hundred
 * nodes, not forty thousand. The items are sorted into a grid of [cellSize] world units once per
 * list, and the set composed changes only when the view moves into a different cell — so a pan
 * across one cell recomposes nothing, and one across many recomposes the items coming and going.
 *
 * [area] is read when [items] changes, not every frame. [key] keeps an item's state as it scrolls
 * out and back in, as a lazy list's does; without it an item is known by its index. Everything else
 * is [PanZoomCanvas]'s.
 */
@Composable
fun <T> LazyPanZoomCanvas(
    items: List<T>,
    area: (T) -> Rect,
    state: PanZoomState = rememberPanZoomState(),
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = null,
    margin: Float = 64f,
    cellSize: Float = 256f,
    background: (UiCanvas.(visible: Rect) -> Unit)? = null,
    reset: PanZoomReset = PanZoomReset.Initial,
    resetButton: GamepadButton? = GamepadButton.RightStick,
    focusable: Boolean = true,
    style: String = "panzoom",
    itemContent: @Composable (T) -> Unit,
) {
    require(cellSize > 0f) { "a cell has to have some size, was $cellSize" }
    val index = remember(items, cellSize) { WorldIndex(Array(items.size) { area(items[it]) }, cellSize) }
    val window by remember(state, index, margin) {
        derivedStateOf { index.window(state.visibleWorld, margin / state.zoom) }
    }
    PanZoomCanvas(
        state = state,
        modifier = modifier,
        background = background,
        reset = reset,
        resetButton = resetButton,
        focusable = focusable,
        style = style,
    ) {
        val shown = index.within(window)
        for (at in shown) {
            val item = items[at]
            val box = index.areas[at]
            key(key?.invoke(item) ?: at) {
                Box(Modifier.worldPosition(box.left, box.top).size(box.width, box.height)) { itemContent(item) }
            }
        }
    }
}

private val FocusedOnly = setOf(WidgetState.Focused)

private val NoClick: () -> Unit = {}

/** What the draw pass, the pointer and a bounds walk ask of the canvas node. See [ContentCamera]. */
internal class PanZoomCamera(private val state: PanZoomState) : ContentCamera {

    /**
     * What draws under the children. A redraw is asked for when it changes, which is by identity:
     * the caller's lambda, remembered on the data it draws as a widget's own draw is.
     */
    var background: (UiCanvas.(Rect) -> Unit)? = null
        set(value) {
            if (field === value) return
            field = value
            state.node?.invalidate()
        }

    override val zoom: Float get() = state.zoom

    override fun panX(atZoom: Float): Float =
        if (atZoom == state.zoom) state.pan.x else state.viewport.width / 2f - state.centre.x * atZoom

    override fun panY(atZoom: Float): Float =
        if (atZoom == state.zoom) state.pan.y else state.viewport.height / 2f - state.centre.y * atZoom
    override val textZoom: Float get() = state.textZoom

    override fun attach(node: UiNode) {
        state.node = node
    }

    override fun drawBackground(canvas: UiCanvas, visible: Rect) {
        val draw = background ?: return
        canvas.draw(visible)
    }
}

/**
 * The children at their world positions, at their natural size, and the canvas as big as it is
 * allowed: a view onto the world, not something sized by it. Told to the state as the viewport.
 */
private data class PanZoomPolicy(private val state: PanZoomState) : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val width = if (constraints.maxWidth.isFinite()) constraints.maxWidth else constraints.minWidth
        val height = if (constraints.maxHeight.isFinite()) constraints.maxHeight else constraints.minHeight
        state.measured(width, height)
        val placeables = placeables(measurables.size)
        for (index in measurables.indices) placeables[index] = measurables[index].measure(Unbounded)
        return layout(width, height) {
            for (index in measurables.indices) {
                val placeable = checkNotNull(placeables[index])
                val pin = measurables[index].worldPosition
                if (pin == null) {
                    placeable.at(0f, 0f)
                } else {
                    placeable.at(
                        pin.x - pin.anchor.xIn(placeable.width, 0f),
                        pin.y - pin.anchor.yIn(placeable.height, 0f),
                    )
                }
            }
        }
    }

    // A view wants no size of its own, and a question must not move the camera.
    override fun MeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) = 0f
    override fun MeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) = 0f
    override fun MeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) = 0f
    override fun MeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) = 0f

    private companion object {
        val Unbounded = Constraints()
    }
}

/** Every handler the canvas node carries, made once per state so the chain compares equal. */
internal class PanZoomHandlers(
    val placed: PlacedHandler,
    val directions: DirectionHandler,
    val within: FocusWithinHandler,
    val keys: KeyHandler,
    val pad: GamepadHandler,
    val reveal: RevealHandler,
    val pointer: PointerHandler,
    val doubleClick: () -> Unit,
    val dragStart: (Offset) -> Unit,
    val drag: (Offset) -> Unit,
    val dragEnd: () -> Unit,
    val dragCancel: () -> Unit,
) {
    private var element: CameraElement? = null

    fun camera(camera: PanZoomCamera): CameraElement =
        element?.takeIf { it.camera === camera } ?: CameraElement(camera).also { element = it }
}

/**
 * The pointer, the keys and the pad, turned into camera moves.
 *
 * The pointer arrives between frames in the middle of a gesture, so this is a plain object the
 * canvas keeps, as a scrolling area's gestures are.
 */
internal class PanZoomInput(private val state: PanZoomState) {

    var reset = PanZoomReset.Initial
    var resetButton: GamepadButton? = GamepadButton.RightStick

    // Up to two pointers on the canvas, in its own units: one pans, two pinch.
    private val ids = LongArray(2)
    private val xs = FloatArray(2)
    private val ys = FloatArray(2)
    private var count = 0

    private var pinchDistance = 0f
    private var pinchX = 0f
    private var pinchY = 0f

    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private var speedX = 0f
    private var speedY = 0f

    fun handlers() = PanZoomHandlers(
        placed = PlacedHandler { state.node = it },
        directions = DirectionHandler { direction(it) },
        within = FocusWithinHandler { focused -> if (!focused) state.releasePad() },
        keys = KeyHandler { key(it) },
        pad = GamepadHandler { pad(it) },
        reveal = RevealHandler { area -> reveal(area) },
        pointer = PointerHandler { pointer(it) },
        doubleClick = { resetView() },
        dragStart = { dragStart() },
        drag = { delta -> if (count < 2) state.dragBy(delta.x, delta.y) },
        dragEnd = { dragEnd(fling = true) },
        dragCancel = { dragEnd(fling = false) },
    )

    fun resetView() = when (reset) {
        PanZoomReset.Initial -> state.reset()
        PanZoomReset.Fit -> state.fit()
        PanZoomReset.None -> Unit
    }

    // --- the pointer ------------------------------------------------------------------------------

    private fun pointer(event: PointerEvent): Boolean = when (event) {
        is PointerEvent.Press -> {
            // A hand on a moving camera catches it.
            touch(event.pointerId, event.position)
            lastTime = event.timeMillis
            speedX = 0f
            speedY = 0f
            // The press is the draggable's and the clickable's to take; this only watches.
            false
        }

        is PointerEvent.Move -> {
            if (event.pressed.isNotEmpty()) moved(event)
            false
        }

        is PointerEvent.Release, is PointerEvent.Cancel -> {
            lift(event.pointerId)
            false
        }

        is PointerEvent.Scroll -> {
            val at = contentPoint(event.position)
            if (event.delta.y != 0f) state.zoomAbout(at, state.zoom * WheelZoom.pow(-event.delta.y))
            if (event.delta.x != 0f) state.panBy(-event.delta.x * WheelPan, 0f)
            true
        }

        is PointerEvent.Exit -> false
    }

    /** A point of the canvas node's, from the corner of its content, where the camera measures from. */
    private fun contentPoint(point: Offset): Offset {
        val padding = state.node?.resolved?.padding ?: return point
        return Offset(point.x - padding.left, point.y - padding.top)
    }

    private fun slot(id: PointerId): Int {
        for (at in 0 until count) if (ids[at] == id.value) return at
        return -1
    }

    private fun touch(id: PointerId, at: Offset) {
        if (slot(id) >= 0 || count == 2) return
        val point = contentPoint(at)
        ids[count] = id.value
        xs[count] = point.x
        ys[count] = point.y
        count++
        // A hand on a moving camera catches it where it is.
        state.stop()
        hold()
        lastX = point.x
        lastY = point.y
        if (count == 2) startPinch()
    }

    private fun moved(event: PointerEvent.Move) {
        var at = slot(event.pointerId)
        // A press a button inside took, handed to the canvas once it became a drag.
        if (at < 0) {
            touch(event.pointerId, event.position)
            at = slot(event.pointerId)
            if (at < 0) return
        }
        val point = contentPoint(event.position)
        xs[at] = point.x
        ys[at] = point.y
        if (count == 2) {
            pinch()
            return
        }
        val elapsed = (event.timeMillis - lastTime) / 1000f
        if (elapsed > 0f) {
            // Smoothed, as a list's is, so the last jittery sample does not decide the whole flick.
            speedX = speedX * (1f - Smoothing) + (point.x - lastX) / elapsed * Smoothing
            speedY = speedY * (1f - Smoothing) + (point.y - lastY) / elapsed * Smoothing
        }
        lastTime = event.timeMillis
        lastX = point.x
        lastY = point.y
    }

    private fun lift(id: PointerId) {
        val at = slot(id)
        if (at < 0) return
        if (at == 0 && count == 2) {
            ids[0] = ids[1]
            xs[0] = xs[1]
            ys[0] = ys[1]
        }
        count--
        if (count < 2) {
            pinchDistance = 0f
            // A finger still down carries on panning from where it is, not from where it was, and
            // at its own speed rather than the pinch's. The last one up keeps the speed it measured:
            // the router delivers the release here before it ends the drag, and the speed is what
            // dragEnd flings with.
            if (count > 0) {
                lastX = xs[0]
                lastY = ys[0]
                speedX = 0f
                speedY = 0f
            }
        }
        hold()
    }

    private fun startPinch() {
        pinchX = (xs[0] + xs[1]) / 2f
        pinchY = (ys[0] + ys[1]) / 2f
        pinchDistance = distance()
        speedX = 0f
        speedY = 0f
    }

    private fun pinch() {
        val x = (xs[0] + xs[1]) / 2f
        val y = (ys[0] + ys[1]) / 2f
        val spread = distance()
        if (pinchDistance > 0f && spread > 0f) state.pinch(pinchX, pinchY, x, y, spread / pinchDistance)
        pinchX = x
        pinchY = y
        pinchDistance = spread
    }

    private fun distance(): Float {
        val dx = xs[1] - xs[0]
        val dy = ys[1] - ys[0]
        return sqrt(dx * dx + dy * dy)
    }

    private fun dragStart() {
        if (dragging) return
        dragging = true
        hold()
    }

    private fun dragEnd(fling: Boolean) {
        if (!dragging) return
        dragging = false
        // A drag taken away — the window lost focus mid-gesture — takes the fingers with it: the
        // router lets go of the gesture without telling anyone the pointers came up.
        if (!fling) count = 0
        hold()
        // Last hand off a flick, so the camera carries on. This is after the release rather than in
        // it because the router lets the drag catch up with the pointer in between, and that last
        // step would stop a fling that had already started.
        if (fling && count == 0) state.fling(speedX, speedY)
        speedX = 0f
        speedY = 0f
    }

    /** Tells the state whether a hand is still on the canvas. */
    private fun hold() = state.holding(count > 0 || dragging)

    // --- keys, the pad and focus --------------------------------------------------------------------

    private fun key(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.Down) return false
        val middle = Offset(state.viewport.width / 2f, state.viewport.height / 2f)
        return when (event.key) {
            Key.Equals -> {
                state.zoomAbout(middle, state.zoom * KeyZoom)
                true
            }
            Key.Minus -> {
                state.zoomAbout(middle, state.zoom / KeyZoom)
                true
            }
            Key.Digit0 -> {
                resetView()
                true
            }
            else -> false
        }
    }

    private fun pad(event: GamepadEvent): Boolean = when (event) {
        is GamepadEvent.Axis -> when (event.axis) {
            GamepadAxis.LeftX -> { state.stickX = event.value; true }
            GamepadAxis.LeftY -> { state.stickY = event.value; true }
            GamepadAxis.RightTrigger -> { state.zoomingIn = event.value; true }
            GamepadAxis.LeftTrigger -> { state.zoomingOut = event.value; true }
            else -> false
        }
        is GamepadEvent.ButtonDown -> if (event.button == resetButton) {
            resetView()
            true
        } else {
            false
        }
        else -> false
    }

    /** The focused node's rectangle, in the canvas node's units, brought into view. */
    private fun reveal(area: Rect): Boolean {
        val padding = state.node?.resolved?.padding
        val inContent = if (padding == null) area else Rect(area.left - padding.left, area.top - padding.top, area.right - padding.left, area.bottom - padding.top)
        state.reveal(inContent)
        // Said not to have used it, so a scrolling area round the canvas can bring the canvas into view too.
        return false
    }

    /**
     * A direction with the canvas itself focused: to the nearest focusable node that way from the
     * middle of the view, or a pan step when there is none. False at an edge with nothing further,
     * so a press can carry focus out of the canvas.
     */
    private fun direction(direction: FocusDirection): Boolean {
        val canvas = state.node ?: return false
        val target = nearest(canvas, direction)
        if (target != null) {
            val manager = rootOf(canvas).focusManager ?: return false
            return manager.focusOn(target)
        }
        val stepX = state.viewport.width * PanStep
        val stepY = state.viewport.height * PanStep
        val (dx, dy) = when (direction) {
            FocusDirection.Left -> stepX to 0f
            FocusDirection.Right -> -stepX to 0f
            FocusDirection.Up -> 0f to stepY
            FocusDirection.Down -> 0f to -stepY
            else -> return false
        }
        if (!state.canPan(dx, dy)) return false
        val world = state.centre
        state.animateTo(Offset(world.x - dx / state.zoom, world.y - dy / state.zoom), state.zoom, PanZoomState.RevealMillis)
        return true
    }

    private fun nearest(canvas: UiNode, direction: FocusDirection): UiNode? {
        val view = canvas.boundsInRoot
        val fromX = (view.left + view.right) / 2f
        val fromY = (view.top + view.bottom) / 2f
        var best: UiNode? = null
        var bestScore = Float.MAX_VALUE
        fun visit(node: UiNode) {
            for (child in node.children) {
                if (child.resolved.alpha <= 0f) continue
                if (child.resolved.focusable?.enabled == true) {
                    val box = child.boundsInRoot
                    if (!box.isEmpty) {
                        val dx = (box.left + box.right) / 2f - fromX
                        val dy = (box.top + box.bottom) / 2f - fromY
                        val (along, across) = when (direction) {
                            FocusDirection.Left -> -dx to dy
                            FocusDirection.Right -> dx to dy
                            FocusDirection.Up -> -dy to dx
                            FocusDirection.Down -> dy to dx
                            else -> return
                        }
                        // Along the direction counts far more than across it, as focus search does.
                        val score = along + abs(across) * AcrossWeight
                        if (along > 0f && score < bestScore) {
                            best = child
                            bestScore = score
                        }
                    }
                }
                visit(child)
            }
        }
        visit(canvas)
        return best
    }

    private fun rootOf(node: UiNode): UiNode {
        var root = node
        while (true) root = root.parent ?: return root
    }

    private companion object {
        /** One notch of the wheel zooms by this much. */
        const val WheelZoom = 1.15f
        const val WheelPan = 48f
        const val KeyZoom = 1.25f
        const val PanStep = 0.25f
        const val Smoothing = 0.4f
        const val AcrossWeight = 2f
    }
}

/**
 * Where each item of a [LazyPanZoomCanvas] is, sorted into square cells, so the items near the view
 * are found without looking at the rest.
 */
internal class WorldIndex(val areas: Array<Rect>, private val cellSize: Float) {

    private val cells = HashMap<Long, MutableList<Int>>()

    init {
        for (at in areas.indices) {
            val box = areas[at]
            for (column in cell(box.left)..cell(box.right)) {
                for (row in cell(box.top)..cell(box.bottom)) {
                    cells.getOrPut(key(column, row)) { ArrayList(4) } += at
                }
            }
        }
    }

    /** The cells a view, grown by [margin] world units, touches. Equal while the view stays in them. */
    fun window(visible: Rect, margin: Float): CellWindow = CellWindow(
        cell(visible.left - margin),
        cell(visible.top - margin),
        cell(visible.right + margin),
        cell(visible.bottom + margin),
    )

    /** The items with any part in [window], in list order. */
    fun within(window: CellWindow): List<Int> {
        val columns = window.right.toLong() - window.left + 1
        val rows = window.bottom.toLong() - window.top + 1
        val found = HashSet<Int>()
        if (columns * rows > cells.size) {
            // A view wider than the world has more cells than there are filled ones: ask those.
            for ((packed, inCell) in cells) {
                val column = (packed shr 32).toInt()
                val row = packed.toInt()
                if (column in window.left..window.right && row in window.top..window.bottom) found += inCell
            }
        } else {
            for (column in window.left..window.right) {
                for (row in window.top..window.bottom) cells[key(column, row)]?.let { found += it }
            }
        }
        return found.sorted()
    }

    private fun cell(at: Float): Int = floor(at / cellSize).toInt()

    private fun key(column: Int, row: Int): Long = (column.toLong() shl 32) or (row.toLong() and 0xFFFFFFFFL)
}

/** A block of cells, inclusive, in a [WorldIndex]. */
internal data class CellWindow(val left: Int, val top: Int, val right: Int, val bottom: Int)
