package dev.wildware.composegl.ui.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.layout.Alignment
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
 * A crossfade is an [AnimatedContent] that always fades and never animates its size. The pages sit
 * on top of each other in one box carrying [modifier], in the order they arrived — a
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
) = AnimatedContent(
    targetState, modifier,
    // No size animation and nothing cut off: the box is as big as the biggest page, as it always was.
    transition = { _, _ -> ContentTransform(fadeIn(spec = spec), fadeOut(spec = spec), sizeSpec = null, clip = false) },
    contentAlignment, clock, contentKey,
    restingSizeSpec = null,
    pages,
    content,
)

/** The pages a [Crossfade] has on screen: an [AnimatedContent]'s. */
internal typealias CrossfadePages<T> = ContentPages<T>

/** For a test that shows pages by hand: every change a plain fade. */
internal fun <T> CrossfadePages<T>.show(key: Any?, state: T) =
    show(key, state) { _, _ -> fadeIn() togetherWith fadeOut() }
