package dev.wildware.composegl.render

import dev.wildware.composegl.ui.effect.ShaderEffect

/**
 * A GPU that writes down what it was asked to do and draws nothing.
 *
 * What lets the canvas, the batch, the layers and the atlas be tested on every target with no
 * context anywhere: a test draws, then reads [calls] and [draws] back.
 */
class RecordingDevice(offscreen: Boolean = true, maxTextureSize: Int = 4096) : GpuDevice {

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

    override fun drawShapes(vertices: VertexStream, quads: Int, texture: DeviceTexture, blend: Blend, projection: FloatArray) {
        prepared = true
        val stream = vertices as FakeStream
        draws += Draw(quads, texture, blend, projection.copyOf(), stream.floats.copyOf(quads * 4 * ShapeVertex.Floats))
        calls += "drawShapes($quads, ${name(texture)}, $blend)"
    }

    override fun drawEffect(effect: ShaderEffect, picture: DeviceTexture, quad: EffectQuad, blend: Blend) {
        prepared = true
        effects += EffectDraw(effect, picture, blend, quad.left, quad.top, quad.right, quad.bottom, quad.alpha)
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
    }

    /** One `drawShapes`. */
    class Draw(val quads: Int, val texture: DeviceTexture, val blend: Blend, val projection: FloatArray, val vertices: FloatArray) {

        /** Float [offset] of vertex [vertex], counted across the whole draw. */
        fun at(vertex: Int, offset: Int): Float = vertices[vertex * ShapeVertex.Floats + offset]

        /** The fill colour of [vertex], red, green, blue and alpha as fractions. */
        fun fill(vertex: Int): List<Float> = (3 until 7).map { at(vertex, it) }
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
    )
}
