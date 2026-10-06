package dev.wildware.composegl.render

import dev.wildware.composegl.ui.effect.ShaderEffect

/**
 * A GPU that writes down what it was asked to do and draws nothing.
 *
 * What lets the canvas, the batch, the layers and the atlas be tested on every target with no
 * context anywhere: a test draws, then reads [calls] and [draws] back.
 */
class RecordingDevice(
    offscreen: Boolean = true,
    maxTextureSize: Int = 4096,
    override val masks: Boolean = true,
) : GpuDevice {

    override val limits = DeviceLimits(maxTextureSize = maxTextureSize, offscreen = offscreen)

    /** Every call but the vertex writes, one line each, in order. */
    val calls = ArrayList<String>()

    /** Every `drawShapes`, with a copy of the vertices it was handed. */
    val draws = ArrayList<Draw>()

    val effects = ArrayList<EffectDraw>()

    /** Resources given back, in order. */
    val deleted = ArrayList<DeviceResource>()

    private var next = 1

    override var prepared = false
        private set

    override fun prepare() {
        if (!prepared) calls += "prepare"
        prepared = true
    }

    override fun begin(into: FrameTarget) {
        calls += "begin(${name(into)})"
    }

    override fun end() {
        calls += "end"
    }

    override fun suspend() {
        calls += "suspend"
    }

    override fun resume() {
        calls += "resume"
    }

    override fun suspendInScene() {
        calls += "suspendInScene"
    }

    override fun resumeInScene() {
        calls += "resumeInScene"
    }

    /** Whether each texture made was asked to be sampled smoothly, in order. */
    val smoothness = ArrayList<Boolean>()

    override fun texture(width: Int, height: Int, smooth: Boolean): DeviceTexture =
        FakeTexture(next++, width, height).also {
            calls += "texture(${it.id}, ${width}x$height)"
            smoothness += smooth
        }

    override fun write(texture: DeviceTexture, x: Int, y: Int, width: Int, height: Int, source: ByteArray, sourceWidth: Int) {
        calls += "write(${(texture as FakeTexture).id}, $x, $y, ${width}x$height)"
    }

    override fun offscreen(width: Int, height: Int, depth: Boolean): DeviceTarget =
        FakeTarget(next++, FakeTexture(next++, width, height), depth).also {
            calls += "offscreen(${it.id}, ${width}x$height${if (depth) ", depth" else ""})"
        }

    override fun delete(resource: DeviceResource) {
        deleted += resource
        calls += "delete(${name(resource)})"
    }

    override fun target(target: FrameTarget, x: Int, y: Int, width: Int, height: Int) {
        calls += "target(${name(target)}, $x, $y, $width, $height)"
    }

    override fun scissor(x: Int, y: Int, width: Int, height: Int) {
        calls += "scissor($x, $y, $width, $height)"
    }

    override fun noScissor() {
        calls += "noScissor"
    }

    override fun clear(red: Float, green: Float, blue: Float, alpha: Float) {
        calls += "clear($red, $green, $blue, $alpha)"
    }

    override fun vertices(quads: Int): VertexStream = FakeStream(quads)

    override fun drawShapes(vertices: VertexStream, quads: Int, texture: DeviceTexture, blend: Blend, projection: FloatArray) =
        drawShapes(vertices, quads, texture, blend, projection, mask = null, ShapeProgram.Full)

    override fun drawShapes(
        vertices: VertexStream,
        quads: Int,
        texture: DeviceTexture,
        blend: Blend,
        projection: FloatArray,
        mask: ClipMask?,
    ) = drawShapes(vertices, quads, texture, blend, projection, mask, ShapeProgram.Full)

    /** Written down as `drawShapes(quads, texture, blend)`, with `masked` and the program after when not the common one. */
    override fun drawShapes(
        vertices: VertexStream,
        quads: Int,
        texture: DeviceTexture,
        blend: Blend,
        projection: FloatArray,
        mask: ClipMask?,
        program: ShapeProgram,
    ) {
        prepared = true
        val stream = vertices as FakeStream
        val floats = stream.floats.copyOf(quads * 4 * ShapeVertex.Floats)
        draws += Draw(quads, texture, blend, projection.copyOf(), floats, mask?.let(::Mask), program)
        val masked = if (mask != null) ", masked" else ""
        val which = if (program == ShapeProgram.Common) "" else ", ${program.name.lowercase()}"
        calls += "drawShapes($quads, ${name(texture)}, $blend$masked$which)"
    }

    override fun drawEffect(effect: ShaderEffect, picture: DeviceTexture, quad: EffectQuad, blend: Blend) {
        prepared = true
        effects += EffectDraw(
            effect, picture, blend, quad.left, quad.top, quad.right, quad.bottom, quad.alpha,
            listOf(quad.u, quad.v, quad.u2, quad.v2), quad.textureWidth to quad.textureHeight,
            quad.width, quad.height,
        )
        calls += "drawEffect(${effect.source.name}, ${name(picture)}, $blend)"
    }

    override fun read(x: Int, y: Int, width: Int, height: Int, into: ByteArray) {
        calls += "read($x, $y, $width, $height)"
    }

    override fun contextLost() {
        calls += "contextLost"
        prepared = false
    }

    override fun close() {
        calls += "close"
    }

    /** The calls whose names start with [prefix]. */
    fun named(prefix: String): List<String> = calls.filter { it.startsWith(prefix) }

    private fun name(of: Any): String = when (of) {
        FrameTarget.Host -> "host"
        is FakeTarget -> "target${of.id}"
        is FakeTexture -> "texture${of.id}"
        else -> of.toString()
    }

    class FakeTexture(val id: Int, override val width: Int, override val height: Int) : DeviceTexture

    class FakeTarget(val id: Int, override val texture: FakeTexture, override val depth: Boolean = false) : DeviceTarget {
        override val width: Int get() = texture.width
        override val height: Int get() = texture.height
    }

    class FakeStream(override val quads: Int) : VertexStream {
        val floats = FloatArray(quads * 4 * ShapeVertex.Floats)

        override fun set(index: Int, value: Float) {
            floats[index] = value
        }

        override fun put(from: FloatArray, count: Int) {
            from.copyInto(floats, 0, 0, count)
        }
    }

    /** A [ClipMask] as it was when it was drawn with: the canvas refills its own as it goes. */
    class Mask(mask: ClipMask) {
        val centreX = mask.centreX
        val centreY = mask.centreY
        val halfWidth = mask.halfWidth
        val halfHeight = mask.halfHeight

        /** Top-left, top-right, bottom-right, bottom-left, with top meaning up the target. */
        val corners = listOf(mask.topLeft, mask.topRight, mask.bottomRight, mask.bottomLeft)
        val pixelsAcross = mask.pixelsAcross
        val pixelsUp = mask.pixelsUp
    }

    /** One `drawShapes`, and the rounded clip it was kept inside, if any. */
    class Draw(
        val quads: Int,
        val texture: DeviceTexture,
        val blend: Blend,
        val projection: FloatArray,
        val vertices: FloatArray,
        val mask: Mask? = null,
        val program: ShapeProgram = ShapeProgram.Common,
    ) {

        /** Slot [offset] of vertex [vertex], counted across the whole draw. */
        fun at(vertex: Int, offset: Int): Float = vertices[vertex * ShapeVertex.Floats + offset]

        /** Component [component] of the attribute called [name], in vertex [vertex]. Not for a packed colour. */
        fun at(vertex: Int, name: String, component: Int = 0): Float = at(vertex, slot(name) + component)

        /** The bits of the slot a packed colour [name] rides in, in vertex [vertex], exactly as written. */
        fun bits(vertex: Int, name: String): Int = at(vertex, slot(name)).toRawBits()

        /** The fill colour of [vertex], red, green, blue and alpha as fractions, as the GPU reads them. */
        fun fill(vertex: Int): List<Float> = unpacked(bits(vertex, "a_color"))

        /** The border colour of [vertex], the same way. */
        fun border(vertex: Int): List<Float> = unpacked(bits(vertex, "a_borderColor"))

        /** The shadow colour of [vertex], the same way: for a lit surface, its light and gloss. */
        fun shadow(vertex: Int): List<Float> = unpacked(bits(vertex, "a_shadowColor"))

        private fun unpacked(bits: Int): List<Float> = (0 until 4).map { (bits ushr (it * 8) and 0xFF) / 255f }
    }

    class EffectDraw(
        val effect: ShaderEffect,
        val picture: DeviceTexture,
        val blend: Blend,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val alpha: Float,
        /** Where the picture lies in [picture]: u, v (its top), u2, v2. */
        val corners: List<Float> = emptyList(),
        /** What the shader is told the picture's size is, in pixels. */
        val pictureSize: Pair<Float, Float> = 0f to 0f,
        /** What the shader is told `u_size` is. */
        val width: Float = 0f,
        val height: Float = 0f,
    )
}

/** Where the attribute called [name] starts in a vertex, in four-byte slots. */
internal fun slot(name: String): Int = ShapeVertex.Attributes.single { it.name == name }.offset
