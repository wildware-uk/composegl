package dev.wildware.composegl.ui.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import kotlinx.coroutines.channels.Channel

/**
 * Several values animated off one state, so they start, turn round and finish as one.
 *
 * A button that shrinks, darkens and drops a pixel when pressed is three animations. Written as
 * three `animate…AsState` calls they are three strangers that happen to be told the same thing;
 * held by one transition they are one movement:
 *
 * ```kotlin
 * val pressed = updateTransition(interaction.isPressed)
 * val scale by pressed.animateFloat { if (it) 0.95f else 1f }
 * val colour by pressed.animateColour { if (it) dark else light }
 * ```
 *
 * What holding them together buys:
 *
 * - **They set off on the same clock tick.** Each value is started from one frame loop in the same
 *   breath, so two tweens of the same length are in step on every frame, not just usually.
 * - **They turn round together.** Change the state mid-flight and every value retargets on the
 *   same frame, each from where it is and at the speed it was going.
 * - **The transition knows when the movement is over.** [currentState] moves to [targetState] once
 *   the slowest value has arrived, and [isRunning] is true for the whole of it — the thing a game
 *   waits on before it lets the player press the button again, or plays the sound on landing.
 * - **A value can join late.** One composed part way through starts from where the state it is
 *   coming from would put it and heads for the target, and the transition waits for it too.
 *
 * Each value has its own spec, chosen per [Segment], so a press can be quick and the release slow.
 *
 * Settled, a transition costs nothing: its loop waits for a change, not for frames.
 *
 * @param S the state. Compared with `==`, so a data class or an enum is what it wants.
 */
@Stable
class Transition<S> internal constructor(
    initial: S,
    internal val clocks: Clocks,
    /** The clock every value in it plays on. [Clock.World] freezes the whole movement while paused. */
    val clock: Clock,
) {

    /** Where it is heading: the state it was last given. */
    var targetState: S by mutableStateOf(initial)
        internal set

    /**
     * Where it last came to rest.
     *
     * Stays the old state until every value has arrived at the new one. A transition that turns
     * round before it gets there never changes it, because it never left.
     */
    var currentState: S by mutableStateOf(initial)
        private set

    /** Something is moving, from the frame it sets off until the slowest value has arrived. */
    var isRunning: Boolean by mutableStateOf(false)
        private set

    /** The move being made now, from [currentState] to [targetState]. What a value's spec is chosen by. */
    val segment: Segment<S> get() = Segment(currentState, targetState)

    private val values = ArrayList<TransitionValue<S, *>>()

    /** Conflated, so any number of changes between two looks at it are one reason to play. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    internal fun add(value: TransitionValue<S, *>) {
        values += value
        poke()
    }

    internal fun remove(value: TransitionValue<S, *>) {
        values -= value
    }

    /**
     * Something may have changed: start playing if nothing is, and there is somewhere to go.
     *
     * Already playing, every value told somewhere new turns round now rather than at the top of the
     * loop's next frame, which would be a frame after the one the change was composed on. Every
     * value is told in the same pass, at the same clock time, so they still turn round as one.
     */
    internal fun poke() {
        if (isRunning) {
            val segment = segment
            for (value in values) value.retarget(segment)
            return
        }
        if (targetState != currentState || values.any { it.isOffTarget }) wake.trySend(Unit)
    }

    /** The loop a transition lives in, for as long as it is composed. Asleep while it is settled. */
    internal suspend fun run() {
        for (reason in wake) play()
    }

    private suspend fun play() {
        clocks.register(clock)
        isRunning = true
        clocks.began(clock)
        try {
            while (true) {
                // Every value whose target moved since the last frame sets off now, at the same clock
                // time as the rest — the whole of "they start and turn round together".
                val segment = segment
                for (value in values) value.retarget(segment)

                withFrameNanos { }

                var arrived = true
                for (value in values) if (!value.advance()) arrived = false
                // A value told somewhere new during the frame has not arrived at it, whatever it
                // just said about the old target.
                if (arrived && values.none { it.isOffTarget }) {
                    currentState = targetState
                    return
                }
            }
        } finally {
            isRunning = false
            clocks.ended(clock)
        }
    }

    /**
     * A move from one state to another, for choosing a spec by.
     *
     * ```kotlin
     * pressed.animateFloat({ if (false isTransitioningTo true) Tween(60) else Spring(Spring.Gentle) }) { … }
     * ```
     *
     * [initialState] is where the transition last came to rest, so a press let go of before it
     * finished is a move from not-pressed to not-pressed: the release spec, not the press one.
     */
    class Segment<S>(val initialState: S, val targetState: S) {

        /** True when this is exactly the move from this state to [target]. */
        infix fun S.isTransitioningTo(target: S): Boolean = this == initialState && target == targetState

        override fun toString(): String = "Segment($initialState -> $targetState)"
    }
}

/**
 * A [Transition] that belongs to this place in the interface, told [targetState] every time it is
 * composed.
 *
 * @param clock what every value in it plays on. [Clock.Ui] keeps moving while the game is paused.
 */
@Composable
fun <S> updateTransition(targetState: S, clock: Clock = Clock.Ui): Transition<S> {
    val clocks = LocalClocks.current
    val transition = remember(clocks, clock) { Transition(targetState, clocks, clock) }
    // Written while composing, not after, so the values composed below this line — which read it —
    // work out their targets for the new state on this same pass rather than a frame late.
    if (transition.targetState != targetState) transition.targetState = targetState
    SideEffect { transition.poke() }
    LaunchedEffect(transition) { transition.run() }
    return transition
}

/**
 * A value that follows the transition's state.
 *
 * @param transitionSpec how to get there, chosen afresh each time it sets off, for the move being
 *   made. Not a key of anything, so writing it inline is fine.
 * @param targetValueByState where the value rests in each state. Asked for both the state the
 *   transition is coming from and the one it is going to, so it should be quick and have no effects.
 *   If what it says for the target changes while the state does not — the skin changed a colour —
 *   the value animates to the new answer rather than jumping.
 */
@Composable
fun <S, T> Transition<S>.animateValue(
    vectoriser: Vectoriser<T>,
    transitionSpec: Transition.Segment<S>.() -> AnimationSpec = { Spring() },
    targetValueByState: @Composable (state: S) -> T,
): State<T> {
    val from = targetValueByState(currentState)
    val to = targetValueByState(targetState)
    val value = remember(this, vectoriser) {
        // Composed part way through, it starts where the state it is coming from puts it.
        TransitionValue(Animatable(from, vectoriser, clock, clocks), to, transitionSpec)
    }
    DisposableEffect(value) {
        add(value)
        onDispose { remove(value) }
    }
    SideEffect {
        value.wanted = to
        value.spec = transitionSpec
        poke()
    }
    return value
}

@Composable
fun <S> Transition<S>.animateFloat(
    transitionSpec: Transition.Segment<S>.() -> AnimationSpec = { Spring() },
    targetValueByState: @Composable (state: S) -> Float,
): State<Float> = animateValue(FloatVectoriser, transitionSpec, targetValueByState)

/** A colour, each channel on its own, alpha included. A tween by default, as with [animateColourAsState]. */
@Composable
fun <S> Transition<S>.animateColour(
    transitionSpec: Transition.Segment<S>.() -> AnimationSpec = { Tween() },
    targetValueByState: @Composable (state: S) -> Colour,
): State<Colour> = animateValue(ColourVectoriser, transitionSpec, targetValueByState)

@Composable
fun <S> Transition<S>.animateOffset(
    transitionSpec: Transition.Segment<S>.() -> AnimationSpec = { Spring(threshold = 0.5f) },
    targetValueByState: @Composable (state: S) -> Offset,
): State<Offset> = animateValue(OffsetVectoriser, transitionSpec, targetValueByState)

@Composable
fun <S> Transition<S>.animateSize(
    transitionSpec: Transition.Segment<S>.() -> AnimationSpec = { Spring(threshold = 0.5f) },
    targetValueByState: @Composable (state: S) -> Size,
): State<Size> = animateValue(SizeVectoriser, transitionSpec, targetValueByState)

/** One value in a transition: where it has got to, and where the transition's state says it should be. */
internal class TransitionValue<S, T>(
    private val animatable: Animatable<T>,
    var wanted: T,
    var spec: Transition.Segment<S>.() -> AnimationSpec,
) : State<T> {

    override val value: T get() = animatable.value

    /** Heading somewhere other than where the state says, or resting somewhere other than there. */
    val isOffTarget: Boolean get() = animatable.target != wanted

    fun retarget(segment: Transition.Segment<S>) {
        if (isOffTarget) animatable.start(wanted, segment.spec())
    }

    fun advance(): Boolean = animatable.advance()
}
