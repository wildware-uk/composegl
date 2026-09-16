package dev.wildware.composegl.preview.fixture.invalid

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.preview.Preview
import dev.wildware.composegl.ui.widget.Text

/** A preview discovery refuses: it takes an argument nobody could pass. */
@Preview(name = "needs-argument")
@Composable
fun NeedsArgumentPreview(label: String) {
    Text(label)
}
