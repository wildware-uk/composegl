package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.focus.FocusDirection
import dev.wildware.composegl.ui.focus.FocusRequester
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.clipShape
import dev.wildware.composegl.ui.modifier.mirror
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusOrder
import dev.wildware.composegl.ui.modifier.focusRequester
import dev.wildware.composegl.ui.modifier.focusTrap
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.hitShape
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `FocusOverlay`: where the pad will take focus and where a click will land, drawn over the screen
 * before anybody presses anything.
 *
 * Every test composes a real screen with [uiTest], turns the overlay on with a click, drives focus
 * with the pad and reads the arrows, outlines and tints back off a recording canvas. Where an arrow
 * points, the pad is then pressed to check focus really goes there.
 */
class FocusOverlayTest {

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

    private fun List<DrawCall>.outlines(colour: Colour) =
        filterIsInstance<DrawCall.Border>().filter { it.colour == colour }.map { it.rect }

    private fun List<DrawCall>.fills(colour: Colour) =
        filterIsInstance<DrawCall.Rectangle>().filter { it.colour == colour }.map { it.rect }

    /** Where each arrow of [colour] points: the first point of its head, a three-point fan. */
    private fun List<DrawCall>.arrowTips(colour: Colour) =
        filterIsInstance<DrawCall.Fan>().filter { it.colour == colour && it.points.size == 3 }.map { it.points[0] }

    private fun List<DrawCall>.overlayCalls() = filter {
        when (it) {
            is DrawCall.Border -> it.colour in OverlayInks
            is DrawCall.Rectangle -> it.colour in OverlayInks
            is DrawCall.Fan -> it.colour in OverlayInks
            else -> false
        }
    }

    private fun centre(ui: UiTest, tag: String) = ui.node(tag).boundsInRoot.centre

    // --- the screen ------------------------------------------------------------------------------

    /**
     * Four focusable boxes in a two by two grid, `a` focused first, and a toggle to their right that
     * can be clicked but not focused, so turning the overlay on leaves focus where it was.
     */
    @Composable
    private fun Grid(show: Set<FocusShow> = FocusShow.All, aDown: FocusRequester? = null, dRequester: FocusRequester? = null) {
        var debug by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.offset(260f, 60f).size(80f, 40f).clickable { debug = !debug }.testTag("toggle"))
            val a = Modifier.offset(40f, 60f).size(60f, 40f).focusable(initial = true).testTag("a")
            Box(if (aDown != null) a.focusOrder(down = aDown) else a)
            Box(Modifier.offset(140f, 60f).size(60f, 40f).focusable().testTag("b"))
            Box(Modifier.offset(40f, 140f).size(60f, 40f).focusable().testTag("c"))
            val d = Modifier.offset(140f, 140f).size(60f, 40f).focusable().testTag("d")
            Box(if (dRequester != null) d.focusRequester(dRequester) else d)
            FocusOverlay(debug, show)
        }
    }

    // --- tests -----------------------------------------------------------------------------------

    @Test
    fun `off it composes nothing and draws nothing`() {
        val ui = open { Grid() }

        assertNull(ui.root.firstOrNull { it.name == FocusOverlayName })
        assertTrue(drawn(ui).overlayCalls().isEmpty())
    }

    @Test
    fun `a click turns it on and arrows point where the pad would go`() {
        val ui = open { Grid() }
        ui.assertFocused("a")

        ui.click("toggle")
        ui.assertFocused("a")
        val calls = drawn(ui)

        assertEquals(setOf(centre(ui, "b"), centre(ui, "c")), calls.arrowTips(FocusOverlayColours.Geometry).toSet())
        assertEquals(2, calls.arrowTips(FocusOverlayColours.Geometry).size, "no arrow for Up or Left, where there is nothing")
        // Each arrow leaves from the middle of the edge it points out of.
        val lines = calls.filterIsInstance<DrawCall.Fan>().filter { it.colour == FocusOverlayColours.Geometry && it.points.size == 4 }
        val a = ui.node("a").boundsInRoot
        val starts = lines.map { Offset((it.points[0].x + it.points[3].x) / 2f, (it.points[0].y + it.points[3].y) / 2f) }
        assertTrue(Offset(a.right, a.centre.y) in starts, "the Right arrow from a's right edge: $starts")
        assertTrue(Offset(a.centre.x, a.bottom) in starts, "the Down arrow from a's bottom edge: $starts")

        ui.click("toggle")
        assertTrue(drawn(ui).overlayCalls().isEmpty(), "a second click takes it off")
    }

    @Test
    fun `the arrows follow focus as the pad moves it`() {
        val ui = open { Grid() }
        ui.click("toggle")

        ui.pad(GamepadButton.DpadRight)
        ui.assertFocused("b")
        val calls = drawn(ui)

        assertEquals(setOf(centre(ui, "a"), centre(ui, "d")), calls.arrowTips(FocusOverlayColours.Geometry).toSet())
        assertEquals(listOf(ui.node("b").boundsInRoot), calls.outlines(FocusOverlayColours.Focused))
        assertEquals(
            listOf("a", "c", "d").map { ui.node(it).boundsInRoot }.toSet(),
            calls.outlines(FocusOverlayColours.Focusable).toSet(),
            "the others outlined green, and nothing that is not focusable",
        )

        // The Down arrow was right: the pad goes to d.
        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("d")
    }

    @Test
    fun `a focusOrder arrow is orange and is where the pad really goes`() {
        val toD = FocusRequester()
        val ui = open { Grid(aDown = toD, dRequester = toD) }
        ui.click("toggle")
        val calls = drawn(ui)

        assertEquals(listOf(centre(ui, "d")), calls.arrowTips(FocusOverlayColours.Ordered), "Down, named by hand")
        assertEquals(listOf(centre(ui, "b")), calls.arrowTips(FocusOverlayColours.Geometry), "Right, from geometry")
        assertEquals(ui.node("d"), ui.focus.targetOf(FocusDirection.Down))

        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("d")
    }

    @Test
    fun `a focus trap is shaded and what it shuts out is grey`() {
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(10f, 10f).size(50f, 30f).clickable { debug = true }.testTag("toggle"))
                Box(Modifier.offset(10f, 60f).size(60f, 40f).focusable().testTag("behind"))
                Box(Modifier.offset(150f, 100f).size(200f, 150f).focusTrap().testTag("dialog")) {
                    Box(Modifier.offset(20f, 20f).size(60f, 30f).focusable().testTag("ok"))
                    Box(Modifier.offset(110f, 20f).size(60f, 30f).focusable().testTag("cancel"))
                }
                FocusOverlay(debug)
            }
        }
        ui.click("toggle")
        ui.assertFocused("ok")
        val calls = drawn(ui)

        assertEquals(listOf(ui.node("dialog").boundsInRoot), calls.fills(FocusOverlayColours.Trap))
        assertEquals(listOf(ui.node("behind").boundsInRoot), calls.outlines(FocusOverlayColours.Unreachable))
        assertEquals(listOf(ui.node("cancel").boundsInRoot), calls.outlines(FocusOverlayColours.Focusable))
        assertEquals(listOf(centre(ui, "cancel")), calls.arrowTips(FocusOverlayColours.Geometry), "only Right, inside the trap")

        // Left has no arrow, and the pad agrees: it cannot walk out to the box behind.
        ui.pad(GamepadButton.DpadLeft)
        ui.assertFocused("ok")
    }

    @Test
    fun `clickable areas are tinted and scenery is not`() {
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(10f, 10f).size(50f, 30f).clickable { debug = true }.testTag("toggle"))
                Box(Modifier.offset(100f, 100f).size(70f, 40f).testTag("scenery"))
                FocusOverlay(debug, setOf(FocusShow.HitAreas))
            }
        }
        ui.click("toggle")
        val calls = drawn(ui)

        assertEquals(listOf(ui.node("toggle").boundsInRoot), calls.fills(FocusOverlayColours.HitArea))
        assertTrue(calls.fills(FocusOverlayColours.Hole).isEmpty())
        assertTrue(calls.arrowTips(FocusOverlayColours.Geometry).isEmpty(), "show picks what is drawn")
        assertTrue(calls.filterIsInstance<DrawCall.Border>().none { it.colour in OverlayInks })
    }

    @Test
    fun `a hitShape's corners are shown as holes and a click there really misses`() {
        var clicks = 0
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(10f, 10f).size(50f, 30f).clickable { debug = true }.testTag("toggle"))
                Box(Modifier.offset(100f, 100f).size(80f, 80f).hitShape(Shapes.Circle).clickable { clicks++ }.testTag("round"))
                FocusOverlay(debug, setOf(FocusShow.HitAreas))
            }
        }
        ui.click("toggle")
        val calls = drawn(ui)
        val round = ui.node("round").boundsInRoot
        val tinted = calls.fills(FocusOverlayColours.HitArea).filter { it.overlaps(round) }
        val holes = calls.fills(FocusOverlayColours.Hole)

        val corner = Offset(102f, 102f)
        val middle = round.centre
        assertTrue(holes.any { corner in it }, "the corner is a hole: $holes")
        assertTrue(tinted.none { corner in it }, "and not tinted")
        assertTrue(tinted.any { middle in it }, "the middle is tinted")
        assertTrue(holes.none { middle in it }, "and not a hole")
        val covered = (tinted + holes).sumOf { (it.width * it.height).toDouble() }
        assertEquals(80.0 * 80.0, covered, 0.01, "tint and holes together cover the box once")

        // The picture is the truth: the corner does not click, the middle does.
        ui.click(corner)
        assertEquals(0, clicks)
        ui.click(middle)
        assertEquals(1, clicks)
    }

    @Test
    fun `a clickable hanging out of a clip is tinted only where it can be reached`() {
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(10f, 10f).size(50f, 30f).clickable { debug = true }.testTag("toggle"))
                Box(Modifier.offset(100f, 100f).size(50f, 30f).clip()) {
                    Box(Modifier.offset(30f, 0f).size(50f, 30f).clickable { }.testTag("wide"))
                }
                FocusOverlay(debug, setOf(FocusShow.HitAreas))
            }
        }
        ui.click("toggle")

        assertEquals(Rect(130f, 100f, 180f, 130f), ui.node("wide").boundsInRoot)
        val tints = drawn(ui).fills(FocusOverlayColours.HitArea)
        assertTrue(Rect(130f, 100f, 150f, 130f) in tints, "$tints")
        assertFalse(ui.node("wide").boundsInRoot in tints, "not the part the clip cut off")
        assertFalse(ui.click(Offset(170f, 115f)), "past the clip nothing takes the click, as the tint says")
    }

    @Test
    fun `a focusable box with no click is not tinted and a click there is not taken`() {
        val ui = open { Grid(show = setOf(FocusShow.HitAreas)) }
        ui.click("toggle")

        val tints = drawn(ui).fills(FocusOverlayColours.HitArea)
        assertEquals(listOf(ui.node("toggle").boundsInRoot), tints)
        assertFalse(ui.click("c"), "the picture is the truth: nothing takes it")
        ui.assertFocused("a")
    }

    @Test
    fun `a round clip above a clickable cuts holes in its tint where clicks miss`() {
        var clicks = 0
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(10f, 10f).size(50f, 30f).clickable { debug = true }.testTag("toggle"))
                Box(Modifier.offset(100f, 100f).size(80f, 80f).clipShape(Shapes.Circle)) {
                    Box(Modifier.fillMaxSize().clickable { clicks++ }.testTag("inside"))
                }
                FocusOverlay(debug, setOf(FocusShow.HitAreas))
            }
        }
        ui.click("toggle")
        val calls = drawn(ui)
        val inside = ui.node("inside").boundsInRoot
        val tinted = calls.fills(FocusOverlayColours.HitArea).filter { it.overlaps(inside) }
        val holes = calls.fills(FocusOverlayColours.Hole)

        val corner = Offset(102f, 102f)
        assertTrue(holes.any { corner in it }, "the parent's clip cuts the corner: $holes")
        assertTrue(tinted.none { corner in it })
        assertTrue(tinted.any { inside.centre in it })

        assertFalse(ui.click(corner))
        assertEquals(0, clicks)
        ui.click(inside.centre)
        assertEquals(1, clicks)
    }

    @Test
    fun `a clickable hanging out of a mirrored panel is tinted only where it can be reached`() {
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(10f, 10f).size(50f, 30f).clickable { debug = true }.testTag("toggle"))
                Box(Modifier.offset(100f, 100f).size(50f, 30f).mirror()) {
                    Box(Modifier.offset(30f, 0f).size(50f, 30f).clickable { }.testTag("wide"))
                }
                FocusOverlay(debug, setOf(FocusShow.HitAreas))
            }
        }
        ui.click("toggle")

        val wide = ui.node("wide").boundsInRoot
        val panel = Rect(100f, 100f, 150f, 130f)
        val reachable = wide.intersect(panel)
        val tints = drawn(ui).fills(FocusOverlayColours.HitArea)
        assertTrue(reachable in tints, "$tints, $wide")
        assertFalse(wide in tints, "not the part the mirror's capture cuts off")
        val outside = if (wide.left < panel.left) Offset(wide.left + 2f, 115f) else Offset(wide.right - 2f, 115f)
        assertFalse(ui.click(outside), "outside the panel nothing takes the click, as the tint says")
        assertTrue(ui.click(reachable.centre))
    }

    @Test
    fun `a focus manager built after it is on is the one it follows`() {
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(40f, 60f).size(60f, 40f).focusable().testTag("a"))
                Box(Modifier.offset(140f, 60f).size(60f, 40f).focusable().testTag("b"))
                FocusOverlay(true)
            }
        }
        // The game's own manager, built once the screen is already up with the overlay on.
        val later = FocusManager(ui.root, autoFocus = false)
        later.focusOn(ui.node("a"))
        ui.render()
        ui.render()
        assertFalse(ui.render(), "still")

        later.moveFocus(FocusDirection.Right)
        assertTrue(ui.render(), "the later manager's move redraws")
        assertEquals(listOf(centre(ui, "a")), drawn(ui).arrowTips(FocusOverlayColours.Geometry))
    }

    @Test
    fun `a focus manager built on a still screen redraws on its very first move`() {
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(40f, 60f).size(60f, 40f).focusable().testTag("a"))
                Box(Modifier.offset(140f, 60f).size(60f, 40f).focusable().testTag("b"))
                FocusOverlay(true)
            }
        }
        ui.render()
        assertFalse(ui.render(), "still")

        // Built and moved with no frame between, so no draw has had the chance to find it.
        val later = FocusManager(ui.root, autoFocus = false)
        later.focusOn(ui.node("a"))
        assertTrue(ui.render(), "the first focus of the new manager is drawn")
        assertEquals(listOf(ui.node("a").boundsInRoot), drawn(ui).outlines(FocusOverlayColours.Focused))
        assertFalse(ui.render(), "and then it is still again")
    }

    @Test
    fun `turning it on moves nothing and clicks go straight through it`() {
        var clicks = 0
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(10f, 10f).size(50f, 30f).clickable { debug = true }.testTag("toggle"))
                Box(Modifier.offset(100f, 100f).size(60f, 40f).clickable { clicks++ }.testTag("button"))
                FocusOverlay(debug)
            }
        }
        val before = ui.node("button").boundsInRoot
        ui.click("toggle")

        assertEquals(before, ui.node("button").boundsInRoot)
        val overlay = ui.root.firstOrNull { it.name == FocusOverlayName }!!
        assertEquals(0f, overlay.width)
        assertEquals(0f, overlay.height)
        assertTrue(ui.click("button"))
        assertEquals(1, clicks)
    }

    @Test
    fun `a focus move redraws the screen while it is on even when nothing else changes`() {
        var debug by mutableStateOf(false)
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(40f, 60f).size(60f, 40f).focusable(initial = true).testTag("a"))
                Box(Modifier.offset(140f, 60f).size(60f, 40f).focusable().testTag("b"))
                FocusOverlay(debug)
            }
        }
        ui.render()
        assertFalse(ui.render(), "a still frame")
        // Plain boxes have no focus look, so without the overlay a move changes nothing on screen.
        ui.focus.moveFocus(FocusDirection.Right)
        assertFalse(ui.render(), "off, a focus move on bare boxes is not a change")

        debug = true
        ui.settle()
        ui.render()
        assertFalse(ui.render(), "on and still")
        ui.focus.moveFocus(FocusDirection.Left)
        assertTrue(ui.render(), "on, the move redraws")
        assertEquals(listOf(centre(ui, "b")), drawn(ui).arrowTips(FocusOverlayColours.Geometry))

        // Taken off, it stops listening.
        debug = false
        ui.settle()
        ui.render()
        ui.focus.moveFocus(FocusDirection.Right)
        assertFalse(ui.render(), "off again, the move is quiet again")
    }

    @Test
    fun `a still screen with it on is not redrawn`() {
        val ui = open { Grid() }
        ui.click("toggle")
        ui.render()

        assertFalse(ui.render())
        assertFalse(ui.render())
        assertTrue(drawn(ui).arrowTips(FocusOverlayColours.Geometry).isNotEmpty(), "and it is still drawing")
    }

    @Test
    fun `an equal show set built again on recomposition keeps a still screen still`() {
        var tick by mutableStateOf(0)
        var compositions = 0
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(40f, 60f).size(60f, 40f).focusable(initial = true).testTag("a"))
                Box(Modifier.offset(140f, 60f).size(60f, 40f).focusable().testTag("b"))
                compositions++
                // Built again from the state on every pass, so each one is a different object.
                FocusOverlay(true, FocusShow.entries.filter { tick >= 0 && it != FocusShow.Traps }.toSet())
            }
        }
        ui.render()
        assertFalse(ui.render(), "still")

        val overlay = ui.root.firstOrNull { it.name == FocusOverlayName }!!
        val drawing = overlay.content
        val before = compositions
        tick++
        ui.settle()
        assertTrue(compositions > before, "the screen really recomposed")
        assertSame(drawing, overlay.content, "the same drawing, so the tree was not told it changed")
        assertFalse(ui.render(), "a new set with the same things in it is not a change")
        assertEquals(1, ui.focus.movedListenerCount, "and it still listens once, not twice")
    }

    @Test
    fun `changing what it shows swaps the drawing and leaves no listener behind`() {
        var show by mutableStateOf(FocusShow.All)
        var debug by mutableStateOf(true)
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(40f, 60f).size(60f, 40f).focusable(initial = true).testTag("a"))
                Box(Modifier.offset(140f, 60f).size(60f, 40f).clickable { }.focusable().testTag("b"))
                FocusOverlay(debug, show)
            }
        }
        assertEquals(listOf(centre(ui, "b")), drawn(ui).arrowTips(FocusOverlayColours.Geometry))

        show = setOf(FocusShow.HitAreas)
        ui.settle()
        val calls = drawn(ui)
        assertTrue(calls.arrowTips(FocusOverlayColours.Geometry).isEmpty(), "the arrows went with the old set")
        assertEquals(listOf(ui.node("b").boundsInRoot), calls.fills(FocusOverlayColours.HitArea))
        assertEquals(1, ui.focus.movedListenerCount, "the old drawing stopped listening")
        ui.focus.moveFocus(FocusDirection.Right)
        assertTrue(ui.render(), "the new one listens")

        debug = false
        ui.settle()
        ui.render()
        assertTrue(ui.focus.movedListenerCount == 0, "off, nothing is left listening")
        ui.focus.moveFocus(FocusDirection.Left)
        assertFalse(ui.render())
    }

    @Test
    fun `it and the layout overlay leave each other out`() {
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(40f, 60f).size(60f, 40f).focusable(initial = true).testTag("a"))
                // A wrapper with a size of its own, so the only zero-size node is the focus overlay.
                Box(Modifier.offset(120f, 60f).size(40f, 40f)) { FocusOverlay(true) }
                LayoutOverlay(true, setOf(Show.Bounds))
            }
        }
        val calls = drawn(ui)

        assertTrue(calls.fills(LayoutOverlayColours.Bounds).isEmpty(), "no one-unit dot for the focus overlay")
        assertEquals(listOf(ui.node("a").boundsInRoot), calls.outlines(FocusOverlayColours.Focused))
    }

    private companion object {
        val OverlayInks = setOf(
            FocusOverlayColours.Geometry,
            FocusOverlayColours.Ordered,
            FocusOverlayColours.Focusable,
            FocusOverlayColours.Focused,
            FocusOverlayColours.Unreachable,
            FocusOverlayColours.Trap,
            FocusOverlayColours.HitArea,
            FocusOverlayColours.Hole,
        )
    }
}
