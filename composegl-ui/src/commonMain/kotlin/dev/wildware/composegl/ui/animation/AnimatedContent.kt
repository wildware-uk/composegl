package dev.wildware.composegl.ui.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.layout.layoutId
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.animateContentSize
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.layoutId

/**
 * How one page gives way to the next: how the new one arrives, how the old one leaves, and how the
 * space they share changes size between them.
 *
 * Written as an enter and an exit put together with [togetherWith], or picked from [slideLeft],
 * [slideRight], [slideUp] and [slideDown]:
 *
 * ```kotlin
 * fadeIn() + scaleIn(from = 0.9f) togetherWith fadeOut()
 * slideUp().using(sizeSpec = null)
 * ```
 *
 * @property enter how the new page arrives.
 * @property exit how the old page, and any page still leaving from before, goes.
 * @property sizeSpec how the space changes from the old page's size to the new one's, or null for no
 *   size animation at all: the space is then as big as the biggest page on screen, as in [Crossfade].
 * @property clip whether a page is cut off at the edge of the space while pages are changing, so one
 *   sliding out does not draw over whatever is beside it. Settled, nothing is cut.
 */
class ContentTransform(
    val enter: EnterTransition,
    val exit: ExitTransition,
    val sizeSpec: AnimationSpec? = DefaultSizeSpec,
    val clip: Boolean = true,
) {
    /** The same, with the space changing size on [sizeSpec] — or not at all, for null. */
    fun using(sizeSpec: AnimationSpec?) = ContentTransform(enter, exit, sizeSpec, clip)

    companion object {
        /** A spring, as `animateContentSize` uses, so a page changing again part way turns smoothly. */
        val DefaultSizeSpec: AnimationSpec = Spring(threshold = 0.5f)
    }
}

/** [this] for the page arriving, and [exit] for the page leaving. */
infix fun EnterTransition.togetherWith(exit: ExitTransition) = ContentTransform(this, exit)

/** The new page comes in from the right as the old one leaves to the left: going forward. */
fun slideLeft(spec: AnimationSpec = Tween()) =
    slideInRelative(Offset(1f, 0f), spec) togetherWith slideOutRelative(Offset(-1f, 0f), spec)

/** The new page comes in from the left as the old one leaves to the right: going back. */
fun slideRight(spec: AnimationSpec = Tween()) =
    slideInRelative(Offset(-1f, 0f), spec) togetherWith slideOutRelative(Offset(1f, 0f), spec)

/** The new page comes up from below as the old one leaves upwards: a score rolling up. */
fun slideUp(spec: AnimationSpec = Tween()) =
    slideInRelative(Offset(0f, 1f), spec) togetherWith slideOutRelative(Offset(0f, -1f), spec)

/** The new page comes down from above as the old one leaves downwards: a score rolling down. */
fun slideDown(spec: AnimationSpec = Tween()) =
    slideInRelative(Offset(0f, -1f), spec) togetherWith slideOutRelative(Offset(0f, 1f), spec)

/**
 * Shows [content] for [targetState], and animates from the old content to the new whenever it
 * changes, with a transition picked for each change.
 *
 * [Crossfade] always fades. This asks [transition] what to do, given the state it is leaving and the
 * one it is going to, so a menu can slide left going deeper and right coming back:
 *
 * ```kotlin
 * AnimatedContent(
 *     targetState = page,
 *     transition = { from, to -> if (to > from) slideLeft() else slideRight() },
 * ) { page -> PageContent(page) }
 * ```
 *
 * The space the pages share is the size of the page being shown, and when that changes it grows or
 * shrinks there on the transform's [ContentTransform.sizeSpec], with [contentAlignment] saying where
 * the pages sit in it meanwhile. A page bigger than the space is cut off at its edge while it is
 * changing. The same size animation follows the page when it changes size by itself.
 *
 * Everything [Crossfade] says about pages holds here too, because a crossfade is one of these:
 *
 * - **Each page keeps the state it was showing**, so a counter rolling from 9 to 10 shows 9 on the
 *   way out.
 * - **Changing your mind** turns round. Go back to a page that is still leaving and it comes back
 *   from wherever it had got to, with its own state intact, on the new change's enter; the page it
 *   was leaving for goes on the new change's exit.
 * - **Once gone, forgotten.** A page that has finished leaving leaves the composition.
 * - **Clicks and focus** still reach a page while it leaves.
 *
 * Settled, there is one page, at rest, and it asks for no frames.
 *
 * @param targetState which page to show. Compared by [contentKey].
 * @param transition how to get from one page to the next, asked once per change with the state
 *   being left and the state being shown. A fade, with the size animated, by default.
 * @param contentAlignment where a page sits in a space that is not its size.
 * @param clock which clock it plays on. [Clock.World] holds a transition still while the game is paused.
 * @param contentKey what counts as a different page. A change between two states with the same key
 *   redraws that page with no transition, and [transition] is not asked.
 * @param content the page for a state.
 */
@Composable
fun <T> AnimatedContent(
    targetState: T,
    modifier: Modifier = Modifier,
    transition: (from: T, to: T) -> ContentTransform = { _, _ -> fadeIn() togetherWith fadeOut() },
    contentAlignment: Alignment = Alignment.TopStart,
    clock: Clock = Clock.Ui,
    contentKey: (T) -> Any? = { it },
    content: @Composable (T) -> Unit,
) = AnimatedContent(
    targetState, modifier, transition, contentAlignment, clock, contentKey,
    restingSizeSpec = ContentTransform.DefaultSizeSpec,
    pages = remember { ContentPages(targetState, contentKey(targetState)) },
    content,
)

/**
 * [AnimatedContent], with the list of [pages] handed in so a test can see gone pages let go, and
 * with [restingSizeSpec] for the size animation before the first change — none, for [Crossfade].
 */
@Composable
internal fun <T> AnimatedContent(
    targetState: T,
    modifier: Modifier,
    transition: (from: T, to: T) -> ContentTransform,
    contentAlignment: Alignment,
    clock: Clock,
    contentKey: (T) -> Any?,
    restingSizeSpec: AnimationSpec?,
    pages: ContentPages<T>,
    content: @Composable (T) -> Unit,
) {
    val targetKey = contentKey(targetState)
    // Read, so that a page being forgotten recomposes this. Written only from the transition's effect.
    pages.forgotten
    pages.show(targetKey, targetState, transition)

    val change = pages.change
    val sizeSpec = if (change == null) restingSizeSpec else change.sizeSpec
    var chain = modifier
    if (sizeSpec != null) chain = chain.animateContentSize(sizeSpec, contentAlignment, clock)
    if (change != null && change.clip && pages.all.size > 1) chain = chain.clip()
    val policy = remember(contentAlignment, sizeSpec != null) { PagesPolicy(contentAlignment, sizeSpec != null) }

    Layout(chain, name = "animatedContent", measurePolicy = policy, content = {
        for (page in pages.all) {
            // Keyed, so a page keeps what it remembers while pages before it in the list come and go.
            key(page.key) {
                val shown = page.key == targetKey
                AnimatedPresence(
                    visible = shown,
                    modifier = if (shown) Modifier.layoutId(ShownPage) else Modifier,
                    enter = page.enter,
                    exit = page.exit,
                    initiallyVisible = page.initiallyVisible,
                    clock = clock,
                    onGone = { pages.forget(page) },
                ) { content(page.state) }
            }
        }
    })
}

/** The name the page being shown goes by, so the layout can size the space to it. */
private object ShownPage

/**
 * Pages on top of each other, like a box, in a space the size of the page being shown — or of the
 * biggest page, when there is no size to follow.
 *
 * The space is only the size the pages would like. The `animateContentSize` on the same node is what
 * takes it there gradually.
 */
private data class PagesPolicy(val contentAlignment: Alignment, val followShown: Boolean) : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val count = measurables.size
        val placeables = placeables(count)
        val offered = if (count == 0) constraints else constraints.loosen(offers(1)[0])
        var widest = 0f
        var tallest = 0f
        var shownWidth = Float.NaN
        var shownHeight = Float.NaN
        for (index in 0 until count) {
            val placeable = measurables[index].measure(offered)
            placeables[index] = placeable
            if (placeable.width > widest) widest = placeable.width
            if (placeable.height > tallest) tallest = placeable.height
            if (followShown && measurables[index].layoutId === ShownPage) {
                shownWidth = placeable.width
                shownHeight = placeable.height
            }
        }
        val width = constraints.constrainWidth(if (shownWidth.isNaN()) widest else shownWidth)
        val height = constraints.constrainHeight(if (shownHeight.isNaN()) tallest else shownHeight)

        val placements = placements(count)
        for (index in 0 until count) {
            val placeable = placeables[index] ?: continue
            placements[index * 2] = contentAlignment.xIn(width, placeable.width)
            placements[index * 2 + 1] = contentAlignment.yIn(height, placeable.height)
        }
        return layout(width, height, count)
    }
}

/**
 * The pages an [AnimatedContent] has on screen, oldest first, and how each is arriving or leaving.
 *
 * A plain list rather than a state list, because pages are added while composing — which must not
 * invalidate the composition doing the adding — and taken away from a finished transition, which
 * must. [forgotten] is the one piece of state, and it is only there to be read.
 */
internal class ContentPages<T>(initial: T, initialKey: Any?) {

    class Page<T>(val key: Any?, var state: T, val initiallyVisible: Boolean) {
        /** How it arrives, from the change that last made it the page shown. */
        var enter: EnterTransition = EnterTransition.None

        /** How it leaves, from the change that last took it off. */
        var exit: ExitTransition = ExitTransition.None
    }

    private val pages = mutableListOf(Page(initialKey, initial, initiallyVisible = true))

    val all: List<Page<T>> get() = pages

    /** The last change's transform, or null before there has been one. */
    var change: ContentTransform? = null
        private set

    private var shownKey: Any? = initialKey
    private var shownState: T = initial

    /** How many pages have finished leaving and been let go. Read by the composition, so a change recomposes. */
    var forgotten by mutableIntStateOf(0)
        private set

    /**
     * Makes [key] the page shown, with [state] as what it shows.
     *
     * A change of key asks [transition] how to go from the state shown until now, and hands its enter
     * to this page and its exit to every other. A page already on screen — leaving, or the current
     * one — keeps its place and is handed the newer state. One that is not arrives at the end of the
     * list, starting from nothing.
     */
    fun show(key: Any?, state: T, transition: (from: T, to: T) -> ContentTransform) {
        val changed = key != shownKey
        val transform = if (changed) transition(shownState, state).also { change = it } else null
        shownKey = key
        shownState = state

        val existing = pages.firstOrNull { it.key == key }
        val page = existing?.also { it.state = state } ?: Page(key, state, initiallyVisible = false).also { pages += it }
        if (transform != null) {
            page.enter = transform.enter
            for (other in pages) if (other !== page) other.exit = transform.exit
        }
    }

    fun forget(page: Page<T>) {
        if (pages.remove(page)) forgotten++
    }
}
