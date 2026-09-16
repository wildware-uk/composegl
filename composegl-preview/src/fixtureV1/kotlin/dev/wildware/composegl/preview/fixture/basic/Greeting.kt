package dev.wildware.composegl.preview.fixture.basic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.preview.Preview
import dev.wildware.composegl.ui.widget.Text

/**
 * The first build of a module. The second build, in `fixtureV2`, is the same file with other words.
 *
 * It keeps state and starts a coroutine, as a real screen does, so the reload tests exercise what a
 * composition holds on to and not just a label.
 */
@Preview(width = 240, height = 80, name = "greeting", background = 0xFF2040A0)
@Composable
fun GreetingPreview() {
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ready = true }
    Column {
        Text("greeting: version one")
        if (ready) Text("ready")
    }
}

@Preview(width = 240, height = 80, name = "farewell")
@Composable
fun FarewellPreview() {
    Text("farewell: " + Words.farewell())
}

/** Read from the module's own resources, which are reloaded with its classes. */
internal object Words {
    fun farewell(): String =
        checkNotNull(Words::class.java.getResource("farewell.txt")) { "farewell.txt is missing" }.readText().trim()
}
