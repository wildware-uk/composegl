package dev.wildware.composegl.ui.debug

import dev.wildware.composegl.ui.node.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The trace's bookkeeping, with no canvas and no tree: what is counted, grouped and handed back. */
class DrawCallTraceTest {

    private val hotbar = UiNode("hotbar")
    private val icon = UiNode("icon")

    @Test
    fun `calls are grouped by the node and the reason together`() {
        val trace = DrawCallTrace()
        trace.node = hotbar
        trace.record(BatchBreak.Blend)
        trace.record(BatchBreak.Blend)
        trace.record(BatchBreak.Clip)
        trace.node = icon
        trace.record(BatchBreak.Blend)

        assertEquals(
            listOf(
                DrawCallCulprit(hotbar, "hotbar", BatchBreak.Blend, 2),
                DrawCallCulprit(hotbar, "hotbar", BatchBreak.Clip, 1),
                DrawCallCulprit(icon, "icon", BatchBreak.Blend, 1),
            ),
            trace.culprits(),
        )
        assertEquals(4, trace.total)
    }

    @Test
    fun `the most expensive comes first and ties keep the order they happened in`() {
        val trace = DrawCallTrace()
        trace.node = hotbar
        trace.record(BatchBreak.Texture)
        trace.node = icon
        trace.record(BatchBreak.Layer)
        trace.record(BatchBreak.Layer)
        trace.record(BatchBreak.Layer)
        trace.node = hotbar
        trace.record(BatchBreak.Clip)

        assertEquals(
            listOf("icon Layer", "hotbar Texture", "hotbar Clip"),
            trace.culprits().map { "${it.name} ${it.reason}" },
        )
    }

    @Test
    fun `the frame closing is counted but not blamed unless asked for`() {
        val trace = DrawCallTrace()
        trace.node = icon
        trace.record(BatchBreak.Shader)
        trace.node = null
        trace.record(BatchBreak.End)

        assertEquals(listOf(BatchBreak.Shader), trace.culprits().map { it.reason })
        val all = trace.culprits(withEnd = true)
        assertEquals(listOf(BatchBreak.Shader, BatchBreak.End), all.map { it.reason })
        assertEquals("outside the tree", all[1].name)
        assertEquals(trace.total, all.sumOf { it.calls }, "every call is somewhere in the list")
    }

    @Test
    fun `clearing forgets the frame and the next one starts from nothing`() {
        val trace = DrawCallTrace()
        trace.node = hotbar
        trace.record(BatchBreak.Raw)
        trace.clear()

        assertEquals(0, trace.total)
        assertTrue(trace.culprits(withEnd = true).isEmpty())
        assertNull(trace.node, "a node from last frame is not this frame's")

        trace.node = icon
        trace.record(BatchBreak.Full)
        assertEquals(listOf(DrawCallCulprit(icon, "icon", BatchBreak.Full, 1)), trace.culprits())
    }
}
