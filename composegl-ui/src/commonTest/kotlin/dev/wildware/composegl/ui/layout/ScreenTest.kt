package dev.wildware.composegl.ui.layout

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a screen says about itself, without anything composed. */
class ScreenSizeTest {

    @Test
    fun `a width falls into one of the three classes at Material's own breakpoints`() {
        assertEquals(WindowClass.Compact, WindowClass.of(0f))
        assertEquals(WindowClass.Compact, WindowClass.of(599f))
        assertEquals(WindowClass.Medium, WindowClass.of(600f))
        assertEquals(WindowClass.Medium, WindowClass.of(839f))
        assertEquals(WindowClass.Expanded, WindowClass.of(840f))
        assertEquals(WindowClass.Expanded, WindowClass.of(3840f))
    }

    @Test
    fun `which way round a screen is comes from its shape and not its size`() {
        assertEquals(Orientation.Portrait, Screen(Size(420f, 860f)).orientation)
        assertEquals(Orientation.Landscape, Screen(Size(1280f, 720f)).orientation)
        // A square is landscape, because a layout that splits left-and-right still fits one.
        assertEquals(Orientation.Landscape, Screen(Size(500f, 500f)).orientation)
    }

    @Test
    fun `a screen is the room left after the safe area rather than the resolution declared`() {
        val viewport = Viewport(
            design = Size(1000f, 600f),
            physical = Size(1000f, 600f),
            safeArea = Padding(left = 40f, right = 20f),
        )
        val screen = Screen.of(viewport)

        assertEquals(Size(940f, 600f), screen.size)
        assertEquals(40f, screen.safeInsets.left)
        assertEquals(20f, screen.safeInsets.right)
    }
}

/** What a composed tree is told about the room it has. */
class ScreenUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(size: Size, content: @Composable () -> Unit): UiTest =
        uiTest(size, content = content).also { opened += it }

    @Test
    fun `a desktop screen reaches the tree as an expanded one`() {
        var seen: Screen? = null
        var windowClass: WindowClass? = null
        open(Size(1280f, 720f)) {
            seen = LocalScreen.current
            windowClass = LocalWindowClass.current
        }.settle()

        assertEquals(Size(1280f, 720f), seen?.size)
        assertEquals(WindowClass.Expanded, windowClass)
        assertEquals(Orientation.Landscape, seen?.orientation)
    }

    @Test
    fun `a phone screen reaches it as a compact one held upright`() {
        var seen: Screen? = null
        var windowClass: WindowClass? = null
        open(Size(420f, 860f)) {
            seen = LocalScreen.current
            windowClass = LocalWindowClass.current
        }.settle()

        assertEquals(420f, seen?.width)
        assertEquals(WindowClass.Compact, windowClass)
        assertEquals(Orientation.Portrait, seen?.orientation)
    }

    @Test
    fun `a layout can pick a different shape from the class it is given`() {
        var shape: String? = null
        open(Size(500f, 900f)) {
            shape = when (LocalWindowClass.current) {
                WindowClass.Compact -> "stacked"
                WindowClass.Medium -> "split"
                WindowClass.Expanded -> "desktop"
            }
        }.settle()

        assertEquals("stacked", shape)
    }

    @Test
    fun `WithSize guesses on the first frame and knows the real width after it`() {
        val widths = mutableListOf<Float>()
        open(Size(1280f, 720f)) {
            Box(Modifier.width(300f)) {
                WithSize { room -> widths += room.width }
            }
        }.settle()

        // The guess is the whole screen, which is what a box filling it would get; the answer is
        // the 300 its parent really offered, and that is what the content ends up composed with.
        assertEquals(1280f, widths.first())
        assertEquals(300f, widths.last())
    }

    @Test
    fun `WithSize can be given a nearer guess for the frame before it knows`() {
        val widths = mutableListOf<Float>()
        open(Size(1280f, 720f)) {
            Box(Modifier.width(300f)) {
                WithSize(estimate = Size(300f, 0f)) { room -> widths += room.width }
            }
        }.settle()

        assertEquals(300f, widths.first())
        assertEquals(300f, widths.last())
    }
}
