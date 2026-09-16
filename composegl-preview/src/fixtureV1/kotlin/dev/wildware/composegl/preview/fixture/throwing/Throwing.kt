package dev.wildware.composegl.preview.fixture.throwing

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.preview.Preview
import dev.wildware.composegl.ui.widget.Text

/** A module where one preview works and its neighbour throws. */
@Preview(width = 240, height = 80, name = "steady")
@Composable
fun SteadyPreview() {
    Text("still standing")
}

@Preview(width = 240, height = 80, name = "boom")
@Composable
fun BoomPreview() {
    Text("about to throw")
    explode()
}

private fun explode(): Nothing = throw IllegalStateException("boom from the preview")
