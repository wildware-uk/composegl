package dev.wildware.composegl.gdx

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.DragAndDropHost
import dev.wildware.composegl.ui.widget.DropTargetState
import dev.wildware.composegl.ui.widget.dragSource
import dev.wildware.composegl.ui.widget.dropTarget
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An item carried from one slot to another on a real GPU, judged by its pixels: the picture under
 * the pointer and over the slot it is passing, that slot lit, and the item in it once let go.
 */
class DragAndDropGlTest {

    private val ground = Colour.rgb(0x0B0E13)
    private val empty = Colour.rgb(0x39424E)
    private val lit = Colour.rgb(0x30C060)
    private val gem = Colour.rgb(0xE0303A)
    private val carried = Colour.rgb(0xFFD000)

    private fun frame(render: () -> Unit): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        render()
        return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
    }

    /** y down from the top, as the toolkit counts it; OpenGL hands the bottom row back first. */
    private fun Pixmap.rgbAt(x: Int, y: Int) = getPixel(x, Gl.size - 1 - y) ushr 8

    private fun assertNear(expected: Colour, actual: Int, because: String) {
        val want = expected.argb and 0xFFFFFF
        val channels = listOf(16, 8, 0).map { shift -> kotlin.math.abs((want shr shift and 0xFF) - (actual shr shift and 0xFF)) }
        assertTrue(channels.all { it <= 6 }, "$because: expected #%06X, got #%06X".format(want, actual))
    }

    @Test
    fun `a carried item is drawn under the pointer over a lit slot and lands in it`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
            val slots = remember { mutableStateListOf<String?>("gem", null) }
            DragAndDropHost {
                Box(Modifier.fillMaxSize().background(ground)) {
                    slots.forEachIndexed { index, item ->
                        val state = remember { DropTargetState() }
                        var modifier = Modifier.offset(20f + index * 120f, 80f).size(60f, 60f).testTag("slot$index")
                        if (item != null) {
                            modifier = modifier.dragSource(payload = item) {
                                Box(Modifier.size(30f, 30f).background(carried))
                            }
                        }
                        modifier = modifier.dropTarget<String>(state = state, onDrop = { moved ->
                            slots[slots.indexOf(moved)] = null
                            slots[index] = moved
                        })
                        Box(modifier.background(if (state.isHovered) lit else empty)) {
                            if (item != null) Box(Modifier.offset(15f, 15f).size(30f, 30f).background(gem))
                        }
                    }
                }
            }
        }
        try {
            // Grabbed by the middle, 30 in from the slot's corner, and held over the middle of the other.
            ui.press("slot0")
            ui.moveTo(Offset(100f, 110f))
            ui.moveTo(Offset(170f, 110f))

            frame { ui.render() }.also {
                assertNear(carried, it.rgbAt(150, 90), "the picture, with its corner 30 up and left of the pointer")
                assertNear(lit, it.rgbAt(190, 130), "the slot under it, lit")
                assertNear(gem, it.rgbAt(50, 110), "the item still in its slot until it is let go")
                Goldens.assertMatches("drag-and-drop", imageOf(240, 200) { x, y -> it.rgbAt(x, y) })
                it.dispose()
            }

            ui.release()

            frame { ui.render() }.also {
                assertNear(gem, it.rgbAt(170, 110), "the item in the slot it was dropped on")
                assertNear(empty, it.rgbAt(50, 110), "and gone from the one it came out of")
                assertNear(empty, it.rgbAt(150, 90), "no picture left behind, and nothing lit")
                it.dispose()
            }
            Unit
        } finally {
            ui.close()
            backend.dispose()
        }
    }
}
