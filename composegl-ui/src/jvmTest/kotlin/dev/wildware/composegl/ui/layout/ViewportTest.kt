package dev.wildware.composegl.ui.layout

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ViewportTest {

    private val design = Size(1280f, 720f)

    private fun viewport(
        width: Float,
        height: Float,
        policy: ScalePolicy = ScalePolicy.Fit,
        safeArea: Padding = Padding.None,
    ) = Viewport(design, Size(width, height), policy, safeArea)

    // --- scaling ---

    @Test
    fun `at the design resolution nothing is scaled`() {
        val viewport = viewport(1280f, 720f)

        assertEquals(1f, viewport.scaleX)
        assertEquals(Offset.Zero, viewport.origin)
    }

    @Test
    fun `fit scales the whole thing up`() {
        val viewport = viewport(3840f, 2160f)

        assertEquals(3f, viewport.scaleX)
        assertEquals(3f, viewport.scaleY)
        assertEquals(Offset.Zero, viewport.origin)
    }

    @Test
    fun `fit puts bars on the two long sides`() {
        // 21:9, so the height runs out first and the bars are left and right.
        val viewport = viewport(2560f, 720f)

        assertEquals(1f, viewport.scaleY)
        assertEquals(640f, viewport.origin.x)
        assertEquals(0f, viewport.origin.y)
    }

    @Test
    fun `fill covers the screen and loses the edges`() {
        val viewport = viewport(2560f, 720f, ScalePolicy.Fill)

        assertEquals(2f, viewport.scaleX)
        // Half the overflow off each end of the height.
        assertEquals(-360f, viewport.origin.y)
    }

    @Test
    fun `stretch bends each axis on its own`() {
        val viewport = viewport(2560f, 720f, ScalePolicy.Stretch)

        assertEquals(2f, viewport.scaleX)
        assertEquals(1f, viewport.scaleY)
        assertEquals(Offset.Zero, viewport.origin)
    }

    @Test
    fun `integer scaling never produces a fraction`() {
        val screens = listOf(
            1280f to 720f,
            1600f to 900f,
            1920f to 1080f,
            2000f to 1300f,
            3840f to 2160f,
        )

        screens.forEach { (width, height) ->
            val scale = viewport(width, height, ScalePolicy.Integer).scaleX
            assertEquals(scale, floorOf(scale), "$width x $height scaled by $scale")
        }
    }

    @Test
    fun `integer scaling takes the largest whole multiple that fits`() {
        assertEquals(1f, viewport(1600f, 900f, ScalePolicy.Integer).scaleX)
        assertEquals(2f, viewport(2560f, 1440f, ScalePolicy.Integer).scaleX)
        assertEquals(2f, viewport(2600f, 1500f, ScalePolicy.Integer).scaleX)
    }

    @Test
    fun `integer scaling never disappears on a screen too small for one multiple`() {
        assertEquals(1f, viewport(640f, 360f, ScalePolicy.Integer).scaleX)
    }

    private fun floorOf(value: Float) = value.toInt().toFloat()

    // --- the safe area ---

    @Test
    fun `an inset moves content out from under a notch`() {
        val viewport = viewport(1280f, 720f, safeArea = Padding(left = 100f))

        assertEquals(100f, viewport.contentOrigin.x)
        assertEquals(1180f, viewport.contentSize.width)
    }

    @Test
    fun `an inset is measured in the interface's own units, not the screen's`() {
        // Twice the size, so 100 real pixels of notch are 50 of ours.
        val viewport = viewport(2560f, 1440f, safeArea = Padding(left = 100f))

        assertEquals(50f, viewport.contentOrigin.x)
    }

    @Test
    fun `an inset already covered by a bar costs nothing`() {
        // 640 pixels of bar down each side; the notch is well inside it.
        val viewport = viewport(2560f, 720f, safeArea = Padding(left = 100f))

        assertEquals(0f, viewport.contentOrigin.x)
        assertEquals(1280f, viewport.contentSize.width)
    }

    @Test
    fun `an inset deeper than the bar costs only the difference`() {
        val viewport = viewport(2560f, 720f, safeArea = Padding(left = 700f))

        assertEquals(60f, viewport.contentOrigin.x)
    }

    // --- screen and interface coordinates ---

    @Test
    fun `a screen position becomes an interface position`() {
        val viewport = viewport(2560f, 720f)

        assertEquals(Offset(0f, 0f), viewport.toDesign(Offset(640f, 0f)))
        assertEquals(Offset(640f, 360f), viewport.toDesign(Offset(1280f, 360f)))
    }

    @Test
    fun `the two directions agree`() {
        val viewport = viewport(3000f, 1500f)
        val point = Offset(123f, 456f)

        val roundTrip = viewport.toDesign(viewport.toScreen(point))

        assertEquals(point.x, roundTrip.x, 0.001f)
        assertEquals(point.y, roundTrip.y, 0.001f)
    }

    // --- what it is all for ---

    @Test
    fun `the same interface lays out identically at 720p and 4K`() {
        fun layoutAt(width: Float, height: Float): String {
            val tree = UiTree()
            val panel = UiNode("panel").also {
                it.modifier = Modifier.size(400f, 200f)
                it.measurePolicy = BoxPolicy(Alignment.Centre)
                it.insertAt(0, UiNode("label").also { label ->
                    label.modifier = Modifier.size(100f, 20f)
                    label.measurePolicy = MeasurePolicy.Empty
                })
            }
            val screen = UiNode("screen").also {
                it.modifier = Modifier.fillMaxSize()
                it.measurePolicy = BoxPolicy(Alignment.Centre)
                it.insertAt(0, panel)
            }
            tree.root.insertAt(0, screen)
            MeasurePass().run(tree.root, viewport(width, height))
            return tree.root.debugTree()
        }

        assertEquals(layoutAt(1280f, 720f), layoutAt(3840f, 2160f))
    }

    @Test
    fun `the root fills what the safe area leaves`() {
        val tree = UiTree()
        val screen = UiNode("screen").also {
            it.modifier = Modifier.fillMaxSize()
            it.measurePolicy = MeasurePolicy.Empty
        }
        tree.root.insertAt(0, screen)

        MeasurePass().run(tree.root, viewport(1280f, 720f, safeArea = Padding(80f, 40f, 80f, 40f)))

        assertEquals(1120f, tree.root.width)
        assertEquals(640f, tree.root.height)
        assertEquals(80f, tree.root.x)
        assertEquals(40f, tree.root.y)
    }
}
