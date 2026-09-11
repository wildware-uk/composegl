package composegl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import composegl.ui.backend.MonospaceFontProvider
import composegl.ui.draw.DrawPass
import composegl.ui.game.Bar
import composegl.ui.game.Reticle
import composegl.ui.game.rememberReticleState
import composegl.ui.geometry.Rect
import composegl.ui.graphics.Colour
import composegl.ui.graphics.TextureHandle
import composegl.ui.host.UiHost
import composegl.ui.layout.Alignment
import composegl.ui.layout.Arrangement
import composegl.ui.layout.Box
import composegl.ui.layout.Column
import composegl.ui.layout.Constraints
import composegl.ui.layout.MeasurePass
import composegl.ui.layout.Row
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.align
import composegl.ui.modifier.fillMaxSize
import composegl.ui.modifier.fillMaxWidth
import composegl.ui.modifier.padding
import composegl.ui.modifier.width
import composegl.ui.widget.Button
import composegl.ui.widget.Panel
import composegl.ui.widget.ProvideFonts
import composegl.ui.widget.Text
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

/**
 * What a whole screen costs when nothing on it is changing.
 *
 * The claim this project is built on is that an interface which is not moving costs almost nothing
 * a frame. Two widgets have their own allocation tests; this one asks it of a screen — a HUD with
 * panels, text, bars, buttons and a crosshair on it — because a cost per frame that only appears
 * when there are twenty widgets is exactly the cost a per-widget test cannot see.
 *
 * Two separate questions, and they fail differently:
 *
 *  - a still screen asks the runtime for no frames at all, so a game's own loop skips the whole of
 *    layout and drawing;
 *  - and drawing one anyway — which a game does every frame if it never checks — allocates a
 *    handful of small objects rather than a pile of them.
 *
 * The numbers are deliberately loose. This runs on whatever JVM CI has, and what is being caught
 * is an order of magnitude — a lambda made per widget per frame, a list rebuilt per frame, a string
 * formatted per frame — and not a byte.
 */
class FrameCostTest {

    private val host = UiHost()
    private val bounds = Rect.of(0f, 0f, 1280f, 720f)

    private var wall = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private var hull by mutableFloatStateOf(0.8f)
    private var ammo by mutableStateOf(148)

    /** A combat HUD: about twenty widgets, the sort of thing a game actually leaves on screen. */
    private fun hud() {
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                Box(Modifier.fillMaxSize()) {
                    val reticle = rememberReticleState()
                    Reticle(reticle, Modifier.align(Alignment.Centre))

                    Panel(Modifier.align(Alignment.BottomStart).padding(left = 28f, bottom = 28f).width(280f)) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f)) {
                            Text("HULL")
                            Bar(hull, Modifier.fillMaxWidth())
                            Text("HEAT")
                            Bar(0.24f, Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("AMMO")
                                Text("$ammo")
                            }
                        }
                    }

                    Panel(Modifier.align(Alignment.TopEnd).padding(right = 28f, top = 28f).width(260f)) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8f)) {
                            Text("SYSTEMS")
                            Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                                Button("PULSE", onClick = {})
                                Button("CLOAK", onClick = {})
                                Button("REPAIR", onClick = {})
                            }
                        }
                    }
                }
            }
        }
        repeat(3) { frame() }
    }

    private fun frame() {
        wall += 16_000_000L
        host.frame(wall)
    }

    @Test
    fun `a still screen asks for no frames at all`() {
        hud()

        repeat(60) {
            wall += 16_000_000L
            assertTrue(!host.frame(wall), "frame $it redrew a screen where nothing had changed")
        }
    }

    @Test
    fun `drawing a still screen anyway allocates almost nothing`() {
        hud()

        val silent = Silent()
        // Measured after a few passes, so what is counted is the steady state rather than the
        // first sight of every glyph on the screen.
        repeat(5) {
            MeasurePass().run(host.root, Constraints.atMost(1280f, 720f))
            DrawPass(silent).draw(host.root)
        }

        val before = allocatedBytes()
        repeat(20) {
            wall += 16_000_000L
            host.frame(wall)
            MeasurePass().run(host.root, Constraints.atMost(1280f, 720f))
            DrawPass(silent).draw(host.root)
        }
        val perFrame = (allocatedBytes() - before) / 20

        // Where it stands today, measured: about 5.5k of it is the layout pass and 1.3k the draw.
        // A ratchet rather than a target — what is left in the layout half is the closure each
        // policy hands back to place its children with, and the Constraints objects a node that
        // resizes still makes.
        assertTrue(perFrame < 8_192, "a still frame of a whole HUD allocated $perFrame bytes")
    }

    @Test
    fun `one number changing redraws without dragging the whole screen with it`() {
        hud()

        val silent = Silent()
        repeat(5) {
            ammo -= 1
            frame()
            MeasurePass().run(host.root, Constraints.atMost(1280f, 720f))
            DrawPass(silent).draw(host.root)
        }

        val before = allocatedBytes()
        repeat(20) {
            ammo -= 1
            wall += 16_000_000L
            assertTrue(host.frame(wall), "a changed number should have redrawn something")
            MeasurePass().run(host.root, Constraints.atMost(1280f, 720f))
            DrawPass(silent).draw(host.root)
        }
        val perFrame = (allocatedBytes() - before) / 20

        // A number that changes every frame has to be measured again — a new string, a new layout —
        // so this is not free, and is not expected to be. What it is watching is that it stays in
        // the same order of magnitude as a still frame rather than recomposing the whole screen.
        assertTrue(perFrame < 65_536, "one changed number cost $perFrame bytes a frame")
    }

    /** A canvas that draws nothing and keeps nothing, for measuring what the toolkit itself costs. */
    private class Silent : composegl.ui.graphics.UiCanvas {
        override fun rect(rect: Rect, colour: Colour, corner: Float) = Unit
        override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) = Unit
        override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) = Unit
        override fun fan(points: FloatArray, colour: Colour) = Unit
        override fun text(layout: composegl.ui.text.TextLayout, x: Float, y: Float, colour: Colour) = Unit
        override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) = Unit
        override fun pushClip(rect: Rect) = Unit
        override fun popClip() = Unit
        override fun pushAlpha(alpha: Float) = Unit
        override fun popAlpha() = Unit
        override fun raw(block: (Any) -> Unit) = Unit
    }

    /** What this thread has allocated so far. HotSpot only, which is what these tests run on. */
    private fun allocatedBytes(): Long {
        val beans = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        return beans.getThreadAllocatedBytes(Thread.currentThread().threadId())
    }
}
