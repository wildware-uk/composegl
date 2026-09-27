package dev.wildware.composegl.demo.docs

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.WindowClass
import dev.wildware.composegl.ui.layout.WithSize
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.widget.Text

/**
 * The same screen at three widths, changing shape rather than scaling.
 *
 * One composable, laid out three times. It asks [WithSize] how much room it has and picks what
 * every responsive interface picks: everything stacked in one column on a phone, a rail beside the
 * page on a tablet, and navigation, page and details all at once where there is room for them.
 *
 * Each column is exactly as wide as the class it is showing, so the picture is the breakpoints
 * themselves rather than three sizes somebody thought looked about right.
 */
@Composable
internal fun ResponsiveWidths() {
    Frame {
        Column(verticalArrangement = Arrangement.spacedBy(12f)) {
            listOf(360f to "Compact", 700f to "Medium", 920f to "Expanded").forEach { (width, name) ->
                Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6f)) {
                    Text("$name  ${width.toInt()} wide", style = "label.dim")
                    Box(Modifier.fillMaxWidth().background(Panel, corner = 8f).padding(8f)) {
                        Adaptive()
                    }
                }
            }
        }
    }
}

/**
 * One screen that lays itself out from the room it is given.
 *
 * `WithSize` rather than `LocalScreen`, because in this picture all three are on one screen: the
 * question is what a *place* has, not what the window has. In a real game these are the same
 * question, and `LocalWindowClass` answers it without the frame's delay.
 */
@Composable
private fun Adaptive() {
    WithSize(estimate = Size(0f, 0f)) { room ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6f)) {
            when (WindowClass.of(room.width)) {
                // A phone: one column, and the navigation goes along the bottom as a bar.
                WindowClass.Compact -> {
                    Slab("Page", Ink, height = 96f)
                    Slab("Nav", Accent, height = 28f)
                }
                // A tablet: a rail down the side of the page, and the details fold into it.
                WindowClass.Medium -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6f)) {
                    Box(Modifier.width(64f)) { Slab("Nav", Accent, height = 124f) }
                    Box(Modifier.weight(1f)) { Slab("Page", Ink, height = 124f) }
                }
                // Room for the lot: nothing has to be hidden behind anything.
                WindowClass.Expanded -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6f)) {
                    Box(Modifier.width(120f)) { Slab("Nav", Accent, height = 124f) }
                    Box(Modifier.weight(1f)) { Slab("Page", Ink, height = 124f) }
                    Box(Modifier.width(180f)) { Slab("Details", Steel, height = 124f) }
                }
            }
        }
    }
}

/** One block with its name on it. */
@Composable
private fun Slab(name: String, colour: Colour, height: Float) {
    Box(
        Modifier.fillMaxWidth().height(height).background(colour, corner = 6f),
        contentAlignment = Alignment.Centre,
    ) {
        Text(name, style = "label")
    }
}

private val Panel = Colour.rgb(0x161B24)
private val Ink = Colour.rgb(0x232A35)
private val Steel = Colour.rgb(0x2C3545)
private val Accent = Colour.rgb(0x1D4F70)
