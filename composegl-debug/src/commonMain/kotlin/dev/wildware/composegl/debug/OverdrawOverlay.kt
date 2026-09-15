package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.debug.DebugOverlay
import dev.wildware.composegl.ui.debug.OverdrawMap
import dev.wildware.composegl.ui.debug.measureOverdraw
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.zIndex
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode
import kotlin.math.min

/**
 * Which pixels of the whole screen are painted over and over, shaded over it.
 *
 * A stack of translucent panels, a scrim across the whole screen and a background under a
 * background all cost fill rate, and none of them look any different for it — a phone just gets
 * slower. This shows it: every part of the screen the interface painted twice is washed blue,
 * three times green, four times pink, and five or more red. Painted once, or not at all, is left
 * as it is.
 *
 * Compose it last, at the top level of the screen, behind the game's own switch, as
 * [LayoutOverlay] is:
 *
 * ```kotlin
 * Box(Modifier.fillMaxSize()) {
 *     Game()
 *     OverdrawOverlay(enabled = debug)
 * }
 * ```
 *
 * Each frame it is drawn it draws the whole tree again into an [OverdrawMap], with
 * [measureOverdraw], and shades the map. So it follows every change, it counts the draw calls the
 * frame really made, and it never tells the tree anything changed: a still screen with it on stays
 * still. It has no size and takes no input, so it moves nothing and clicks go straight through. Its
 * own shading and any other overlay are left out of the count.
 *
 * Only what the interface draws is counted. A world a game draws behind it is not, so a scrim over
 * a 3D scene shows as painted once even though the scene under it was painted too.
 *
 * Deliberately unskinnable, like [LayoutOverlay]. It costs a second draw pass and a count per cell
 * a frame while it is on. Take it off before shipping.
 *
 * @param enabled whether it is there at all. Off composes nothing.
 * @param cell the side of one shaded square, in design units. Bigger is cheaper and coarser.
 */
@Composable
fun OverdrawOverlay(
    enabled: Boolean,
    cell: Float = 2f,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    // Remembered by value, so recomposing with the same cell hands the node the same painter and
    // the tree hears nothing.
    val painter = remember(cell) { OverdrawPainter(cell) }
    ComposeNode<UiNode, UiApplier>(
        factory = { UiNode(OverdrawName) },
        update = {
            set(modifier) { this.modifier = Modifier.zIndex(Float.MAX_VALUE).then(it) }
            set(MeasurePolicy.Empty) { this.measurePolicy = it }
            // "Painted nothing", so a parent's painted rectangle is not stretched to reach this node.
            set(NoInk) { this.ink = it }
            set(painter) {
                it.node = this
                this.content = it
            }
        },
    )
}

/** What the overlay node is called in a dump. */
internal const val OverdrawName = "overdraw overlay"

private val NoInk: (Rect) -> Rect? = { null }

/**
 * The overlay's shading, by how many times a cell was painted. Half strength, so what was painted is
 * still readable under it, and the same order of colours a phone's own overdraw view uses.
 */
internal object OverdrawColours {
    val Twice = Colour.argb(0x802060FF)
    val Three = Colour.argb(0x8020C040)
    val Four = Colour.argb(0x80FF50C0)
    val More = Colour.argb(0x80FF2020)

    /** The shade for a cell painted [count] times, or null for none. */
    fun of(count: Int): Colour? = when {
        count < 2 -> null
        count == 2 -> Twice
        count == 3 -> Three
        count == 4 -> Four
        else -> More
    }
}

/**
 * The drawing, handed to the overlay node as its content. Counts the tree, then shades it one run
 * of same-shaded cells per row, so a screen covered twice is one rectangle a row rather than one a
 * cell.
 */
internal class OverdrawPainter(private val cell: Float) : DebugOverlay {

    /** The node this draws for. Set when it is handed over, and how it finds the tree. */
    var node: UiNode? = null

    /** Kept from frame to frame while the screen stays the same size. */
    private var map: OverdrawMap? = null

    override fun invoke(canvas: UiCanvas, content: Rect) {
        val self = node ?: return
        var root = self
        while (true) root = root.parent ?: break

        // Where the canvas's origin is, as LayoutOverlay works it out: the root's coordinates except
        // in a picture a caller drew a subtree into somewhere else.
        val inner = self.contentBoundsInRoot
        val dx = content.left - inner.left
        val dy = content.top - inner.top

        val width = root.width
        val height = root.height
        val map = this.map?.takeIf { it.width == width && it.height == height }
            ?: OverdrawMap(width, height, cell).also { this.map = it }
        // The count runs the same draw pass again, which leaves every debug overlay out — this one too.
        measureOverdraw(root, into = map, like = canvas)

        for (row in 0 until map.rows) {
            var column = 0
            while (column < map.columns) {
                val shade = OverdrawColours.of(map.atCell(column, row))
                var end = column + 1
                while (end < map.columns && OverdrawColours.of(map.atCell(end, row)) == shade) end++
                if (shade != null) {
                    canvas.rect(
                        Rect(column * cell + dx, row * cell + dy, min(end * cell, width) + dx, min((row + 1) * cell, height) + dy),
                        shade,
                    )
                }
                column = end
            }
        }
    }
}

