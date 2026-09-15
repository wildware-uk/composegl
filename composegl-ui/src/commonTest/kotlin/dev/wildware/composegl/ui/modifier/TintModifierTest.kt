package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.rememberAnimatable
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.backend.FakeTexture
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Image
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `Modifier.tint`, on real composed UI.
 *
 * A tinted box and an untinted one of a colour that happens to match draw the same call, so every
 * question here is asked of [RecordingCanvas.tintOf]: what the call went out multiplied by.
 */
class TintModifierTest {

    private val viewport = Viewport.oneToOne(Size(640f, 360f))
    private val canvas: RecordingCanvas = HeadlessBackend().canvas
    private val host = UiHost()
    private val renderer = UiRenderer(host, canvas)
    private val focus = FocusManager(host.root)
    private var clock = 0L

    @AfterTest
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frame()
    }

    /** One frame, [millis] after the last, with only its own calls left on the canvas. */
    private fun frame(millis: Long = 16L) {
        clock += millis * 1_000_000L
        canvas.clear()
        renderer.render(viewport, nanos = clock)
    }

    private fun click(tag: String) {
        val pointer = PointerRouter(host.root, focus)
        val centre = host.root.find(tag).boundsInRoot.centre
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, centre))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, centre))
    }

    @Test
    fun `a modifier with no tint on it is white`() {
        assertEquals(Colour.White, Modifier.resolve().tint)
    }

    @Test
    fun `the tint asked for is the tint resolved`() {
        assertEquals(Colour.Red, Modifier.tint(Colour.Red).resolve().tint)
    }

    @Test
    fun `two tints on one node multiply`() {
        val modifier = Modifier.tint(Colour.rgb(0x808080)).tint(Colour.Red)

        assertEquals(Colour.rgb(0x800000), modifier.resolve().tint)
    }

    @Test
    fun `a tint at no strength resolves to no tint at all`() {
        assertEquals(Colour.White, Modifier.tint(Colour.Red.scaleAlpha(0f)).resolve().tint)
    }

    @Test
    fun `a tint does not change layout`() {
        show {
            Column {
                LeafLayout(Modifier.size(40f, 20f).tint(Colour.Red).testTag("tinted"))
                LeafLayout(Modifier.size(40f, 20f).testTag("plain"))
            }
        }

        assertEquals(Size(40f, 20f), host.root.find("tinted").boundsInRoot.size)
        assertEquals(20f, host.root.find("plain").boundsInRoot.top, "the next node is where it was")
    }

    @Test
    fun `a node's own background is drawn under its tint`() {
        show { LeafLayout(Modifier.size(40f, 20f).tint(Colour.Red).background(Colour.White)) }

        val rectangle = canvas.only<DrawCall.Rectangle>().single()
        assertEquals(Colour.White, rectangle.colour, "the colour asked for is unchanged")
        assertEquals(Colour.Red, canvas.tintOf(rectangle), "and it goes out multiplied by red")
        canvas.assertBalanced()
    }

    @Test
    fun `the tint does not reach the node drawn next to it`() {
        show {
            Box {
                LeafLayout(Modifier.size(40f, 20f).tint(Colour.Red).background(Colour.White))
                LeafLayout(Modifier.size(10f, 10f).background(Colour.White))
            }
        }

        val (tinted, plain) = canvas.only<DrawCall.Rectangle>().sortedByDescending { it.rect.width }
        assertEquals(Colour.Red, canvas.tintOf(tinted))
        assertEquals(Colour.White, canvas.tintOf(plain), "a sibling is not tinted by it")
    }

    @Test
    fun `text and borders under a tinted parent are tinted too`() {
        show {
            Box(Modifier.tint(Colour.Green).border(Colour.White, 1f).padding(4f)) {
                Text("LOCKED")
            }
        }

        val border = canvas.only<DrawCall.Border>().single()
        val text = canvas.only<DrawCall.Text>().single()
        assertEquals(Colour.Green, canvas.tintOf(border))
        assertEquals(Colour.Green, canvas.tintOf(text), "the whole subtree, not only the box")
    }

    @Test
    fun `a tint inside a tint multiplies`() {
        show {
            Box(Modifier.tint(Colour.rgb(0x808080))) {
                LeafLayout(Modifier.size(10f, 10f).tint(Colour.Red).background(Colour.White))
            }
        }

        assertEquals(Colour.rgb(0x800000), canvas.tintOf(canvas.only<DrawCall.Rectangle>().single()))
        canvas.assertBalanced()
    }

    @Test
    fun `a tint on a scaled node goes into the picture and not onto it`() {
        show {
            Box(Modifier.size(40f, 40f).tint(Colour.Red).scale(1.5f)) {
                LeafLayout(Modifier.size(10f, 10f).background(Colour.White))
            }
        }

        assertEquals(Colour.Red, canvas.tintOf(canvas.only<DrawCall.Rectangle>().single()))
        assertEquals(Colour.White, canvas.tintOf(canvas.only<DrawCall.Layer>().single()))
    }

    @Test
    fun `a tint and a blend and an alpha on one node all apply`() {
        show {
            LeafLayout(
                Modifier.size(40f, 20f).alpha(0.5f).blend(BlendMode.Additive).tint(Colour.Blue)
                    .background(Colour.White),
            )
        }

        val rectangle = canvas.only<DrawCall.Rectangle>().single()
        assertEquals(0.5f, rectangle.alpha)
        assertEquals(BlendMode.Additive, canvas.blendOf(rectangle))
        assertEquals(Colour.Blue, canvas.tintOf(rectangle))
        canvas.assertBalanced()
    }

    /**
     * The issue's own example, end to end: a click lands a hit, the bar flashes red, and the flash
     * fades back to nothing on the UI clock as frames go by.
     */
    @Test
    fun `a click flashes the bar red and the flash fades as frames pass`() {
        show { DamageFlash() }

        fun barTint() = canvas.tintOf(canvas.only<DrawCall.Rectangle>().single { it.rect.width == 200f })
        assertEquals(Colour.White, barTint(), "before any hit the bar is its own colour")

        click("hit")
        // The effect that starts the flash is launched by the frame that sees the hit, and snaps
        // the value on the frame after, so a flash shows within two frames of the click.
        var frames = 0
        while (barTint() != Colour.Red && frames < 2) {
            frame()
            frames++
        }
        assertEquals(Colour.Red, barTint(), "within $frames frames of the hit the bar is fully red")

        // Ordinary frames rather than one long one: a clock does not let a single frame jump it far.
        repeat(6) { frame() }
        val halfway = barTint()
        assertEquals(255, halfway.red)
        assertTrue(halfway.green in 60..200, "about a hundred milliseconds in it is on its way back: $halfway")
        assertEquals(halfway.green, halfway.blue)

        repeat(15) { frame() }
        assertEquals(Colour.White, barTint(), "after the flash the bar is its own colour again")
        canvas.assertBalanced()
    }

    @Test
    fun `a tinted button still takes clicks`() {
        var clicks = 0
        show { Button("USE", onClick = { clicks++ }, modifier = Modifier.tint(Colour.Grey).testTag("use")) }

        click("use")
        frame()

        assertEquals(1, clicks)
        assertNotEquals(0, canvas.calls.size)
        assertTrue(canvas.calls.all { canvas.tintOf(it) == Colour.Grey }, "every part of it is dimmed:\n$canvas")
    }

    /**
     * The harness settles every action until nothing is animating, so a flash has finished by the
     * time a click returns; the fade itself is watched frame by frame in the test above. What it is
     * good for here is the round trip: a click locks the slot, a second click unlocks it.
     */
    @Test
    fun `through the ui test harness clicking locks a slot and clicking again unlocks it`() {
        val backend = HeadlessBackend()
        uiTest(size = Size(640f, 360f), backend = backend) {
            var locked by remember { mutableStateOf(false) }
            Column {
                LeafLayout(
                    Modifier.size(60f, 60f).tint(if (locked) Colour.Grey else Colour.White)
                        .background(Colour.Orange).clickable { locked = !locked }.testTag("slot"),
                )
                LeafLayout(Modifier.size(60f, 60f).background(Colour.Orange).testTag("neighbour"))
            }
        }.use { test ->
            fun tints(): List<Colour> {
                backend.canvas.clear()
                test.render()
                return backend.canvas.only<DrawCall.Rectangle>().sortedBy { it.rect.top }.map { backend.canvas.tintOf(it) }
            }
            assertEquals(listOf(Colour.White, Colour.White), tints(), "nothing locked yet")

            test.click("slot")
            assertEquals(listOf(Colour.Grey, Colour.White), tints(), "the clicked slot is dimmed and only it")

            test.click("slot")
            assertEquals(listOf(Colour.White, Colour.White), tints(), "and a second click brings it back")
            backend.canvas.assertBalanced()
        }
    }

    @Test
    fun `through the ui test harness the pad locks a focused slot and the lock dims it`() {
        val backend = HeadlessBackend()
        uiTest(size = Size(640f, 360f), backend = backend) {
            var locked by remember { mutableStateOf(false) }
            Button(
                "SWORD",
                onClick = { locked = !locked },
                initialFocus = true,
                modifier = Modifier.tint(if (locked) Colour.Grey else Colour.White).testTag("slot"),
            )
        }.use { test ->
            test.assertFocused("slot")
            backend.canvas.clear()
            test.render()
            assertTrue(backend.canvas.calls.all { backend.canvas.tintOf(it) == Colour.White }, "unlocked")

            test.pad(GamepadButton.South)
            backend.canvas.clear()
            test.render()

            assertEquals("SWORD", test.text("slot"), "still the same slot")
            assertTrue(backend.canvas.calls.isNotEmpty())
            assertTrue(
                backend.canvas.calls.all { backend.canvas.tintOf(it) == Colour.Grey },
                "every part of the locked slot is dimmed:\n${backend.canvas}",
            )
        }
    }

    @Test
    fun `a recomposition that writes the same tint again costs the frame nothing`() {
        var ticks by mutableStateOf(0)
        var colour by mutableStateOf(Colour.Grey)
        show {
            // Read here so this scope runs again and hands the box a freshly built tint.
            ticks.let { LeafLayout(Modifier.size(40f, 20f).tint(colour).background(Colour.White)) }
        }
        frame()

        ticks++
        clock += 16_666_667L
        canvas.clear()
        assertFalse(renderer.render(viewport, clock), "an equal tint is not a change, so a still screen stays still")

        colour = Colour.Red
        clock += 16_666_667L
        canvas.clear()
        assertTrue(renderer.render(viewport, clock), "a different tint is a change")
        assertEquals(Colour.Red, canvas.tintOf(canvas.only<DrawCall.Rectangle>().single()))
    }

    @Test
    fun `a tinted image keeps its own tint and takes the one in force as well`() {
        show {
            Image(
                FakeTexture(16, 16),
                Modifier.size(16f, 16f).tint(Colour.Grey),
                tint = Colour.Red,
            )
        }

        val image = canvas.only<DrawCall.Image>().single()
        assertEquals(Colour.Red, image.tint, "the picture's own tint is passed on untouched")
        assertEquals(Colour.Grey, canvas.tintOf(image), "and the node's tint is in force over it")
        canvas.assertBalanced()
    }

    @Test
    fun `a tint on a node of no size or with nothing in it leaves the canvas balanced`() {
        show {
            Column {
                Box(Modifier.size(0f, 0f).tint(Colour.Red).background(Colour.White))
                Box(Modifier.tint(Colour.Red)) {}
                LeafLayout(Modifier.size(10f, 10f).background(Colour.White).testTag("after"))
            }
        }

        val tints = canvas.only<DrawCall.Rectangle>().filter { it.rect.width > 0f }.map { canvas.tintOf(it) }
        assertEquals(listOf(Colour.White), tints, "the node after them is its own colour")
        canvas.assertBalanced()
    }

    @Test
    fun `clicking away a tinted panel leaves nothing tinted behind it`() {
        show {
            var showing by remember { mutableStateOf(true) }
            Column {
                if (showing) {
                    Box(Modifier.tint(Colour.Red)) {
                        LeafLayout(Modifier.size(40f, 20f).background(Colour.White))
                    }
                }
                LeafLayout(Modifier.size(60f, 24f).background(Colour.White).clickable { showing = false }.testTag("close"))
            }
        }
        assertEquals(2, canvas.only<DrawCall.Rectangle>().size)

        click("close")
        frame()
        frame()

        val left = canvas.only<DrawCall.Rectangle>()
        assertEquals(1, left.size, "the panel is gone:\n$canvas")
        assertEquals(Colour.White, canvas.tintOf(left.single()), "and its tint went with it")
        canvas.assertBalanced()
    }

    @Composable
    private fun DamageFlash() {
        var hits by remember { mutableStateOf(0) }
        val flash = rememberAnimatable(0f)
        LaunchedEffect(hits) {
            if (hits > 0) {
                flash.snapTo(1f)
                flash.animateTo(0f, Tween(200, easing = Easings.Linear))
            }
        }
        Column {
            LeafLayout(
                Modifier.size(200f, 24f).tint(Colour.Red.scaleAlpha(flash.value)).background(Colour.White),
            )
            LeafLayout(Modifier.size(60f, 24f).clickable { hits++ }.testTag("hit"))
        }
    }
}
