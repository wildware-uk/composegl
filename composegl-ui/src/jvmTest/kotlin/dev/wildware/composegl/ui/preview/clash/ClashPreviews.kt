package dev.wildware.composegl.ui.preview.clash

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.preview.Preview
import dev.wildware.composegl.ui.widget.Text

// Two previews that would write the same file.

@Preview(name = "menu")
@Composable
fun MenuPreview() {
    Text("one")
}

object Other {
    @Preview(name = "menu")
    @Composable
    fun AlsoMenu() {
        Text("two")
    }
}
