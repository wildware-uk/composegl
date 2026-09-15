package dev.wildware.composegl.lwjgl3.preview.failing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.preview.Preview

// One preview that breaks and one that does not, to show the second is still drawn.

@Preview(width = 20, height = 20)
@Composable
fun Broken() {
    error("this screen is broken")
}

/** Not written yet. `TODO()` throws an Error rather than an Exception, and is still one preview. */
@Preview(width = 20, height = 20)
@Composable
fun Unfinished() {
    TODO("the shop screen")
}

/** A pulse with no end. The picture would be of whichever frame it happened to stop on. */
@Preview(width = 20, height = 20)
@Composable
fun Forever() {
    var ticks by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { ticks++ } }
    Box(Modifier.size((ticks % 10 + 1).toFloat(), 10f).background(Colour.rgb(0xFFFFFF)))
}

@Preview(width = 20, height = 20, name = "menus/working")
@Composable
fun Working() {
    Box(Modifier.size(10f, 10f).background(Colour.rgb(0xFFFFFF)))
}
