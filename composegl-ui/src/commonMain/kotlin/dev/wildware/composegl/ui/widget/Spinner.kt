package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Clocks
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.FrameWaiter
import dev.wildware.composegl.ui.input.LocalUiSounds
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.styled
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import dev.wildware.composegl.ui.skin.LocalSkin
import dev.wildware.composegl.ui.skin.ResolvedStyle
import dev.wildware.composegl.ui.skin.rememberStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Something is happening and nobody knows for how long: saving, loading, connecting, finding a
 * match. The turning arc in the corner of almost every game.
 *
 * ```kotlin
 * if (saving) Spinner(Modifier.size(24f))
 * ```
 *
 * An arc chases its own tail round a circle, stretching and shrinking as it goes, once round every
 * [revolutionMillis]. It is 24 across unless it is given a size, and it is always a circle: a box
 * wider than it is tall gets a circle as tall as the box, in the middle.
 *
 * It runs on [clock]. [Clock.Ui], the default, keeps turning under a pause menu, which is what a
 * save icon wants. A spinner in the world — a loot chest being opened — passes [Clock.World] and
 * stops with the game.
 *
 * **Only the drawing moves.** Nothing recomposes and layout does not change: each frame the clock
 * has moved on, the spinner asks for that frame to be drawn again and reads the time while it is
 * being drawn. A spinner on a stopped clock asks for nothing. A test waiting for the screen to settle
 * does not wait for a spinner, which would be waiting for ever.
 *
 * Everything it looks like is the skin's: `"<style>"`'s text colour is the arc and its background
 * sits behind it, and `"<style>.track"`'s text colour, when the skin names that style, is a full
 * ring under the arc. For a picture of a spinner instead — a turning disc, a sprite sheet of an
 * hourglass — play its frames with the overload that takes a [SpriteAnimation].
 *
 * It takes no focus and no pointer: the pad and the mouse pass straight over it. It turns clockwise
 * on a right-to-left screen too, as a clock's hands do.
 *
 * @param thickness how wide the arc is drawn, or null for an eighth of the circle's width.
 * @param revolutionMillis how long one turn takes.
 * @param clock whose time it turns on.
 */
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    style: String = "spinner",
    thickness: Float? = null,
    revolutionMillis: Int = 1000,
    clock: Clock = Clock.Ui,
) {
    require(revolutionMillis > 0) { "a spinner has to turn, so a revolution takes more than $revolutionMillis ms" }
    require(thickness == null || thickness >= 0f) { "a spinner's arc cannot be $thickness thick" }
    val resolved = rememberStyle(style)
    val skin = LocalSkin.current
    val trackStyle = rememberStyle("$style.track")
    // Only a track the skin names: one it does not would fall back to the arc's own colour and draw a
    // full circle under it.
    val track = if (skin.has("$style.track")) trackStyle.textColour else null
    val ticker = rememberDrawTicker(clock)
    val draw = remember(ticker, resolved, track, thickness, revolutionMillis) {
        spinnerDraw(ticker, resolved.textColour, track, thickness, revolutionMillis)
    }
    TickingLeaf(modifier.styled(resolved), "spinner", SpinnerPolicy, draw, ticker)
}

/**
 * A spinner that plays [animation]'s pictures instead of drawing an arc: a sprite sheet of a turning
 * disc, an hourglass, the studio's logo.
 *
 * ```kotlin
 * Spinner(rememberSpriteAnimation("spinner_", fps = 12f), Modifier.size(24f))
 * ```
 *
 * The frames come from the skin's atlas when they are named by a prefix, as above, so a skin can
 * swap the picture without the screen changing. It is an [AnimatedImage] underneath, with that
 * widget's costs: a redraw each time the picture changes, and nothing between. Its clock is the
 * animation's, given to [rememberSpriteAnimation].
 */
@Composable
fun Spinner(
    animation: SpriteAnimation,
    modifier: Modifier = Modifier,
    fit: ImageFit = ImageFit.Contain,
    tint: Colour = Colour.White,
) {
    require(animation.loop) { "a spinner goes round for ever, so its animation has to loop" }
    AnimatedImage(animation, modifier, fit = fit, tint = tint)
}

/**
 * A bar for "working on it", when there is no fraction to show: a block that slides along a track
 * again and again.
 *
 * ```kotlin
 * IndeterminateBar(Modifier.fillMaxWidth())
 * ```
 *
 * It is the partner of a progress bar that knows how far it has got. Swap one for the other when the
 * total becomes known. The block crosses the track once every [sweepMillis], easing in and out, and
 * is cut to the track, so it slides in from one end and off the other.
 *
 * It is [length] along unless it is given a size — `fillMaxWidth` is the usual — and [thickness]
 * across. A vertical one runs up from the bottom, the way a tank fills. On a right-to-left screen a
 * horizontal one runs from right to left, the way the words do.
 *
 * Like [Spinner], only the drawing moves, on [clock]: nothing recomposes, layout does not change,
 * and on a stopped clock it asks for no frames at all.
 *
 * Everything it looks like is the skin's: `"<style>.track"` is drawn under the whole bar, and its
 * padding insets the block, and `"<style>.fill"` is drawn as the block.
 *
 * @param blockFraction how much of the track the block covers, from above 0 to 1.
 * @param sweepMillis how long the block takes to cross the track.
 * @param clock whose time it moves on.
 */
@Composable
fun IndeterminateBar(
    modifier: Modifier = Modifier,
    style: String = "indeterminatebar",
    orientation: Orientation = Orientation.Horizontal,
    thickness: Float = 6f,
    length: Float = 160f,
    blockFraction: Float = 0.35f,
    sweepMillis: Int = 1400,
    clock: Clock = Clock.Ui,
) {
    require(sweepMillis > 0) { "an indeterminate bar has to move, so a sweep takes more than $sweepMillis ms" }
    require(blockFraction > 0f && blockFraction <= 1f) { "the block covers some of the track, not $blockFraction" }
    require(thickness >= 0f && length >= 0f) { "a bar cannot be $length long and $thickness thick" }
    val trackStyle = rememberStyle("$style.track")
    val fill = rememberStyle("$style.fill")
    val direction = LocalLayoutDirection.current
    val horizontal = orientation == Orientation.Horizontal
    val ticker = rememberDrawTicker(clock)
    val policy = remember(horizontal, thickness, length) { IndeterminateBarPolicy(horizontal, thickness, length) }
    val draw = remember(ticker, fill, horizontal, direction, blockFraction, sweepMillis) {
        indeterminateDraw(ticker, fill, horizontal, direction == LayoutDirection.Rtl, blockFraction, sweepMillis)
    }
    TickingLeaf(modifier.styled(trackStyle), "indeterminatebar", policy, draw, ticker)
}

// --- the motion, worked out from the time alone -------------------------------------------------

/**
 * Where a spinner's arc is: [startDegrees] clockwise from three o'clock, and [sweepDegrees] of arc
 * clockwise from there.
 */
internal data class SpinnerArc(val startDegrees: Float, val sweepDegrees: Float)

/** The shortest the arc gets, so it never disappears at the moment it turns from shrinking to growing. */
internal const val SpinnerMinSweep = 20f

/** How much longer than [SpinnerMinSweep] the arc stretches, at its longest. */
internal const val SpinnerStretch = 250f

/**
 * Where the arc is [nanos] into its clock.
 *
 * Two movements added together. The whole thing turns steadily, once a [revolutionMillis]. On top,
 * in each revolution the head of the arc races [SpinnerStretch] ahead in the first half while the
 * tail waits, and the tail catches up in the second half — so the arc stretches and shrinks. Each
 * revolution starts where the last one's tail stopped, so there is no jump between them.
 *
 * Nothing but the time goes in, so a dropped frame or a paused clock lands exactly where it would
 * have anyway, and a test can say where the arc is.
 */
internal fun spinnerArcAt(nanos: Long, revolutionMillis: Int): SpinnerArc {
    val period = revolutionMillis * 1_000_000L
    val t = nanos.coerceAtLeast(0L)
    val turns = t / period
    val into = (t % period).toDouble() / period
    val head = SpinnerStretch * smooth((into * 2).coerceIn(0.0, 1.0))
    val tail = SpinnerStretch * smooth((into * 2 - 1).coerceIn(0.0, 1.0))
    val turning = (into * 360.0).toFloat()
    // Wrapped to a turn so the numbers stay small however long the game has been running. Starting at
    // twelve o'clock, where a clock starts.
    val start = ((turns % 36) * SpinnerStretch + tail + turning - 90f).mod(360f)
    return SpinnerArc(start, SpinnerMinSweep + head - tail)
}

/**
 * Where the block of an indeterminate bar starts along a track [span] long, [nanos] into its clock,
 * measured from the end it sets off from.
 *
 * It starts wholly off that end — `-block` — and ends wholly off the other, so on a track that cuts
 * it off it slides in and away rather than appearing. Eased, so it gathers speed and slows.
 */
internal fun indeterminateBlockAt(nanos: Long, sweepMillis: Int, span: Float, block: Float): Float {
    val period = sweepMillis * 1_000_000L
    val into = (nanos.coerceAtLeast(0L) % period).toDouble() / period
    return -block + (span + block) * smooth(into)
}

/** Smoothstep: slow at both ends, quickest in the middle. */
private fun smooth(f: Double): Float = (f * f * (3 - 2 * f)).toFloat()

// --- drawing ------------------------------------------------------------------------------------

private fun spinnerDraw(
    ticker: DrawTicker,
    colour: Colour,
    track: Colour?,
    thickness: Float?,
    revolutionMillis: Int,
): UiCanvas.(Rect) -> Unit = { bounds ->
    val radius = minOf(bounds.width, bounds.height) / 2f
    if (radius > 0f) {
        val centre = Offset(bounds.left + bounds.width / 2f, bounds.top + bounds.height / 2f)
        val width = (thickness ?: (radius / 4f)).coerceAtMost(radius)
        if (track != null && track.alpha > 0) ring(centre, radius, width, 0f, 360f, track)
        val arc = spinnerArcAt(ticker.now, revolutionMillis)
        ring(centre, radius, width, arc.startDegrees, arc.sweepDegrees, colour)
    }
}

private fun indeterminateDraw(
    ticker: DrawTicker,
    fill: ResolvedStyle,
    horizontal: Boolean,
    rtl: Boolean,
    blockFraction: Float,
    sweepMillis: Int,
): UiCanvas.(Rect) -> Unit = { bounds ->
    if (!bounds.isEmpty) {
        val span = if (horizontal) bounds.width else bounds.height
        val block = span * blockFraction
        val along = indeterminateBlockAt(ticker.now, sweepMillis, span, block)
        // Measured from the end it sets off from: the left, the right on a right-to-left screen, and
        // the bottom of a vertical one.
        val rect = when {
            !horizontal -> Rect.of(bounds.left, bounds.bottom - along - block, bounds.width, block)
            rtl -> Rect.of(bounds.right - along - block, bounds.top, block, bounds.height)
            else -> Rect.of(bounds.left + along, bounds.top, block, bounds.height)
        }
        val shown = rect.intersect(bounds)
        if (!shown.isEmpty) {
            pushClip(bounds)
            fill.background.drawInto(this, rect, fill.tint)
            popClip()
        }
    }
}

/**
 * A stroked arc of [width], its outside edge on [radius], from [startDegrees] through [sweepDegrees].
 *
 * A strip of four-sided pieces, one [UiCanvas.fan] each, because a fan can only fill convex shapes
 * and a thick arc is not one. About one piece per two units along the outside, as [UiCanvas.arc]
 * does, and never fewer than four.
 */
internal fun UiCanvas.ring(
    centre: Offset,
    radius: Float,
    width: Float,
    startDegrees: Float,
    sweepDegrees: Float,
    colour: Colour,
) {
    if (radius <= 0f || width <= 0f || sweepDegrees == 0f) return
    val span = sweepDegrees.coerceIn(-360f, 360f)
    val inner = (radius - width).coerceAtLeast(0f)
    val length = abs(span) * DegreesToRadians * radius
    val steps = ceil(length / 2f).toInt().coerceIn(4, 180)
    val step = span * DegreesToRadians / steps
    val start = startDegrees * DegreesToRadians
    val quad = FloatArray(8)
    for (i in 0 until steps) {
        val a = start + step * i
        val b = a + step
        quad[0] = centre.x + cos(a) * radius
        quad[1] = centre.y + sin(a) * radius
        quad[2] = centre.x + cos(b) * radius
        quad[3] = centre.y + sin(b) * radius
        quad[4] = centre.x + cos(b) * inner
        quad[5] = centre.y + sin(b) * inner
        quad[6] = centre.x + cos(a) * inner
        quad[7] = centre.y + sin(a) * inner
        fan(quad.copyOf(), colour)
    }
}

private const val DegreesToRadians = (PI / 180.0).toFloat()

// --- sizes --------------------------------------------------------------------------------------

/** Twenty-four across unless told otherwise: the size of an icon. */
private object SpinnerPolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult =
        layout(constraints.constrainWidth(SpinnerSize), constraints.constrainHeight(SpinnerSize)) {}
}

private const val SpinnerSize = 24f

/**
 * [length] along and [thickness] across, or whatever size it is fixed to. A data class, so the same
 * arguments again are an equal policy and the node reports no change.
 */
private data class IndeterminateBarPolicy(
    val horizontal: Boolean,
    val thickness: Float,
    val length: Float,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult =
        if (horizontal) {
            layout(constraints.constrainWidth(length), constraints.constrainHeight(thickness)) {}
        } else {
            layout(constraints.constrainWidth(thickness), constraints.constrainHeight(length)) {}
        }
}

// --- the frame a clock moving costs -------------------------------------------------------------

/**
 * Asks for a node to be drawn again on every frame its [clock] has moved on, without recomposing it.
 *
 * Waits on the tree's frames the way a marquee does. The drawing reads [now] itself, so the arc is
 * wherever the time says whatever else caused the frame. A stopped clock reads the same time frame
 * after frame, and a frame whose time has not moved asks for nothing.
 *
 * It waits only while it is [running] and its node is in a tree, and follows the node from tree to
 * tree. The node is often not in one when the composition starts the ticker: a popup composes its
 * contents first and puts them on the screen afterwards.
 */
internal class DrawTicker(private val clock: Clock, private val clocks: Clocks) : FrameWaiter {

    /** The node being drawn. Set when the node is made, before it is ever in a tree. */
    var node: UiNode? = null
        set(value) {
            if (field === value) return
            stopWaiting()
            field?.let { if (it.onTreeChanged === follow) it.onTreeChanged = null }
            field = value
            value?.onTreeChanged = follow
            follow()
        }

    /** Whether the composition wants it turning: from when it is remembered until it is forgotten. */
    private var running = false

    private var waitingOn: UiTree? = null
    private var last = Long.MIN_VALUE

    /** Waits on whichever tree the node is in now, or none. */
    private val follow: () -> Unit = {
        val tree = node?.tree.takeIf { running }
        if (tree !== waitingOn) {
            stopWaiting()
            if (tree != null) {
                // A clock nobody has asked about is not being wound on, and this one has to be.
                clocks.register(clock)
                waitingOn = tree
                tree.wait(this)
                // A frame, so a node that joined a tree mid-turn is drawn where the time says.
                last = Long.MIN_VALUE
            }
        }
    }

    /** The clock's time now, in nanoseconds. What the drawing is worked out from. */
    val now: Long get() = clocks.time(clock)

    fun start() {
        running = true
        follow()
    }

    fun stop() {
        running = false
        stopWaiting()
    }

    private fun stopWaiting() {
        waitingOn?.stopWaiting(this)
        waitingOn = null
    }

    override fun onFrame(clocks: Clocks) {
        val node = node ?: return
        val time = now
        if (time == last) return
        last = time
        node.tree?.redraw(node)
    }
}

@Composable
private fun rememberDrawTicker(clock: Clock): DrawTicker {
    val clocks = LocalClocks.current
    val ticker = remember(clock, clocks) { DrawTicker(clock, clocks) }
    DisposableEffect(ticker) {
        ticker.start()
        onDispose { ticker.stop() }
    }
    return ticker
}

/** A leaf, as [dev.wildware.composegl.ui.layout.LeafLayout] makes one, that [ticker] knows the node of. */
@Composable
private fun TickingLeaf(
    modifier: Modifier,
    name: String,
    measurePolicy: MeasurePolicy,
    draw: UiCanvas.(Rect) -> Unit,
    ticker: DrawTicker,
) {
    val sounds = LocalUiSounds.current
    val direction = LocalLayoutDirection.current
    ComposeNode<UiNode, UiApplier>(
        factory = { UiNode(name) },
        update = {
            set(ticker) { it.node = this }
            set(name) { this.name = it }
            set(sounds) { this.sounds = it }
            set(direction) { this.layoutDirection = it }
            set(modifier) { this.modifier = it }
            set(measurePolicy) { this.measurePolicy = it }
            set(draw) { this.content = it }
        },
    )
}
