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
import dev.wildware.composegl.ui.layout.BoxPolicy
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
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
 * Built from [fadeIn], [scaleIn], [slideIn] and [slideInRelative], and put together with `+`:
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
 * The mirror of [EnterTransition], built from [fadeOut], [scaleOut], [slideOut] and [slideOutRelative]. [None] takes the
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
 * Slides in from [from], measured in its own size rather than in pixels: `Offset(1f, 0f)` starts one
 * whole width to the right, `Offset(0f, -1f)` one whole height above.
 *
 * What a page coming in from the side wants, because a page does not know how wide it is when it is
 * written and a slide of a fixed number of pixels is either too short or too long on some screen.
 * The contents move inside the space they take, so like [slideIn] nothing around them moves and they
 * are hit where they are drawn. A separate part from [slideIn]: one of each adds up.
 */
fun slideInRelative(from: Offset, spec: AnimationSpec = Tween()) =
    EnterTransition(TransitionParts(push = Push(from, spec)))

/** Slides away to [to], measured in its own size: `Offset(-1f, 0f)` leaves one whole width to the left. */
fun slideOutRelative(to: Offset, spec: AnimationSpec = Tween()) =
    ExitTransition(TransitionParts(push = Push(to, spec)))

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
 * The content sits in one box carrying [modifier] and the transition, so a `Modifier.align` in
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
) = AnimatedPresence(visible, modifier, enter, exit, initiallyVisible, clock, onGone = {}, content)

/**
 * [AnimatedVisibility], and told when the content has finished leaving.
 *
 * [onGone] runs once an exit has played to its end and the content is out of the tree — never when
 * the exit is cut short by showing it again. It is how [AnimatedContent] knows when to forget a page.
 */
@Composable
internal fun AnimatedPresence(
    visible: Boolean,
    modifier: Modifier,
    enter: EnterTransition,
    exit: ExitTransition,
    initiallyVisible: Boolean,
    clock: Clock,
    onGone: () -> Unit,
    content: @Composable () -> Unit,
) {
    val clocks = LocalClocks.current
    val state = remember(clocks, clock) { VisibilityState(initiallyVisible, clocks, clock) }

    // Not keys of the effect. A transition is written inline, so it is a new object on every
    // recomposition, and keying on it would restart the animation every frame it plays.
    val currentEnter by rememberUpdatedState(enter)
    val currentExit by rememberUpdatedState(exit)
    val currentOnGone by rememberUpdatedState(onGone)

    LaunchedEffect(state, visible) {
        if (visible) {
            state.show(currentEnter.parts)
        } else {
            state.hide(currentExit.parts)
            // Only reached when the exit finished; shown again part-way, this was cancelled.
            currentOnGone()
        }
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
    val push = if (arriving) parts.push?.fraction ?: Offset.Zero else state.push.value
    Layout(
        modifier
            .offset(shift.x, shift.y)
            .alpha(alpha)
            // A bouncy spring overshoots below zero on the way out, and a negative scale throws.
            .scale(scale.coerceAtLeast(0f), origin),
        name = "box",
        content = content,
        // At rest, the plain box every other box is, so a settled panel makes nothing new per frame.
        measurePolicy = if (push == Offset.Zero) RestingPolicy else PushPolicy(push),
    )
}

private val RestingPolicy = BoxPolicy(Alignment.TopStart)

/**
 * A box whose contents are moved by a fraction of its own size, for [slideInRelative].
 *
 * The fraction is read where the size is known, in layout, rather than turned into pixels in
 * composition — where the size of the frame being composed is not known yet, and last frame's would
 * be wrong the frame a page arrives. A data class, so the same fraction twice is the same policy.
 */
private data class PushPolicy(val fraction: Offset) : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val count = measurables.size
        val placeables = placeables(count)
        val offered = if (count == 0) constraints else constraints.loosen(offers(1)[0])
        var widest = 0f
        var tallest = 0f
        for (index in 0 until count) {
            val placeable = measurables[index].measure(offered)
            placeables[index] = placeable
            if (placeable.width > widest) widest = placeable.width
            if (placeable.height > tallest) tallest = placeable.height
        }
        val width = constraints.constrainWidth(widest)
        val height = constraints.constrainHeight(tallest)
        val placements = placements(count)
        for (index in 0 until count) {
            val placeable = placeables[index] ?: continue
            // Where the resting box would put it, so a centred child slides in centred rather than
            // jumping to the corner for the length of the slide.
            val alignment = measurables[index].layoutData.alignment ?: Alignment.TopStart
            placements[index * 2] = alignment.xIn(width, placeable.width) + fraction.x * width
            placements[index * 2 + 1] = alignment.yIn(height, placeable.height) + fraction.y * height
        }
        return layout(width, height, count)
    }
}

// --- the machinery --------------------------------------------------------------------------

/** One transition's worth of parts. Null means that part stays at rest. */
internal data class TransitionParts(
    val fade: Fade? = null,
    val scale: Grow? = null,
    val slide: Slide? = null,
    val push: Push? = null,
) {
    /** Nothing named, as with [EnterTransition.None] and [ExitTransition.None]. */
    val isEmpty get() = fade == null && scale == null && slide == null && push == null

    operator fun plus(other: TransitionParts) = TransitionParts(
        fade = other.fade ?: fade,
        scale = other.scale ?: scale,
        slide = other.slide ?: slide,
        push = other.push ?: push,
    )
}

/** An opacity at the far end of a transition — where an enter starts, or where an exit ends. */
internal class Fade(val alpha: Float, val spec: AnimationSpec)

internal class Grow(val factor: Float, val origin: Alignment, val spec: AnimationSpec)

internal class Slide(val offset: Offset, val spec: AnimationSpec)

/** A slide measured in the thing's own size. */
internal class Push(val fraction: Offset, val spec: AnimationSpec)

/**
 * Whether the content is in the tree, and where each part of its transition has got to.
 *
 * An animatable per part rather than one progress fraction, because each part has its own spec, and
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
    val push = Animatable(Offset.Zero, OffsetVectoriser, clock, clocks)

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
            push.snapTo(enter.push?.fraction ?: Offset.Zero)
            isPresent = true
        }
        enter.scale?.let { origin = it.origin }
        playTo(1f, 1f, Offset.Zero, Offset.Zero, enter)
    }

    /** Every part already where [parts] would leave it, so playing them would move nothing. */
    fun hasArrivedAt(parts: TransitionParts) =
        alpha.value == (parts.fade?.alpha ?: 1f) &&
            scale.value == (parts.scale?.factor ?: 1f) &&
            offset.value == (parts.slide?.offset ?: Offset.Zero) &&
            push.value == (parts.push?.fraction ?: Offset.Zero)

    suspend fun hide(exit: TransitionParts) {
        if (!isPresent) return
        if (exit.isEmpty) {
            // Taken away at once. Nothing is played back to rest: shown again, it starts from the
            // enter's beginning anyway.
            isPresent = false
            return
        }
        exit.scale?.let { origin = it.origin }
        playTo(
            exit.fade?.alpha ?: 1f,
            exit.scale?.factor ?: 1f,
            exit.slide?.offset ?: Offset.Zero,
            exit.push?.fraction ?: Offset.Zero,
            exit,
        )
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
    private suspend fun playTo(toAlpha: Float, toScale: Float, toOffset: Offset, toPush: Offset, parts: TransitionParts) =
        coroutineScope {
            if (alpha.value != toAlpha) launch { alpha.animateTo(toAlpha, parts.fade?.spec ?: Tween()) }
            if (scale.value != toScale) launch { scale.animateTo(toScale, parts.scale?.spec ?: Tween()) }
            if (offset.value != toOffset) launch { offset.animateTo(toOffset, parts.slide?.spec ?: Tween()) }
            if (push.value != toPush) launch { push.animateTo(toPush, parts.push?.spec ?: Tween()) }
        }
}
