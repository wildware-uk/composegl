package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.ProvideTextScale
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The player's text size, as the game widgets that draw text take it.
 *
 * The same arithmetic as composegl-ui's `TextScaleTest`: the monospace font makes a character 0.6
 * of the size across, so a centred run moves left by half of what it grew.
 */
class GameTextScaleTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(size: Size = Size(400f, 300f), content: @Composable () -> Unit): UiTest =
        uiTest(size, content = content).also { opened += it }

    private fun assertNear(expected: Float, actual: Float, message: String) =
        assertTrue(kotlin.math.abs(expected - actual) < 0.01f, "$message: expected $expected, was $actual")

    @Test
    fun `a still minimap and damage layer under a text scale do not redraw even when they compose again`() {
        var tick by mutableStateOf(0)
        val numbers = DamageNumbers(clock = Clock.Ui)
        val ui = open {
            ProvideTextScale(1.5f) {
                Column {
                    // Read in here, so every tick composes each widget below again rather than
                    // letting Compose skip the unchanged lambdas round them.
                    @Suppress("UNUSED_EXPRESSION") tick
                    Text("HULL")
                    MinimapFrame(Modifier.size(100f, 100f), live = false)
                    DamageNumberLayer(numbers)
                }
            }
        }
        ui.advanceBy(2_000)
        ui.render()
        assertFalse(ui.render(), "nothing changed, so nothing is drawn again")

        tick++

        assertFalse(ui.render(), "composing the same scale again is not a change")
    }

    @Test
    fun `a damage number is centred on its point at the scaled width`() {
        val size = Skin.Default.resolve("damage").textStyle.size

        fun leftOf148(scale: Float): Float {
            val numbers = DamageNumbers(clock = Clock.Ui)
            val ui = open { ProvideTextScale(scale) { DamageNumberLayer(numbers) } }
            numbers.show("148", WorldAnchor.at(200f, 100f))
            val canvas = (ui.backend as HeadlessBackend).canvas
            canvas.clear()
            ui.render()
            return assertNotNull(canvas.calls.filterIsInstance<DrawCall.Text>().firstOrNull { it.text == "148" }).at.x
        }

        assertNear(200f - 3 * size * 0.6f / 2f, leftOf148(1f), "half of three characters to the left")
        assertNear(200f - 3 * size * 0.6f, leftOf148(2f), "half of three characters at twice the size")
    }

    @Test
    fun `a minimap's compass letter is drawn at the scaled size`() {
        val size = Skin.Default.resolve("minimap.compass").textStyle.size

        fun north(scale: Float): Offset {
            val ui = open {
                ProvideTextScale(scale) { MinimapFrame(Modifier.size(200f, 200f), live = false) }
            }
            val canvas = (ui.backend as HeadlessBackend).canvas
            canvas.clear()
            ui.render()
            return assertNotNull(canvas.calls.filterIsInstance<DrawCall.Text>().firstOrNull { it.text == "N" }).at
        }

        val plain = north(1f)
        val big = north(2f)
        assertNear(plain.x - size * 0.6f / 2f, big.x, "centred on the same point at twice the width")
        assertTrue(big.y > plain.y, "and kept further in from the edge, because it is taller")
    }
}
