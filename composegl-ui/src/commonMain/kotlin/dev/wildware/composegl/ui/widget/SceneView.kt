package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.SceneSurface
import dev.wildware.composegl.ui.graphics.SceneTarget
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadHandler
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.GamepadNavigator
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.LocalUiSounds
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.input.movedTo
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.drawInFront
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onGamepadEvent
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.skin.ResolvedStyle
import dev.wildware.composegl.ui.skin.WidgetState
import dev.wildware.composegl.ui.skin.rememberStyle

/**
 * A panel with the game's own 3D scene inside it.
 *
 * ```kotlin
 * val scene = rememberSceneViewState()
 *
 * SceneView(
 *     scene,
 *     Modifier.size(480f, 270f),
 *     onPointer = { e -> camera.orbit(e.position); true },   // (0, 0) is the picture's corner
 * ) {
 *     clear(Colour.Black)
 *     raw { frame -> myRenderer.draw(frame as GlFrame, width, height) }
 * }
 *
 * scene.invalidate()   // draw it again, once, next frame
 * ```
 *
 * It is an ordinary layout node: a size modifier, a weight, a splitter or a debug window places it
 * without knowing what it is. It takes the room it is given and asks for none of its own.
 *
 * The scene is not drawn while the tree is. The state owns an offscreen picture with a depth
 * buffer, sized to the panel's real pixels; a prepass run by
 * [UiRenderer][dev.wildware.composegl.ui.host.UiRenderer] — or by a
 * [WorldPanel][dev.wildware.composegl.ui.world.WorldPanel], for one on a panel in the world — after
 * layout and before the frame fills every dirty one by calling [draw]; and then the tree draws the
 * picture as an image. So clipping,
 * rounded corners and the effect modifiers work on it unchanged, and a scene nobody marked dirty
 * costs a comparison. See [dev.wildware.composegl.ui.draw.ScenePass] for driving that step yourself.
 *
 * **Input.** A pointer event's position is in the picture's own pixels — the units [draw] is given
 * its `width` and `height` in — with `(0, 0)` at the picture's top-left corner on screen. That holds
 * whatever is round the panel: a scrolled column, a splitter, the viewport's design-to-pixel
 * scaling, a `scale` modifier, padding, [SceneViewState.resolutionScale] and right to left. So the
 * far corner is `(width, height)`, and a position goes straight into the scene's own unprojection.
 * A press the handler takes holds the pointer, so a drag that leaves the panel keeps arriving, with
 * positions below zero or past the size, until the button comes up. Returning false lets an event
 * carry on to whatever is behind: a click to the card the preview sits on, a wheel to the list.
 *
 * The widget interprets nothing: no camera, no scene graph, no picking. Right to left moves the
 * panel to the other side like anything else, and does not mirror the picture — a scene is not
 * text. Use one [SceneViewState] per `SceneView`.
 *
 * The picture is made on the first render, stretched while the panel is being resized and remade
 * once its size holds for a frame, capped at the device's biggest texture, and given back when this
 * leaves the composition; see [ScenePass][dev.wildware.composegl.ui.draw.ScenePass] for the rules.
 *
 * @param state what owns the picture, its size and whether it needs drawing again.
 * @param onPointer pointer events over the panel, and every event of a gesture it took the press
 *   of, positioned in the picture's pixels. Return true to use the event.
 * @param onKey keys while focus is on the panel. Return true to use the key; false lets Tab and the
 *   arrows move focus on as usual. A key or button whose down was heard here is not heard coming up
 *   if focus moved away first: stop what it started when [interaction] loses focus.
 * @param onPad pad buttons and sticks while focus is on the panel, before the pad navigates. Return
 *   true to use the event; false lets the d-pad or the stick move focus out. Taking a stick coming
 *   back to rest does not keep the pad's navigator from letting go of it.
 * @param focusable whether Tab, the pad and a taken press can put focus here. On whenever there is
 *   a handler, and off for a picture with none, so a shelf of previews is not a row of tab stops.
 * @param initialFocus true on the one node a screen should open with focus on.
 * @param style the skin name drawn over the picture: `"sceneview"`, whose `focused` state is the
 *   ring a keyboard or pad player sees.
 * @param interaction the panel's hover, press and focus, for a game that draws its own ring.
 * @param draw fills the picture. Runs only when [state] is dirty or the panel's new pixel size has
 *   held for a frame, and the panel is on the screen, with the picture bound; see [SceneDrawScope].
 */
@Composable
fun SceneView(
    state: SceneViewState,
    modifier: Modifier = Modifier,
    onPointer: ((PointerEvent) -> Boolean)? = null,
    onKey: ((KeyEvent) -> Boolean)? = null,
    onPad: ((GamepadEvent) -> Boolean)? = null,
    focusable: Boolean = onPointer != null || onKey != null || onPad != null,
    initialFocus: Boolean = false,
    style: String = "sceneview",
    interaction: InteractionState = remember { InteractionState() },
    draw: SceneDrawScope.() -> Unit,
) {
    // After the composition applies and before the prepass reads it, whichever lambda is newest.
    SideEffect { state.content = draw }
    val sounds = LocalUiSounds.current
    val direction = LocalLayoutDirection.current
    val paint = remember(state) { scenePainter(state) }
    // Leaving the composition gives the picture back, whoever holds the state: a list of previews
    // frees each one as its row scrolls away, not when the list itself goes.
    DisposableEffect(state) { onDispose { state.release() } }

    // One handler object per state, reading the newest lambdas, so the chain compares equal from one
    // recomposition to the next and a drag in progress is not lost to a new closure.
    val input = remember(state) { SceneInput(state) }
    input.pointer = onPointer
    input.key = onKey
    input.pad = onPad
    val focused = interaction.isFocused
    // Keys and buttons that went down here and are still down when focus leaves will come up
    // somewhere else. Forget them, so an up heard after focus comes back is not taken for theirs.
    SideEffect { if (!focused) input.forgetHeld() }
    val ring = rememberStyle(style, if (focused) FocusedOnly else NoStates)
    val front = remember(ring) { ringPainter(ring) }
    val chain = modifier
        .then(if (focusable || onPointer != null) Modifier.interaction(interaction) else Modifier)
        .then(if (focusable) Modifier.focusable(interaction, initial = initialFocus) else Modifier)
        .then(if (onPointer != null) Modifier.onPointer(input.pointerHandler) else Modifier)
        .then(if (onKey != null) Modifier.onKeyEvent(input.keyHandler) else Modifier)
        .then(if (onPad != null) Modifier.onGamepadEvent(input.padHandler) else Modifier)
        // Last in the chain, so it is inset by the caller's padding and sits on the picture's edge.
        .then(if (focusable) Modifier.drawInFront(front) else Modifier)

    ComposeNode<UiNode, UiApplier>(
        factory = { UiNode("scene view") },
        update = {
            set(state) {
                sceneView?.let { old -> if (old.node === this) old.node = null }
                sceneView = it
                it.node = this
            }
            set(sounds) { this.sounds = it }
            set(direction) { this.layoutDirection = it }
            set(chain) { this.modifier = it }
            set(MeasurePolicy.Empty) { this.measurePolicy = it }
            set(paint) { this.content = it }
        },
    )
}

private val FocusedOnly = setOf(WidgetState.Focused)
private val NoStates = emptySet<WidgetState>()

/** The skin's drawing for the panel's state, over the picture: in every shipped skin, a ring while focused and nothing otherwise. */
private fun ringPainter(style: ResolvedStyle): UiCanvas.(Rect) -> Unit = { bounds ->
    if (!bounds.isEmpty) style.background.drawInto(this, bounds, style.tint)
}

/**
 * The three handlers a [SceneView] puts on its node. Made once per state and reading the newest
 * lambdas; the pointer's positions go through [SceneViewState.toPicture] on the way.
 *
 * One rule on top, and it is not interpretation: a key or pad button coming **up** is offered only
 * when its going down was offered here too. Focus arrives on the panel on a press — Tab, the
 * d-pad — whose release lands on the panel. A game handler that takes everything would swallow that
 * release, and the navigator, still holding the d-pad down, would never move focus again.
 *
 * A stick has no down to match, so it has no rule here: [GamepadNavigator] lets go of a stick that
 * comes back inside its dead zone whoever takes the event, which only it can do, knowing its own
 * dead zone and what it is holding. And what was down when focus left is forgotten, because its up
 * lands on whatever has focus by then.
 */
private class SceneInput(private val state: SceneViewState) {
    var pointer: ((PointerEvent) -> Boolean)? = null
    var key: ((KeyEvent) -> Boolean)? = null
    var pad: ((GamepadEvent) -> Boolean)? = null

    private val keysDown = HashSet<Key>(4)
    private val buttonsDown = HashSet<Pair<GamepadId, GamepadButton>>(4)

    /** Focus left: whatever was held will come up somewhere else, and is not the panel's any more. */
    fun forgetHeld() {
        keysDown.clear()
        buttonsDown.clear()
    }

    val pointerHandler = PointerHandler { event ->
        val handler = pointer
        val node = state.node
        if (handler == null || node == null) false else handler(event.movedTo(state.toPicture(node, event.position)))
    }

    val keyHandler = KeyHandler { event ->
        val handler = key
        when {
            handler == null -> false
            event.type == KeyEventType.Down -> {
                keysDown += event.key
                handler(event)
            }
            keysDown.remove(event.key) -> handler(event)
            else -> false
        }
    }

    val padHandler = GamepadHandler { event ->
        val handler = pad
        when {
            handler == null -> false
            event is GamepadEvent.ButtonDown -> {
                buttonsDown += event.gamepadId to event.button
                handler(event)
            }
            event is GamepadEvent.ButtonUp -> buttonsDown.remove(event.gamepadId to event.button) && handler(event)
            event is GamepadEvent.Disconnected -> {
                buttonsDown.removeAll { it.first == event.gamepadId }
                handler(event)
            }
            else -> handler(event)
        }
    }
}

/** The picture, stretched over the content box. Nothing before the first render. */
private fun scenePainter(state: SceneViewState): UiCanvas.(Rect) -> Unit = { bounds ->
    val picture = state.surface
    if (picture != null && !picture.closed && !bounds.isEmpty) image(picture, bounds)
}

/**
 * Remembers a [SceneViewState], so a recomposition keeps the picture rather than throwing it away,
 * and gives the picture back when the state leaves the composition.
 *
 * @param resolutionScale the state's starting [SceneViewState.resolutionScale]. Read once; change
 *   the property afterwards.
 */
@Composable
fun rememberSceneViewState(resolutionScale: Float = 1f): SceneViewState {
    val state = remember { SceneViewState(resolutionScale) }
    DisposableEffect(state) { onDispose { state.release() } }
    return state
}

/**
 * What a [SceneView] owns: the offscreen picture, the size it was last rendered at, whether it has
 * to be rendered again, and at what fraction of the panel's pixels.
 *
 * Marking it dirty is the game's job. A still preview is rendered once; a live camera calls
 * [invalidate] every frame.
 *
 * @param resolutionScale see the property.
 */
@Stable
class SceneViewState(resolutionScale: Float = 1f) {

    /**
     * How many of the panel's real pixels the picture has, each way: 1 is one for one, 0.5 renders
     * a heavy scene at half the width and half the height and stretches it over the panel.
     * Changing it renders again at the new size.
     */
    var resolutionScale: Float = checkScale(resolutionScale)
        set(value) {
            if (field == value) return
            field = checkScale(value)
            askForFrame()
        }

    /** True until the next prepass renders it: at first, and after [invalidate] or [release]. */
    var dirty: Boolean = true
        internal set

    /** How wide the picture really is, in pixels, as last rendered. Zero before the first render. */
    var width: Int = 0
        internal set

    /** How tall the picture really is, in pixels, as last rendered. Zero before the first render. */
    var height: Int = 0
        internal set

    /** How many times the scene has been rendered. What a test, or a game proving the point, reads. */
    var draws: Long = 0L
        internal set

    /**
     * Whether the last render was cut down to fit the device's biggest texture: the panel's pixels
     * times [resolutionScale] would have been more than a GPU will make, so it was rendered at a
     * smaller scale that fits, the same shape. The prepass warns about it once per state.
     */
    var clamped: Boolean = false
        internal set

    /** The picture the tree draws, or null before the first render and after [release]. */
    val texture: TextureHandle? get() = surface

    internal var surface: SceneSurface? = null

    /** The size the prepass last asked the canvas for, which a small GPU may have cut down. */
    internal var askedWidth = 0
    internal var askedHeight = 0

    /**
     * The panel's pixel size, before the resolution scale, as the last prepass saw it. A size that
     * matches is one that has held for a frame, which is when a resize is worth a new picture.
     */
    internal var seenWidth = 0
    internal var seenHeight = 0

    /** Whether the prepass has already warned that this state was clamped. */
    internal var warnedClamped = false

    internal var node: UiNode? = null

    /**
     * Real pixels per design unit of the panel, each way, as the prepass last measured it: the
     * viewport's scale times every `scale` above the panel times [resolutionScale]. What a position
     * is converted with before there is a picture to measure it by.
     */
    internal var pixelsPerUnitX = 0f
    internal var pixelsPerUnitY = 0f

    /**
     * A point in [node]'s own coordinates — what the pointer router hands a handler, with every
     * scroll, scale and mirror above the node already taken out — as a point in the picture.
     *
     * The picture is stretched over the content box, so the answer is the content box's corner taken
     * away and the rest scaled by the picture's pixels over the box's units. That ratio, rather than
     * the prepass's scale, is what is on screen: it is still right when a small GPU cut the picture
     * down. Before the first render there is no picture, and the prepass's scale stands in; before
     * even that, a unit is a pixel.
     */
    internal fun toPicture(node: UiNode, local: Offset): Offset {
        val padding = node.resolved.padding
        val top = padding.top + node.baselineTop
        val boxWidth = node.width - padding.left - padding.right
        val boxHeight = node.height - top - padding.bottom - node.baselineBottom
        val sx = when {
            width > 0 && boxWidth > 0f -> width / boxWidth
            pixelsPerUnitX > 0f -> pixelsPerUnitX
            else -> 1f
        }
        val sy = when {
            height > 0 && boxHeight > 0f -> height / boxHeight
            pixelsPerUnitY > 0f -> pixelsPerUnitY
            else -> 1f
        }
        return Offset((local.x - padding.left) * sx, (local.y - top) * sy)
    }

    internal var content: SceneDrawScope.() -> Unit = {}

    /** Renders the scene again, once, in the next frame's prepass. */
    fun invalidate() {
        dirty = true
        askForFrame()
    }

    /**
     * Gives the picture back to the device now, on the thread that holds the context. The next
     * frame makes a new one if the view is still showing.
     *
     * Called for you when a [SceneView] leaves the composition — a row of a `LazyColumn` scrolled
     * away, a tab closed — and when a [rememberSceneViewState] state does, so a shelf of previews
     * holds pictures only for the rows that exist.
     */
    fun release() {
        surface?.close()
        surface = null
        width = 0
        height = 0
        askedWidth = 0
        askedHeight = 0
        seenWidth = 0
        seenHeight = 0
        clamped = false
        dirty = true
        askForFrame()
    }

    /** The tree reports the next frame as changed, so a game that skips unchanged frames draws it. */
    internal fun askForFrame() {
        val node = node ?: return
        node.tree?.redraw(node)
    }

    private companion object {
        fun checkScale(scale: Float): Float {
            require(scale > 0f && scale.isFinite()) { "a resolution scale is a positive number, not $scale" }
            return scale
        }
    }
}

/**
 * What a [SceneView]'s draw block has to work with: the picture's size in real pixels, the frame's
 * time, a clear, and the backend's own drawing object.
 *
 * Only good inside that block.
 */
class SceneDrawScope internal constructor() {

    /** How wide the picture is, in real pixels: the panel's, times the resolution scale. */
    var width: Int = 0
        internal set

    /** How tall the picture is, in real pixels. */
    var height: Int = 0
        internal set

    /** The frame's time, in nanoseconds, from the clock the game handed the renderer. */
    var nanos: Long = 0L
        internal set

    internal var target: SceneTarget? = null

    /** Fills the picture with [colour] and its depth buffer with the far plane. */
    fun clear(colour: Colour) = target().clear(colour)

    /**
     * The backend's own drawing object — the same one `UiCanvas.raw` hands over on that backend —
     * with the picture bound and the viewport covering all of it, `0, 0, width, height`. Set the
     * depth test and whatever else the scene needs; the engine's state is put back afterwards.
     */
    fun raw(block: (Any) -> Unit) = target().raw(block)

    private fun target(): SceneTarget =
        checkNotNull(target) { "a SceneDrawScope is only good inside its SceneView's draw block" }
}
