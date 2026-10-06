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

    /**
     * The engine's own framebuffer is a different one from the next frame on: it has bound one of
     * its own around the interface's frames, or stopped. A device that remembers which one
     * [FrameTarget.Host] is asks again on the next frame. Nothing to do for one that does not.
     */
    fun hostTargetChanged() = Unit

    /** Around a game's own drawing inside a frame: its state back while the block runs… */
    fun suspend()

    /** …and ours again afterwards, with the target, viewport and scissor re-applied. */
    fun resume()

    /**
     * Around a game's own drawing inside a scene: its state back while the block runs, as [suspend]
     * gives it, except that the scene's picture stays bound, the viewport over all of it and the
     * scissor off, so the drawing lands in the picture…
     */
    fun suspendInScene()

    /**
     * …and ours again afterwards. What the engine left, short of the framebuffer, viewport and
     * scissor, is what it gets back when the scene ends, as it is after [resume].
     */
    fun resumeInScene()

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

    /** Vertex storage for [quads] quads of [ShapeVertex.Floats] four-byte slots a vertex, owned by the device. */
    fun vertices(quads: Int): VertexStream

    /** The first [quads] quads of [vertices], through the shape shader, sampling [texture]. */
    fun drawShapes(vertices: VertexStream, quads: Int, texture: DeviceTexture, blend: Blend, projection: FloatArray)

    /**
     * Whether the shape shader can keep what it draws inside a [ClipMask], so a rounded clip is
     * drawn in place rather than through a picture. False unless a device says so.
     */
    val masks: Boolean get() = false

    /**
     * The same, with nothing landing outside [mask], or as before when it is null.
     *
     * Only called with a mask on a device that [masks]. The default body draws without it, for a
     * device written before masks existed.
     */
    @Suppress("LongParameterList")
    fun drawShapes(
        vertices: VertexStream,
        quads: Int,
        texture: DeviceTexture,
        blend: Blend,
        projection: FloatArray,
        mask: ClipMask?,
    ) = drawShapes(vertices, quads, texture, blend, projection)

    /**
     * The same, through [program]: every quad in [vertices] is one [program] draws. [ShapeProgram.Full]
     * draws every quad there is.
     *
     * The default body draws through the one program a device written before there were several
     * has, which draws everything.
     */
    @Suppress("LongParameterList")
    fun drawShapes(
        vertices: VertexStream,
        quads: Int,
        texture: DeviceTexture,
        blend: Blend,
        projection: FloatArray,
        mask: ClipMask?,
        program: ShapeProgram,
    ) = drawShapes(vertices, quads, texture, blend, projection, mask)

    /** One picture through somebody's shader, on the quad [quad] describes. Premultiplied. */
    fun drawEffect(effect: ShaderEffect, picture: DeviceTexture, quad: EffectQuad, blend: Blend)

    /** The pixels of the current target in the box, bottom row first, RGBA, into [into]. */
    fun read(x: Int, y: Int, width: Int, height: Int, into: ByteArray)

    /** The context is gone: forget every object without deleting it. The next use rebuilds. */
    fun contextLost()

    /** Lets go of everything this device built. What was never built is not touched. */
    fun close()
}

/**
 * Which of the shape programs a draw goes through. The shader is one text either way (see
 * `render.gl.GlslSources.ShapeFragment`); what differs is which paths are compiled in.
 */
enum class ShapeProgram {

    /**
     * Letters, pictures, boxes, borders, shadows and two-colour gradients: nearly every pixel. It
     * leaves out the heavy paths so it needs fewer registers, and a phone GPU keeps more pixels in
     * flight at once.
     */
    Common,

    /**
     * What [Common] draws, for pictures held inside the corner of a bigger pooled picture (see
     * [QuadBatch.holdInside]). Holding a read takes a value every letter would otherwise have to
     * load too, so only these pay for it.
     */
    Held,

    /**
     * Everything [Common] draws, and the three paths it leaves out: a lit surface, a gradient of more
     * than two colours, and a shade inside a shape.
     */
    Full,
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

    /**
     * The engine's own framebuffer: usually the window.
     *
     * A device may ask the driver which framebuffer that is once and remember it, since asking
     * every frame makes the CPU wait for the driver. So a frame drawn into a framebuffer of the
     * game's own names that framebuffer as its target, rather than binding it and saying [Host];
     * a game that binds one around frames it cannot name a target for says so with
     * [GpuDevice.hostTargetChanged], after binding it and again after letting it go.
     */
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

    /**
     * The first [count] slots of [from], written from the start in one copy. Bit for bit: a packed
     * colour rides in a float's place, and its bits may be a NaN's that a copy by value would change.
     */
    fun put(from: FloatArray, count: Int)
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
 * A rounded rectangle the shape shader keeps what it draws inside: a rounded clip, drawn in place.
 *
 * The middle is in pixels of the target, counted from its bottom-left like everything else a device
 * is handed. The size and the corners are in the canvas's own units, and [pixelsAcross] and
 * [pixelsUp] say how many pixels one of those is, so a window stretched more one way than the other
 * still gets round corners, and the soft edge is one pixel wide however big the units are. The
 * corners are named the way they lie in the target, top meaning up, and are already held to half
 * the shorter side.
 *
 * Mutable, and refilled by the canvas as it goes, so a rounded clip costs no allocation. A device
 * reads it in [GpuDevice.drawShapes] and keeps nothing.
 */
class ClipMask {
    var centreX = 0f
    var centreY = 0f
    var halfWidth = 0f
    var halfHeight = 0f
    var topLeft = 0f
    var topRight = 0f
    var bottomRight = 0f
    var bottomLeft = 0f
    var pixelsAcross = 1f
    var pixelsUp = 1f
}

/**
 * Where a shader effect's quad goes and what its shader is told. One is kept and refilled per
 * draw, so an effect costs no allocation.
 *
 * The corners are in clip space, already through the frame's projection: the canvas knows where a
 * design coordinate ends up, the device only knows how to put a picture through a shader. `top` is
 * the clip y of the picture's top edge.
 *
 * [u], [v], [u2] and [v2] say where the picture lies in its texture — `(u, v)` its top-left corner,
 * `(u2, v2)` its bottom-right — which is often a corner of a bigger pooled picture. The shader still
 * sees the picture from 0 to 1: the device maps its reads there. [textureWidth] and [textureHeight]
 * are the picture's own size in pixels, what the shader is told as `u_textureSize`, not the
 * texture's.
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
