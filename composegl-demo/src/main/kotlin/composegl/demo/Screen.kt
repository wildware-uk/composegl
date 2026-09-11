package composegl.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import composegl.ui.graphics.Colour
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
import composegl.ui.modifier.fillMaxHeight
import composegl.ui.modifier.fillMaxSize
import composegl.ui.modifier.fillMaxWidth
import composegl.ui.modifier.height
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
fun Screen(fonts: FontProvider, skin: DemoSkin, health: Float, selected: Int) {
    CompositionLocalProvider(LocalFonts provides fonts, LocalSkin provides skin) {
        Box(Modifier.fillMaxSize().background(Background)) {
            Column(Modifier.fillMaxSize().padding(28f), verticalArrangement = Arrangement.spacedBy(20f)) {
                Text("COMPOSEGL", style = Title, colour = Accent)
                Text("a game interface toolkit on the Compose runtime", style = Small, colour = Dim)

                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20f)) {
                    StatusPanel(Modifier.width(300f).fillMaxHeight(), health)
                    LorePanel(Modifier.weight(1f).fillMaxHeight())
                }

                Hotbar(selected)
            }
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
private fun LorePanel(modifier: Modifier) {
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
                Chip("ACCEPT", Accent)
                Chip("DECLINE", Dim)
            }
        }
    }
}

@Composable
private fun Chip(label: String, colour: Colour) {
    Box(
        Modifier
            .background(Colour.argb(0x20FFFFFF), corner = 6f)
            .border(colour, width = 1f, corner = 6f)
            .padding(horizontal = 14f, vertical = 8f),
    ) {
        Text(label, style = Small, colour = colour)
    }
}

/** Ten slots, one of them lit. The shape of every action bar ever shipped. */
@Composable
private fun Hotbar(selected: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8f, Arrangement.Centre),
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        repeat(10) { slot ->
            val lit = slot == selected
            Box(
                Modifier
                    .size(54f)
                    .background(if (lit) Colour.argb(0x304CC2FF) else Colour.argb(0x30000000), corner = 8f)
                    .border(if (lit) Accent else Colour.argb(0x30FFFFFF), width = if (lit) 2f else 1f, corner = 8f),
                contentAlignment = Alignment.Centre,
            ) {
                Text("${(slot + 1) % 10}", style = Body, colour = if (lit) Accent else Dim)
            }
        }
    }
}

/** What the demo animates, so that a frame is worth redrawing. */
class DemoState {
    var health by mutableStateOf(0.86f)
    var selected by mutableStateOf(3)
}
