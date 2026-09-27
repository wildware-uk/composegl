package dev.wildware.composegl.ui.layout

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a weight means in a line with no end to it — a column inside a scroll area.
 *
 * There is nothing to share out, and a share of infinity is not a number a child can be measured
 * at. It used to be measured at nothing instead, so every weighted child in such a column came out
 * with no height at all and they were all drawn on top of each other at the same place. Reported
 * from the showcase, where three blocks and their labels piled up into one smudge.
 */
class UnboundedWeightTest {

    private val host = UiHost()
    private var clock = 0L

    @AfterTest
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent(content)
        repeat(8) {
            clock += 16_666_667L
            // As tall as it likes, which is what a scroll area offers what is inside it.
            if (!host.settle(Constraints(maxWidth = 300f, maxHeight = Float.POSITIVE_INFINITY), nanos = clock)) return
        }
        throw AssertionError("settle still reported a change after 8 turns")
    }

    @Test
    fun `weighted children in a column with no end to it keep the size they asked for`() {
        show {
            Column {
                Box(Modifier.weight(1f).width(120f).height(38f).testTag("first")) {}
                Box(Modifier.weight(1f).width(120f).height(38f).testTag("second")) {}
            }
        }

        assertEquals(Rect.of(0f, 0f, 120f, 38f), host.root.find("first").layoutBoundsInRoot)
        assertEquals(Rect.of(0f, 38f, 120f, 38f), host.root.find("second").layoutBoundsInRoot)
    }

    @Test
    fun `a weight still shares out a line that has an end`() {
        show {
            Row(Modifier.width(300f).height(40f)) {
                Box(Modifier.weight(1f).testTag("left")) {}
                Box(Modifier.weight(2f).testTag("right")) {}
            }
        }

        assertEquals(100f, host.root.find("left").width)
        assertEquals(200f, host.root.find("right").width)
    }
}
