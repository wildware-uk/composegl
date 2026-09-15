package dev.wildware.composegl.ui.preview.broken

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.preview.Preview
import dev.wildware.composegl.ui.widget.Text

// Mistakes Previews has to refuse, one per class so each can be asked about alone.

object NotComposable {
    @Preview
    fun Plain() = Unit
}

object TakesArguments {
    @Preview
    @Composable
    fun Greeting(name: String) {
        Text(name)
    }
}

class InsideAClass {
    @Preview
    @Composable
    fun Member() {
        Text("member")
    }
}

object Empty {
    @Preview(width = 0, height = 10)
    @Composable
    fun Nothing() {
        Text("no room")
    }
}

object Throws {
    @Preview
    @Composable
    fun Fails() {
        error("the preview itself went wrong")
    }
}
