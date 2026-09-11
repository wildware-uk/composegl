package uk.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import uk.wildware.composegl.ui.input.BackHandler
import uk.wildware.composegl.ui.input.BackStack
import uk.wildware.composegl.ui.input.Key
import uk.wildware.composegl.ui.input.KeyEventType
import uk.wildware.composegl.ui.input.KeyHandler
import uk.wildware.composegl.ui.input.PointerEvent
import uk.wildware.composegl.ui.input.PointerHandler
import uk.wildware.composegl.ui.layout.Alignment
import uk.wildware.composegl.ui.layout.Arrangement
import uk.wildware.composegl.ui.layout.Box
import uk.wildware.composegl.ui.layout.Column
import uk.wildware.composegl.ui.layout.Constraints
import uk.wildware.composegl.ui.layout.Layout
import uk.wildware.composegl.ui.layout.Measurable
import uk.wildware.composegl.ui.layout.MeasurePolicy
import uk.wildware.composegl.ui.layout.MeasureResult
import uk.wildware.composegl.ui.layout.MeasureScope
import uk.wildware.composegl.ui.layout.Row
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.alpha
import uk.wildware.composegl.ui.modifier.fillMaxSize
import uk.wildware.composegl.ui.modifier.focusTrap
import uk.wildware.composegl.ui.modifier.onKeyEvent
import uk.wildware.composegl.ui.modifier.onPointer
import uk.wildware.composegl.ui.skin.styled

/**
 * A surface with something on it.
 *
 * The whole widget: a box wearing a skin style. What a panel *is* — a colour, a piece of art cut
 * into nine, how far its contents sit from the edge — is the skin's business, and a game changes
 * every panel in the interface by editing one entry.
 *
 * @param style the skin name. `"panel.raised"` and the like are there for a game that wants two.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    style: String = "panel",
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit,
) {
    Box(modifier.styled(style), contentAlignment = contentAlignment, content = content)
}

/** Where "back" goes. A game provides its own; anything composed without one is talking to nobody. */
val LocalBackStack: ProvidableCompositionLocal<BackStack> = staticCompositionLocalOf { BackStack() }

@Composable
fun ProvideBackStack(stack: BackStack, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalBackStack provides stack, content = content)

/**
 * Answers the Back button while this is in the composition.
 *
 * ```kotlin
 * OnBack(enabled = paused) { paused = false }
 * ```
 *
 * The innermost one wins, because the stack asks the most recently added handler first, and the
 * most recently added is the thing that opened last.
 */
@Composable
fun OnBack(enabled: Boolean = true, onBack: () -> Unit) {
    val stack = LocalBackStack.current
    val latest by rememberUpdatedState(onBack)
    val enabledNow by rememberUpdatedState(enabled)
    DisposableEffect(stack) {
        val handler = BackHandler {
            if (!enabledNow) false else {
                latest()
                true
            }
        }
        stack.add(handler)
        onDispose { stack.remove(handler) }
    }
}

/**
 * A panel over everything else, that the player must answer.
 *
 * Three things make it a dialogue rather than a box:
 *
 * - **Focus cannot leave it.** Pressing down at the bottom of it does not walk into the screen
 *   behind, and closing it puts the player back on the control that opened it.
 * - **Nothing behind it can be touched.** The scrim swallows the press rather than passing it
 *   through to a button nobody can see properly.
 * - **Escape and Back close it**, and the innermost one closes first.
 *
 * It has no window of its own: it fills its parent, so it goes at the top level of a screen, inside
 * whatever `Box` fills the display. There is no portal in this toolkit yet, and a dialogue composed
 * halfway down a column would dim halfway down a column.
 *
 * ```kotlin
 * if (quitting) {
 *     Dialog(onDismiss = { quitting = false }) {
 *         Text("Leave the mission?")
 *         Row { Button("STAY") { quitting = false }; Button("LEAVE") { quit() } }
 *     }
 * }
 * ```
 *
 * @param onDismiss what Escape, Back and a press on the scrim do. Null for a dialogue the player
 *   must answer — a difficulty choice at the start of a game, a licence nobody may skip.
 * @param dismissOnScrim whether a press outside it counts as dismissing it. Off for anything with
 *   consequences, because a mis-aimed click is not an answer.
 */
@Composable
fun Dialog(
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    style: String = "dialog",
    scrimStyle: String = "scrim",
    alignment: Alignment = Alignment.Centre,
    dismissOnScrim: Boolean = false,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    val onScrim by rememberUpdatedState(dismissOnScrim)

    OnBack(enabled = onDismiss != null) { dismiss?.invoke() }

    val escape = remember {
        KeyHandler { event ->
            when {
                event.type != KeyEventType.Down || event.key != Key.Escape -> false
                dismiss == null -> true   // still consumed: a modal dialogue eats the key
                else -> {
                    dismiss?.invoke()
                    true
                }
            }
        }
    }

    // The scrim swallows every press, so nothing behind the dialogue can be clicked, and takes the
    // dismissing one itself if it was asked to.
    val scrim = remember {
        PointerHandler { event ->
            if (event is PointerEvent.Press && onScrim) dismiss?.invoke()
            event !is PointerEvent.Scroll
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .styled(scrimStyle)
            .focusTrap()
            .onKeyEvent(escape)
            .onPointer(scrim),
        contentAlignment = alignment,
    ) {
        Panel(modifier, style = style, contentAlignment = contentAlignment, content = content)
    }
}

/**
 * A row of headings over one page at a time.
 *
 * Every page stays composed. That is the whole reason this is a widget rather than a `when`: a tab
 * a player switches away from and back to is the same tab, with its scroll position, its half-typed
 * name and its chosen item still there. The pages not being shown are laid out at no size at all
 * and drawn not at all, and they cannot be focused, so a pad walks the visible page only.
 *
 * The trade is worth saying out loud: a page that is enormous costs its composition even while it
 * is hidden. Tabs are for the settings screen and the inventory, not for six whole screens of game.
 *
 * It asks for focus for nothing. Which control a screen opens on is the screen's decision, and a
 * row of headings is rarely it.
 *
 * @param titles one per tab, in order. The number of pages is the number of titles.
 * @param style the skin name for a tab heading. The chosen one is `"<style>.selected"`.
 * @param page what a page looks like, by index.
 */
@Composable
fun Tabs(
    selected: Int,
    onSelect: (Int) -> Unit,
    titles: List<String>,
    modifier: Modifier = Modifier,
    style: String = "tab",
    spacing: Float = 4f,
    page: @Composable (Int) -> Unit,
) {
    val chosen = selected.coerceIn(0, (titles.size - 1).coerceAtLeast(0))

    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            titles.forEachIndexed { index, title ->
                Button(
                    text = title,
                    onClick = { onSelect(index) },
                    style = if (index == chosen) "$style.selected" else style,
                )
            }
        }

        Layout(
            name = "tabs.pages",
            content = {
                titles.indices.forEach { index ->
                    // A hidden page is transparent, which is also what makes it unfocusable: the
                    // same rule the draw pass uses, so what cannot be seen cannot be reached.
                    Box(if (index == chosen) Modifier else Modifier.alpha(0f)) { page(index) }
                }
            },
            measurePolicy = TabPolicy(chosen),
        )
    }
}

/**
 * The chosen page gets the room; the others get none.
 *
 * They are still measured, because a child that is never measured is a child layout would complain
 * about, and measuring one at nothing is the cheapest way to say "you are not on screen" without
 * taking it out of the composition and losing everything it remembered.
 */
private class TabPolicy(private val chosen: Int) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        // Lent by the node and used again next frame; see MeasureScope.
        val count = measurables.size
        val placeables = placeables(count)
        val placements = placements(count)
        val offers = offers(count)

        for (index in 0 until count) {
            val offer = offers[index]
            val room =
                if (index == chosen) constraints.loosen(offer) else offer.of(0f, 0f, 0f, 0f)
            placeables[index] = measurables[index].measure(room)
            placements[index * 2] = 0f
            placements[index * 2 + 1] = 0f
        }

        val shown = placeables.getOrNull(chosen)
        val width = constraints.constrainWidth(shown?.width ?: 0f)
        val height = constraints.constrainHeight(shown?.height ?: 0f)

        return layout(width, height, count)
    }
}
