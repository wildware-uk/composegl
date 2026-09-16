package dev.wildware.composegl.showcase.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.PanZoomCanvas
import dev.wildware.composegl.ui.widget.PanZoomReset
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.rememberPanZoomState
import dev.wildware.composegl.ui.widget.worldPosition

/** One system on the map: where it is in the world, and what it is called. */
private class System(val name: String, val x: Float, val y: Float)

private val Systems = listOf(
    System("Sol", 200f, 320f),
    System("Procyon", 520f, 180f),
    System("Wolf 359", 520f, 470f),
    System("Altair", 880f, 260f),
    System("Vega", 900f, 620f),
    System("Deneb", 1240f, 420f),
)

/** Which systems have a lane between them, as pairs of indices into [Systems]. */
private val Lanes = listOf(0 to 1, 0 to 2, 1 to 3, 2 to 3, 2 to 4, 3 to 5, 4 to 5)

/**
 * A star map on a [PanZoomCanvas]: drag it about, wheel or pinch to zoom, double click to go back.
 *
 * Everything on it is an ordinary widget — the systems are clickable boxes with labels — placed at
 * world positions rather than screen ones. The lanes between them are drawn by the canvas's
 * background, in world units, as one pass rather than a node per lane. The "you are here" pin keeps
 * its size whatever the zoom, which is what `scaleWithZoom = false` is for.
 */
@Composable
fun StarMap() {
    val world = Rect(0f, 0f, 1440f, 800f)
    val camera = rememberPanZoomState(zoom = 0.8f, minZoom = 0.4f, maxZoom = 2.5f, bounds = world)
    var chosen by remember { mutableStateOf(0) }

    // Remembered, as a widget's own draw is: the canvas asks for a redraw when the background it was
    // given changes, and these lanes never do.
    val lanes: UiCanvas.(Rect) -> Unit = remember {
        { visible ->
            Lanes.forEach { (from, to) ->
                val a = Systems[from]
                val b = Systems[to]
                // Skip a lane nowhere near the view: the background is one pass, but it is still
                // the game's own drawing and it is handed the visible world to cut it down by.
                val box = Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
                if (box.overlaps(visible)) {
                    line(Offset(a.x, a.y), Offset(b.x, b.y), width = 2f, colour = Colour.argb(0x66A0C4FF))
                }
            }
        }
    }

    Column(
        Modifier.align(Alignment.BottomEnd).padding(right = 28f, bottom = 28f).size(360f, 220f),
    ) {
        Text("Star map — drag, wheel, double click", style = "label.dim")
        PanZoomCanvas(
            state = camera,
            modifier = Modifier.size(360f, 196f),
            reset = PanZoomReset.Fit,
            background = lanes,
        ) {
            Systems.forEachIndexed { index, system ->
                Box(
                    Modifier.size(if (index == chosen) 22f else 16f)
                        .worldPosition(system.x, system.y, anchor = Alignment.Centre)
                        .focusable()
                        .clickable { chosen = index }
                        .background(
                            if (index == chosen) Colour.rgb(0xFFD48A) else Colour.rgb(0x5B8DEF),
                            corner = 11f,
                        ),
                )
                Text(
                    system.name,
                    Modifier.worldPosition(system.x, system.y + 16f, anchor = Alignment.TopCentre),
                    style = "label.dim",
                )
            }
            // Follows the camera, stays readable at any zoom.
            Text(
                "you are here",
                Modifier.worldPosition(
                    Systems[0].x,
                    Systems[0].y - 18f,
                    anchor = Alignment.BottomCentre,
                    scaleWithZoom = false,
                ),
            )
        }
    }
}
