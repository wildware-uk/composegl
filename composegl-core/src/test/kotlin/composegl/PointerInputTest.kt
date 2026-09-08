package composegl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class PointerInputTest {

    private val ui = HeadlessSurface(400, 300)

    @AfterEach
    fun tearDown() = ui.close()

    private fun show(content: @Composable () -> Unit) = ui.setContent(content)
    private fun frame() = ui.frame()
    private fun press(x: Float, y: Float, id: Int = 0) = ui.press(x, y, id)
    private fun release(x: Float, y: Float, id: Int = 0) = ui.release(x, y, id)
    private fun move(x: Float, y: Float, id: Int = 0) = ui.move(x, y, id)

    @Test
    fun `clicking a button fires onClick and reports consumed`() {
        var clicks = 0
        show {
            Box(Modifier.fillMaxSize()) {
                Button(onClick = { clicks++ }, modifier = Modifier.align(Alignment.Center)) { Text("go") }
            }
        }

        move(200f, 150f)
        assertTrue(press(200f, 150f), "a press on a button is consumed")
        assertTrue(release(200f, 150f), "so is its release")
        assertEquals(1, clicks, "and onClick has already fired by the time the call returns")
    }

    @Test
    fun `clicking empty space is not consumed, so the game gets it`() {
        show {
            Box(Modifier.fillMaxSize()) {
                Button(onClick = {}, modifier = Modifier.align(Alignment.TopStart)) { Text("go") }
            }
        }

        assertFalse(press(390f, 290f), "the bottom-right corner is empty HUD")
        assertFalse(release(390f, 290f))
    }

    @Test
    fun `a press on empty space hands keyboard focus back to the game`() {
        val focus = FocusRequester()
        var value by mutableStateOf(TextFieldValue(""))
        show {
            Box(Modifier.fillMaxSize()) {
                TextField(value, { value = it }, Modifier.align(Alignment.TopStart).focusRequester(focus))
            }
        }
        focus.requestFocus()
        frame()
        assertTrue(ui.hasKeyboardFocus)

        press(390f, 290f)
        release(390f, 290f)
        frame()

        assertFalse(ui.hasKeyboardFocus, "otherwise the field would eat the game's next keystroke")
        assertFalse(ui.surface.sendChar('a'.code))
    }

    @Test
    fun `a drag that starts on a slider stays with Compose until release`() {
        var value by mutableStateOf(0f)
        show {
            Box(Modifier.fillMaxSize()) {
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.align(Alignment.TopStart).size(width = 200.dp, height = 40.dp),
                )
            }
        }

        assertTrue(press(20f, 20f), "the press lands on the slider")
        // Drag right, out of the slider's own bounds and off the bottom of the HUD.
        assertTrue(move(120f, 20f), "a captured pointer stays with Compose")
        assertTrue(move(300f, 280f), "even once it has left the widget entirely")
        assertTrue(release(300f, 280f), "and the release belongs to Compose too")
        frame()
        assertTrue(value > 0f, "the drag actually moved the slider")

        assertFalse(move(300f, 280f), "after release the capture is gone")
    }

    @Test
    fun `hover is delivered to Compose but never claimed`() {
        val moves = AtomicInteger()
        show {
            Box(Modifier.fillMaxSize()) {
                Button(
                    onClick = {},
                    modifier = Modifier.align(Alignment.Center).pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Move) moves.incrementAndGet()
                            }
                        }
                    },
                ) { Text("go") }
            }
        }

        assertFalse(move(200f, 150f), "hover over a button must still return false")
        assertFalse(move(201f, 151f))
        assertTrue(moves.get() >= 1, "but Compose must still see it, or hover effects break")
        assertFalse(move(10f, 10f), "and so must hover over nothing")
    }

    @Test
    fun `two pointers are captured independently`() {
        show {
            Box(Modifier.fillMaxSize()) {
                Button(onClick = {}, modifier = Modifier.align(Alignment.TopStart)) { Text("go") }
            }
        }

        assertTrue(press(30f, 20f, id = 1), "pointer 1 lands on the button")
        assertFalse(press(390f, 290f, id = 2), "pointer 2 lands on empty space")
        assertTrue(move(200f, 150f, id = 1), "pointer 1 is captured")
        assertFalse(move(200f, 150f, id = 2), "pointer 2 is not")
        assertTrue(release(200f, 150f, id = 1))
        assertFalse(release(200f, 150f, id = 2))
    }

    @Test
    fun `scroll reports what a scrollable took`() {
        show {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(100.dp).verticalScroll(rememberScrollState())) {
                    Box(Modifier.size(width = 100.dp, height = 1000.dp))
                }
            }
        }
        val overScrollable = ui.scroll(50f, 50f, 1f)
        val overNothing = ui.scroll(390f, 290f, 1f)
        assertTrue(overScrollable, "a scrollable under the pointer takes the wheel")
        assertFalse(overNothing, "empty HUD lets the wheel through to the game")
    }

    @Test
    fun `cancelPointerInput drops the capture`() {
        show {
            Box(Modifier.fillMaxSize()) {
                Button(onClick = {}, modifier = Modifier.align(Alignment.TopStart)) { Text("go") }
            }
        }
        assertTrue(press(30f, 20f))
        ui.surface.cancelPointerInput()
        assertFalse(move(200f, 150f), "the gesture was cancelled, so the game gets the pointer back")
    }

    @Test
    fun `pointer events before setContent are refused`() {
        assertFalse(press(10f, 10f))
    }
}
