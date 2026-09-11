package uk.wildware.composegl.ui.widget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uk.wildware.composegl.ui.backend.MonospaceFontProvider
import uk.wildware.composegl.ui.draw.DrawPass
import uk.wildware.composegl.ui.focus.FocusManager
import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.graphics.RecordingCanvas
import uk.wildware.composegl.ui.host.UiHost
import uk.wildware.composegl.ui.input.PointerEvent
import uk.wildware.composegl.ui.input.PointerId
import uk.wildware.composegl.ui.input.PointerRouter
import uk.wildware.composegl.ui.input.PointerType
import uk.wildware.composegl.ui.layout.Constraints
import uk.wildware.composegl.ui.layout.MeasurePass
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.layout.Box
import uk.wildware.composegl.ui.modifier.padding
import uk.wildware.composegl.ui.modifier.width
import uk.wildware.composegl.ui.skin.Skin
import uk.wildware.composegl.ui.text.TextFieldValue
import uk.wildware.composegl.ui.text.TextRange
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Where a tap lands, for a field that is not in the corner of the screen.
 *
 * Every other test in [TextFieldTest] puts its field at the top left, where a widget's own
 * coordinates and the screen's are the same numbers — and a field on a phone, halfway down a
 * panel, is where the difference between the two shows up as every caret going to the start of
 * the text. So this one deliberately moves the field first.
 */
class TextFieldPlacementTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private var clock = 0L

    /** One character of the test font, at the default text size. */
    private val character = 16f * 0.6f

    /** How far the field is pushed away from the corner, in both directions. */
    private val away = 60f

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame() {
        canvas.clear()
        host.frame(clock)
        clock += 16_666_667L
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
    }

    private fun tap(at: Offset) {
        val time = clock / 1_000_000
        pointer.onPointer(PointerEvent.Press(PointerId(0), at, type = PointerType.Touch, timeMillis = time))
        pointer.onPointer(PointerEvent.Release(PointerId(0), at, type = PointerType.Touch, timeMillis = time))
        frame()
    }

    @Test
    fun `a tap puts the caret where it was tapped, wherever the field is`() {
        var value by mutableStateOf(TextFieldValue("hello world", TextRange(0)))
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                Box(Modifier.padding(left = away, top = away)) {
                    TextField(value, onValueChange = { value = it }, modifier = Modifier.width(300f))
                }
            }
        }
        repeat(2) { frame() }

        val padding = Skin.Default.resolve("field").padding
        tap(Offset(away + padding.left + 3 * character + 1f, away + padding.top + 4f))

        assertEquals(3, value.selection.start)
    }
}
