package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.ArtAtlas
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.skin.ProvideSkin
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Animated pictures on a composed screen, with time passed a frame at a time and buttons pressed
 * the way a player presses them, judged by which picture reached the canvas.
 */
class AnimatedImageUiTest {

    /** A frame that is only equal to itself, so the canvas can say which one it was handed. */
    private class Frame(val name: String, override val width: Int = 16, override val height: Int = 16) :
        TextureHandle {
        override fun toString() = name
    }

    private val red = Frame("red")
    private val green = Frame("green")
    private val blue = Frame("blue")
    private val strip = listOf(red, green, blue)

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    /** The one picture [tag] drew, read off a recording of just that node. */
    private fun UiTest.drawn(tag: String): TextureHandle = drawCall(tag).texture

    /** The whole call [tag] drew its picture with: where, and which part of the texture. */
    private fun UiTest.drawCall(tag: String): DrawCall.Image {
        val node = node(tag)
        val canvas = RecordingCanvas(Rect.of(0f, 0f, size.width, size.height))
        val bounds = node.layoutBoundsInRoot
        DrawPass(canvas).draw(node, bounds.left - node.x, bounds.top - node.y)
        return canvas.calls.filterIsInstance<DrawCall.Image>().single()
    }

    /*
     * Most of these play at two pictures a second. Each action settles the screen afterwards, which
     * is a few frames more than asked for, and at two a second that drift can never carry a check
     * across a boundary between pictures.
     */

    @Test
    fun `the pictures play in turn and go round again`() {
        val ui = open {
            AnimatedImage(rememberSpriteAnimation(strip, fps = 2f), Modifier.testTag("coin"))
        }

        val seen = mutableListOf(ui.drawn("coin"))
        repeat(4) {
            ui.advanceBy(500)
            seen += ui.drawn("coin")
        }

        assertEquals(listOf<TextureHandle>(red, green, blue, red, green), seen)
    }

    @Test
    fun `frames named by a prefix come out of the skin's atlas in number order`() {
        val frames = (0..10).map { Frame("coin_$it") }
        val atlas = ArtAtlas.of(frames.reversed().associateBy { it.name } + ("coin_shadow" to Frame("shadow")))
        val ui = open {
            ProvideSkin(Skin(art = atlas)) {
                AnimatedImage(rememberSpriteAnimation(prefix = "coin_", fps = 2f), Modifier.testTag("coin"))
            }
        }

        ui.advanceBy(5_000)

        assertSame(frames[10], ui.drawn("coin"), "coin_10 is the eleventh picture, not the third")
        ui.advanceBy(500)
        assertSame(frames[0], ui.drawn("coin"), "and after it comes coin_0, not the shadow")
    }

    @Test
    fun `a pause button stops an animation on the world clock and it carries on from the same picture`() {
        // Seven pictures, so carrying on (one or two pictures later) and jumping ahead by the
        // two seconds spent paused (five or six later) can never land on the same one.
        val torch = (0 until 7).map { Frame("torch$it") }
        val ui = open {
            val clocks = LocalClocks.current
            var paused by remember { mutableStateOf(false) }
            Column {
                Button(
                    if (paused) "RESUME" else "PAUSE",
                    onClick = {
                        paused = !paused
                        clocks.setRunning(Clock.World, !paused)
                    },
                    initialFocus = true,
                    modifier = Modifier.testTag("pause"),
                )
                AnimatedImage(
                    rememberSpriteAnimation(torch, fps = 2f, clock = Clock.World),
                    Modifier.testTag("torch"),
                )
                AnimatedImage(rememberSpriteAnimation(strip, fps = 2f), Modifier.testTag("menu"))
            }
        }

        ui.advanceBy(500)
        ui.click("pause")
        val frozen = ui.drawn("torch")
        val menuBefore = ui.drawn("menu")

        ui.advanceBy(2_000)

        assertEquals("RESUME", ui.text("pause"))
        assertSame(frozen, ui.drawn("torch"), "the world is paused, so the torch is too")
        assertTrue(ui.drawn("menu") !== menuBefore, "the menu's spinner is on the interface's clock and kept going")

        ui.pad(GamepadButton.South)
        ui.advanceBy(500)

        assertEquals("PAUSE", ui.text("pause"))
        val moved = (torch.indexOf(ui.drawn("torch")) - torch.indexOf(frozen) + torch.size) % torch.size
        assertTrue(moved in 1..2, "expected the torch one or two pictures on from where it stopped, it moved $moved")
    }

    @Test
    fun `the ui clock keeps playing while the world is paused`() {
        val ui = open {
            AnimatedImage(rememberSpriteAnimation(strip, fps = 2f), Modifier.testTag("spinner"))
        }
        ui.host.clocks.stop(Clock.World)

        ui.advanceBy(500)

        assertSame(green, ui.drawn("spinner"))
    }

    @Test
    fun `a one-shot stops on its last picture and says so once`() {
        var finishedCalls = 0
        lateinit var animation: SpriteAnimation
        val ui = open {
            animation = rememberSpriteAnimation(strip, fps = 10f, loop = false)
            Column {
                Button("REPLAY", onClick = { animation.restart() }, modifier = Modifier.testTag("replay"))
                AnimatedImage(animation, Modifier.testTag("boom"), onFinished = { finishedCalls++ })
            }
        }

        // The harness waits for a one-shot the way it waits for a fade.
        assertTrue(animation.isFinished)
        assertSame(blue, ui.drawn("boom"))
        assertEquals(1, finishedCalls)

        ui.advanceBy(1_000)
        assertSame(blue, ui.drawn("boom"), "it stays on the last picture")
        assertEquals(1, finishedCalls, "and does not finish again")

        ui.click("replay")

        assertTrue(animation.isFinished, "replayed to the end before the click returned")
        assertEquals(2, finishedCalls)
    }

    @Test
    fun `a replay click starts again from the first picture`() {
        lateinit var animation: SpriteAnimation
        val ui = open {
            animation = rememberSpriteAnimation(strip, fps = 10f, loop = false, clock = Clock.World)
            Column {
                Button("REPLAY", onClick = { animation.restart() }, modifier = Modifier.testTag("replay"))
                AnimatedImage(animation, Modifier.testTag("boom"))
            }
        }
        assertSame(blue, ui.drawn("boom"))

        // Paused, so the replay can be looked at before it plays out.
        ui.host.clocks.stop(Clock.World)
        ui.click("replay")

        assertSame(red, ui.drawn("boom"))
        assertFalse(animation.isFinished)
    }

    @Test
    fun `a loop only changes the screen when the picture changes`() {
        val ui = open {
            AnimatedImage(rememberSpriteAnimation(strip, fps = 10f), Modifier.testTag("coin"))
        }
        val before = ui.host.changedFrames

        ui.advanceBy(1_000)

        val changed = ui.host.changedFrames - before
        // Sixty frames went by, and ten of them put a new picture up.
        assertTrue(changed in 9..12, "expected about ten changed frames in a second, got $changed")
    }

    @Test
    fun `a finished one-shot costs nothing`() {
        val ui = open {
            AnimatedImage(rememberSpriteAnimation(strip, fps = 10f, loop = false), Modifier.testTag("boom"))
        }
        val before = ui.host.changedFrames

        ui.advanceBy(1_000)

        assertEquals(before, ui.host.changedFrames)
        assertFalse(ui.host.clocks.isAnimating)
    }

    @Test
    fun `the box is the largest frame and does not move as the pictures change`() {
        val trimmed = listOf(Frame("small", 10, 20), Frame("wide", 30, 8), Frame("tall", 12, 24))
        val ui = open {
            AnimatedImage(rememberSpriteAnimation(trimmed, fps = 2f), Modifier.testTag("spin"))
        }

        val boxes = (0 until 3).map {
            val bounds = ui.node("spin").boundsInRoot
            ui.advanceBy(500)
            bounds.width to bounds.height
        }

        assertEquals(List(3) { 30f to 24f }, boxes)
    }

    @Test
    fun `new frames start from the first picture`() {
        val other = listOf(Frame("a"), Frame("b"))
        val ui = open {
            var which by remember { mutableStateOf(strip) }
            Column {
                Button("SWAP", onClick = { which = other }, modifier = Modifier.testTag("swap"))
                AnimatedImage(rememberSpriteAnimation(which, fps = 2f, clock = Clock.World), Modifier.testTag("coin"))
            }
        }
        ui.advanceBy(500)
        assertSame(green, ui.drawn("coin"))

        ui.host.clocks.stop(Clock.World)
        ui.click("swap")

        assertSame(other[0], ui.drawn("coin"))
    }

    @Test
    fun `trimmed frames are all scaled by the same amount`() {
        // Twice the largest frame's box, so every frame should come out at twice its own size. Fitted
        // one at a time, the small frames would be blown up further than the big one and the coin
        // would pulse as it turned.
        val trimmed = listOf(Frame("small", 10, 20), Frame("wide", 30, 8), Frame("tall", 12, 24))
        val ui = open {
            AnimatedImage(rememberSpriteAnimation(trimmed, fps = 2f), Modifier.size(60f, 48f).testTag("spin"))
        }
        val box = ui.node("spin").boundsInRoot

        val sizes = (0 until 3).map {
            val call = ui.drawCall("spin")
            ui.advanceBy(500)
            call.destination.width to call.destination.height
        }
        assertEquals(listOf(20f to 40f, 60f to 16f, 24f to 48f), sizes)

        // Each still centred in the box.
        ui.advanceBy(1_000)
        val small = ui.drawCall("spin").destination
        assertEquals(box.centre, small.centre)
    }

    @Test
    fun `a strip of one size lands exactly where an image of it would for every fit`() {
        val wide = Frame("wide", 40, 20)
        for (fit in ImageFit.entries) {
            for (alignment in listOf(Alignment.Centre, Alignment.TopStart, Alignment.BottomEnd)) {
                val ui = open {
                    Column {
                        Image(wide, Modifier.size(30f, 30f).testTag("image"), fit = fit, alignment = alignment)
                        AnimatedImage(
                            rememberSpriteAnimation(listOf(wide, wide), fps = 2f),
                            Modifier.size(30f, 30f).testTag("animated"),
                            fit = fit,
                            alignment = alignment,
                        )
                    }
                }
                val image = ui.drawCall("image")
                val animated = ui.drawCall("animated")
                val dy = ui.node("animated").boundsInRoot.top - ui.node("image").boundsInRoot.top

                assertEquals(image.destination.translate(Offset(0f, dy)), animated.destination, "$fit $alignment")
                assertEquals(image.source, animated.source, "$fit $alignment")
            }
        }
    }

    @Test
    fun `a faster button carries on from the same picture rather than starting again`() {
        val torch = (0 until 8).map { Frame("torch$it") }
        val ui = open {
            var fps by remember { mutableStateOf(2f) }
            Column {
                Button("FASTER", onClick = { fps = 4f }, modifier = Modifier.testTag("faster"))
                AnimatedImage(rememberSpriteAnimation(torch, fps = fps), Modifier.testTag("torch"))
            }
        }
        ui.advanceBy(1_700)
        val before = torch.indexOf(ui.drawn("torch"))
        assertTrue(before >= 3, "three pictures in at two a second, got $before")

        ui.click("faster")
        assertTrue(torch.indexOf(ui.drawn("torch")) >= before, "it did not jump back to the start")

        val at = torch.indexOf(ui.drawn("torch"))
        ui.advanceBy(500)
        val moved = (torch.indexOf(ui.drawn("torch")) - at + torch.size) % torch.size
        assertTrue(moved in 2..3, "four a second moves two pictures in half a second, it moved $moved")
    }

    @Test
    fun `hiding a one-shot part way through lets the screen settle`() {
        val long = (0 until 6).map { Frame("boom$it") }
        val ui = open {
            val clocks = LocalClocks.current
            // Held until the test says, so the one-shot is still playing when it is hidden.
            remember { clocks.stop(Clock.World) }
            var shown by remember { mutableStateOf(true) }
            Column {
                Button("HIDE", onClick = { shown = false }, modifier = Modifier.testTag("hide"))
                if (shown) {
                    AnimatedImage(
                        rememberSpriteAnimation(long, fps = 2f, loop = false, clock = Clock.World),
                        Modifier.testTag("boom"),
                    )
                }
            }
        }

        ui.click("hide")
        ui.host.clocks.start(Clock.World)
        ui.settle()

        assertFalse(ui.host.clocks.isAnimating, "a one-shot nobody can see is not still playing")
    }

    @Test
    fun `a finished one-shot shown again does not say it finished again`() {
        var finishedCalls = 0
        val ui = open {
            val animation = rememberSpriteAnimation(strip, fps = 10f, loop = false)
            var shown by remember { mutableStateOf(true) }
            Column {
                Button("TOGGLE", onClick = { shown = !shown }, modifier = Modifier.testTag("toggle"))
                if (shown) AnimatedImage(animation, Modifier.testTag("boom"), onFinished = { finishedCalls++ })
            }
        }
        assertEquals(1, finishedCalls)

        ui.click("toggle")
        ui.click("toggle")

        assertSame(blue, ui.drawn("boom"), "back on its last picture")
        assertEquals(1, finishedCalls)
    }

    @Test
    fun `a loop switched to a one-shot stops on its last picture`() {
        var finished = false
        val ui = open {
            var loop by remember { mutableStateOf(true) }
            Column {
                Button("ONCE", onClick = { loop = false }, modifier = Modifier.testTag("once"))
                AnimatedImage(
                    rememberSpriteAnimation(strip, fps = 2f, loop = loop),
                    Modifier.testTag("coin"),
                    onFinished = { finished = true },
                )
            }
        }
        ui.advanceBy(3_000)
        assertFalse(finished)

        ui.click("once")
        ui.advanceBy(1_000)

        assertTrue(finished)
        assertSame(blue, ui.drawn("coin"))
    }

    @Test
    fun `an empty strip or a rate of nothing is refused`() {
        assertFailsWith<IllegalArgumentException> { open { rememberSpriteAnimation(emptyList(), fps = 2f) } }
        assertFailsWith<IllegalArgumentException> { open { rememberSpriteAnimation(strip, fps = 0f) } }
    }
}
