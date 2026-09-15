package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusTrap
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode

/**
 * The popups a screen has open, drawn over everything else in it.
 *
 * Only [PopupHost] makes one. Widgets that open something — a [Dropdown]'s list — put an entry
 * here while it is open, and the host composes it last, above the whole screen.
 */
class Popups internal constructor() {
    internal val open = mutableStateListOf<PopupEntry>()

    /** How many are up. For tests, and for a game that pauses something while one is. */
    val count: Int get() = open.size
}

/** The screen's popup layer, or null when nothing put a [PopupHost] round it. */
val LocalPopups = staticCompositionLocalOf<Popups?> { null }

/**
 * Makes popups work for everything inside it. Put it round a screen, once.
 *
 * A popup has to be drawn over the panel it came out of and over whatever is next to that panel,
 * and it must not be cut off by a scrolling list it sits inside. Draw order here is tree order, so
 * the only place that is true is the end of the screen: the host composes each open popup there,
 * after [content], and places it in the host's own coordinates next to the thing that opened it.
 *
 * What is composed there is still composed **as if it were where it was opened**: it gets the
 * skin, the fonts and every other composition local that was in force at that spot, not the ones
 * at the host. Only the nodes move.
 *
 * It fills its parent, so it goes at the top of a screen, like [Dialog] and [TooltipHost].
 */
@Composable
fun PopupHost(content: @Composable () -> Unit) {
    val popups = remember { Popups() }

    CompositionLocalProvider(LocalPopups provides popups) {
        Box(Modifier.fillMaxSize()) {
            content()
            popups.open.forEach { entry -> key(entry) { PopupSlot(entry) } }
        }
    }
}

/**
 * The screen's popup layer, or a failure that says to add one.
 *
 * A widget that opens a popup asks for it as soon as it is composed, not when it opens: an error
 * thrown by a recompose after a click stops the screen dead with nothing on it to say why, while one
 * thrown by the first composition fails the screen where it is built.
 */
@Composable
internal fun requirePopups(): Popups = checkNotNull(LocalPopups.current) {
    "no popup layer: wrap the screen in a PopupHost, the way tooltips need a TooltipHost"
}

/** The node a popup is placed next to. Filled in when that node is made. */
internal class PopupAnchor {
    var node: UiNode? = null
}

/**
 * One open popup: its own nodes, and what closes it.
 *
 * The nodes are a composition of their own under [root], parented to the composition that opened
 * it, which is what lets the content live at the host and still read the locals at the anchor.
 */
internal class PopupEntry(val anchor: PopupAnchor) {
    val root = UiNode("popup.content")
    var slot: UiNode? = null
    var dismiss: () -> Unit = {}
    var maxHeight: Float = Float.POSITIVE_INFINITY
}

/**
 * Opens [content] next to [anchor] for as long as this is in the composition.
 *
 * While it is up, focus is trapped inside it and given back to wherever it was when it closes, a
 * press anywhere outside it calls [onDismiss] and goes no further, and so does Escape.
 *
 * @param maxHeight the tallest it may be. It is also never taller than the room above or below the
 *   anchor, whichever is bigger.
 */
@Composable
internal fun Popup(
    anchor: PopupAnchor,
    onDismiss: () -> Unit,
    maxHeight: Float = Float.POSITIVE_INFINITY,
    content: @Composable () -> Unit,
) {
    val popups = requirePopups()
    val context = rememberCompositionContext()
    val latest = rememberUpdatedState(content)
    val entry = remember(anchor) { PopupEntry(anchor) }
    entry.dismiss = onDismiss
    entry.maxHeight = maxHeight

    DisposableEffect(popups, entry) {
        val composition = Composition(UiApplier(entry.root), context)
        composition.setContent { latest.value() }
        popups.open += entry
        onDispose {
            popups.open -= entry
            composition.dispose()
        }
    }
}

/**
 * Where one popup goes: a node over the whole host, holding the popup's own nodes.
 *
 * The node covers the host so that it can take the press that lands outside the popup, and so
 * that the popup's content can be placed anywhere on it.
 */
@Composable
private fun PopupSlot(entry: PopupEntry) {
    val escape = remember(entry) {
        KeyHandler { event ->
            if (event.type == KeyEventType.Down && event.key == Key.Escape) {
                entry.dismiss()
                true
            } else {
                false
            }
        }
    }

    // A press outside closes it and is used up doing so: a player clicking away from an open list
    // means "close that", not "and also press whatever I happened to click on". Scrolling is let
    // through, since nothing about a wheel turning is an answer. Saying yes to the press is what
    // stops it: the router offers a declined press to whatever is underneath, and the field that
    // opened the list is underneath, so a second click on it would close the list and open it again.
    val outside = remember(entry) {
        PointerHandler { event ->
            if (event is PointerEvent.Press) {
                entry.dismiss()
                true
            } else {
                false
            }
        }
    }

    val modifier = Modifier.fillMaxSize().focusTrap().onKeyEvent(escape).onPointer(outside)
    val policy = remember(entry) { PopupPlacement(entry) }

    ComposeNode<UiNode, UiApplier>(
        factory = {
            UiNode("popup").also { slot ->
                // Its nodes are not the applier's to manage: the slot has no composed children,
                // so nothing but this ever adds to or takes from its list.
                entry.root.parent?.let { old -> old.removeAt(old.children.indexOf(entry.root), 1) }
                slot.insertAt(0, entry.root)
                entry.slot = slot
            }
        },
        update = {
            set(modifier) { this.modifier = it }
            set(policy) { this.measurePolicy = it }
        },
    )
}

/** How far a popup sits from the thing it came out of. */
private const val PopupGap = 2f

/**
 * Below the anchor and as wide as it by choice; above it when below has no room.
 *
 * The anchor is measured and placed by the time this runs, because the host lays its children out
 * in order and the screen comes before its popups — so the popup is next to where the anchor is
 * this frame, not where it was the last.
 */
private class PopupPlacement(private val entry: PopupEntry) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (measurables.isEmpty()) return layout(width, height, 0)

        val host = entry.slot?.parent
        val anchorNode = entry.anchor.node
        val at = if (host != null && anchorNode != null) anchorNode.boundsIn(host) else Rect.Zero

        val below = (height - at.bottom - PopupGap).coerceAtLeast(0f)
        val above = (at.top - PopupGap).coerceAtLeast(0f)
        val across = at.width.coerceAtMost(width).coerceAtLeast(0f)
        val tallest = maxOf(below, above).coerceAtMost(entry.maxHeight)

        val placeables = placeables(1)
        val placements = placements(1)
        val placeable = measurables[0].measure(Constraints(across, across, 0f, tallest))
        placeables[0] = placeable

        val downwards = placeable.height <= below || below >= above
        placements[0] = at.left.coerceIn(0f, (width - placeable.width).coerceAtLeast(0f))
        placements[1] = if (downwards) at.bottom + PopupGap else at.top - PopupGap - placeable.height

        return layout(width, height, 1)
    }
}
