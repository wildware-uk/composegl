package dev.wildware.composegl.render.gl

import dev.wildware.composegl.render.Blend
import dev.wildware.composegl.render.BoundPicture
import dev.wildware.composegl.render.ClipMask
import dev.wildware.composegl.render.EffectQuad
import dev.wildware.composegl.render.FrameTarget
import dev.wildware.composegl.render.RenderCanvas
import dev.wildware.composegl.render.ShapeVertex
import dev.wildware.composegl.render.TextureResolver
import dev.wildware.composegl.render.VertexStream
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.layout.Viewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The device sends the driver only what changed since it last set it, and all of it again after a hand-back. */
class GlDeviceStateTest {

    private val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    private val glow = ShaderEffect(ShaderSource("glow", "void main() { gl_FragColor = texture2D(u_texture, v_texCoord) * u_alpha; }"))

    /** OpenGL ES 2 without vertex arrays, a phone's path, and a core context with them. */
    private val profiles = listOf(GlProfile(GlApi.Es, 2, 0), GlProfile(GlApi.Desktop, 3, 2, core = true))

    private val arrayBuffer = GlConst.ARRAY_BUFFER
    private val elementBuffer = GlConst.ELEMENT_ARRAY_BUFFER
    private val oneQuad = "bufferData($arrayBuffer, floats ${4 * ShapeVertex.Floats})"

    /** A built device on [gl] inside a frame, with two textures to switch between. */
    private class Frame(val gl: RecordingGl, handOver: HostState = HostState.Leave) {
        val device = GlDevice(gl, handOver)
        val textures: List<GlDeviceTexture>
        val vertices: VertexStream

        init {
            device.prepare()
            device.begin(FrameTarget.Host)
            textures = List(2) { device.texture(4, 4, smooth = true) as GlDeviceTexture }
            vertices = device.vertices(8)
        }

        val shapeProgram: String get() = gl.named("createProgram=").first().substringAfter('=')
        val shapeBuffer: String get() = gl.named("createBuffer=")[0].substringAfter('=')
        val indexBuffer: String get() = gl.named("createBuffer=")[2].substringAfter('=')
        val shapeArray: String get() = gl.named("createVertexArray=").first().substringAfter('=')

        fun draw(texture: Int = 0, blend: Blend = Blend.SourceOver, projection: FloatArray, mask: ClipMask? = null) =
            device.drawShapes(vertices, 1, textures[texture], blend, projection, mask)

        /** What [block] sent the driver. */
        fun sent(block: () -> Unit): List<String> {
            val from = gl.calls.size
            block()
            return gl.calls.subList(from, gl.calls.size).toList()
        }
    }

    /** Each way the context goes back to the game and comes back. */
    private val handBacks: List<Pair<String, (GlDevice) -> Unit>> = listOf(
        "a game's drawing in a frame" to { device -> device.suspend(); device.resume() },
        "a game's drawing in a scene" to { device -> device.suspendInScene(); device.resumeInScene() },
        "the next frame" to { device -> device.end(); device.begin(FrameTarget.Host) },
    )

    @Test
    fun `ten draws that differ only in texture set the program and the layout once`() {
        profiles.forEach { profile ->
            val frame = Frame(RecordingGl(profile))
            val drawn = frame.sent { repeat(10) { frame.draw(texture = it % 2, projection = identity) } }

            assertEquals(1, drawn.count { it.startsWith("useProgram(") }, "$profile: $drawn")
            assertEquals(1, drawn.count { it.startsWith("uniformMatrix4fv") }, "$profile: $drawn")
            assertEquals(1, drawn.count { it.startsWith("blendFuncSeparate") }, "$profile: $drawn")
            assertEquals(ShapeVertex.Attributes.size, drawn.count { it.startsWith("enableVertexAttribArray") }, "$profile: $drawn")
            assertEquals(ShapeVertex.Attributes.size, drawn.count { it.startsWith("vertexAttribPointer") }, "$profile: $drawn")
            assertEquals(0, drawn.count { it.startsWith("disableVertexAttribArray") }, "$profile: $drawn")

            // After the first, a draw is its texture, its vertices and the draw itself.
            val afterFirst = drawn.subList(drawn.indexOf("drawElements(6)") + 1, drawn.size)
            val expected = (1 until 10).flatMap { listOf("bindTexture(${frame.textures[it % 2].name})", oneQuad, "drawElements(6)") }
            assertEquals(expected, afterFirst, "$profile")
        }
    }

    @Test
    fun `a draw after the context was handed back sets everything again`() {
        profiles.forEach { profile ->
            listOf(HostState.Leave, HostState.Restore).forEach { handOver ->
                handBacks.forEach { (what, handBack) ->
                    val frame = Frame(RecordingGl(profile), handOver)
                    frame.draw(projection = identity)
                    val gone = frame.sent { handBack(frame.device) }
                    val again = frame.sent { frame.draw(projection = identity) }
                    val case = "$profile, $handOver, after $what"

                    listOf(
                        "useProgram(${frame.shapeProgram})",
                        "bindTexture(${frame.textures[0].name})",
                        "bindBuffer($arrayBuffer, ${frame.shapeBuffer})",
                    ).forEach { assertTrue(it in again, "$case: missing $it in $again") }
                    assertEquals(1, again.count { it.startsWith("blendFuncSeparate") }, "$case: $again")
                    if (profile.core) {
                        // The layout lives in the device's own vertex array, which the game never binds.
                        assertTrue("bindVertexArray(${frame.shapeArray})" in again, "$case: $again")
                        assertEquals(0, again.count { it.startsWith("vertexAttribPointer") }, "$case: $again")
                    } else {
                        // The game was handed its attributes switched off, as it always was.
                        ShapeVertex.Attributes.indices.forEach { assertTrue("disableVertexAttribArray($it)" in gone, "$case: $gone") }
                        assertEquals(ShapeVertex.Attributes.size, again.count { it.startsWith("enableVertexAttribArray") }, "$case: $again")
                        assertEquals(ShapeVertex.Attributes.size, again.count { it.startsWith("vertexAttribPointer") }, "$case: $again")
                        assertTrue("bindBuffer($elementBuffer, ${frame.indexBuffer})" in again, "$case: $again")
                    }
                }
            }
        }
    }

    @Test
    fun `a blend or a projection is sent again only when it changes`() {
        val frame = Frame(RecordingGl(GlProfile(GlApi.Es, 2, 0)))
        val moved = identity.copyOf().also { it[12] = 0.5f }
        val drawn = frame.sent {
            frame.draw(blend = Blend.SourceOver, projection = identity)
            frame.draw(blend = Blend.Additive, projection = identity)
            frame.draw(blend = Blend.Additive, projection = moved)
            // The same numbers in a different array are the same projection.
            frame.draw(blend = Blend.Additive, projection = moved.copyOf())
        }

        assertEquals(2, drawn.count { it.startsWith("blendFuncSeparate") }, "$drawn")
        assertEquals(2, drawn.count { it.startsWith("uniformMatrix4fv") }, "$drawn")
    }

    @Test
    fun `a uniform is the program's own and outlives a hand-back`() {
        val frame = Frame(RecordingGl(GlProfile(GlApi.Es, 2, 0)))
        frame.draw(projection = identity)
        frame.device.end()
        frame.device.begin(FrameTarget.Host)

        val again = frame.sent { frame.draw(projection = identity) }
        assertEquals(0, again.count { it.startsWith("uniform") }, "nobody else draws with the device's program: $again")
    }

    @Test
    fun `the same rounded clip is sent once and its mode only when it changes`() {
        val frame = Frame(RecordingGl(GlProfile(GlApi.Es, 2, 0)))
        val mask = ClipMask().apply {
            centreX = 10f
            halfWidth = 5f
            halfHeight = 5f
        }
        val drawn = frame.sent {
            frame.draw(texture = 0, projection = identity, mask = mask)
            frame.draw(texture = 1, projection = identity, mask = mask)
            frame.draw(texture = 0, projection = identity)
            frame.draw(texture = 1, projection = identity)
            mask.centreX = 20f
            frame.draw(texture = 0, projection = identity, mask = mask)
        }

        assertEquals(2, drawn.count { it.startsWith("uniform4f(u_maskBox") }, "$drawn")
        assertEquals(1, drawn.count { it.startsWith("uniform4f(u_maskRadii") }, "$drawn")
        assertEquals(1, drawn.count { it.startsWith("uniform2f(u_maskScale") }, "$drawn")
        assertEquals(listOf(1f, 0f, 1f), drawn.filter { it.startsWith("uniform1f(u_maskMode") }.map { it.substringAfter(", ").removeSuffix(")").toFloat() })
    }

    @Test
    fun `an effect between two draws costs the shapes their program and blend and on ES 2 two attributes`() {
        profiles.forEach { profile ->
            val frame = Frame(RecordingGl(profile))
            frame.draw(projection = identity)
            val effects = frame.sent {
                frame.device.drawEffect(glow, frame.textures[1], EffectQuad(), Blend.PremultipliedSourceOver)
                frame.device.drawEffect(glow, frame.textures[1], EffectQuad(), Blend.PremultipliedSourceOver)
            }
            assertEquals(1, effects.count { it.startsWith("useProgram(") }, "$profile: one program for both: $effects")
            assertEquals(1, effects.count { it.startsWith("blendFuncSeparate") }, "$profile: $effects")

            val back = frame.sent { frame.draw(projection = identity) }
            assertTrue("useProgram(${frame.shapeProgram})" in back, "$profile: $back")
            assertEquals(1, back.count { it.startsWith("blendFuncSeparate") }, "$profile: $back")
            assertEquals(0, back.count { it.startsWith("uniform") }, "$profile: the shape program kept its own: $back")
            val pointed = back.filter { it.startsWith("vertexAttribPointer") }.map { it.substringAfter('(').substringBefore(',').toInt() }
            assertEquals(if (profile.core) emptyList() else listOf(0, 1), pointed, "$profile: $back")
        }
    }

    @Test
    fun `a texture the device bound for itself or let go is bound again for the next draw`() {
        val frame = Frame(RecordingGl(GlProfile(GlApi.Es, 2, 0)))
        val texture = frame.textures[0]
        frame.draw(projection = identity)

        // A glyph going up binds the texture it writes and then none.
        frame.device.write(frame.textures[1], 0, 0, 1, 1, ByteArray(4 * 4 * 4), 4)
        assertTrue("bindTexture(${texture.name})" in frame.sent { frame.draw(projection = identity) })

        // A picture the size of nothing made mid-frame does the same.
        frame.device.offscreen(4, 4)
        assertTrue("bindTexture(${texture.name})" in frame.sent { frame.draw(projection = identity) })

        // A texture given back and its name handed out again is a different texture.
        frame.device.delete(texture)
        val reborn = GlDeviceTexture.adopt(texture.name, 4, 4)
        val drawn = frame.sent { frame.device.drawShapes(frame.vertices, 1, reborn, Blend.SourceOver, identity, null) }
        assertTrue("bindTexture(${texture.name})" in drawn, "$drawn")
    }

    @Test
    fun `a game's texture is bound through its engine for every draw`() {
        // An engine can bind a texture of its own in the middle of a frame — KorGE and Kool do, as
        // the canvas first resolves a game's picture — so the device never assumes its own is still bound.
        val frame = Frame(RecordingGl(GlProfile(GlApi.Es, 2, 0)))
        var binds = 0
        val sprite = GlDeviceTexture.adopt(77, 4, 4) { binds++ }
        frame.device.drawShapes(frame.vertices, 1, sprite, Blend.SourceOver, identity, null)
        frame.device.drawShapes(frame.vertices, 1, sprite, Blend.Additive, identity, null)
        assertEquals(2, binds)
    }

    @Test
    fun `a scissor set twice is sent once and comes back after a game's drawing`() {
        val frame = Frame(RecordingGl(GlProfile(GlApi.Es, 2, 0)))
        val set = frame.sent {
            frame.device.scissor(1, 2, 3, 4)
            frame.device.scissor(1, 2, 3, 4)
            frame.device.noScissor()
            frame.device.noScissor()
            frame.device.scissor(1, 2, 3, 4)
        }
        assertEquals(
            listOf(
                "enable(${GlConst.SCISSOR_TEST})",
                "scissor(1, 2, 3, 4)",
                "disable(${GlConst.SCISSOR_TEST})",
                // Switched back on with the box it had: the box was never changed.
                "enable(${GlConst.SCISSOR_TEST})",
            ),
            set,
        )
        frame.device.suspend()
        val back = frame.sent { frame.device.resume() }
        assertTrue("scissor(1, 2, 3, 4)" in back, "$back")
    }

    /** A game's sprite, as a backend would resolve it. */
    private class Sprite : TextureHandle {
        override val width = 8
        override val height = 8
    }

    @Test
    fun `a canvas drawing pictures from two sheets costs three calls a draw after the first`() {
        val gl = RecordingGl(GlProfile(GlApi.Es, 2, 0))
        val device = GlDevice(gl)
        val sprites = List(2) { Sprite() }
        val sheets = sprites.associateWith { BoundPicture(device.texture(8, 8, smooth = true)) }
        val canvas = RenderCanvas(device, textures = TextureResolver { sheets[it] })
        canvas.warmUp()

        canvas.begin(Viewport.oneToOne(Size(400f, 300f)))
        val from = gl.calls.size
        repeat(10) { canvas.image(sprites[it % 2], Rect.of(it * 10f, 0f, 10f, 10f)) }
        canvas.end()
        // The vertices copied into the buffer the driver reads are not a call into the driver.
        val frame = gl.calls.subList(from, gl.calls.size).filterNot { it.startsWith("copy(") }

        val draws = frame.indices.filter { frame[it] == "drawElements(6)" }
        assertEquals(10, draws.size, "$frame")
        val between = frame.subList(draws.first() + 1, draws.last() + 1)
        assertEquals(9 * 3, between.size, "$between")
    }
}
