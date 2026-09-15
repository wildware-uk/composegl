package dev.wildware.composegl.ui.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Matrix4
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.CanvasState
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.zIndex
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.text.TextLayout
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * How many times each part of the screen was painted in one frame.
 *
 * The screen is cut into square cells [cell] units a side, and a cell counts one paint for every
 * rectangle, glyph run, picture or shape that covers its middle — the same rule a GPU uses to
 * decide which pixels a triangle fills. At a [cell] of one it is a count per design pixel.
 *
 * Made by [measureOverdraw], or `UiTest.overdraw()` in a test, and drawn by [OverdrawOverlay].
 */
class OverdrawMap(
    /** The width of the screen it covers, in design units. */
    val width: Float,
    /** The height of the screen it covers, in design units. */
    val height: Float,
    /** The side of one cell, in design units. */
    val cell: Float = 1f,
) {

    init {
        require(cell > 0f) { "a cell has to have a size, not $cell" }
    }

    val columns: Int = ceil(max(width, 0f) / cell).toInt()
    val rows: Int = ceil(max(height, 0f) / cell).toInt()

    private val counts = IntArray(columns * rows)

    /** How many times the cell under [x], [y] was painted. Zero off the screen. */
    fun at(x: Float, y: Float): Int {
        if (x < 0f || y < 0f) return 0
        return atCell(floor(x / cell).toInt(), floor(y / cell).toInt())
    }

    /** How many times the cell in [column], [row] was painted. Zero off the screen. */
    fun atCell(column: Int, row: Int): Int =
        if (column in 0 until columns && row in 0 until rows) counts[row * columns + column] else 0

    /** The most times any one cell was painted. */
    val deepest: Int get() = counts.maxOrNull() ?: 0

    /**
     * Paints per cell across the whole screen: 1.0 is a screen covered exactly once, 2.5 is the fill
     * rate of two and a half screens. A cell painted nothing brings it down.
     */
    val average: Float get() = if (counts.isEmpty()) 0f else counts.sum().toFloat() / counts.size

    /** The area painted [times] times or more, in square design units. */
    fun areaAtLeast(times: Int): Float = counts.count { it >= times } * cell * cell

    /** Every cell back to zero, for a map used again next frame. */
    fun clear() = counts.fill(0)

    /** One paint over every cell whose middle lies inside the rectangle, left and top edges in. */
    internal fun fill(left: Float, top: Float, right: Float, bottom: Float) {
        val firstColumn = firstCentreAtOrAfter(left, columns)
        val endColumn = firstCentreAtOrAfter(right, columns)
        val firstRow = firstCentreAtOrAfter(top, rows)
        val endRow = firstCentreAtOrAfter(bottom, rows)
        for (row in firstRow until endRow) {
            val start = row * columns
            for (column in firstColumn until endColumn) counts[start + column]++
        }
    }

    /**
     * One paint over every cell inside [clip] whose middle lies in any triangle of the fan: the first
     * point shared, as [UiCanvas.fan] draws it. A cell two triangles share is still painted once,
     * because the GPU gives a shared edge to one of them.
     */
    internal fun fillFan(points: FloatArray, clip: Rect) {
        if (points.size < 6) return
        var minX = points[0]
        var maxX = points[0]
        var minY = points[1]
        var maxY = points[1]
        for (index in 2 until points.size - 1 step 2) {
            minX = min(minX, points[index]); maxX = max(maxX, points[index])
            minY = min(minY, points[index + 1]); maxY = max(maxY, points[index + 1])
        }
        val firstColumn = firstCentreAtOrAfter(max(minX, clip.left), columns)
        val endColumn = firstCentreAtOrAfter(min(maxX, clip.right), columns)
        val firstRow = firstCentreAtOrAfter(max(minY, clip.top), rows)
        val endRow = firstCentreAtOrAfter(min(maxY, clip.bottom), rows)
        val triangles = points.size / 2 - 2
        for (row in firstRow until endRow) {
            val y = (row + 0.5f) * cell
            for (column in firstColumn until endColumn) {
                val x = (column + 0.5f) * cell
                // Inclusive on the far edges, so a cell centred exactly on the rim is in; the range
                // above already cut it to the clip.
                if (x > maxX || y > maxY) continue
                for (triangle in 0 until triangles) {
                    val b = (triangle + 1) * 2
                    val c = b + 2
                    if (inTriangle(x, y, points[0], points[1], points[b], points[b + 1], points[c], points[c + 1])) {
                        counts[row * columns + column]++
                        break
                    }
                }
            }
        }
    }

    /** The first cell whose middle is at or past [edge], held to the grid. */
    private fun firstCentreAtOrAfter(edge: Float, count: Int): Int =
        ceil(edge / cell - 0.5f).toInt().coerceIn(0, count)

    @Suppress("LongParameterList")
    private fun inTriangle(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Boolean {
        val d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by)
        val d2 = (x - cx) * (by - cy) - (bx - cx) * (y - cy)
        val d3 = (x - ax) * (cy - ay) - (cx - ax) * (y - ay)
        val negative = d1 < 0f || d2 < 0f || d3 < 0f
        val positive = d1 > 0f || d2 > 0f || d3 > 0f
        return !(negative && positive)
    }
}

/**
 * Draws the tree [node] is in into an [OverdrawMap] instead of onto the screen.
 *
 * The same draw pass a frame runs, handed a canvas that paints nothing and counts everything, so
 * it counts the calls the frame really makes: a panel, its border, its shadow, every run of text,
 * every picture. A subtree taken into a picture — a scale, a turn, an effect, a shaped clip — is
 * counted twice where it lands, once drawn into the picture and once more when the picture is put
 * down, because that is what the GPU fills.
 *
 * [like] is the canvas the frame really draws into. Asked what it can do — take a picture, turn one,
 * cut one — so the pass takes the same road here as it does there. Null counts as a canvas that can
 * do all of it.
 *
 * What it cannot see, it leaves out: whatever a game draws itself, behind the interface or through
 * `raw`. A rounded corner counts as its square box, and a run of text as its box rather than its
 * letters.
 */
fun measureOverdraw(node: UiNode, like: UiCanvas? = null, cell: Float = 1f): OverdrawMap {
    var root = node
    while (true) root = root.parent ?: break
    val map = OverdrawMap(root.width, root.height, cell)
    OverdrawCanvas(map, like).count(root)
    return map
}

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
internal class OverdrawPainter(private val cell: Float) : DebugOverlayPainter {

    /** The node this draws for. Set when it is handed over, and how it finds the tree. */
    var node: UiNode? = null

    /** Kept from frame to frame while the screen stays the same size. */
    private var map: OverdrawMap? = null

    override fun invoke(canvas: UiCanvas, content: Rect) {
        // Called again from inside the count: neither this nor another overdraw overlay is counted.
        if (canvas is OverdrawCanvas) return
        val self = node ?: return
        var root = self
        while (true) root = root.parent ?: break

        // Where the canvas's origin is, as LayoutOverlay works it out: the root's coordinates except
        // in a picture a caller drew a subtree into somewhere else.
        val box = self.layoutBoundsInRoot
        val dx = content.left - box.left - self.resolved.padding.left
        val dy = content.top - box.top - self.resolved.padding.top - self.baselineTop

        val width = root.width
        val height = root.height
        val map = this.map?.takeIf { it.width == width && it.height == height }
            ?: OverdrawMap(width, height, cell).also { this.map = it }
        map.clear()
        OverdrawCanvas(map, canvas).count(root)

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

/**
 * A canvas that paints nothing and counts where everything would have gone, into [map].
 *
 * Clip, opacity and layers behave as they do on a real canvas — [CanvasState] keeps them — so a
 * clipped-away child and a faded-out one count nothing, as they paint nothing. Whether it can take,
 * turn, cut or mirror a picture is whatever [like] says, so the draw pass takes the same road it
 * takes on the screen.
 */
internal class OverdrawCanvas(private val map: OverdrawMap, private val like: UiCanvas?) : UiCanvas {

    private var state = CanvasState(Rect(0f, 0f, map.width, map.height))

    /**
     * The whole tree, counted. The draw pass writes down on each node whether its scale and mirror
     * really happened, and a picture this canvas takes might be one the real canvas refused, so what
     * it wrote is put back afterwards.
     */
    fun count(root: UiNode) {
        val nodes = ArrayList<UiNode>()
        collect(root, nodes)
        val scaled = BooleanArray(nodes.size) { nodes[it].scaleApplied }
        val mirrored = BooleanArray(nodes.size) { nodes[it].mirrorApplied }
        try {
            DrawPass(this).draw(root)
        } finally {
            for (index in nodes.indices) {
                nodes[index].scaleApplied = scaled[index]
                nodes[index].mirrorApplied = mirrored[index]
            }
        }
    }

    private fun collect(node: UiNode, into: MutableList<UiNode>) {
        into += node
        val children = node.children
        for (index in children.indices) collect(children[index], into)
    }

    // --- counting --------------------------------------------------------------------------------

    private fun box(rect: Rect) {
        if (state.isHidden || rect.isEmpty) return
        val clip = state.clip
        map.fill(max(rect.left, clip.left), max(rect.top, clip.top), min(rect.right, clip.right), min(rect.bottom, clip.bottom))
    }

    private fun shape(points: FloatArray) {
        if (state.isHidden) return
        map.fillFan(points, state.clip)
    }

    /** A picture's four corners, turned [degrees] clockwise about a pivot given as a fraction of it. */
    private fun turned(destination: Rect, degrees: Float, pivotX: Float, pivotY: Float): FloatArray {
        val radians = degrees * RadiansPerDegree
        val c = cos(radians)
        val s = sin(radians)
        val px = destination.left + destination.width * pivotX
        val py = destination.top + destination.height * pivotY
        val corners = FloatArray(8)
        for (corner in 0 until 4) {
            val ax = (if (corner == 1 || corner == 2) destination.right else destination.left) - px
            val ay = (if (corner >= 2) destination.bottom else destination.top) - py
            corners[corner * 2] = px + ax * c - ay * s
            corners[corner * 2 + 1] = py + ax * s + ay * c
        }
        return corners
    }

    override fun rect(rect: Rect, colour: Colour, corner: Float) = box(rect)

    override fun rect(rect: Rect, brush: Brush, corner: Float) = box(rect)

    override fun rect(rect: Rect, colour: Colour, corners: Corners) = box(rect)

    override fun rect(rect: Rect, brush: Brush, corners: Corners) = box(rect)

    override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) = edges(rect, width)

    override fun border(rect: Rect, colour: Colour, width: Float, corners: Corners) = edges(rect, width)

    /** Four bands inside the rectangle, meeting without overlapping, and nothing in the middle. */
    private fun edges(rect: Rect, width: Float) {
        if (width <= 0f || rect.isEmpty) return
        val across = min(width, rect.height / 2f)
        val down = min(width, rect.width / 2f)
        box(Rect(rect.left, rect.top, rect.right, rect.top + across))
        box(Rect(rect.left, rect.bottom - across, rect.right, rect.bottom))
        box(Rect(rect.left, rect.top + across, rect.left + down, rect.bottom - across))
        box(Rect(rect.right - down, rect.top + across, rect.right, rect.bottom - across))
    }

    /** The whole spread, which the GPU fills to fade it out even where it is nearly clear. */
    override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) {
        if (spread > 0f) box(rect.inset(-spread))
    }

    override fun shadow(rect: Rect, colour: Colour, spread: Float, corners: Corners) {
        if (spread > 0f) box(rect.inset(-spread))
    }

    override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) =
        box(Rect(x, y, x + layout.size.width, y + layout.size.height))

    override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) = box(destination)

    override fun image(
        texture: TextureHandle,
        destination: Rect,
        degrees: Float,
        pivotX: Float,
        pivotY: Float,
        tint: Colour,
        source: Rect?,
    ) {
        if (degrees == 0f || !rotatesImages) box(destination)
        else if (!destination.isEmpty) shape(turned(destination, degrees, pivotX, pivotY))
    }

    override fun fan(points: FloatArray, colour: Colour) = shape(points)

    override fun layer(bounds: Rect, block: () -> Unit): TextureHandle? {
        if (!drawsLayers) return null
        val outer = state
        state = outer.forLayer(bounds)
        try {
            block()
        } finally {
            state = outer
        }
        return Picture(bounds)
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, effect: ShaderEffect?) = box(destination)

    override fun drawLayer(layer: TextureHandle, destination: Rect, degrees: Float, pivotX: Float, pivotY: Float) {
        if (degrees == 0f || !turnsLayers) box(destination)
        else if (!destination.isEmpty) shape(turned(destination, degrees, pivotX, pivotY))
    }

    override fun cutLayer(layer: TextureHandle, destination: Rect, outline: FloatArray) {
        if (destination.isEmpty) return
        if (cutsLayers) shape(outline) else box(destination)
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, mirrorX: Boolean, mirrorY: Boolean) =
        box(destination)

    override fun drawLayerOnto(layer: TextureHandle, destination: Rect, corners: FloatArray) {
        if (destination.isEmpty) return
        if (drawsLayersOnto && corners.size == 8) shape(corners) else box(destination)
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, transform: Matrix4) {
        if (destination.isEmpty) return
        val xs = floatArrayOf(destination.left, destination.right, destination.right, destination.left)
        val ys = floatArrayOf(destination.top, destination.top, destination.bottom, destination.bottom)
        // A corner at or behind the camera is clipped away by a real canvas; that shape is not a quad
        // any more, so the flat rectangle stands in for it.
        if (!tiltsLayers || (0 until 4).any { transform.depthOf(xs[it], ys[it]) <= 0f }) {
            box(destination)
            return
        }
        val corners = FloatArray(8)
        for (corner in 0 until 4) {
            val at = transform.map(xs[corner], ys[corner])
            corners[corner * 2] = at.x
            corners[corner * 2 + 1] = at.y
        }
        shape(corners)
    }

    /** A picture that only exists as a size: nothing was drawn to be a handle to. */
    private class Picture(bounds: Rect) : TextureHandle {
        override val width: Int = bounds.width.toInt()
        override val height: Int = bounds.height.toInt()
    }

    // --- state -----------------------------------------------------------------------------------

    override fun pushClip(rect: Rect) = state.pushClip(rect)

    override fun popClip() = state.popClip()

    override fun pushAlpha(alpha: Float) = state.pushAlpha(alpha)

    override fun popAlpha() = state.popAlpha()

    override fun pushBlend(mode: BlendMode) = state.pushBlend(mode)

    override fun popBlend() = state.popBlend()

    override fun pushTint(tint: Colour) = state.pushTint(tint)

    override fun popTint() = state.popTint()

    /** Whatever a game draws through the hatch is its own, and not seen here: the block is not run. */
    override fun raw(block: (Any) -> Unit) = Unit

    // --- the same answers as the canvas the frame really draws into ------------------------------

    override val drawsGradients: Boolean get() = like?.drawsGradients ?: true
    override val roundsCornersSeparately: Boolean get() = like?.roundsCornersSeparately ?: true
    override val rotatesImages: Boolean get() = like?.rotatesImages ?: true
    override fun supports(mode: BlendMode): Boolean = like?.supports(mode) ?: true
    override val tints: Boolean get() = like?.tints ?: true
    override val drawsLayers: Boolean get() = like?.drawsLayers ?: true
    override val turnsLayers: Boolean get() = like?.turnsLayers ?: true
    override val cutsLayers: Boolean get() = like?.cutsLayers ?: true
    override val mirrorsLayers: Boolean get() = like?.mirrorsLayers ?: true
    override val drawsLayersOnto: Boolean get() = like?.drawsLayersOnto ?: true
    override val tiltsLayers: Boolean get() = like?.tiltsLayers ?: true
    override val handsOverRaw: Boolean get() = like?.handsOverRaw ?: false
    override val movesRawOrigin: Boolean get() = like?.movesRawOrigin ?: false

    private companion object {
        const val RadiansPerDegree = 0.017453292f
    }
}
