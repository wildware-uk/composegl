package dev.wildware.composegl.render

import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.graphics.BlendMode

/**
 * The GPU, as the renderer needs it and no more.
 *
 * The seam a future Vulkan, Metal or DirectX device plugs into. Everything above it — the canvas,
 * the batch, layers, the glyph atlas — is shared by every device; everything below it knows one
 * graphics API. Today there is one device, `render.gl.GlDevice`, for every flavour of OpenGL.
 *
 * Coordinates handed to a device are **pixels of the target, counted from its bottom-left**, the
 * way OpenGL counts them. A device whose API counts from the top converts, since it is the one that
 * knows how tall its target is.
 *
 * Nothing here may touch the GPU until it is first asked to draw or build: a canvas that merely
 * exists must be constructible in a plain unit test with no context anywhere.
 */
interface GpuDevice {

    /** What this device can do. May reach the driver the first time it is read. */
    val limits: DeviceLimits

    /** Builds programs and buffers now rather than in the first frame. Twice does nothing. */
    fun prepare()

    /** Whether [prepare] (or a first draw) has built what it builds. */
    val prepared: Boolean

    /**
     * Takes the engine's state for a frame drawn into [into]. The device remembers what it needs to
     * give back, according to how it was told to hand state back.
     */
    fun begin(into: FrameTarget)

    /** Gives the engine its state back. */
    fun end()

    /** Around a game's own drawing inside a frame: its state back while the block runs… */
    fun suspend()

    /** …and ours again afterwards, with the target, viewport and scissor re-applied. */
    fun resume()

    /** A texture this device owns, its pixels undefined until [write]. */
    fun texture(width: Int, height: Int, smooth: Boolean): DeviceTexture

    /**
     * Uploads the rectangle [x], [y], [width], [height] of [source] — straight RGBA, [sourceWidth]
     * pixels wide, top row first — to the same place in [texture].
     */
    @Suppress("LongParameterList")
    fun write(texture: DeviceTexture, x: Int, y: Int, width: Int, height: Int, source: ByteArray, sourceWidth: Int)

    /**
     * An offscreen picture to draw into, with its colour texture inside.
     *
     * @param depth ask for a depth buffer as well, made and given back with the picture and always
     *   its size. A 3D scene drawn into a picture without one comes out inside-out, because there
     *   is nothing to depth-test against. The interface itself never needs it, so it is off by
     *   default and a layer or an effect costs no more than it did.
     */
    fun offscreen(width: Int, height: Int, depth: Boolean = false): DeviceTarget

    /** Gives [resource] back to the driver. Adopted resources that belong to a game are left alone. */
    fun delete(resource: DeviceResource)

    /** Where the following draws land, and the part of it the frame covers. */
    fun target(target: FrameTarget, x: Int, y: Int, width: Int, height: Int)

    /** Nothing lands outside this box until [noScissor]. */
    fun scissor(x: Int, y: Int, width: Int, height: Int)

    fun noScissor()

    /**
     * Fills the whole current target (the scissor must be off) with a premultiplied colour. A
     * target that has a depth buffer has that cleared to the far plane at the same time, so a scene
     * drawn into it starts from nothing in both.
     */
    fun clear(red: Float, green: Float, blue: Float, alpha: Float)

    /** Vertex storage for [quads] quads of [ShapeVertex.Floats] floats a vertex, owned by the device. */
    fun vertices(quads: Int): VertexStream

    /** The first [quads] quads of [vertices], through the shape shader, sampling [texture]. */
    fun drawShapes(vertices: VertexStream, quads: Int, texture: DeviceTexture, blend: Blend, projection: FloatArray)

    /** One picture through somebody's shader, on the quad [quad] describes. Premultiplied. */
    fun drawEffect(effect: ShaderEffect, picture: DeviceTexture, quad: EffectQuad, blend: Blend)

    /** The pixels of the current target in the box, bottom row first, RGBA, into [into]. */
    fun read(x: Int, y: Int, width: Int, height: Int, into: ByteArray)

    /** The context is gone: forget every object without deleting it. The next use rebuilds. */
    fun contextLost()

    /** Lets go of everything this device built. What was never built is not touched. */
    fun close()
}

/** What a device can do. */
class DeviceLimits(
    /** The biggest texture it will make, each way. */
    val maxTextureSize: Int,
    /** Whether it can draw into offscreen pictures at all: layers, effects, render targets. */
    val offscreen: Boolean,
)

/** Anything a device made or adopted. */
interface DeviceResource

/**
 * A texture a device can bind. Batching compares these with `==`: two handles to the same texture
 * object must be equal, so that pictures cut from one sheet batch together.
 */
interface DeviceTexture : DeviceResource {
    val width: Int
    val height: Int
}

/** Where a frame is drawn. */
interface FrameTarget {

    /** Whatever the engine had bound when the frame began: usually the window. */
    object Host : FrameTarget
}

/** An offscreen picture a device draws into. Its [texture] is premultiplied colour. */
interface DeviceTarget : FrameTarget, DeviceResource {
    val width: Int
    val height: Int
    val texture: DeviceTexture

    /**
     * Whether it has a depth buffer to test and write against. False unless it was asked for: only
     * a game's own 3D drawing needs one, and it is cleared with the colour when it is there.
     */
    val depth: Boolean get() = false
}

/** Storage for vertices that the device can hand to the GPU without copying it first. */
interface VertexStream {
    /** How many quads it holds. */
    val quads: Int

    operator fun set(index: Int, value: Float)
}

/**
 * How a quad is combined with what is under it. The toolkit's [BlendMode], and whether the colour
 * arriving is already multiplied by its own opacity.
 *
 * The alpha half always accumulates (`ONE, ONE_MINUS_SRC_ALPHA` or `ONE, ONE`), so what lands in an
 * offscreen picture is premultiplied.
 */
enum class Blend(val additive: Boolean, val premultiplied: Boolean) {
    SourceOver(additive = false, premultiplied = false),
    Additive(additive = true, premultiplied = false),
    PremultipliedSourceOver(additive = false, premultiplied = true),
    PremultipliedAdditive(additive = true, premultiplied = true),
    ;

    companion object {
        fun of(mode: BlendMode, premultiplied: Boolean): Blend = when (mode) {
            BlendMode.SourceOver -> if (premultiplied) PremultipliedSourceOver else SourceOver
            BlendMode.Additive -> if (premultiplied) PremultipliedAdditive else Additive
        }
    }
}

/**
 * Where a shader effect's quad goes and what its shader is told. One is kept and refilled per
 * draw, so an effect costs no allocation.
 *
 * The corners are in clip space, already through the frame's projection: the canvas knows where a
 * design coordinate ends up, the device only knows how to put a picture through a shader. `top` is
 * the clip y of the picture's top edge.
 */
class EffectQuad {
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f
    var u = 0f
    var v = 0f
    var u2 = 0f
    var v2 = 0f
    var textureWidth = 0f
    var textureHeight = 0f
    var width = 0f
    var height = 0f
    var alpha = 1f
}
