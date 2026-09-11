package uk.wildware.composegl.ui.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.geometry.Size
import uk.wildware.composegl.ui.graphics.Colour

/**
 * An [Animatable] that belongs to this place in the interface.
 *
 * It picks up the clocks the host is advancing, so a game never wires that up by hand.
 */
@Composable
fun <T> rememberAnimatable(
    initial: T,
    vectoriser: Vectoriser<T>,
    clock: Clock = Clock.Ui,
): Animatable<T> {
    val clocks = LocalClocks.current
    return remember(vectoriser, clock, clocks) { Animatable(initial, vectoriser, clock, clocks) }
}

@Composable
fun rememberAnimatable(initial: Float, clock: Clock = Clock.Ui): Animatable<Float> =
    rememberAnimatable(initial, FloatVectoriser, clock)

/**
 * A value that follows [target] instead of jumping to it.
 *
 * The whole of the declarative half of animation: write down where the thing should be, and it goes
 * there. Change the target mid-flight and it turns around from where it is, at the speed it was
 * already going.
 *
 * ```kotlin
 * val alpha by animateFloatAsState(if (visible) 1f else 0f)
 * Box(Modifier.alpha(alpha))
 * ```
 *
 * A settled animation subscribes to nothing and costs no frames, so a screen with a dozen of them
 * sitting at their targets redraws exactly as often as a screen with none: never.
 *
 * @param clock which clock it runs on. [Clock.Ui] keeps moving while the game is paused; anything
 *   that belongs to the world should say [Clock.World] and stop with it.
 * @param onFinished called with the value it arrived at, once, when it gets there. Not called when
 *   the target changes before it arrives.
 */
@Composable
fun <T> animateAsState(
    target: T,
    vectoriser: Vectoriser<T>,
    spec: AnimationSpec = Spring(),
    clock: Clock = Clock.Ui,
    onFinished: ((T) -> Unit)? = null,
): State<T> {
    val animatable = rememberAnimatable(target, vectoriser, clock)
    // Not a key of the effect. A spec is usually written inline, so it is a new object on every
    // recomposition, and keying on it would start the animation again several times a second.
    val current by rememberUpdatedState(spec)
    val finished by rememberUpdatedState(onFinished)

    LaunchedEffect(target) {
        animatable.animateTo(target, current)
        finished?.invoke(target)
    }

    return remember(animatable) { AnimatedState(animatable) }
}

@Composable
fun animateFloatAsState(
    target: Float,
    spec: AnimationSpec = Spring(),
    clock: Clock = Clock.Ui,
    onFinished: ((Float) -> Unit)? = null,
): State<Float> = animateAsState(target, FloatVectoriser, spec, clock, onFinished)

/**
 * A colour that fades to [target] rather than changing.
 *
 * Each channel is animated on its own, including the alpha, so a colour fading to transparent goes
 * transparent rather than going black on the way.
 */
@Composable
fun animateColourAsState(
    target: Colour,
    spec: AnimationSpec = Tween(),
    clock: Clock = Clock.Ui,
    onFinished: ((Colour) -> Unit)? = null,
): State<Colour> = animateAsState(target, ColourVectoriser, spec, clock, onFinished)

@Composable
fun animateOffsetAsState(
    target: Offset,
    spec: AnimationSpec = Spring(threshold = 0.5f),
    clock: Clock = Clock.Ui,
    onFinished: ((Offset) -> Unit)? = null,
): State<Offset> = animateAsState(target, OffsetVectoriser, spec, clock, onFinished)

@Composable
fun animateSizeAsState(
    target: Size,
    spec: AnimationSpec = Spring(threshold = 0.5f),
    clock: Clock = Clock.Ui,
    onFinished: ((Size) -> Unit)? = null,
): State<Size> = animateAsState(target, SizeVectoriser, spec, clock, onFinished)

/** The animatable's value, seen as the read-only thing a caller delegates to. */
private class AnimatedState<T>(private val animatable: Animatable<T>) : State<T> {
    override val value: T get() = animatable.value
}
