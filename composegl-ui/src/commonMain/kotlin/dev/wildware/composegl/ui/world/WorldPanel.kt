package dev.wildware.composegl.ui.world

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.animation.Clocks
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.draw.ScenePass
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.node.UiNode

/**
 * An interface that lives in the game's world rather than on top of it: a screen on a wall, a
 * terminal, the display on a gun.
 *
 * The same tree, laid out the same way, drawn by the same passes — the only difference is where
 * the pixels land. A game gives the backend a texture to draw into, calls [render] once a frame,
 * and maps the texture onto whatever quad it likes.
 *
 * **It draws only when something changed.** That is the whole reason for doing it this way rather
 * than rendering an interface into the world every frame: a terminal on a wall that nobody is
 * looking at costs one comparison a frame, not a full pass over its tree. [render] says whether it
 * drew, so a game can skip binding its framebuffer at all.
 *
 * It is a panel, not a window: no input, no focus, no clipboard. Feeding it a pointer is the
 * game's job, because only the game knows where its ray hit.
 *
 * ```kotlin
 * val panel = WorldPanel(512f, 256f)
 * panel.setContent { TerminalScreen(reactor) }
 * // in the game loop:
 * if (panel.needsRedraw(now)) panel.draw(canvas) { tree -> target.draw(canvas) { tree() } }
 * scene.draw(quad, target.texture)
 * ```
 *
 * **A `SceneView` inside works**, through the same scene pass a screen's
 * [UiRenderer][dev.wildware.composegl.ui.host.UiRenderer] runs: after layout, before the frame the
 * tree is drawn in opens. That is why [draw] takes the frame as a block rather than being called
 * inside one — the panel renders its scenes first, then opens the target's frame through the block.
 * A scene the game marks dirty makes the panel need drawing again, so a live one — a security
 * camera on a monitor — keeps the panel redrawing every frame: the whole tree, not just the scene.
 * Keep such a panel small, or put the live scene on a panel of its own.
 *
 * @param width how wide the panel is, in interface units. The texture it is drawn into is usually
 *   the same number of pixels, but nothing here insists on it: a panel drawn at twice the size is
 *   a sharper one, and that is the backend's business.
 */
class WorldPanel(
    width: Float,
    height: Float,
    val host: UiHost = UiHost(),
) : AutoCloseable {

    /**
     * How big the panel is.
     *
     * Setting it is a redraw, because a tree that has not changed still lands somewhere else when
     * the thing it is laid out in changes size.
     */
    var size: Size = Size(width, height)
        set(value) {
            if (field == value) return
            field = value
            dirty = true
        }

    val root: UiNode get() = host.root

    val clocks: Clocks get() = host.clocks

    /** True when the tree has changed since it was last drawn. */
    var dirty = true
        private set

    /** How many times it has actually been drawn. What a game prints to prove the point. */
    var draws = 0L
        private set

    /**
     * Where the panel's `SceneView`s count their renders: hand it the budget of the screen's
     * [UiRenderer][dev.wildware.composegl.ui.host.UiRenderer], and draw the panel before that
     * renderer's frame, so the overlay shows the panel's scenes with the frame they happened in.
     * Null, the default, counts nothing.
     */
    var budget: FrameBudget? = null
        set(value) {
            field = value
            scenePass?.budget = value
        }

    /**
     * Told, once per `SceneViewState`, that a scene in the panel was cut down to fit the device's
     * biggest picture. Prints by default, as the screen's scene pass does.
     */
    var warn: (String) -> Unit = { println(it) }
        set(value) {
            field = value
            scenePass?.warn = value
        }

    /** The time [needsRedraw] last advanced the panel to, which its scenes are handed. */
    private var nanos = 0L

    // Kept rather than made each frame: a pass holds the canvas and little else.
    private var drawPass: DrawPass? = null
    private var scenePass: ScenePass? = null
    private var viewport: Viewport? = null

    fun setContent(content: @Composable () -> Unit) = host.setContent(content)

    /**
     * Advances the runtime, and says whether the panel has to be drawn again.
     *
     * Call it every frame. It is cheap when nothing is happening, which is nearly always: a
     * composition nobody has touched answers false forever. A `SceneView` inside that the game
     * marks dirty is something happening: it answers true, so the scene is rendered.
     */
    fun needsRedraw(nanos: Long): Boolean {
        this.nanos = nanos
        if (host.frame(nanos)) dirty = true
        return dirty
    }

    /**
     * Lays the panel out at its size, renders any dirty `SceneView` in it, and then draws the tree
     * inside [frame].
     *
     * [frame] opens the backend's frame — a render target's `draw`, in practice — and runs the block
     * it is handed inside it:
     *
     * ```kotlin
     * panel.draw(canvas) { tree -> target.draw(canvas) { tree() } }
     * ```
     *
     * The scenes have to come first and outside that frame: a scene binds a picture of its own, and a
     * canvas refuses one in the middle of the interface's batch. This is the same
     * [ScenePass] a screen's [UiRenderer][dev.wildware.composegl.ui.host.UiRenderer] runs, at one
     * design unit to the pixel, which is what a render target draws the tree at too.
     */
    fun draw(canvas: UiCanvas, frame: (drawTree: () -> Unit) -> Unit) {
        MeasurePass().run(host.root, Constraints.fixed(size.width, size.height))
        val scenes = scenePass?.takeIf { it.canvas === canvas }
            ?: ScenePass(host.tree, canvas, budget).also { it.warn = warn; scenePass = it }
        scenes.render(viewport(), nanos)
        val pass = drawPass?.takeIf { it.canvas === canvas } ?: DrawPass(canvas).also { drawPass = it }
        var drew = false
        frame {
            pass.draw(host.root)
            drew = true
        }
        // A block that never ran the tree would leave the panel blank and still call it drawn.
        check(drew) { "WorldPanel.draw: the frame block has to call the function it is handed, inside the target's frame" }
        dirty = false
        draws++
    }

    /**
     * Draws the panel into a frame of [canvas] that is already open. Fine for a panel with no
     * `SceneView` in it; one with a scene that needs rendering — dirty, new, or settled at a new
     * size — fails, because the scene cannot be rendered in the middle of that frame. Use the [draw] that takes the frame as a block for those.
     */
    fun draw(canvas: UiCanvas) = draw(canvas, InsideOpenFrame)

    /** The panel's size as a viewport, one unit to the pixel, made again only when the size changes. */
    private fun viewport(): Viewport {
        val size = size
        viewport?.let { if (it.design == size) return it }
        return Viewport.oneToOne(size).also { viewport = it }
    }

    /** [needsRedraw] and [draw] together, for a game that has its canvas ready either way. */
    fun render(canvas: UiCanvas, nanos: Long, frame: (drawTree: () -> Unit) -> Unit): Boolean {
        if (!needsRedraw(nanos)) return false
        draw(canvas, frame)
        return true
    }

    /** [needsRedraw] and [draw] into a frame of [canvas] that is already open. */
    fun render(canvas: UiCanvas, nanos: Long): Boolean = render(canvas, nanos, InsideOpenFrame)

    /** Draws it again next frame whether or not anything changed: the texture was lost, say. */
    fun invalidate() {
        dirty = true
    }

    override fun close() = host.dispose()

    private companion object {
        /** The frame is already open: draw straight into it. */
        val InsideOpenFrame: (() -> Unit) -> Unit = { drawTree -> drawTree() }
    }
}
