package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.SceneSurface
import dev.wildware.composegl.ui.graphics.SceneTarget
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.LocalUiSounds
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode

/**
 * A panel with the game's own 3D scene inside it.
 *
 * ```kotlin
 * val scene = rememberSceneViewState()
 *
 * SceneView(scene, Modifier.size(480f, 270f)) {
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
 * [UiRenderer][dev.wildware.composegl.ui.host.UiRenderer] after layout and before the frame fills
 * every dirty one by calling [draw]; and then the tree draws the picture as an image. So clipping,
 * rounded corners and the effect modifiers work on it unchanged, and a scene nobody marked dirty
 * costs a comparison. See [dev.wildware.composegl.ui.draw.ScenePass] for driving that step yourself.
 *
 * The widget interprets nothing: no camera, no scene graph, no picking. Right to left moves the
 * panel to the other side like anything else, and does not mirror the picture — a scene is not
 * text. Use one [SceneViewState] per `SceneView`.
 *
 * @param state what owns the picture, its size and whether it needs drawing again.
 * @param draw fills the picture. Runs only when [state] is dirty or the panel's pixel size
 *   changed, with the picture bound; see [SceneDrawScope].
 */
@Composable
fun SceneView(
    state: SceneViewState,
    modifier: Modifier = Modifier,
    draw: SceneDrawScope.() -> Unit,
) {
    // After the composition applies and before the prepass reads it, whichever lambda is newest.
    SideEffect { state.content = draw }
    val sounds = LocalUiSounds.current
    val direction = LocalLayoutDirection.current
    val paint = remember(state) { scenePainter(state) }
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
            set(modifier) { this.modifier = it }
            set(MeasurePolicy.Empty) { this.measurePolicy = it }
            set(paint) { this.content = it }
        },
    )
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

    /** The picture the tree draws, or null before the first render and after [release]. */
    val texture: TextureHandle? get() = surface

    internal var surface: SceneSurface? = null

    /** The size the prepass last asked the canvas for, which a small GPU may have cut down. */
    internal var askedWidth = 0
    internal var askedHeight = 0

    internal var node: UiNode? = null

    internal var content: SceneDrawScope.() -> Unit = {}

    /** Renders the scene again, once, in the next frame's prepass. */
    fun invalidate() {
        dirty = true
        askForFrame()
    }

    /**
     * Gives the picture back to the device now, on the thread that holds the context. The next
     * frame makes a new one if the view is still showing. [rememberSceneViewState] calls it when
     * the state leaves the composition.
     */
    fun release() {
        surface?.close()
        surface = null
        width = 0
        height = 0
        askedWidth = 0
        askedHeight = 0
        dirty = true
        askForFrame()
    }

    /** The tree reports the next frame as changed, so a game that skips unchanged frames draws it. */
    private fun askForFrame() {
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
