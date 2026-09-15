package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.debug.DebugOverlay
import dev.wildware.composegl.ui.debug.isDebugOverlay
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.zIndex
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The overlay a game writes itself, as the Debugging page shows it: public API only, and treated as
 * debug tooling by the toolkit and by this module's overlays because its drawing is a [DebugOverlay].
 */
class OwnOverlayTest {

    /** A red dot on the middle of every node: the example on the Debugging page, word for word. */
    class Centres : DebugOverlay {
        var node: UiNode? = null

        override fun invoke(canvas: UiCanvas, content: Rect) {
            val self = node ?: return
            // Where this canvas's origin is: the root's, unless something drew the tree elsewhere.
            val dx = content.left - self.contentBoundsInRoot.left
            val dy = content.top - self.contentBoundsInRoot.top
            self.tree?.root?.forEach { node ->
                if (!node.everMeasured || isDebugOverlay(node)) return@forEach
                val centre = node.boundsInRoot.centre
                canvas.rect(Rect.of(centre.x + dx - 1f, centre.y + dy - 1f, 2f, 2f), Colour.Red)
            }
        }
    }

    @Composable
    fun CentresOverlay(enabled: Boolean) {
        if (!enabled) return
        val centres = remember { Centres() }
        ComposeNode<UiNode, UiApplier>(
            factory = { UiNode("centres") },
            update = {
                set(Modifier.zIndex(Float.MAX_VALUE)) { this.modifier = it }
                set(MeasurePolicy.Empty) { this.measurePolicy = it }
                set(centres) {
                    it.node = this
                    this.content = it
                }
            },
        )
    }

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    private fun drawn(ui: UiTest): List<DrawCall> {
        val canvas = ui.backend.canvas as RecordingCanvas
        canvas.clear()
        ui.render()
        return canvas.calls.toList()
    }

    private fun List<DrawCall>.dots() = filterIsInstance<DrawCall.Rectangle>().filter { it.colour == Colour.Red }.map { it.rect }

    /** A button that turns the dots on, a panel, and F2 turning on the layout overlay as well. */
    @Composable
    private fun Screen() {
        var dots by remember { mutableStateOf(false) }
        var layout by remember { mutableStateOf(false) }
        Box(
            Modifier.fillMaxSize().onKeyEvent(
                KeyHandler {
                    if (it.key == Key.F2) layout = true
                    it.key == Key.F2
                },
            ),
        ) {
            Button("dots", onClick = { dots = !dots }, modifier = Modifier.testTag("toggle"), initialFocus = true)
            Box(Modifier.offset(100f, 100f).size(60f, 40f).background(Colour.Blue).testTag("panel"))
            CentresOverlay(dots)
            LayoutOverlay(layout, setOf(Show.Bounds))
        }
    }

    @Test
    fun `a click puts a dot on the middle of every node and a pad press takes them off`() {
        val ui = open { Screen() }
        assertTrue(drawn(ui).dots().isEmpty())

        ui.click("toggle")
        val dots = drawn(ui).dots()
        assertTrue(Rect.of(129f, 119f, 2f, 2f) in dots, "the panel's middle: $dots")
        assertTrue(dots.none { it.width != 2f }, "only dots")

        ui.pad(GamepadButton.South)
        assertTrue(drawn(ui).dots().isEmpty(), "the focused button turned them off")
    }

    @Test
    fun `the toolkit and the layout overlay treat it as debug tooling`() {
        val ui = open { Screen() }
        val before = ui.overdraw()
        ui.click("toggle")
        ui.key(Key.F2)

        val overlay = ui.root.firstOrNull { it.name == "centres" }!!
        assertTrue(isDebugOverlay(overlay))
        assertEquals(before.at(130f, 120f), ui.overdraw().at(130f, 120f), "the dot over the panel is not counted as paint")
        val node = overlay.layoutBoundsInRoot
        val calls = drawn(ui)
        assertTrue(calls.any { it is DrawCall.Border && it.colour == LayoutOverlayColours.Bounds }, "F2 put the layout overlay up")
        val edges = calls.filterIsInstance<DrawCall.Rectangle>().filter { it.colour == LayoutOverlayColours.Bounds }
        assertFalse(edges.any { it.rect.left == node.left && it.rect.top == node.top }, "the layout overlay leaves it out: $edges")
    }
}
