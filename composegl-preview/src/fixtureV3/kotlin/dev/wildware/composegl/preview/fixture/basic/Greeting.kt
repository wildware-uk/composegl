package dev.wildware.composegl.preview.fixture.basic

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.preview.Preview
import dev.wildware.composegl.ui.widget.Text

/** The third build of the module in `fixtureV1`: `farewell` has been deleted. */
@Preview(width = 240, height = 80, name = "greeting", background = 0xFF2040A0)
@Composable
fun GreetingPreview() {
    Text("greeting: version three")
}
