package dev.wildware.composegl.ui.preview.samples

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.preview.Preview
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text

// The previews PreviewsTest finds. Each shape a real one takes: top level, private, named, on a
// background, in an object.

@Preview(width = 300, height = 120)
@Composable
fun CounterPreview() {
    var presses by remember { mutableStateOf(0) }
    Button("PRESSED $presses", onClick = { presses++ }, modifier = Modifier.testTag("counter"))
}

@Preview(width = 64, height = 48, name = "square-on-blue", background = 0xFF0000FF)
@Composable
private fun SquarePreview() {
    Box(Modifier.offset(8f, 8f).size(16f, 16f).background(Colour.rgb(0xFF0000)).testTag("square"))
}

@Preview(width = 32, height = 32, background = 0x00000000)
@Composable
fun ClearPreview() {
    Box(Modifier.size(8f, 8f).background(Colour.rgb(0x00FF00)))
}

/** Not a preview: unmarked functions are left alone. */
@Composable
fun NotAPreview() {
    Text("never found")
}

object ObjectPreviews {

    @Preview(width = 120, height = 40)
    @Composable
    fun InsideAnObject() {
        Text("FROM AN OBJECT", modifier = Modifier.testTag("label"))
    }
}

class CompanionPreviews {
    companion object {

        @Preview(width = 200, height = 60)
        @Composable
        fun InACompanion() {
            var presses by remember { mutableStateOf(0) }
            Button("COMPANION $presses", onClick = { presses++ }, modifier = Modifier.testTag("companion"))
        }

        /** Kotlin writes this one twice, a static copy on the class and the companion's own. */
        @JvmStatic
        @Preview(width = 120, height = 40)
        @Composable
        fun StaticInACompanion() {
            Text("STATIC", modifier = Modifier.testTag("static"))
        }
    }
}
