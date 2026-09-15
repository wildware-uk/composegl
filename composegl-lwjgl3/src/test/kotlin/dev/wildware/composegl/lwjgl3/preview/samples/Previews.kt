package dev.wildware.composegl.lwjgl3.preview.samples

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.animateFloatAsState
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.preview.Preview
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.Text

// What PreviewRendererTest draws. Wider than the test window on purpose: a preview draws off the
// window, so its size is its own.

/** A red square at a known place on a blue ground, for reading single pixels back. */
@Preview(width = 480, height = 60, name = "squares", background = 0xFF0000FF)
@Composable
fun Squares() {
    Box(Modifier.offset(10f, 10f).size(20f, 20f).background(Colour.rgb(0xFF0000)))
    Box(Modifier.offset(440f, 30f).size(30f, 20f).background(Colour.rgb(0x00FF00)))
}

/** Nothing behind, and a half-faded square: what a PNG with a hole in it has to keep. */
@Preview(width = 40, height = 40, name = "see-through", background = 0x00000000)
@Composable
fun SeeThrough() {
    Box(Modifier.offset(10f, 10f).size(20f, 20f).alpha(0.5f).background(Colour.rgb(0xFFFFFF)))
}

/** A white square that fades in over a second once it is on the screen: the picture is taken after. */
@Preview(width = 40, height = 40, name = "fade-in", background = 0xFF000000)
@Composable
fun FadeIn() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val opacity by animateFloatAsState(if (shown) 1f else 0f, Tween(1000))
    Box(Modifier.offset(10f, 10f).size(20f, 20f).alpha(opacity).background(Colour.rgb(0xFFFFFF)))
}

/** Real widgets through the default skin, with text: the golden. */
@Preview(width = 240, height = 120, name = "menu", background = 0xFF101418)
@Composable
fun Menu() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
        Panel {
            Column(verticalArrangement = Arrangement.spacedBy(8f), horizontalAlignment = HorizontalAlignment.Centre) {
                Text("PAUSED")
                Button("RESUME", onClick = {})
            }
        }
    }
}
