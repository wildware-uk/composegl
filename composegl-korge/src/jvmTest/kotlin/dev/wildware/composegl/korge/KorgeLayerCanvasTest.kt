package dev.wildware.composegl.korge

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.effect.Uniform
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Matrix4
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.clipShape
import dev.wildware.composegl.ui.modifier.mirror
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.skew
import dev.wildware.composegl.ui.testing.TestTree
import korlibs.image.bitmap.Bitmap32
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

/**
 * Layers on the KorGE canvas: the picture, each way of putting it back — upright, turned, cut,
 * mirrored, on four corners and tilted in depth — and a shader in the way.
 *
 * The LibGDX canvas's layer tests, with the same rectangles and the same probes, so the two backends
 * are held to one picture.
 */
class KorgeLayerCanvasTest {

    private val size = KorgeGl.size.toFloat()
    private val viewport = Viewport(design = Size(size, size), physical = Size(size, size), policy = ScalePolicy.Fit)

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)

    /** The plainest effect there is: the picture, unchanged, faded by whatever is in force. */
    private val passThrough = ShaderEffect(
        ShaderSource("pass-through", "void main() { gl_FragColor = texture2D(u_texture, v_texCoord) * u_alpha; }"),
    )

    /** Draws [content] through a fresh canvas into an offscreen picture over black, and reads it back. */
    private fun draw(on: Viewport = viewport, content: KorgeCanvas.() -> Unit): Bitmap32 {
        val canvas = KorgeCanvas()
        try {
            return KorgeGl.picture { ctx ->
                canvas.begin(on, ctx)
                canvas.content()
                canvas.end()
            }
        } finally {
            canvas.close()
        }
    }

    /** A composed screen, one frame of it, through a KorGE canvas. */
    private fun screen(on: Viewport = viewport, content: @Composable () -> Unit): Bitmap32 {
        val host = UiHost()
        val canvas = KorgeCanvas()
        try {
            host.setContent(content)
            val ui = UiRenderer(host, canvas)
            return KorgeGl.picture { ctx ->
                canvas.renderContext = ctx
                try {
                    ui.render(on, 0L)
                } finally {
                    canvas.renderContext = null
                }
            }
        } finally {
            host.dispose()
            canvas.close()
        }
    }

    private fun picture(handle: TextureHandle?): TextureHandle = checkNotNull(handle) { "this driver gave us no layer" }

    // --- the layer itself ---

    @Test
    fun `it says it draws layers and every way of putting one back`() {
        val canvas = KorgeCanvas()
        assertTrue(canvas.drawsLayers, "layers")
        assertTrue(canvas.turnsLayers, "turned")
        assertTrue(canvas.cutsLayers, "cut")
        assertTrue(canvas.mirrorsLayers, "mirrored")
        assertTrue(canvas.drawsLayersOnto, "on four corners")
        assertTrue(canvas.tiltsLayers, "tilted")
        canvas.close()
    }

    @Test
    fun `a layer lands exactly where drawing straight onto the screen would have`() {
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val picture = layer(bounds) { rect(Rect.of(60f, 60f, 60f, 60f), red) }
            drawLayer(picture(picture), bounds)
        }

        assertColour(Red, frame.at(90, 90), "the middle of the rectangle")
        assertColour(Red, frame.at(62, 62), "its top-left corner")
        assertColour(Black, frame.at(50, 50), "inside the layer but outside the rectangle")
        assertColour(Black, frame.at(130, 130), "past its bottom-right corner")
        assertColour(Black, frame.at(20, 20), "outside the layer altogether")
    }

    @Test
    fun `a layer fades as one object rather than as a pile of parts`() {
        val bounds = Rect.of(0f, 0f, 200f, 200f)
        val frame = draw {
            pushAlpha(0.5f)
            val picture = layer(bounds) {
                rect(Rect.of(20f, 20f, 100f, 100f), red)
                rect(Rect.of(60f, 60f, 100f, 100f), blue)
            }
            drawLayer(picture(picture), bounds)
            popAlpha()
        }

        val overlap = frame.at(90, 90)
        assertTrue(overlap.r < 0.06f, "the red should be hidden under the blue, but the overlap is ${overlap.r} red")
        assertTrue(abs(overlap.b - 0.5f) < 0.1f, "expected half the blue, got ${overlap.b}")
    }

    @Test
    fun `a layer inside a layer draws the same as one on its own`() {
        val inner = Rect.of(60f, 60f, 60f, 60f)
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val outer = layer(bounds) {
                val nested = layer(inner) { rect(inner, red) }
                drawLayer(picture(nested), inner)
            }
            drawLayer(picture(outer), bounds)
        }

        assertColour(Red, frame.at(90, 90), "the middle of the rectangle")
        assertColour(Black, frame.at(50, 50), "outside it")
    }

    @Test
    fun `a clip inside a layer cuts in the layer's own pixels`() {
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val picture = layer(bounds) {
                pushClip(Rect.of(40f, 40f, 60f, 120f))
                rect(bounds, red)
                popClip()
            }
            drawLayer(picture(picture), bounds)
        }

        assertColour(Red, frame.at(70, 100), "the left half, which the clip kept")
        assertColour(Black, frame.at(130, 100), "the right half, which it cut")
    }

    @Test
    fun `the frame carries on normally after a layer`() {
        val bounds = Rect.of(0f, 0f, 100f, 100f)
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 300f, 200f))
            val picture = layer(bounds) { rect(bounds, blue) }
            drawLayer(picture(picture), bounds)
            rect(Rect.of(150f, 150f, 100f, 100f), red)
            popClip()
        }

        assertColour(Blue, frame.at(50, 50), "the layer")
        assertColour(Red, frame.at(200, 180), "drawn after it, inside the clip")
        assertColour(Black, frame.at(200, 220), "drawn after it, outside the clip")
    }

    @Test
    fun `a layer on a letterboxed design is taken at screen resolution and put back in place`() {
        // A 200 square design on the 400 square picture: scaled by two, so the layer is 240 pixels.
        val doubled = Viewport(design = Size(200f, 200f), physical = Size(size, size), policy = ScalePolicy.Fit)
        val bounds = Rect.of(20f, 20f, 120f, 120f)
        val frame = draw(doubled) {
            val picture = layer(bounds) { rect(Rect.of(30f, 30f, 30f, 30f), red) }
            drawLayer(picture(picture), bounds)
        }

        assertColour(Red, frame.at(90, 90), "design (45, 45) is pixel (90, 90)")
        assertColour(Black, frame.at(50, 50), "design (25, 25) is inside the layer, outside the box")
        assertColour(Black, frame.at(130, 90), "design (65, 45) is past the box")
    }

    @Test
    fun `a layer nobody could draw is refused rather than half drawn`() {
        var asked = false
        val frame = draw {
            val picture = layer(Rect.of(0f, 0f, 0f, 50f)) { asked = true }
            assertEquals(null, picture, "an empty layer has no picture")
            rect(Rect.of(10f, 10f, 50f, 50f), red)
        }

        assertTrue(!asked, "nothing should have been drawn for a layer that was refused")
        assertColour(Red, frame.at(30, 30), "the caller's own drawing still works")
    }

    @Test
    fun `layer outside a frame is a mistake`() {
        val canvas = KorgeCanvas()
        assertThrows<IllegalStateException> { canvas.layer(Rect.of(0f, 0f, 10f, 10f)) {} }
        canvas.close()
    }

    // --- through a shader ---

    @Test
    fun `an effect draws where the picture would have gone`() {
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val picture = layer(bounds) { rect(bounds, red) }
            drawLayer(picture(picture), bounds, passThrough)
        }

        assertColour(Red, frame.at(100, 100), "the middle of the effect")
        assertColour(Red, frame.at(42, 42), "its top-left corner")
        assertColour(Black, frame.at(30, 100), "left of it")
        assertColour(Black, frame.at(170, 100), "right of it")
        assertColour(Black, frame.at(100, 170), "below it")
    }

    @Test
    fun `an effect is handed the picture the interface drew`() {
        val bounds = Rect.of(0f, 0f, 200f, 200f)
        val frame = draw {
            val picture = layer(bounds) { rect(Rect.of(0f, 0f, 200f, 100f), red) }
            drawLayer(picture(picture), bounds, passThrough)
        }

        assertColour(Red, frame.at(100, 40), "the painted half")
        assertColour(Black, frame.at(100, 160), "the empty half")
    }

    @Test
    fun `a shader reads the uniforms it was given`() {
        val bounds = Rect.of(0f, 0f, 200f, 200f)
        val tint = ShaderEffect(
            source = ShaderSource(
                "tint",
                """
                uniform vec4 u_tint;
                void main() {
                    gl_FragColor = vec4(u_tint.rgb * u_tint.a, u_tint.a) * u_alpha;
                }
                """.trimIndent(),
            ),
            uniforms = mapOf("u_tint" to Uniform.of(blue)),
        )
        val frame = draw {
            val picture = layer(bounds) { rect(bounds, red) }
            drawLayer(picture(picture), bounds, tint)
        }

        assertColour(Blue, frame.at(100, 100), "the shader's own colour, not the picture's")
    }

    @Test
    fun `a shader is told the picture's size and the design size`() {
        // Green where both are what they should be: 120 pixels of picture at one to one, and 120 units.
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val sizes = ShaderEffect(
            ShaderSource(
                "sizes",
                """
                void main() {
                    float right = step(abs(u_textureSize.x - 120.0), 0.5) * step(abs(u_size.y - 120.0), 0.5);
                    gl_FragColor = vec4(1.0 - right, right, 0.0, 1.0) * u_alpha;
                }
                """.trimIndent(),
            ),
        )
        val frame = draw {
            val picture = layer(bounds) { rect(bounds, red) }
            drawLayer(picture(picture), bounds, sizes)
        }

        assertColour(Green, frame.at(100, 100), "both sizes as the shader expected")
    }

    @Test
    fun `an effect is faded by the opacity in force`() {
        val bounds = Rect.of(0f, 0f, 200f, 200f)
        val frame = draw {
            pushAlpha(0.5f)
            val picture = layer(bounds) { rect(bounds, red) }
            drawLayer(picture(picture), bounds, passThrough)
            popAlpha()
        }

        val lit = frame.at(100, 100).r
        assertTrue(abs(lit - 0.5f) < 0.1f, "expected half the red, got $lit")
    }

    @Test
    fun `the frame carries on normally after an effect`() {
        val bounds = Rect.of(0f, 0f, 100f, 100f)
        val frame = draw {
            val picture = layer(bounds) { rect(bounds, red) }
            drawLayer(picture(picture), bounds, passThrough)
            rect(Rect.of(150f, 150f, 100f, 100f), blue)
            // A second layer and a composite after the shader, which uses a texture unit of its own.
            val second = Rect.of(300f, 0f, 50f, 50f)
            val again = layer(second) { rect(second, red) }
            drawLayer(picture(again), second)
        }

        assertColour(Red, frame.at(50, 50), "the effect")
        assertColour(Blue, frame.at(200, 200), "an ordinary rectangle drawn after it")
        assertColour(Red, frame.at(325, 25), "a plain layer drawn after it")
    }

    @Test
    fun `an effect inside a clip is cut by the clip`() {
        val bounds = Rect.of(0f, 0f, 200f, 200f)
        val frame = draw {
            val picture = layer(bounds) { rect(bounds, red) }
            pushClip(Rect.of(0f, 0f, 100f, 200f))
            drawLayer(picture(picture), bounds, passThrough)
            popClip()
        }

        assertColour(Red, frame.at(50, 100), "inside the clip")
        assertColour(Black, frame.at(150, 100), "outside it")
    }

    @Test
    fun `a shader that will not compile says so, with its name and the driver's words`() {
        assumeTrue(KorgeGl.available, "no display and KORGE_HEADLESS is not set; this test needs a real GL context")
        val broken = ShaderEffect(ShaderSource("broken", "void main() { this is not GLSL }"))

        val thrown = assertThrows<IllegalArgumentException> {
            draw {
                val bounds = Rect.of(0f, 0f, 100f, 100f)
                val picture = layer(bounds) { rect(bounds, red) }
                drawLayer(picture(picture), bounds, broken)
            }
        }

        assertTrue(thrown.message.orEmpty().contains("broken"), "the message should name the shader: ${thrown.message}")
        // And the game goes on drawing afterwards.
        assertColour(Red, draw { rect(Rect.of(0f, 0f, 50f, 50f), red) }.at(25, 25), "a frame after the failure")
    }

    @Test
    fun `a shader effect composites the way the blend stack says`() {
        val half = Colour.rgb(0x404040)
        val bounds = Rect.of(10f, 10f, 60f, 60f)
        val frame = draw {
            rect(bounds, half)
            pushBlend(BlendMode.Additive)
            val picture = layer(bounds) { rect(bounds, half) }
            drawLayer(picture(picture), bounds, passThrough)
            popBlend()
        }

        assertColour(Rgb(0.5f, 0.5f, 0.5f), frame.at(40, 40), "a group through a shader inside a pushBlend adds")
    }

    @Test
    fun `a plain layer composites the way the blend stack says, and draws plainly inside`() {
        val half = Colour.rgb(0x404040)
        val bounds = Rect.of(10f, 10f, 60f, 60f)
        val frame = draw {
            rect(bounds, half)
            pushBlend(BlendMode.Additive)
            val picture = layer(bounds) {
                rect(bounds, half)
                rect(bounds, half)
            }
            drawLayer(picture(picture), bounds)
            popBlend()
        }

        assertColour(Rgb(0.5f, 0.5f, 0.5f), frame.at(40, 40), "covered inside, then added onto the quarter under it")
    }

    // --- turned ---

    /** Red over blue, captured from [bounds] and handed to [put]. */
    private fun KorgeCanvas.topAndBottom(bounds: Rect, put: KorgeCanvas.(TextureHandle) -> Unit) {
        val picture = layer(bounds) {
            rect(Rect(bounds.left, bounds.top, bounds.right, bounds.centre.y), red)
            rect(Rect(bounds.left, bounds.centre.y, bounds.right, bounds.bottom), blue)
        }
        put(picture(picture))
    }

    @Test
    fun `a layer turned a quarter clockwise sends its top to the right`() {
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw { topAndBottom(bounds) { drawLayer(it, bounds, degrees = 90f, pivotX = 0.5f, pivotY = 0.5f) } }

        assertColour(Red, frame.at(130, 100), "the top half is on the right")
        assertColour(Blue, frame.at(70, 100), "and the bottom half on the left")
    }

    @Test
    fun `a layer turned half way round is upside down in the same box`() {
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw { topAndBottom(bounds) { drawLayer(it, bounds, degrees = 180f, pivotX = 0.5f, pivotY = 0.5f) } }

        assertColour(Blue, frame.at(100, 60), "the bottom half on top")
        assertColour(Red, frame.at(100, 140), "the top half underneath")
        assertColour(Black, frame.at(100, 170), "and nothing past the box")
    }

    @Test
    fun `a layer turned about its corner swings out of its box`() {
        // Forty-five degrees about the top-left: the top edge now runs down and to the right.
        val bounds = Rect.of(100f, 100f, 100f, 100f)
        val frame = draw { topAndBottom(bounds) { drawLayer(it, bounds, degrees = 45f, pivotX = 0f, pivotY = 0f) } }

        assertColour(Red, frame.at(150, 160), "just under the turned top edge")
        assertColour(Black, frame.at(180, 110), "above it, inside the unturned box")
        assertColour(Blue, frame.at(45, 170), "the bottom has swung left, out of the box")
    }

    @Test
    fun `a turned layer fades with the opacity in force`() {
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            pushAlpha(0.5f)
            topAndBottom(bounds) { drawLayer(it, bounds, degrees = 180f, pivotX = 0.5f, pivotY = 0.5f) }
            popAlpha()
        }

        assertColour(Rgb(0.5f, 0f, 0f), frame.at(100, 140), "half red over black")
    }

    // --- mirrored ---

    /** Red on the left half of [bounds], blue on the right, then [put] down however the test says. */
    private fun KorgeCanvas.strip(bounds: Rect, put: KorgeCanvas.(TextureHandle) -> Unit) {
        val picture = layer(bounds) {
            rect(Rect(bounds.left, bounds.top, bounds.centre.x, bounds.bottom), red)
            rect(Rect(bounds.centre.x, bounds.top, bounds.right, bounds.bottom), blue)
        }
        put(picture(picture))
    }

    @Test
    fun `a mirrored layer swaps its left and right`() {
        val bounds = Rect.of(40f, 40f, 120f, 80f)
        val pixels = draw { strip(bounds) { drawLayer(it, bounds, mirrorX = true, mirrorY = false) } }

        assertColour(Blue, pixels.at(60, 80), "the picture's right half is drawn on the left")
        assertColour(Red, pixels.at(140, 80), "and its left half on the right")
        assertColour(Black, pixels.at(30, 80), "nothing spills past the rectangle's left")
        assertColour(Black, pixels.at(170, 80), "or its right")
    }

    @Test
    fun `a layer mirrored neither way is the plain composite`() {
        val bounds = Rect.of(40f, 40f, 120f, 80f)
        val pixels = draw { strip(bounds) { drawLayer(it, bounds, mirrorX = false, mirrorY = false) } }

        assertColour(Red, pixels.at(60, 80), "left is left")
        assertColour(Blue, pixels.at(140, 80), "right is right")
    }

    @Test
    fun `a vertically mirrored layer swaps its top and bottom`() {
        val bounds = Rect.of(40f, 40f, 80f, 120f)
        val pixels = draw { topAndBottom(bounds) { drawLayer(it, bounds, mirrorX = false, mirrorY = true) } }

        assertColour(Blue, pixels.at(80, 60), "the picture's bottom half is drawn on top")
        assertColour(Red, pixels.at(80, 140), "and its top half underneath")
    }

    @Test
    fun `a mirrored node in a tree draws its art facing the other way`() {
        val screen = TestTree()
        val sprite = screen.box("sprite", 20f, 20f, 160f, 60f, Modifier.mirror())
        screen.box("face", 0f, 0f, 40f, 60f, Modifier.background(red), parent = sprite)
        screen.box("tail", 40f, 0f, 120f, 60f, Modifier.background(blue), parent = sprite)

        val pixels = draw { DrawPass(this).draw(screen.root) }

        assertColour(Red, pixels.at(160, 50), "the face, laid out on the left, is on the right")
        assertColour(Blue, pixels.at(40, 50), "and the tail on the left")
    }

    @Test
    fun `a mirrored and scaled node flips and shrinks in one picture`() {
        val screen = TestTree()
        val sprite = screen.box("sprite", 0f, 0f, 200f, 100f, Modifier.mirror().scale(0.5f))
        screen.box("face", 0f, 0f, 100f, 100f, Modifier.background(red), parent = sprite)
        screen.box("tail", 100f, 0f, 100f, 100f, Modifier.background(blue), parent = sprite)

        val pixels = draw { DrawPass(this).draw(screen.root) }

        assertColour(Blue, pixels.at(75, 50), "the tail")
        assertColour(Red, pixels.at(125, 50), "the face")
        assertColour(Black, pixels.at(40, 50), "and nothing outside the shrunk rectangle")
    }

    // --- on four corners ---

    private val slant = floatArrayOf(80f, 40f, 200f, 40f, 160f, 160f, 40f, 160f)

    @Test
    fun `a layer put on four corners slants what was drawn into it`() {
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val picture = layer(bounds) { rect(bounds, red) }
            drawLayerOnto(picture(picture), bounds, slant)
        }

        assertColour(Red, frame.at(100, 100), "the middle is still covered")
        assertColour(Red, frame.at(190, 50), "the top leans out past the upright box")
        assertColour(Black, frame.at(45, 50), "and leaves the box's top-left corner bare")
        assertColour(Red, frame.at(50, 150), "the bottom still reaches its left edge")
        assertColour(Black, frame.at(45, 120), "the left edge leans in halfway down too")
    }

    @Test
    fun `the top of a layer on four corners is still the top`() {
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw { topAndBottom(bounds) { drawLayerOnto(it, bounds, slant) } }

        assertColour(Red, frame.at(150, 50), "the red half is on top")
        assertColour(Blue, frame.at(80, 150), "and the blue half underneath")
    }

    @Test
    fun `a layer on four corners fades with the opacity in force`() {
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val picture = layer(bounds) { rect(bounds, red) }
            pushAlpha(0.5f)
            drawLayerOnto(picture(picture), bounds, slant)
            popAlpha()
        }

        assertColour(Rgb(0.5f, 0f, 0f), frame.at(100, 100), "half red over black")
    }

    @Test
    fun `a layer on four corners inside another layer keeps what the outer one drew first`() {
        val outer = Rect.of(40f, 40f, 160f, 160f)
        val inner = Rect.of(80f, 80f, 80f, 80f)
        val frame = draw {
            val picture = layer(outer) {
                rect(outer, blue)
                val nested = layer(inner) { rect(inner, red) }
                drawLayerOnto(picture(nested), inner, floatArrayOf(100f, 80f, 180f, 80f, 160f, 160f, 80f, 160f))
            }
            drawLayerOnto(picture(picture), outer, floatArrayOf(80f, 40f, 240f, 40f, 200f, 200f, 40f, 200f))
        }

        assertColour(Blue, frame.at(200, 50), "the outer picture has its blue in it")
        assertColour(Black, frame.at(45, 45), "and none was left upright on the screen")
    }

    @Test
    fun `a slanted node in a laid out tree is drawn slanted on the screen`() {
        val host = UiHost()
        val drawn = try {
            host.setContent {
                Box(Modifier.padding(60f)) {
                    Box(Modifier.size(120f).skew(x = -45f).background(red))
                }
            }
            host.frame(0L)
            MeasurePass().run(host.root, Constraints.atMost(size, size))
            draw { DrawPass(this).draw(host.root) }
        } finally {
            host.dispose()
        }

        assertColour(Red, drawn.at(120, 120), "the middle does not move")
        assertColour(Red, drawn.at(225, 65), "the top leans out past the laid-out box")
        assertColour(Black, drawn.at(65, 65), "leaving its top-left corner bare")
        assertColour(Red, drawn.at(15, 175), "the bottom leans out the other way")
        assertColour(Black, drawn.at(170, 175), "leaving its bottom-right corner bare")
    }

    // --- tilted in depth ---

    private fun turnedAboutY(degrees: Float, distance: Float = 200f) =
        Matrix4.translation(120f, 120f) * Matrix4.perspective(distance) * Matrix4.rotationY(degrees) * Matrix4.translation(-120f, -120f)

    /** Red on the left half, blue on the right, captured and put down through [transform]. */
    private fun halves(transform: Matrix4, alpha: Float = 1f): Bitmap32 {
        val bounds = Rect.of(40f, 40f, 160f, 160f)
        return draw {
            val picture = layer(bounds) {
                rect(Rect.of(40f, 40f, 80f, 160f), red)
                rect(Rect.of(120f, 40f, 80f, 160f), blue)
            }
            pushAlpha(alpha)
            drawLayer(picture(picture), bounds, transform)
            popAlpha()
        }
    }

    @Test
    fun `a tilted layer is divided by depth per pixel so it does not bend along the diagonal`() {
        val frame = halves(turnedAboutY(60f))

        assertColour(Red, frame.at(112, 120), "the near half takes more of the screen")
        assertColour(Red, frame.at(117, 120), "right up to the middle")
        assertColour(Blue, frame.at(124, 120), "and the far half starts there")
        assertColour(Black, frame.at(160, 120), "the far edge has swung in past 150")
        assertColour(Red, frame.at(64, 10), "the near edge grows past the box's top")
        assertColour(Black, frame.at(145, 55), "while the far edge shrinks below it")
    }

    @Test
    fun `a layer turned all the way over shows its back mirrored`() {
        val frame = halves(turnedAboutY(180f))

        assertColour(Blue, frame.at(60, 120), "blue has come round to the left")
        assertColour(Red, frame.at(180, 120), "and red to the right")
    }

    @Test
    fun `a tilted layer fades with the opacity in force`() {
        val frame = halves(turnedAboutY(30f), alpha = 0.5f)

        assertColour(Rgb(0.5f, 0f, 0f), frame.at(80, 120), "half red over black")
    }

    @Test
    fun `a layer turned so a corner is behind the camera is clipped not drawn inside out`() {
        val frame = halves(turnedAboutY(80f, distance = 60f))

        assertColour(Black, frame.at(200, 120), "nothing inside out on the right")
        assertColour(Black, frame.at(240, 30), "nor in the far corner")
    }

    // --- cut to a shape ---

    @Test
    fun `square art comes out round`() {
        val frame = screen {
            Box(Modifier.padding(100f)) {
                Box(Modifier.size(100f).clipShape(Shapes.Circle)) { Box(Modifier.size(100f).background(red)) }
            }
        }

        assertColour(Red, frame.at(150, 150), "the middle")
        assertColour(Red, frame.at(150, 103), "just inside the top of the circle")
        assertColour(Black, frame.at(103, 103), "the top-left corner of the art, cut away")
        assertColour(Black, frame.at(196, 196), "the bottom-right one")
        assertColour(Black, frame.at(99, 150), "nothing leaks past the side of the box")
    }

    @Test
    fun `a clip rounded only along its top cuts the art's top corners and keeps its bottom ones`() {
        val frame = screen {
            Box(Modifier.padding(100f)) {
                Box(Modifier.size(100f).clip(Corners.top(40f))) { Box(Modifier.size(100f).background(red)) }
            }
        }

        assertColour(Red, frame.at(150, 150), "the middle")
        assertColour(Black, frame.at(103, 103), "the top-left corner, rounded away")
        assertColour(Black, frame.at(196, 103), "the top-right one")
        assertColour(Red, frame.at(101, 198), "the bottom-left corner stays square")
        assertColour(Red, frame.at(198, 198), "and the bottom-right")
    }

    @Test
    fun `the edge of the circle is soft rather than a staircase`() {
        val frame = screen {
            Box(Modifier.padding(100f)) {
                Box(Modifier.size(100f).clipShape(Shapes.Circle)) { Box(Modifier.size(100f).background(red)) }
            }
        }

        val between = partlyCovered(frame)
        assertTrue(between > 100, "only $between pixels were partly covered")
        assertTrue(between < 800, "$between pixels were partly covered, which is a blur rather than an edge")
    }

    @Test
    fun `a circle inside a diamond is cut by both`() {
        val frame = screen {
            Box(Modifier.padding(100f)) {
                Box(Modifier.size(100f).clipShape(Shapes.Diamond)) {
                    Box(Modifier.size(100f).clipShape(Shapes.Circle)) { Box(Modifier.size(100f).background(red)) }
                }
            }
        }

        assertColour(Red, frame.at(150, 150), "inside both")
        assertColour(Black, frame.at(120, 128), "inside the circle but past the diamond's edge")
        assertColour(Black, frame.at(104, 104), "the corner both of them cut")
        assertColour(Red, frame.at(150, 104), "the diamond's tip, where the circle reaches too")
    }

    @Test
    fun `on a screen twice the design size the edge stays one screen pixel soft`() {
        val doubled = Viewport(design = Size(size / 2f, size / 2f), physical = Size(size, size), policy = ScalePolicy.Fit)
        val frame = screen(doubled) {
            Box(Modifier.padding(50f)) {
                Box(Modifier.size(50f).clipShape(Shapes.Circle)) { Box(Modifier.size(50f).background(red)) }
            }
        }
        val plain = screen {
            Box(Modifier.padding(100f)) {
                Box(Modifier.size(100f).clipShape(Shapes.Circle)) { Box(Modifier.size(100f).background(red)) }
            }
        }

        assertColour(Red, frame.at(150, 150), "the middle, in screen pixels")
        assertColour(Black, frame.at(104, 104), "the corner, cut")
        val doubledRing = partlyCovered(frame)
        val plainRing = partlyCovered(plain)
        assertTrue(doubledRing < plainRing * 1.4f, "$doubledRing pixels partly covered at double scale against $plainRing")
    }

    private fun partlyCovered(frame: Bitmap32): Int {
        var between = 0
        for (y in 100 until 200) for (x in 100 until 200) {
            val r = frame.at(x, y).r
            if (r > 0.15f && r < 0.85f) between++
        }
        return between
    }
}
