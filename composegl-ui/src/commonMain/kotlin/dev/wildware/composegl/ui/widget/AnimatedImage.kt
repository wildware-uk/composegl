package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.ArtAtlas
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.graphics.frames
import dev.wildware.composegl.ui.graphics.refuseNineRegions
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.skin.LocalSkin
import kotlin.math.floor

/**
 * A strip of pictures played one after another: a spinning coin, a loading spinner, a torch.
 *
 * Held apart from the widget for the same reason [TypewriterState] is: a game asks it whether a
 * one-shot has finished — the explosion that removes itself when it is done — and tells it to
 * [restart].
 *
 * Time is a [Clock]'s, so an animation on [Clock.World] stops where it is when the game pauses and
 * carries on from that frame when it resumes, rather than jumping to where it would have been.
 *
 * Which frame is showing is worked out from how long the animation has been running, not by
 * counting frames. A slow frame skips the pictures it owed rather than playing in slow motion, and
 * a game at 144 frames a second plays a twelve-frame coin at the same speed as one at 30.
 */
class SpriteAnimation internal constructor(
    frames: List<TextureHandle>,
    fps: Float,
    loop: Boolean,
    val clock: Clock,
) {

    /** The pictures, in the order they play. Never empty. */
    var frames: List<TextureHandle> by mutableStateOf(checked(frames))
        private set

    /** How many pictures a second. */
    var fps: Float by mutableStateOf(checked(fps))
        private set

    /** Whether it goes back to the first picture after the last, or stops on the last. */
    var loop: Boolean by mutableStateOf(loop)
        private set

    /**
     * Which picture is showing, counting from zero.
     *
     * Snapshot state that is only written when it changes, which is the whole cost model: a coin at
     * twelve frames a second redraws twelve times a second, not sixty, and a one-shot that has
     * reached its last picture costs nothing at all.
     */
    var frame: Int by mutableStateOf(0)
        private set

    /** The picture showing now. */
    val current: TextureHandle get() = frames[frame]

    /**
     * True once a one-shot has shown its last picture for as long as it shows any picture. Never
     * true of a loop.
     */
    var isFinished: Boolean by mutableStateOf(false)
        private set

    /**
     * Bumped by [restart], so a widget whose ticking had stopped at the end of a one-shot starts
     * ticking again.
     */
    internal var run: Int by mutableStateOf(0)
        private set

    /** The clock's time when the first picture went up, or [NotStarted] before the first tick. */
    private var startedAt = NotStarted

    /** Back to the first picture, playing again. The way to replay a one-shot. */
    fun restart() {
        startedAt = NotStarted
        frame = 0
        isFinished = false
        run++
    }

    /**
     * New frames, rate or looping, from a recomposition.
     *
     * Different frames are a different animation and start from the first picture. A different
     * rate is the same animation played faster, so it carries on from where it is rather than
     * jumping: the time already played is kept, as a fraction of the way through.
     */
    internal fun update(frames: List<TextureHandle>, fps: Float, loop: Boolean) {
        checked(frames)
        checked(fps)
        if (frames != this.frames) {
            this.frames = frames
            this.fps = fps
            this.loop = loop
            restart()
            return
        }
        if (fps != this.fps) {
            if (startedAt != NotStarted && lastNow != NotStarted) {
                val played = lastNow - startedAt
                startedAt = lastNow - (played * (this.fps / fps)).toLong()
            }
            this.fps = fps
        }
        if (loop != this.loop) {
            this.loop = loop
            // A loop that becomes a one-shot past its end has finished; a one-shot that becomes a
            // loop is not finished any more and has to be ticked again.
            if (loop && isFinished) {
                isFinished = false
                run++
            }
        }
    }

    private var lastNow = NotStarted

    /**
     * Shows whatever [now], in [clock]'s nanoseconds, says should be showing.
     *
     * @return whether there is more to show — false only for a one-shot that has finished.
     */
    internal fun tick(now: Long): Boolean {
        lastNow = now
        if (startedAt == NotStarted) startedAt = now
        val elapsed = (now - startedAt).coerceAtLeast(0L)
        val showing = frameAt(elapsed, fps, frames.size, loop)
        if (showing != frame) frame = showing
        if (!loop && elapsed.toDouble() * fps >= frames.size * NanosPerSecond) {
            if (!isFinished) isFinished = true
            return false
        }
        return true
    }

    private fun checked(frames: List<TextureHandle>): List<TextureHandle> {
        require(frames.isNotEmpty()) { "an animation needs at least one frame" }
        frames.forEach { refuseNineRegions(it) }
        return frames
    }

    private fun checked(fps: Float): Float {
        require(fps > 0f && fps.isFinite()) { "an animation plays at more than nothing a second, not $fps" }
        return fps
    }

    internal companion object {
        const val NotStarted = Long.MIN_VALUE
        const val NanosPerSecond = 1_000_000_000.0
    }
}

/**
 * Which of [count] pictures is showing [elapsed] nanoseconds in, at [fps] a second.
 *
 * A loop wraps around; a one-shot stays on its last picture. The division is done in doubles, and a
 * hair is added before rounding down, so that exactly one twelfth of a second at twelve a second is
 * picture one rather than picture nought by a rounding error in the ninth decimal place.
 */
internal fun frameAt(elapsed: Long, fps: Float, count: Int, loop: Boolean): Int {
    if (count <= 1) return 0
    val index = floor(elapsed.coerceAtLeast(0L) * fps.toDouble() / SpriteAnimation.NanosPerSecond + 1e-9).toLong()
    return if (loop) (index % count).toInt() else index.coerceAtMost(count - 1L).toInt()
}

/**
 * An animation of [frames], which starts again from the first when the frames change.
 *
 * @param fps pictures a second.
 * @param loop whether to go round again after the last picture, or stop on it.
 * @param clock whose time it plays on. [Clock.World] for anything that should freeze when the game
 *   is paused — a torch on a wall — and the default [Clock.Ui] for anything a pause menu shows.
 */
@Composable
fun rememberSpriteAnimation(
    frames: List<TextureHandle>,
    fps: Float,
    loop: Boolean = true,
    clock: Clock = Clock.Ui,
): SpriteAnimation {
    val animation = remember(clock) { SpriteAnimation(frames, fps, loop, clock) }
    animation.update(frames, fps, loop)
    return animation
}

/**
 * An animation of every region in [atlas] called [prefix] followed by a number: `coin_0` to
 * `coin_11` for `"coin_"`, in number order. See [frames] for exactly what counts.
 *
 * ```kotlin
 * val coin = rememberSpriteAnimation(atlas, prefix = "coin_", fps = 12f)
 * AnimatedImage(coin, Modifier.size(32f))
 * ```
 */
@Composable
fun rememberSpriteAnimation(
    atlas: ArtAtlas,
    prefix: String,
    fps: Float,
    loop: Boolean = true,
    clock: Clock = Clock.Ui,
): SpriteAnimation {
    val frames = remember(atlas, prefix) { atlas.frames(prefix) }
    return rememberSpriteAnimation(frames, fps, loop, clock)
}

/**
 * The same, out of the skin's atlas, which is where `Image("icons/heart")` looks too.
 */
@Composable
fun rememberSpriteAnimation(
    prefix: String,
    fps: Float,
    loop: Boolean = true,
    clock: Clock = Clock.Ui,
): SpriteAnimation {
    val atlas = LocalSkin.current.art ?: error("no art atlas is loaded, so frames \"$prefix\" cannot be found")
    return rememberSpriteAnimation(atlas, prefix, fps, loop, clock)
}

/**
 * A picture that plays [animation].
 *
 * Laid out as an [Image] the size of the largest frame, so a strip whose frames the packer trimmed
 * to different sizes does not make everything around it jump as it plays. That box is placed by
 * [fit] and [alignment] exactly as [Image] places its one picture, and every frame is scaled by the
 * same amount and aligned inside it, so a trimmed frame does not grow or shrink either.
 *
 * It is only redrawn when the picture changes. Between changes the frame costs one clock read and
 * nothing else, and a one-shot that has finished asks the runtime for no frames at all.
 *
 * In a `uiTest`, a loop that changes picture more often than one frame in three never lets the
 * screen settle, because it never stops changing. Test such a loop on a clock the test stops, or at
 * a lower rate.
 *
 * @param onFinished called once, on the frame a one-shot finishes; again after each [SpriteAnimation.restart].
 */
@Composable
fun AnimatedImage(
    animation: SpriteAnimation,
    modifier: Modifier = Modifier,
    fit: ImageFit = ImageFit.Contain,
    tint: Colour = Colour.White,
    alignment: Alignment = Alignment.Centre,
    onFinished: () -> Unit = {},
) {
    val clocks = LocalClocks.current
    val finished by rememberUpdatedState(onFinished)

    LaunchedEffect(animation, animation.run, animation.loop, clocks) {
        clocks.register(animation.clock)
        // A one-shot is something a test waits for, the way it waits for a fade. A loop is not: it
        // never arrives, and counting it would have every settle time out.
        val oneShot = !animation.loop
        // One that had already finished before this widget saw it — shown again after being hidden
        // — has said so once already.
        val alreadyFinished = animation.isFinished
        if (oneShot) clocks.began(animation.clock)
        try {
            while (animation.tick(clocks.time(animation.clock))) withFrameNanos { }
        } finally {
            if (oneShot) clocks.ended(animation.clock)
        }
        if (!alreadyFinished) finished()
    }

    val frames = animation.frames
    val width = remember(frames) { frames.maxOf { it.width } }
    val height = remember(frames) { frames.maxOf { it.height } }
    val texture = frames[animation.frame.coerceIn(0, frames.size - 1)]
    // A new painter only when the picture changes, because a node is redrawn when what it was told
    // to draw is not what it was told last time. The box is the same whatever frame is up, so the
    // measure policy is kept apart and a new frame is a redraw rather than a layout.
    val measure = remember(width, height) { FrameBox(width, height) }
    val painter = remember(texture, fit, tint, alignment, width, height) {
        FramePainter(texture, fit, tint, alignment, width, height)
    }
    LeafLayout(
        modifier = modifier,
        name = "animated-image",
        measurePolicy = measure,
        draw = painter.draw,
        ink = painter.ink,
    )
}

/** Asks for the largest frame's size, whichever frame is up. */
private class FrameBox(private val width: Int, private val height: Int) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult =
        layout(constraints.constrainWidth(width.toFloat()), constraints.constrainHeight(height.toFloat())) {}
}

/**
 * One frame, drawn where it sits in the box every frame shares.
 *
 * The box — the largest frame's size — is what [fit] and [alignment] place, exactly as [Image]
 * places its picture, and every frame is scaled by that one amount and aligned inside it. Fitting
 * each frame on its own would blow a frame the packer trimmed small up further than a big one, and
 * the coin would pulse as it turned. For a strip whose frames are all one size the box is the frame,
 * and this lands exactly where [Image] would.
 */
private class FramePainter(
    private val texture: TextureHandle,
    private val fit: ImageFit,
    private val tint: Colour,
    private val alignment: Alignment,
    private val boxWidth: Int,
    private val boxHeight: Int,
) {

    /** Where the frame goes on screen and which part of it is used, or null for nothing to draw. */
    private fun place(bounds: Rect): Pair<Rect, Rect?>? {
        if (bounds.isEmpty || boxWidth <= 0 || boxHeight <= 0 || texture.width <= 0 || texture.height <= 0) {
            return null
        }
        val acrossBox = bounds.width / boxWidth
        val downBox = bounds.height / boxHeight
        val (scaleX, scaleY) = when (fit) {
            ImageFit.Stretch -> acrossBox to downBox
            ImageFit.Contain -> minOf(acrossBox, downBox).let { it to it }
            ImageFit.Cover -> maxOf(acrossBox, downBox).let { it to it }
            ImageFit.None -> 1f to 1f
        }
        val boxW = boxWidth * scaleX
        val boxH = boxHeight * scaleY
        val frameW = texture.width * scaleX
        val frameH = texture.height * scaleY
        val frame = Rect.of(
            bounds.left + alignment.xIn(bounds.width, boxW) + alignment.xIn(boxW, frameW),
            bounds.top + alignment.yIn(bounds.height, boxH) + alignment.yIn(boxH, frameH),
            frameW,
            frameH,
        )
        // Cover crops rather than overflows, as it does for an Image; None is allowed to overflow.
        if (fit != ImageFit.Cover) return frame to null
        val shown = frame.intersect(bounds)
        if (shown.isEmpty) return null
        if (shown == frame) return frame to null
        val source = Rect.of(
            (shown.left - frame.left) / scaleX,
            (shown.top - frame.top) / scaleY,
            shown.width / scaleX,
            shown.height / scaleY,
        )
        return shown to source
    }

    val draw: UiCanvas.(Rect) -> Unit = { bounds ->
        place(bounds)?.let { (destination, source) -> image(texture, destination, tint, source) }
    }

    /** The same call the drawing makes, so the two cannot drift. */
    val ink: (Rect) -> Rect? = { bounds -> place(bounds)?.first }
}
