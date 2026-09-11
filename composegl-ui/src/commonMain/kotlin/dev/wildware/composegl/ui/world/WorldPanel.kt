package dev.wildware.composegl.ui.world

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.animation.Clocks
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
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
 * if (panel.needsRedraw(now)) target.use { canvas -> panel.draw(canvas) }
 * scene.draw(quad, target.texture)
 * ```
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

    fun setContent(content: @Composable () -> Unit) = host.setContent(content)

    /**
     * Advances the runtime, and says whether the panel has to be drawn again.
     *
     * Call it every frame. It is cheap when nothing is happening, which is nearly always: a
     * composition nobody has touched answers false forever.
     */
    fun needsRedraw(nanos: Long): Boolean {
        if (host.frame(nanos)) dirty = true
        return dirty
    }

    /**
     * Lays the panel out at its size and draws it into [canvas].
     *
     * The canvas is the backend's, already pointed at wherever the pixels go — a framebuffer, in
     * practice. Nothing here knows what one is.
     */
    private var drawPass: DrawPass? = null

    fun draw(canvas: UiCanvas) {
        MeasurePass().run(host.root, Constraints.fixed(size.width, size.height))
        // Kept rather than made each frame: a pass holds the canvas and nothing else.
        val pass = drawPass?.takeIf { it.canvas === canvas } ?: DrawPass(canvas).also { drawPass = it }
        pass.draw(host.root)
        dirty = false
        draws++
    }

    /** [needsRedraw] and [draw] together, for a game that has its canvas ready either way. */
    fun render(canvas: UiCanvas, nanos: Long): Boolean {
        if (!needsRedraw(nanos)) return false
        draw(canvas)
        return true
    }

    /** Draws it again next frame whether or not anything changed: the texture was lost, say. */
    fun invalidate() {
        dirty = true
    }

    override fun close() = host.dispose()
}
