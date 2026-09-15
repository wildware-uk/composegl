package dev.wildware.composegl.ui.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.scale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * How something arrives: where it starts, as a fade, a scale and a slide, and how it gets from there
 * to where it rests.
 *
 * Built from [fadeIn], [scaleIn] and [slideIn], and put together with `+`:
 *
 * ```kotlin
 * enter = fadeIn() + scaleIn(from = 0.9f)
 * ```
 *
 * Each part has its own spec, so a panel can fade quickly and settle its scale on a slower spring.
 * Two of the same part is a choice rather than a sum, so the later one wins.
 */
class EnterTransition internal constructor(internal val parts: TransitionParts) {

    operator fun plus(other: EnterTransition) = EnterTransition(parts + other.parts)

    companion object {
        /** Arrives at once, with nothing to watch. */
        val None = EnterTransition(TransitionParts())
    }
}

/**
 * How something leaves: where it ends up, as a fade, a scale and a slide, before it is taken away.
 *
 * The mirror of [EnterTransition], built from [fadeOut], [scaleOut] and [slideOut]. [None] takes the
 * thing away the frame it is hidden, which is exactly what an `if` does.
 */
class ExitTransition internal constructor(internal val parts: TransitionParts) {

    operator fun plus(other: ExitTransition) = ExitTransition(parts + other.parts)

    companion object {
        /** Taken away at once. */
        val None = ExitTransition(TransitionParts())
    }
}

/**
 * Fades in from [from], a fraction of full opacity.
 */
fun fadeIn(from: Float = 0f, spec: AnimationSpec = Tween()) =
    EnterTransition(TransitionParts(fade = Fade(from, spec)))

/** Fades out to [to], a fraction of full opacity. */
fun fadeOut(to: Float = 0f, spec: AnimationSpec = Tween()) =
    ExitTransition(TransitionParts(fade = Fade(to, spec)))

/**
 * Grows in from [from] times its size, about [origin].
 *
 * Built on [Modifier.scale], so everything written there holds while it plays: the panel keeps its
 * slot in the layout, clicks land on what is drawn, and a child's glow is cut off at the panel's
 * edge until it arrives. A spring that overshoots is fine; one that dips below zero is held at zero.
 */
fun scaleIn(from: Float = 0f, origin: Alignment = Alignment.Centre, spec: AnimationSpec = Tween()) =
    EnterTransition(TransitionParts(scale = Grow(from, origin, spec)))

/** Shrinks away to [to] times its size, about [origin]. */
fun scaleOut(to: Float = 0f, origin: Alignment = Alignment.Centre, spec: AnimationSpec = Tween()) =
    ExitTransition(TransitionParts(scale = Grow(to, origin, spec)))

/**
 * Slides in from [from], in the same units as [Modifier.offset] — a menu coming up from below is
 * `slideIn(Offset(0f, 40f))`.
 *
 * Like an offset, the slide moves the node rather than the space it takes, so its neighbours do not
 * move while it plays, and it is hit where it is drawn.
 */
fun slideIn(from: Offset, spec: AnimationSpec = Tween()) =
    EnterTransition(TransitionParts(slide = Slide(from, spec)))

/** Slides away to [to], in the same units as [Modifier.offset]. */
fun slideOut(to: Offset, spec: AnimationSpec = Tween()) =
    ExitTransition(TransitionParts(slide = Slide(to, spec)))

/**
 * Shows [content] when [visible] is true, and animates it in and out rather than adding and removing
 * it.
 *
 * The whole point is the leaving. `if (open) Menu()` takes the menu away the frame `open` goes false,
 * so there is nothing left to animate; this keeps [content] composed, laid out, drawn and in the tree
 * until [exit] has finished playing, and only then takes it away.
 *
 * ```kotlin
 * AnimatedVisibility(visible = open, enter = fadeIn() + scaleIn(from = 0.9f), exit = fadeOut()) {
 *     Panel { … }
 * }
 * ```
 *
 * Changing [visible] mid-flight turns round from wherever it has got to, at the speed it was going:
 * a toast dismissed half-way in fades out from half, and a menu reopened half-way out comes back
 * without ever leaving the tree.
 *
 * The content sits in one [Box] carrying [modifier] and the transition, so a `Modifier.align` in
 * [modifier] places the whole thing. At rest the transition is an offset of nothing, an alpha of one
 * and a scale of one — no picture taken, nothing to redraw — and hidden, there is nothing composed at
 * all. Neither state asks for frames.
 *
 * Still in the tree while it leaves means still reachable while it leaves: a button on a menu that is
 * fading out can be clicked and focused until it is gone, the way it is in every other toolkit. A
 * screen that must not allow that says so on the button, with `enabled = open`.
 *
 * @param visible whether the content should be there.
 * @param enter how it arrives. Unused on the first composition unless [initiallyVisible] is false.
 * @param exit how it leaves.
 * @param initiallyVisible whether it is already there, at rest, the first time this is composed. It
 *   is [visible] by default, so a screen that opens with its panel showing does not watch the panel
 *   arrive; pass false for a toast that should animate in the moment it is added.
 * @param clock which clock it plays on. [Clock.World] freezes a leaving thing mid-exit while the game
 *   is paused.
 */
@Composable
fun AnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(),
    exit: ExitTransition = fadeOut(),
    initiallyVisible: Boolean = visible,
    clock: Clock = Clock.Ui,
    content: @Composable () -> Unit,
) {
    val clocks = LocalClocks.current
    val state = remember(clocks, clock) { VisibilityState(initiallyVisible, clocks, clock) }

    // Not keys of the effect. A transition is written inline, so it is a new object on every
    // recomposition, and keying on it would restart the animation every frame it plays.
    val currentEnter by rememberUpdatedState(enter)
    val currentExit by rememberUpdatedState(exit)

    LaunchedEffect(state, visible) {
        if (visible) state.show(currentEnter.parts) else state.hide(currentExit.parts)
    }

    // The effect above runs after this frame is composed, so it cannot be what decides this frame.
    // Shown from nothing, the content is drawn where the enter starts the same frame an `if` would
    // add it; hidden with nothing left to play, it goes the same frame an `if` would take it away.
    val arriving = visible && !state.isPresent
    // No exit at all goes at once even part way through arriving, rather than playing back to rest
    // first only to vanish there.
    if (!visible && (!state.isPresent || exit.parts.isEmpty || state.hasArrivedAt(exit.parts))) return

    val parts = enter.parts
    val shift = if (arriving) parts.slide?.offset ?: Offset.Zero else state.offset.value
    val alpha = if (arriving) parts.fade?.alpha ?: 1f else state.alpha.value
    val scale = if (arriving) parts.scale?.factor ?: 1f else state.scale.value
    val origin = if (arriving) parts.scale?.origin ?: state.origin else state.origin
    Box(
        modifier
            .offset(shift.x, shift.y)
            .alpha(alpha)
            // A bouncy spring overshoots below zero on the way out, and a negative scale throws.
            .scale(scale.coerceAtLeast(0f), origin),
    ) { content() }
}

// --- the machinery --------------------------------------------------------------------------

/** One transition's worth of parts. Null means that part stays at rest. */
internal data class TransitionParts(
    val fade: Fade? = null,
    val scale: Grow? = null,
    val slide: Slide? = null,
) {
    /** Nothing named, as with [EnterTransition.None] and [ExitTransition.None]. */
    val isEmpty get() = fade == null && scale == null && slide == null

    operator fun plus(other: TransitionParts) = TransitionParts(
        fade = other.fade ?: fade,
        scale = other.scale ?: scale,
        slide = other.slide ?: slide,
    )
}

/** An opacity at the far end of a transition — where an enter starts, or where an exit ends. */
internal class Fade(val alpha: Float, val spec: AnimationSpec)

internal class Grow(val factor: Float, val origin: Alignment, val spec: AnimationSpec)

internal class Slide(val offset: Offset, val spec: AnimationSpec)

/**
 * Whether the content is in the tree, and where each part of its transition has got to.
 *
 * Three animatables rather than one progress fraction, because each part has its own spec, and
 * because turning round mid-flight has to carry each part's own speed into the new direction — a
 * shared fraction would make a spring scale and a tween fade agree about a speed neither of them has.
 */
internal class VisibilityState(initiallyVisible: Boolean, clocks: Clocks, clock: Clock) {

    /** Composed. True from the moment it is shown until the moment an exit finishes. */
    var isPresent by mutableStateOf(initiallyVisible)
        private set

    val alpha = Animatable(1f, FloatVectoriser, clock, clocks)
    val scale = Animatable(1f, FloatVectoriser, clock, clocks)
    val offset = Animatable(Offset.Zero, OffsetVectoriser, clock, clocks)

    /** Whichever transition set it last, so a scale-in about the top and a scale-out about the
     *  bottom each grow from their own corner. */
    var origin by mutableStateOf(Alignment.Centre)
        private set

    suspend fun show(enter: TransitionParts) {
        if (!isPresent) {
            // Arriving from nothing: put it where the enter starts before the frame that first draws
            // it, or it would flash at rest for a frame and then jump back to begin.
            alpha.snapTo(enter.fade?.alpha ?: 1f)
            scale.snapTo(enter.scale?.factor ?: 1f)
            offset.snapTo(enter.slide?.offset ?: Offset.Zero)
            isPresent = true
        }
        enter.scale?.let { origin = it.origin }
        playTo(1f, 1f, Offset.Zero, enter)
    }

    /** Every part already where [parts] would leave it, so playing them would move nothing. */
    fun hasArrivedAt(parts: TransitionParts) =
        alpha.value == (parts.fade?.alpha ?: 1f) &&
            scale.value == (parts.scale?.factor ?: 1f) &&
            offset.value == (parts.slide?.offset ?: Offset.Zero)

    suspend fun hide(exit: TransitionParts) {
        if (!isPresent) return
        if (exit.isEmpty) {
            // Taken away at once. Nothing is played back to rest: shown again, it starts from the
            // enter's beginning anyway.
            isPresent = false
            return
        }
        exit.scale?.let { origin = it.origin }
        playTo(exit.fade?.alpha ?: 1f, exit.scale?.factor ?: 1f, exit.slide?.offset ?: Offset.Zero, exit)
        // Only reached when every part arrived. Shown again part-way, this coroutine is cancelled
        // and the content never leaves the tree.
        isPresent = false
    }

    /**
     * Every part to its target at once, suspending until the slowest arrives.
     *
     * A part the transition does not name still has to get back to rest if an interrupted transition
     * left it somewhere else, and it does so on a default tween rather than jumping.
     */
    private suspend fun playTo(toAlpha: Float, toScale: Float, toOffset: Offset, parts: TransitionParts) =
        coroutineScope {
            if (alpha.value != toAlpha) launch { alpha.animateTo(toAlpha, parts.fade?.spec ?: Tween()) }
            if (scale.value != toScale) launch { scale.animateTo(toScale, parts.scale?.spec ?: Tween()) }
            if (offset.value != toOffset) launch { offset.animateTo(toOffset, parts.slide?.spec ?: Tween()) }
        }
}
