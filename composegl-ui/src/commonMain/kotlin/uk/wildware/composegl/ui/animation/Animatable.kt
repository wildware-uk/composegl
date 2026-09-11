package uk.wildware.composegl.ui.animation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.geometry.Size
import uk.wildware.composegl.ui.graphics.Colour

/**
 * How to take a thing apart into numbers and put it back together.
 *
 * Animation is arithmetic on numbers, and everything a game animates — a colour, a position, a size
 * — is a handful of them. Each number is animated on its own with the same spec, which is both the
 * simplest thing that works and what a spring's maths wants anyway.
 *
 * The numbers should be on a sane scale. A colour is taken apart into four values from 0 to 1
 * rather than 0 to 255 so that the default "close enough to have arrived" means the same thing for
 * a colour as it does for anything else.
 */
interface Vectoriser<T> {

    val size: Int

    fun toVector(value: T, into: FloatArray)

    fun fromVector(vector: FloatArray): T
}

object FloatVectoriser : Vectoriser<Float> {
    override val size: Int get() = 1
    override fun toVector(value: Float, into: FloatArray) {
        into[0] = value
    }
    override fun fromVector(vector: FloatArray): Float = vector[0]
}

object ColourVectoriser : Vectoriser<Colour> {
    override val size: Int get() = 4
    override fun toVector(value: Colour, into: FloatArray) {
        into[0] = value.alpha / 255f
        into[1] = value.red / 255f
        into[2] = value.green / 255f
        into[3] = value.blue / 255f
    }
    override fun fromVector(vector: FloatArray): Colour = Colour(
        channel(vector[0]),
        channel(vector[1]),
        channel(vector[2]),
        channel(vector[3]),
    )
    private fun channel(value: Float): Int = (value * 255f + 0.5f).toInt().coerceIn(0, 255)
}

object OffsetVectoriser : Vectoriser<Offset> {
    override val size: Int get() = 2
    override fun toVector(value: Offset, into: FloatArray) {
        into[0] = value.x
        into[1] = value.y
    }
    override fun fromVector(vector: FloatArray): Offset = Offset(vector[0], vector[1])
}

object SizeVectoriser : Vectoriser<Size> {
    override val size: Int get() = 2
    override fun toVector(value: Size, into: FloatArray) {
        into[0] = value.width
        into[1] = value.height
    }
    override fun fromVector(vector: FloatArray): Size = Size(vector[0], vector[1])
}

/**
 * A value that moves towards what it is told to be.
 *
 * The thing a game holds when it wants to drive an animation itself rather than declare it:
 * `animateTo` suspends until it arrives, so a sequence of movements is written as a sequence of
 * lines. Cancelling the coroutine stops it where it is, with its speed intact, so whatever animates
 * it next carries on from there rather than starting again from a standstill.
 *
 * Retargeting mid-flight is the case worth understanding. Calling `animateTo` again while it is
 * moving starts a new animation from where the value is *and how fast it is going*, which is why a
 * spring handles a player dragging something around and a tween does not: a spring has no duration
 * to argue about.
 *
 * It runs on one named clock. An animation on [Clock.World] stops when the game is paused and
 * resumes from where it was, and the pause menu on top of it — on [Clock.Ui] — carries on.
 *
 * ```kotlin
 * val slide = rememberAnimatable(0f)
 * LaunchedEffect(open) { slide.animateTo(if (open) 1f else 0f, Spring()) }
 * ```
 */
class Animatable<T>(
    initial: T,
    private val vectoriser: Vectoriser<T>,
    val clock: Clock = Clock.Ui,
    private val clocks: Clocks = Clocks(),
) {

    private val current = FloatArray(vectoriser.size)
    private val speed = FloatArray(vectoriser.size)
    private val start = FloatArray(vectoriser.size)
    private val startSpeed = FloatArray(vectoriser.size)
    private val destination = FloatArray(vectoriser.size)

    /** Where it is now. Snapshot state: reading it in a composable is what redraws the frame. */
    var value: T by mutableStateOf(initial)
        private set

    /** What it is heading for, which is where it already is when nothing is running. */
    var target: T by mutableStateOf(initial)
        private set

    var isRunning: Boolean by mutableStateOf(false)
        private set

    init {
        vectoriser.toVector(initial, current)
        vectoriser.toVector(initial, destination)
    }

    /**
     * Moves to [target] and suspends until it gets there.
     *
     * Cancelling leaves the value where it had reached, moving at whatever speed it had, ready for
     * the next animation to pick up.
     */
    suspend fun animateTo(target: T, spec: AnimationSpec = Spring()) {
        this.target = target
        vectoriser.toVector(target, destination)
        current.copyInto(start)
        speed.copyInto(startSpeed)

        clocks.register(clock)
        val began = clocks.time(clock)
        isRunning = true
        try {
            while (true) {
                // The one subscription an animation has. It is dropped the moment it arrives, which
                // is what makes a settled interface cost nothing.
                withFrameNanos { }
                val played = clocks.time(clock) - began
                if (step(spec, played)) return
            }
        } finally {
            isRunning = false
        }
    }

    /** Puts the value there now, and stops. Use when a screen opens already in its end state. */
    fun snapTo(value: T) {
        vectoriser.toVector(value, current)
        vectoriser.toVector(value, destination)
        speed.fill(0f)
        this.value = value
        this.target = value
    }

    /**
     * One frame of the animation.
     *
     * @return true when every number has arrived. A colour whose red has settled and whose blue has
     *   not is still moving, so this is an `and` across all of them.
     */
    private fun step(spec: AnimationSpec, playedNanos: Long): Boolean {
        var finished = true
        for (channel in current.indices) {
            val from = start[channel]
            val to = destination[channel]
            val motion = spec.at(from, to, startSpeed[channel], playedNanos)
            val done = spec.isFinished(from, to, startSpeed[channel], playedNanos)
            if (done) {
                current[channel] = to
                speed[channel] = 0f
            } else {
                current[channel] = motion.value
                speed[channel] = motion.velocity
                finished = false
            }
        }
        // Writing the same value as last frame is not a change, so a paused clock — which hands
        // out the same time every frame — costs no redraws even while the animation is alive.
        value = vectoriser.fromVector(current)
        return finished
    }
}
