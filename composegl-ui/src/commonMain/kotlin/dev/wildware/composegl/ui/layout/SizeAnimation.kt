package dev.wildware.composegl.ui.layout

import dev.wildware.composegl.ui.animation.AnimationSpec
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Clocks

/**
 * The size an `animateContentSize` node is on its way through, kept on the node.
 *
 * Stepped by the layout pass rather than by a coroutine, because layout is the only thing that
 * knows what size the contents want, and it knows it the frame they change. A coroutine would hear
 * about it a frame later and draw one frame at the new size before starting to grow towards it —
 * which is exactly the jump this exists to hide.
 *
 * The maths is [dev.wildware.composegl.ui.animation.Animatable]'s, on two numbers: every frame asks
 * the spec where it is at the time since it started, so a long frame lands in the right place, and
 * a new target mid-flight starts from where it is and how fast it is going.
 *
 * Plain floats rather than a Size or two FloatArrays: a node that has arrived is asked every frame,
 * and on a still screen that has to make nothing.
 */
internal class SizeAnimation {

    /** Where it is, which is the size the node is laid out at. */
    var width = 0f
        private set
    var height = 0f
        private set

    /** What it is heading for. NaN until the first measure, which takes its size without animating. */
    private var targetWidth = Float.NaN
    private var targetHeight = Float.NaN

    private var fromWidth = 0f
    private var fromHeight = 0f
    private var speedWidth = 0f
    private var speedHeight = 0f
    private var startSpeedWidth = 0f
    private var startSpeedHeight = 0f
    private var began = 0L

    /** The clocks and the clock it told it was playing on, so it can tell the same ones it stopped. */
    private var clocks: Clocks? = null
    private var clock: Clock? = null

    /** Whether it is still on its way. A resize on a stopped clock is on its way, and stays clipped. */
    val isRunning: Boolean get() = clocks != null

    /**
     * Heads for [toWidth] by [toHeight] and moves one frame's worth.
     *
     * @param clocks the tree's clocks, or null for a node laid out with no host, which has no time
     *   to animate in and simply takes the size.
     * @return whether the size it is at changed, which is a frame a game has to draw.
     */
    fun follow(toWidth: Float, toHeight: Float, spec: AnimationSpec, clock: Clock, clocks: Clocks?): Boolean {
        val wasWidth = width
        val wasHeight = height
        if (clocks == null || targetWidth.isNaN()) {
            snap(toWidth, toHeight)
            return width != wasWidth || height != wasHeight
        }

        // Moved to a different clock — or a different host — part-way: told to the ones it started
        // on that it stopped, and started again on the new ones from where it had got to.
        if (isRunning && (this.clock != clock || this.clocks !== clocks)) retarget(clock, clocks)

        if (toWidth != targetWidth || toHeight != targetHeight) {
            targetWidth = toWidth
            targetHeight = toHeight
            retarget(clock, clocks)
        }
        if (!isRunning) return false

        val played = clocks.time(clock) - began
        val widthDone = spec.isFinished(fromWidth, targetWidth, startSpeedWidth, played)
        val heightDone = spec.isFinished(fromHeight, targetHeight, startSpeedHeight, played)
        if (widthDone) {
            width = targetWidth
            speedWidth = 0f
        } else {
            val motion = spec.at(fromWidth, targetWidth, startSpeedWidth, played)
            width = motion.value
            speedWidth = motion.velocity
        }
        if (heightDone) {
            height = targetHeight
            speedHeight = 0f
        } else {
            val motion = spec.at(fromHeight, targetHeight, startSpeedHeight, played)
            height = motion.value
            speedHeight = motion.velocity
        }
        if (widthDone && heightDone) stop()
        return width != wasWidth || height != wasHeight
    }

    /**
     * Stops where it is and forgets what it was heading for, so the next measure takes its size at
     * once. For a node leaving the tree, or losing the modifier: either may come back, and coming
     * back is appearing, not resizing.
     */
    fun forget() {
        stop()
        targetWidth = Float.NaN
        targetHeight = Float.NaN
    }

    /** A new start: from here, at this speed, now. */
    private fun retarget(clock: Clock, clocks: Clocks) {
        stop()
        fromWidth = width
        fromHeight = height
        startSpeedWidth = speedWidth
        startSpeedHeight = speedHeight
        clocks.register(clock)
        began = clocks.time(clock)
        // Counted as playing, so a test harness waits for it to land and a game's "is anything
        // still moving" is honest about it. Counted as a resize too, so the host keeps laying it out.
        clocks.beganResizing(clock)
        this.clocks = clocks
        this.clock = clock
    }

    private fun snap(toWidth: Float, toHeight: Float) {
        stop()
        width = toWidth
        height = toHeight
        targetWidth = toWidth
        targetHeight = toHeight
        speedWidth = 0f
        speedHeight = 0f
    }

    private fun stop() {
        val clocks = clocks ?: return
        clock?.let { clocks.endedResizing(it) }
        this.clocks = null
        this.clock = null
    }
}
