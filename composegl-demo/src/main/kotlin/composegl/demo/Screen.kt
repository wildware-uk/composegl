package composegl.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.graphics.Colour
import composegl.ui.input.InputSourceTracker
import composegl.ui.input.InteractionState
import composegl.ui.layout.Alignment
import composegl.ui.layout.Arrangement
import composegl.ui.layout.Box
import composegl.ui.layout.Column
import composegl.ui.layout.HorizontalAlignment
import composegl.ui.layout.LeafLayout
import composegl.ui.layout.MeasurePolicy
import composegl.ui.layout.Row
import composegl.ui.layout.Spacer
import composegl.ui.layout.VerticalAlignment
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.background
import composegl.ui.modifier.border
import composegl.ui.modifier.clickable
import composegl.ui.modifier.fillMaxHeight
import composegl.ui.modifier.focusable
import composegl.ui.modifier.fillMaxSize
import composegl.ui.modifier.fillMaxWidth
import composegl.ui.modifier.height
import composegl.ui.modifier.interaction
import composegl.ui.modifier.offset
import composegl.ui.modifier.padding
import composegl.ui.modifier.size
import composegl.ui.modifier.weight
import composegl.ui.modifier.width
import composegl.ui.text.FontProvider
import composegl.ui.text.TextStyle

private val Background = Colour.rgb(0x0E1116)
private val Accent = Colour.rgb(0x4CC2FF)
private val Danger = Colour.rgb(0xFF5C5C)
private val Dim = Colour.argb(0xFF9AA4B2)

private val Title = TextStyle(family = "display", size = 34f)
private val HeadingStyle = TextStyle(family = "body", size = 20f)
private val Body = TextStyle(family = "body", size = 16f)
private val Small = TextStyle(family = "body", size = 13f)

/**
 * The example interface.
 *
 * Written the way a game would write it: `Row`, `Column`, `Box`, a modifier chain, and widgets the
 * game defined itself. Nothing here reaches into the toolkit's internals, and nothing here mentions
 * LibGDX.
 */
@Composable
fun Screen(fonts: FontProvider, skin: DemoSkin, state: DemoState) {
    CompositionLocalProvider(
        LocalFonts provides fonts,
        LocalSkin provides skin,
        LocalInputSource provides state.source,
    ) {
        Box(Modifier.fillMaxSize().background(Background)) {
            Column(Modifier.fillMaxSize().padding(28f), verticalArrangement = Arrangement.spacedBy(20f)) {
                Text("COMPOSEGL", style = Title, colour = Accent)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("a game interface toolkit on the Compose runtime", style = Small, colour = Dim)
                    // Switches the moment the player picks up something else. Nothing but a mouse
                    // can reach it yet — keys and pads are the next two milestones.
                    Text("input: ${state.source.current}".uppercase(), style = Small, colour = Dim)
                }

                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20f)) {
                    StatusPanel(Modifier.width(300f).fillMaxHeight(), state.health)
                    LorePanel(Modifier.weight(1f).fillMaxHeight(), state)
                }

                Hotbar(state)
            }

            // Proof that a window coordinate made it all the way to a design coordinate, through
            // the HDPI scale and the letterbox — and, now that the chips and the hotbar are live,
            // that the same coordinate found the right node underneath it.
            state.pointer?.let { Reticle(it) }
        }
    }
}

/** Drawn by the shader: a rounded fill, a hairline border, a soft shadow, no art at all. */
@Composable
private fun StatusPanel(modifier: Modifier, health: Float) {
    Panel(modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12f)) {
            Heading("STATUS", style = HeadingStyle)
            Bar("Health", health, Danger)
            Bar("Shield", 0.42f, Accent)
            Bar("Stamina", 0.78f, Colour.rgb(0x7BE08A))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Level 12", style = Small, colour = Dim)
                Text("2,480 XP", style = Small, colour = Dim)
            }
        }
    }
}

/** A labelled bar. Two rounded rectangles and a clip — the whole widget. */
@Composable
private fun Bar(label: String, fraction: Float, colour: Colour) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4f)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = Small, colour = Dim)
            Text("${(fraction * 100).toInt()}%", style = Small, colour = Dim)
        }
        Box(Modifier.fillMaxWidth().height(10f).background(Colour.argb(0x40000000), corner = 5f)) {
            LeafLayout(
                Modifier.fillMaxWidth(fraction).fillMaxHeight().background(colour, corner = 5f),
                name = "fill",
            )
        }
    }
}

/** The same job, done by a nine-patch. Its padding comes from the atlas, not from this file. */
@Composable
private fun LorePanel(modifier: Modifier, state: DemoState) {
    ArtPanel(modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10f)) {
            Heading("BRIEFING", style = HeadingStyle)
            Text(
                "The relay went quiet six hours ago. Whatever is down there has already " +
                    "rewritten the door codes, so bring the cutter and do not count on the lift.",
                style = Body,
                colour = Dim,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10f)) {
                Chip("ACCEPT", Accent, state.briefing == "ACCEPT", first = true) { state.answer("ACCEPT") }
                Chip("DECLINE", Danger, state.briefing == "DECLINE", first = false) { state.answer("DECLINE") }
            }
        }
    }
}

/**
 * A button, three states deep, built from nothing the toolkit does not already offer.
 *
 * `interaction` is what the pointer is doing to it and `clickable` is what that means. The widget
 * reads two booleans and picks colours; recomposition does the rest, and because both booleans are
 * Compose state, this recomposes when the pointer enters or leaves and at no other time.
 */
@Composable
private fun Chip(label: String, colour: Colour, chosen: Boolean, first: Boolean, onClick: () -> Unit) {
    val touch = remember { InteractionState() }
    val fill = when {
        chosen -> colour.withAlpha(0x50)
        touch.isPressed -> colour.withAlpha(0x60)
        touch.isHovered -> colour.withAlpha(0x28)
        else -> Colour.argb(0x20FFFFFF)
    }
    FocusRing(touch.isFocused, corner = 6f) {
        Box(
            Modifier
                .interaction(touch)
                .focusable(touch, initial = first)
                .clickable(onClick = onClick)
                .background(fill, corner = 6f)
                .border(colour, width = if (chosen || touch.isHovered) 2f else 1f, corner = 6f)
                .padding(horizontal = 14f, vertical = 8f),
        ) {
            Text(label, style = Small, colour = colour)
        }
    }
}

/**
 * The ring that says where the player is.
 *
 * A separate box around the widget rather than a thicker border on it, because a focus ring is
 * outside the thing it marks — and because the padding is there whether or not the ring is, so
 * gaining focus never shifts the layout.
 */
@Composable
private fun FocusRing(focused: Boolean, corner: Float, content: @Composable () -> Unit) {
    // Not drawn while somebody is using a mouse: a ring is a cursor for people who have no cursor,
    // and drawn next to a hover highlight it is just a second highlight arguing with the first.
    val outline = if (focused && LocalInputSource.current.showsFocusRing) {
        Modifier.border(Colour.White, width = 1f, corner = corner + 3f)
    } else {
        Modifier
    }
    Box(outline.padding(3f), content = content)
}

/** Ten slots, one of them lit, and now one of them pickable. */
@Composable
private fun Hotbar(state: DemoState) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8f, Arrangement.Centre),
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        repeat(10) { slot ->
            Slot(slot, lit = slot == state.selected) { state.select(slot) }
        }
    }
}

@Composable
private fun Slot(slot: Int, lit: Boolean, onClick: () -> Unit) {
    val touch = remember { InteractionState() }
    val edge = when {
        lit -> Accent
        touch.isHovered -> Colour.argb(0x80FFFFFF)
        else -> Colour.argb(0x30FFFFFF)
    }
    FocusRing(touch.isFocused, corner = 8f) {
        Box(
            Modifier
                .interaction(touch)
                .focusable(touch)
                .clickable(onClick = onClick)
                .size(54f)
                .background(
                    when {
                        touch.isPressed -> Colour.argb(0x604CC2FF)
                        lit -> Colour.argb(0x304CC2FF)
                        touch.isHovered -> Colour.argb(0x18FFFFFF)
                        else -> Colour.argb(0x30000000)
                    },
                    corner = 8f,
                )
                .border(edge, width = if (lit) 2f else 1f, corner = 8f),
            contentAlignment = Alignment.Centre,
        ) {
            Text("${(slot + 1) % 10}", style = Body, colour = if (lit) Accent else Dim)
        }
    }
}

/** A cross where the pointer is, drawn straight onto the canvas. */
@Composable
private fun Reticle(at: Offset) {
    LeafLayout(
        Modifier.offset(at.x - ReticleSize / 2f, at.y - ReticleSize / 2f).size(ReticleSize),
        name = "reticle",
        draw = { bounds ->
            val centre = bounds.centre
            rect(Rect(bounds.left, centre.y - 0.5f, bounds.right, centre.y + 0.5f), Accent)
            rect(Rect(centre.x - 0.5f, bounds.top, centre.x + 0.5f, bounds.bottom), Accent)
        },
    )
}

private const val ReticleSize = 18f

/** What the player is using, so a widget can ask without being handed it. */
val LocalInputSource = staticCompositionLocalOf<InputSourceTracker> { error("no input source tracker") }

/** What the demo animates, and what the player has changed. */
class DemoState {

    val source = InputSourceTracker()

    var health by mutableStateOf(0.86f)
    var selected by mutableStateOf(3)

    /** Where the pointer is, in design units. Null until it has moved at least once. */
    var pointer: Offset? by mutableStateOf(null)

    /** The demo cycles the hotbar until somebody picks a slot, and then stops interfering. */
    var autoCycle by mutableStateOf(true)
        private set

    /** Which chip the player pressed, if either. */
    var briefing: String? by mutableStateOf(null)
        private set

    fun select(slot: Int) {
        selected = slot
        autoCycle = false
    }

    fun answer(choice: String) {
        briefing = choice
    }
}
