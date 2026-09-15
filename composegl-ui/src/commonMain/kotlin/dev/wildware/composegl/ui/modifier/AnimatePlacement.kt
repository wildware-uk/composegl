package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.animation.AnimationSpec
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Clocks
import dev.wildware.composegl.ui.animation.Spring
import dev.wildware.composegl.ui.input.FrameWaiter
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import kotlin.math.abs

/**
 * Slides this node from where it was to where layout puts it now, instead of letting it jump.
 *
 * What makes a change to a list readable: an inventory sorted, a leaderboard reshuffled, a
 * notification taken out of the middle of a stack. Whatever moved the node — a reorder, something
 * inserted above it, a neighbour growing — it starts the frame it moved back where it was and
 * travels the rest of the way.
 *
 * ```kotlin
 * LazyColumn(count = scores.size, key = { scores[it].id }) { index ->
 *     ScoreRow(scores[index], Modifier.animatePlacement())
 * }
 * ```
 *
 * In a list it wants a key, as above. Without one a sorted list keeps each row where it was and
 * changes what it says, and nothing moved.
 *
 * "Where it was" is measured against the nearest thing that carries it about, so only a real move
 * inside that thing slides:
 *
 * - the nearest ancestor that animates its own placement, so a row that slides does not also make
 *   every animated cell inside it slide a second time;
 * - a [dev.wildware.composegl.ui.widget.LazyColumn], `LazyRow`, lazy grid or `ScrollArea`, with
 *   the scroll taken back out, so scrolling a list is not a list of moves;
 * - an ancestor marked with [placementFrame] — a window the player drags about, a panel that
 *   arrives from off screen — so its contents travel with it rather than trailing behind;
 * - otherwise the screen, less its safe area, so a safe area that changes is not a move either.
 *
 * The node is really there on every frame of the slide, not just drawn there: a click, hover and
 * focus find it where it is seen. The first place a node is ever put is not a move, so something
 * newly added appears where it lands. A move in the middle of a slide carries on from where it
 * has got to, at the speed it was going, which is why the default is a spring.
 *
 * @param spec how it travels. Read afresh every recomposition, so an inline one costs nothing.
 * @param clock which clock the slide runs on. A slide that belongs to the world says
 *   [Clock.World] and freezes with it.
 */
@Composable
fun Modifier.animatePlacement(
    spec: AnimationSpec = Spring(threshold = 0.5f),
    clock: Clock = Clock.Ui,
): Modifier {
    val animation = remember { PlacementAnimation() }
    animation.spec = spec
    animation.clock = clock
    return then(AnimatePlacementElement(animation))
}

/**
 * Makes this node the thing its contents' [animatePlacement] is measured against.
 *
 * For anything that moves as a whole while its contents stay put inside it. Without this, a
 * window dragged across the screen is a window full of rows that all moved, and they trail behind
 * it. Scrolling lists and areas already are one.
 */
fun Modifier.placementFrame() = then(PlacementFrameElement(PlacementFrame.Still))

/** The node slides when layout moves it. See [animatePlacement]. */
internal data class AnimatePlacementElement(val animation: PlacementAnimation) : Modifier.Element

/** The node is what placement inside it is measured from. See [placementFrame]. */
internal data class PlacementFrameElement(val frame: PlacementFrame) : Modifier.Element

/**
 * How far a frame has scrolled its contents, so a slot measured inside it ignores the scroll.
 *
 * Two numbers read after layout, rather than an offset handed back, because it is asked of every
 * animated node every frame and a still screen should allocate nothing.
 */
internal interface PlacementFrame {
    val scrolledX: Float
    val scrolledY: Float

    object Still : PlacementFrame {
        override val scrolledX: Float get() = 0f
        override val scrolledY: Float get() = 0f
    }
}

/**
 * One node's slide: where it last sat, and how far it is still drawn from there.
 *
 * Told after every layout pass where the node is ([placed]), and asked during the next one how far
 * to shift it ([x], [y]). Between the two it waits on the tree for frames while it is moving, the
 * way a held press does, which is what makes a node standing still cost nothing.
 *
 * Deliberately not an `Animatable` in a coroutine. A move is only seen once layout has finished,
 * and the frame being finished is the one about to be drawn — a coroutine would get to it a frame
 * later, and for that one frame the node would be drawn in its new place before jumping back to
 * slide. So the node is put back straight away, by hand, and the time is kept here.
 */
internal class PlacementAnimation : FrameWaiter {

    var spec: AnimationSpec = Spring(threshold = 0.5f)
    var clock: Clock = Clock.Ui

    /** How far from its slot the node is drawn now. Zero whenever it is not sliding. */
    var x = 0f
        private set
    var y = 0f
        private set

    // The slot it was last put in, relative to its frame. NaN until layout has put it anywhere.
    private var slotX = Float.NaN
    private var slotY = Float.NaN

    // The slide in progress: where it started, how fast, and when.
    private var fromX = 0f
    private var fromY = 0f
    private var startSpeedX = 0f
    private var startSpeedY = 0f
    private var speedX = 0f
    private var speedY = 0f
    private var began = 0L

    // What it is running on, while it is running. Null when it is still.
    private var node: UiNode? = null
    private var tree: UiTree? = null
    private var clocks: Clocks? = null
    private var playing: Clock? = null

    val isRunning: Boolean get() = tree != null

    /**
     * Where layout left [node] this pass. Called once it has finished, parents first, so every
     * ancestor — an animated one included — is already where it will be drawn.
     */
    fun placed(node: UiNode) {
        var frameX = 0f
        var frameY = 0f
        var ancestor = node.parent
        while (ancestor != null) {
            val resolved = ancestor.resolved
            val frame = resolved.placementFrame
            if (resolved.placement != null || frame != null) {
                frameX = rootX(ancestor) - (frame?.scrolledX ?: 0f)
                frameY = rootY(ancestor) - (frame?.scrolledY ?: 0f)
                break
            }
            ancestor = ancestor.parent
        }
        // Less the slide itself, which layout has already added in.
        val slotX = rootX(node) - x - frameX
        val slotY = rootY(node) - y - frameY

        if (this.slotX.isNaN()) {
            this.slotX = slotX
            this.slotY = slotY
            return
        }
        // Not exact: a slot worked out against an ancestor that is itself sliding is two long sums
        // subtracted, and the last bit of a float must not start a slide of nothing.
        if (abs(slotX - this.slotX) < Epsilon && abs(slotY - this.slotY) < Epsilon) return

        val backX = this.slotX - slotX
        val backY = this.slotY - slotY
        this.slotX = slotX
        this.slotY = slotY
        start(node, x + backX, y + backY)
        // Put back now rather than on the next pass: this is the frame about to be drawn. Its
        // children sit relative to it and move with it for free.
        node.x += backX
        node.y += backY
    }

    private fun start(node: UiNode, fromX: Float, fromY: Float) {
        val tree = node.tree
        if (tree == null) {
            stop()
            return
        }
        val clocks = tree.clocks
        val clock = clock
        if (playing !== clock || this.tree !== tree) stop()

        clocks.register(clock)
        this.fromX = fromX
        this.fromY = fromY
        // From where it is and how fast it is going, so a second move mid-slide turns rather than
        // starting again from a standstill.
        startSpeedX = speedX
        startSpeedY = speedY
        began = clocks.time(clock)
        x = fromX
        y = fromY
        this.node = node

        if (this.tree == null) {
            this.tree = tree
            this.clocks = clocks
            playing = clock
            tree.wait(this)
            clocks.began(clock)
        }
    }

    override fun onFrame(clocks: Clocks) {
        val node = node ?: return
        val tree = tree ?: return
        val clock = playing ?: return
        // Gone from the tree, or its chain no longer asks for this: nothing to slide any more.
        if (node.tree !== tree || node.resolved.placement !== this) {
            forget()
            return
        }
        val played = clocks.time(clock) - began
        val spec = spec
        val wasX = x
        val wasY = y

        val doneX = spec.isFinished(fromX, 0f, startSpeedX, played)
        if (doneX) {
            x = 0f
            speedX = 0f
        } else {
            val motion = spec.at(fromX, 0f, startSpeedX, played)
            x = motion.value
            speedX = motion.velocity
        }
        val doneY = spec.isFinished(fromY, 0f, startSpeedY, played)
        if (doneY) {
            y = 0f
            speedY = 0f
        } else {
            val motion = spec.at(fromY, 0f, startSpeedY, played)
            y = motion.value
            speedY = motion.velocity
        }

        if (doneX && doneY) stop()
        // Only a real change: a slide on a stopped clock hands out the same place every frame and
        // should not redraw every frame for it.
        if (x != wasX || y != wasY) tree.invalidate(node)
    }

    /** Stops where it is supposed to be, leaving the slot it knows alone. */
    private fun stop() {
        tree?.let {
            it.stopWaiting(this)
            clocks?.ended(checkNotNull(playing))
            val sliding = node
            if (sliding != null) it.invalidate(sliding) else it.invalidate()
        }
        tree = null
        clocks = null
        playing = null
        node = null
        x = 0f
        y = 0f
        speedX = 0f
        speedY = 0f
    }

    /** Stops, and forgets where it was: the next place the node is put is a first place again. */
    fun forget() {
        stop()
        slotX = Float.NaN
        slotY = Float.NaN
    }

    private companion object {
        /** Smaller than anything a player could see move. */
        const val Epsilon = 0.01f

        // Where layout put a node inside the root: layoutBoundsInRoot without the Rect, because
        // this is asked of every animated node every frame. The root's own place is left out — it
        // is the safe area's corner, and a phone turned on its side is not every row moving.
        fun rootX(node: UiNode): Float {
            var at = 0f
            var walk = node
            while (true) {
                val parent = walk.parent ?: return at
                at += walk.x
                walk = parent
            }
        }

        fun rootY(node: UiNode): Float {
            var at = 0f
            var walk = node
            while (true) {
                val parent = walk.parent ?: return at
                at += walk.y
                walk = parent
            }
        }
    }
}
