package dev.wildware.composegl.ui.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier

/**
 * Shows [content] for [targetState], and fades from the old content to the new whenever it changes.
 *
 * `when (page) { … }` swaps one screen for the next the frame `page` changes — an instant cut. This
 * keeps the old screen composed and fading out while the new one fades in over the same spot, and
 * takes the old one away once it has gone.
 *
 * ```kotlin
 * Crossfade(targetState = page) { page ->
 *     when (page) {
 *         Page.Main -> MainMenu()
 *         Page.Options -> Options()
 *     }
 * }
 * ```
 *
 * Each page is handed the state it was showing, so a portrait fading from one expression to the next
 * draws the old expression on the way out rather than two copies of the new one. It is built on
 * [AnimatedVisibility], with everything that implies:
 *
 * - **Changing your mind** turns round. Go back to a page that is still fading out and it fades back
 *   in from where it had got to, with its own state intact — a counter on it keeps its count.
 * - **Many changes at once** are fine. Flick through three pages quickly and each one fades from
 *   wherever it was; only the last stays.
 * - **Once gone, forgotten.** A page that has faded all the way out leaves the composition, so
 *   coming back to it later starts it fresh, the way `when` would.
 * - **Clicks and focus** still reach a page while it leaves. Focus on a button in the old page stays
 *   there until the page is gone, then moves into the new one the way it does after any screen change.
 *
 * The pages sit on top of each other in one [Box] carrying [modifier], in the order they arrived — a
 * page gone back to keeps its place under the one it was leaving for — so the box is as
 * big as the biggest page while a fade plays and [contentAlignment] places the smaller ones. Settled,
 * there is one page, drawn at full opacity, and it asks for no frames.
 *
 * @param targetState which page to show. Compared by [contentKey].
 * @param spec how long the fade takes, and its curve. Both pages fade on it at once.
 * @param contentAlignment where a page smaller than the biggest one sits while they overlap.
 * @param clock which clock it plays on. [Clock.World] holds a fade still while the game is paused.
 * @param contentKey what counts as a different page. Two states with the same key are the same page,
 *   and a change between them redraws that page with no fade — a portrait keyed on its expression
 *   does not fade when only its health changes.
 * @param content the page for a state.
 */
@Composable
fun <T> Crossfade(
    targetState: T,
    modifier: Modifier = Modifier,
    spec: AnimationSpec = Tween(),
    contentAlignment: Alignment = Alignment.TopStart,
    clock: Clock = Clock.Ui,
    contentKey: (T) -> Any? = { it },
    content: @Composable (T) -> Unit,
) = Crossfade(
    targetState, modifier, spec, contentAlignment, clock, contentKey,
    pages = remember { CrossfadePages(targetState, contentKey(targetState)) },
    content,
)

/** [Crossfade], with the list of [pages] handed in, so a test can see that gone pages are let go. */
@Composable
internal fun <T> Crossfade(
    targetState: T,
    modifier: Modifier,
    spec: AnimationSpec,
    contentAlignment: Alignment,
    clock: Clock,
    contentKey: (T) -> Any?,
    pages: CrossfadePages<T>,
    content: @Composable (T) -> Unit,
) {
    val targetKey = contentKey(targetState)
    // Read, so that a page being forgotten recomposes this. Written only from the fade's effect.
    pages.forgotten
    pages.show(targetKey, targetState)

    Box(modifier, contentAlignment = contentAlignment) {
        for (page in pages.all) {
            // Keyed, so a page keeps what it remembers while pages before it in the list come and go.
            key(page.key) {
                AnimatedPresence(
                    visible = page.key == targetKey,
                    modifier = Modifier,
                    enter = fadeIn(spec = spec),
                    exit = fadeOut(spec = spec),
                    initiallyVisible = page.initiallyVisible,
                    clock = clock,
                    onGone = { pages.forget(page) },
                ) { content(page.state) }
            }
        }
    }
}

/**
 * The pages a [Crossfade] has on screen, oldest first.
 *
 * A plain list rather than a state list, because pages are added while composing — which must not
 * invalidate the composition doing the adding — and taken away from a finished fade, which must.
 * [forgotten] is the one piece of state, and it is only there to be read.
 */
internal class CrossfadePages<T>(initial: T, initialKey: Any?) {

    class Page<T>(val key: Any?, var state: T, val initiallyVisible: Boolean)

    private val pages = mutableListOf(Page(initialKey, initial, initiallyVisible = true))

    val all: List<Page<T>> get() = pages

    /** How many pages have faded out and been let go. Read by the composition, so a change recomposes. */
    var forgotten by mutableIntStateOf(0)
        private set

    /**
     * Makes [key] the page on top of the fade, with [state] as what it shows.
     *
     * A page already on screen — fading out, or the current one — keeps its place and is handed the
     * newer state. One that is not arrives at the end of the list, starting from nothing.
     */
    fun show(key: Any?, state: T) {
        val existing = pages.firstOrNull { it.key == key }
        if (existing != null) existing.state = state else pages += Page(key, state, initiallyVisible = false)
    }

    fun forget(page: Page<T>) {
        if (pages.remove(page)) forgotten++
    }
}
