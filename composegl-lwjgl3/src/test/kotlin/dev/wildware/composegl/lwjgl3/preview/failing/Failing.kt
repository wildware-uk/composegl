package dev.wildware.composegl.lwjgl3.preview.failing

import androidx.compose.runtime.Composable
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

@Preview(width = 20, height = 20)
@Composable
fun Working() {
    Box(Modifier.size(10f, 10f).background(Colour.rgb(0xFFFFFF)))
}
