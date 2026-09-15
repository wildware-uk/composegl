package dev.wildware.composegl.webgl

import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Matrix4
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.CanvasState
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.NineRegions
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.graphics.featherOutline
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextLayout
import org.khronos.webgl.WebGLFramebuffer
import org.khronos.webgl.WebGLRenderingContext as GL
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * What [UiCanvas.raw] hands a game on this backend: the context itself, the frame's projection in
 * the interface's coordinates, and the viewport it was set up with.
 *
 * The interface's own quads have been flushed by the time this arrives. Put the WebGL state back as
 * you found it.
 */
class WebGlFrame(val gl: GL, val projection: FloatArray, val viewport: Viewport)

/**
 * The toolkit, drawn with WebGL.
 *
 * The browser's backend, and the third that shares no code with the others: the batch, the shader
 * text and the font handling are this module's own, and it is held to the same scenes. The
 * reasoning is the raw OpenGL backend's, unchanged, because WebGL is OpenGL ES wearing a JavaScript
 * API — y is flipped once, the letterbox and the scale live in the viewport, and every stack
 * (clip, alpha, blend, tint) is [CanvasState]'s so nesting cannot be got wrong here.
 *
 * WebGL 1 calls only, so the same canvas runs on a context from `getContext("webgl2")` — what
 * [WebGlBackend] asks for first — and on an older browser's `"webgl"`.
 *
 * @param gl the page's context. Nothing is built on it until the first frame, or [warmUp].
 * @param fonts where text is measured, and where solid colour is sampled from, so a panel and its
 *   label are one draw call. Without it the canvas keeps a white pixel of its own and draws no text.
 */
class WebGlCanvas(val gl: GL, private val fonts: WebFonts? = null) : UiCanvas, AutoCloseable {

    private var batch: WebGlShapeBatch? = null

    private fun batch() = batch ?: WebGlShapeBatch(gl).also { batch = it }

    private var state = CanvasState(Rect.Zero)
    private var viewport: Viewport = Viewport.oneToOne(Size(1f, 1f))
    private var drawing = false

    /** How wide a softened edge is, in design units: one screen pixel, whatever the scale. */
    private var antialias = 1f

    private val projection = FloatArray(16)

    private var ownWhite: WebGlTexture? = null

    private val layers = WebGlLayers(gl)

    private var effects: WebGlEffects? = null

    private fun effects() = effects ?: WebGlEffects(gl).also { effects = it }

    /** The offscreen picture being drawn into, or null when that is the page's canvas. */
    private var layer: LayerFrame? = null

    private class LayerFrame(val bounds: Rect, val pixelWidth: Int, val pixelHeight: Int)

    /** Which framebuffer is bound, where the viewport is and whether the scissor is on. Remembered, not asked. */
    private var framebuffer: WebGLFramebuffer? = null
    private val viewportBox = IntArray(4)
    private var scissorOn = false

    override val drawCalls: Int get() = batch?.renderCalls ?: 0

    override fun warmUp() {
        batch()
        effects()
        white()
    }

    /** Whether the GPU resources exist yet. For the tests. */
    internal val warmedUp: Boolean get() = batch != null && effects != null

    override fun begin(viewport: Viewport) = begin(viewport, null)

    /** @param framebuffer what is bound right now when it is not the page — a render target's. */
    fun begin(viewport: Viewport, framebuffer: WebGLFramebuffer?) {
        check(!drawing) { "begin() was called twice without an end()" }
        drawing = true
        this.framebuffer = framebuffer
        scissor(false)
        this.viewport = viewport
        state = CanvasState(Rect.of(0f, 0f, viewport.design.width, viewport.design.height))
        antialias = 1f / minOf(viewport.scaleX, viewport.scaleY).coerceAtLeast(0.0001f)

        setViewport(
            viewport.origin.x.roundToInt(),
            (viewport.physical.height - viewport.origin.y - viewport.design.height * viewport.scaleY).roundToInt(),
            (viewport.design.width * viewport.scaleX).roundToInt(),
            (viewport.design.height * viewport.scaleY).roundToInt(),
        )
        orthographic(projection, viewport.design.width, viewport.design.height)
        batch().begin(projection)
    }

    override fun end() {
        check(drawing) { "end() without a begin()" }
        batch().end()
        scissor(false)
        setViewport(0, 0, viewport.physical.width.roundToInt(), viewport.physical.height.roundToInt())
        drawing = false
        layers.trim()
        check(state.isBalanced) { "a clip, an alpha, a blend or a tint was pushed and never popped" }
    }

    // --- shapes ---

    override fun rect(rect: Rect, colour: Colour, corner: Float) {
        if (state.isHidden || rect.isEmpty) return
        shape(rect, colour, corner, corner, corner, corner, Colour.Transparent, 0f, Colour.Transparent, 0f)
    }

    override fun rect(rect: Rect, brush: Brush, corner: Float) = gradient(rect, brush, corner, corner, corner, corner)

    override fun rect(rect: Rect, brush: Brush, corners: Corners) =
        gradient(rect, brush, corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft)

    @Suppress("LongParameterList")
    private fun gradient(rect: Rect, brush: Brush, topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float) {
        if (state.isHidden || rect.isEmpty) return
        val axis = (brush as? Brush.Linear)?.axis(rect.width, rect.height)
        batch().gradient(
            white = white(),
            left = rect.left,
            bottom = flip(rect.bottom),
            width = rect.width,
            height = rect.height,
            start = brush.first.inForce(),
            end = brush.last.inForce(),
            radial = brush is Brush.Radial,
            axisX = axis?.x ?: 0f,
            axisY = -(axis?.y ?: 0f),
            topLeft = topLeft,
            topRight = topRight,
            bottomRight = bottomRight,
            bottomLeft = bottomLeft,
            aa = antialias,
        )
    }

    override val drawsGradients: Boolean get() = true

    override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) {
        if (state.isHidden || rect.isEmpty || width <= 0f) return
        shape(rect, Colour.Transparent, corner, corner, corner, corner, colour, width, Colour.Transparent, 0f)
    }

    override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) {
        if (state.isHidden || spread <= 0f) return
        shape(rect, Colour.Transparent, corner, corner, corner, corner, Colour.Transparent, 0f, colour, spread)
    }

    override fun rect(rect: Rect, colour: Colour, corners: Corners) {
        if (state.isHidden || rect.isEmpty) return
        shape(rect, colour, corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft, Colour.Transparent, 0f, Colour.Transparent, 0f)
    }

    override fun border(rect: Rect, colour: Colour, width: Float, corners: Corners) {
        if (state.isHidden || rect.isEmpty || width <= 0f) return
        shape(rect, Colour.Transparent, corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft, colour, width, Colour.Transparent, 0f)
    }

    override fun shadow(rect: Rect, colour: Colour, spread: Float, corners: Corners) {
        if (state.isHidden || spread <= 0f) return
        shape(rect, Colour.Transparent, corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft, Colour.Transparent, 0f, colour, spread)
    }

    override val roundsCornersSeparately: Boolean get() = true

    override fun fan(points: FloatArray, colour: Colour) {
        if (state.isHidden || points.size < 6) return
        val flipped = FloatArray(points.size) { if (it % 2 == 0) points[it] else flip(points[it]) }
        batch().fan(white(), flipped, colour.inForce())
    }

    @Suppress("LongParameterList")
    private fun shape(
        rect: Rect,
        fill: Colour,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float,
        border: Colour,
        borderWidth: Float,
        shadow: Colour,
        shadowSpread: Float,
    ) {
        batch().shape(
            white = white(),
            left = rect.left,
            bottom = flip(rect.bottom),
            width = rect.width,
            height = rect.height,
            fill = fill.inForce(),
            topLeft = topLeft,
            topRight = topRight,
            bottomRight = bottomRight,
            bottomLeft = bottomLeft,
            border = border.inForce(),
            borderWidth = borderWidth,
            shadow = shadow.inForce(),
            shadowSpread = shadowSpread,
            aa = antialias,
        )
    }

    // --- text and pictures ---

    override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) {
        if (state.isHidden) return
        val measured = layout as? WebTextLayout
            ?: error("this canvas can only draw text measured by WebFonts, not ${layout::class}")
        val atlas = checkNotNull(fonts) { "this canvas was made without fonts, so it cannot draw text" }.texture(gl)
        val tint = colour.inForce()
        val placedGlyphs = measured.placed
        for (index in placedGlyphs.indices) {
            val placed = placedGlyphs[index]
            batch().textured(
                name = atlas.name,
                left = x + placed.left,
                bottom = flip(y + placed.top + placed.glyph.height),
                width = placed.glyph.width,
                height = placed.glyph.height,
                u = placed.glyph.u,
                v = placed.glyph.v,
                u2 = placed.glyph.u2,
                v2 = placed.glyph.v2,
                tint = tint,
            )
        }
    }

    override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) {
        if (state.isHidden || destination.isEmpty) return
        val picture = texture as? WebGlTexture ?: notOnePicture(texture)
        slice.of(picture, source)
        batch().textured(
            name = picture.name,
            left = destination.left,
            bottom = flip(destination.bottom),
            width = destination.width,
            height = destination.height,
            u = slice.left,
            v = slice.top,
            u2 = slice.right,
            v2 = slice.bottom,
            tint = tint.inForce(),
        )
    }

    /** The corner of a texture a picture is cut from, worked out once per call into one held object. */
    private val slice = Slice()

    private class Slice {
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f

        fun of(picture: WebGlTexture, source: Rect?) {
            if (source == null) {
                left = picture.u
                top = picture.v
                right = picture.u2
                bottom = picture.v2
                return
            }
            val across = (picture.u2 - picture.u) / picture.width
            val down = (picture.v2 - picture.v) / picture.height
            left = picture.u + source.left * across
            top = picture.v + source.top * down
            right = picture.u + source.right * across
            bottom = picture.v + source.bottom * down
        }
    }

    @Suppress("LongParameterList")
    override fun image(
        texture: TextureHandle,
        destination: Rect,
        degrees: Float,
        pivotX: Float,
        pivotY: Float,
        tint: Colour,
        source: Rect?,
    ) {
        if (degrees == 0f) {
            image(texture, destination, tint, source)
            return
        }
        if (state.isHidden || destination.isEmpty) return
        val picture = texture as? WebGlTexture ?: notOnePicture(texture)
        slice.of(picture, source)
        batch().textured(
            name = picture.name,
            left = destination.left,
            bottom = flip(destination.bottom),
            width = destination.width,
            height = destination.height,
            pivotX = destination.left + destination.width * pivotX,
            pivotY = flip(destination.top + destination.height * pivotY),
            degrees = degrees,
            u = slice.left,
            v = slice.top,
            u2 = slice.right,
            v2 = slice.bottom,
            tint = tint.inForce(),
        )
    }

    override val rotatesImages: Boolean get() = true

    override fun supports(mode: BlendMode): Boolean = true

    // --- clipping, opacity, blending and tint ---

    override fun pushClip(rect: Rect) {
        state.pushClip(rect)
        applyScissor()
    }

    override fun popClip() {
        state.popClip()
        applyScissor()
    }

    override fun pushAlpha(alpha: Float) = state.pushAlpha(alpha)

    override fun popAlpha() = state.popAlpha()

    override fun pushBlend(mode: BlendMode) {
        state.pushBlend(mode)
        applyBlend()
    }

    override fun popBlend() {
        state.popBlend()
        applyBlend()
    }

    override fun pushTint(tint: Colour) = state.pushTint(tint)

    override fun popTint() = state.popTint()

    override val tints: Boolean get() = true

    private fun Colour.inForce(): Colour = modulate(state.tint).scaleAlpha(state.alpha)

    private fun applyBlend() {
        batch?.blend(state.blend, premultiplied = false)
    }

    private fun applyScissor() {
        batch().flush()
        val clip = state.clip
        val into = layer
        if (into != null) {
            scissorLayer(into, clip)
            return
        }
        if (clip.left <= 0f && clip.top <= 0f &&
            clip.right >= viewport.design.width && clip.bottom >= viewport.design.height
        ) {
            scissor(false)
            return
        }
        val topLeft = viewport.toScreen(Offset(clip.left, clip.top))
        val bottomRight = viewport.toScreen(Offset(clip.right, clip.bottom))
        scissor(true)
        gl.scissor(
            topLeft.x.roundToInt(),
            (viewport.physical.height - bottomRight.y).roundToInt(),
            (bottomRight.x - topLeft.x).roundToInt().coerceAtLeast(0),
            (bottomRight.y - topLeft.y).roundToInt().coerceAtLeast(0),
        )
    }

    private fun scissorLayer(into: LayerFrame, clip: Rect) {
        if (clip.left <= into.bounds.left && clip.top <= into.bounds.top &&
            clip.right >= into.bounds.right && clip.bottom >= into.bounds.bottom
        ) {
            scissor(false)
            return
        }
        val left = ((clip.left - into.bounds.left) * viewport.scaleX).roundToInt()
        val right = ((clip.right - into.bounds.left) * viewport.scaleX).roundToInt()
        val top = ((clip.top - into.bounds.top) * viewport.scaleY).roundToInt()
        val bottom = ((clip.bottom - into.bounds.top) * viewport.scaleY).roundToInt()
        scissor(true)
        gl.scissor(left, into.pixelHeight - bottom, (right - left).coerceAtLeast(0), (bottom - top).coerceAtLeast(0))
    }

    // --- layers ---

    override val drawsLayers: Boolean get() = true

    override fun layer(bounds: Rect, block: () -> Unit): TextureHandle? {
        check(drawing) { "layer() outside a frame" }
        if (bounds.isEmpty) return null
        val pixelWidth = ceil(bounds.width * viewport.scaleX).toInt()
        val pixelHeight = ceil(bounds.height * viewport.scaleY).toInt()
        if (pixelWidth <= 0 || pixelHeight <= 0) return null
        if (pixelWidth > MaxLayerPixels || pixelHeight > MaxLayerPixels) return null

        val target = layers.acquire(pixelWidth, pixelHeight)

        val previousFramebuffer = framebuffer
        val previousViewport = viewportBox.copyOf()
        val previousScissor = scissorOn
        val previousState = state
        val previousLayer = layer
        val previousProjection = projection.copyOf()

        batch().flush()
        layer = LayerFrame(bounds, pixelWidth, pixelHeight)
        state = previousState.forLayer(bounds)

        bindFramebuffer(target.framebufferName)
        setViewport(0, 0, pixelWidth, pixelHeight)
        scissor(false)
        gl.clearColor(0f, 0f, 0f, 0f)
        gl.clear(GL.COLOR_BUFFER_BIT)
        orthographic(projection, bounds.width, bounds.height, bounds.left)
        batch().projection(projection)
        applyBlend()

        try {
            block()
            batch().flush()
            check(state.isBalanced) { "a clip, an alpha, a blend or a tint was pushed inside a layer and never popped" }
        } finally {
            layer = previousLayer
            state = previousState
            previousProjection.copyInto(projection)
            batch().projection(projection)
            applyBlend()
            bindFramebuffer(previousFramebuffer)
            setViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
            scissor(previousScissor)
            layers.release(target)
        }

        // A framebuffer's first row is its bottom one, so the picture comes back with v swapped.
        return WebGlTexture(gl, target.texture.name, pixelWidth, pixelHeight, u = 0f, v = 1f, u2 = 1f, v2 = 0f)
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, effect: ShaderEffect?) {
        if (state.isHidden || destination.isEmpty) return
        val picture = ownLayer(layer)
        if (effect != null) {
            drawThrough(effect, picture, destination)
            return
        }
        composite(picture, destination, mirrorX = false, mirrorY = false)
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, mirrorX: Boolean, mirrorY: Boolean) {
        if (state.isHidden || destination.isEmpty) return
        composite(ownLayer(layer), destination, mirrorX, mirrorY)
    }

    override val mirrorsLayers: Boolean get() = true

    /** The opacity in force as a grey, for all four channels of a premultiplied picture. */
    private fun fade(): Colour {
        val grey = (state.alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return Colour((grey shl 24) or (grey shl 16) or (grey shl 8) or grey)
    }

    private fun composite(picture: WebGlTexture, destination: Rect, mirrorX: Boolean, mirrorY: Boolean) {
        batch().blend(state.blend, premultiplied = true)
        batch().textured(
            name = picture.name,
            left = destination.left,
            bottom = flip(destination.bottom),
            width = destination.width,
            height = destination.height,
            u = if (mirrorX) picture.u2 else picture.u,
            v = if (mirrorY) picture.v2 else picture.v,
            u2 = if (mirrorX) picture.u else picture.u2,
            v2 = if (mirrorY) picture.v else picture.v2,
            tint = fade(),
        )
        batch().blend(state.blend, premultiplied = false)
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, degrees: Float, pivotX: Float, pivotY: Float) {
        if (degrees == 0f) {
            drawLayer(layer, destination)
            return
        }
        if (state.isHidden || destination.isEmpty) return
        val picture = ownLayer(layer)
        batch().blend(state.blend, premultiplied = true)
        batch().textured(
            name = picture.name,
            left = destination.left,
            bottom = flip(destination.bottom),
            width = destination.width,
            height = destination.height,
            pivotX = destination.left + destination.width * pivotX,
            pivotY = flip(destination.top + destination.height * pivotY),
            degrees = degrees,
            u = picture.u,
            v = picture.v,
            u2 = picture.u2,
            v2 = picture.v2,
            tint = fade(),
        )
        batch().blend(state.blend, premultiplied = false)
    }

    override val turnsLayers: Boolean get() = true

    override fun cutLayer(layer: TextureHandle, destination: Rect, outline: FloatArray) {
        if (state.isHidden || destination.isEmpty || outline.size < 6) return
        val picture = ownLayer(layer)
        batch().blend(state.blend, premultiplied = true)
        val solid = fade()
        val clear = Colour.Transparent
        val left = destination.left
        val top = destination.top
        val width = destination.width
        val height = destination.height
        val uSpan = picture.u2 - picture.u
        val vSpan = picture.v2 - picture.v

        featherOutline(outline, antialias) { ax, ay, aCover, bx, by, bCover, cx, cy, cCover, dx, dy, dCover ->
            batch().corners(
                picture.name,
                ax, flip(ay),
                picture.u + ((ax - left) / width).coerceIn(0f, 1f) * uSpan,
                picture.v + ((ay - top) / height).coerceIn(0f, 1f) * vSpan,
                if (aCover > 0f) solid else clear,
                bx, flip(by),
                picture.u + ((bx - left) / width).coerceIn(0f, 1f) * uSpan,
                picture.v + ((by - top) / height).coerceIn(0f, 1f) * vSpan,
                if (bCover > 0f) solid else clear,
                cx, flip(cy),
                picture.u + ((cx - left) / width).coerceIn(0f, 1f) * uSpan,
                picture.v + ((cy - top) / height).coerceIn(0f, 1f) * vSpan,
                if (cCover > 0f) solid else clear,
                dx, flip(dy),
                picture.u + ((dx - left) / width).coerceIn(0f, 1f) * uSpan,
                picture.v + ((dy - top) / height).coerceIn(0f, 1f) * vSpan,
                if (dCover > 0f) solid else clear,
            )
        }
        batch().blend(state.blend, premultiplied = false)
    }

    override val cutsLayers: Boolean get() = true

    override fun drawLayerOnto(layer: TextureHandle, destination: Rect, corners: FloatArray) {
        require(corners.size == 8) { "four corners are eight numbers, not ${corners.size}" }
        if (state.isHidden || destination.isEmpty) return
        val picture = ownLayer(layer)
        val flipped = FloatArray(8) { if (it % 2 == 0) corners[it] else flip(corners[it]) }
        batch().blend(state.blend, premultiplied = true)
        batch().textured(picture.name, flipped, picture.u, picture.v, picture.u2, picture.v2, fade())
        batch().blend(state.blend, premultiplied = false)
    }

    override val drawsLayersOnto: Boolean get() = true

    override fun drawLayer(layer: TextureHandle, destination: Rect, transform: Matrix4) {
        if (state.isHidden || destination.isEmpty) return
        val picture = ownLayer(layer)

        // Each corner through the transform, left undivided: x, y and w. The batch counts y up, so
        // y is flipped — as y' = base - y after the divide, which before it is base * w - y.
        val base = flip(0f)
        val corners = FloatArray(12)
        transform.project(destination.left, destination.top, corners, 0)
        transform.project(destination.right, destination.top, corners, 3)
        transform.project(destination.right, destination.bottom, corners, 6)
        transform.project(destination.left, destination.bottom, corners, 9)
        for (at in 0 until 12 step 3) corners[at + 1] = base * corners[at + 2] - corners[at + 1]

        batch().blend(state.blend, premultiplied = true)
        batch().projected(picture.name, corners, picture.u, picture.v, picture.u2, picture.v2, fade())
        batch().blend(state.blend, premultiplied = false)
    }

    /** It really does, dividing by depth for every pixel in the same shader as everything else. */
    override val tiltsLayers: Boolean get() = true

    private fun ownLayer(layer: TextureHandle): WebGlTexture =
        layer as? WebGlTexture ?: error("this canvas can only draw layers it made, not ${layer::class}")

    private fun bindFramebuffer(name: WebGLFramebuffer?) {
        framebuffer = name
        gl.bindFramebuffer(GL.FRAMEBUFFER, name)
    }

    private fun setViewport(x: Int, y: Int, width: Int, height: Int) {
        viewportBox[0] = x
        viewportBox[1] = y
        viewportBox[2] = width
        viewportBox[3] = height
        gl.viewport(x, y, width, height)
    }

    private fun scissor(on: Boolean) {
        scissorOn = on
        if (on) gl.enable(GL.SCISSOR_TEST) else gl.disable(GL.SCISSOR_TEST)
    }

    private fun drawThrough(effect: ShaderEffect, picture: WebGlTexture, destination: Rect) {
        batch().flush()
        effects().draw(
            effect = effect,
            texture = picture.name,
            left = clipX(destination.left),
            top = clipY(flip(destination.top)),
            right = clipX(destination.right),
            bottom = clipY(flip(destination.bottom)),
            u = picture.u,
            v = picture.v,
            u2 = picture.u2,
            v2 = picture.v2,
            textureWidth = picture.width.toFloat(),
            textureHeight = picture.height.toFloat(),
            designWidth = destination.width,
            designHeight = destination.height,
            alpha = state.alpha.coerceIn(0f, 1f),
            mode = state.blend,
        )
        // The effect set its own blending and program behind the batch's back; put ours back.
        batch().blend(state.blend, premultiplied = false)
    }

    private fun clipX(x: Float) = x * projection[0] + projection[12]

    private fun clipY(y: Float) = y * projection[5] + projection[13]

    /** The projection handed over measures y upwards, as WebGL does, so a y goes through [flip]. */
    override fun rawY(y: Float): Float = flip(y)

    override fun rawX(x: Float): Float = x

    override val handsOverRaw: Boolean get() = true

    override val movesRawOrigin: Boolean get() = true

    override fun raw(destination: Rect, block: (Any) -> Unit) {
        batch().flush()
        val moved = projection.copyOf().also {
            it[12] += rawX(destination.left) * it[0]
            it[13] += rawY(destination.bottom) * it[5]
        }
        block(WebGlFrame(gl, moved, viewport))
    }

    override fun raw(block: (Any) -> Unit) {
        batch().flush()
        block(WebGlFrame(gl, projection.copyOf(), viewport))
    }

    override fun close() {
        batch?.close()
        layers.close()
        effects?.close()
        ownWhite?.close()
        ownWhite = null
    }

    private fun white(): WebGlTexture = fonts?.white(gl) ?: ownWhite ?: WebGlTexture
        .rgba(gl, 1, 1, byteArrayOf(-1, -1, -1, -1))
        .also { ownWhite = it }

    private fun flip(y: Float) = (layer?.bounds?.bottom ?: viewport.design.height) - y

    private fun notOnePicture(texture: TextureHandle): Nothing =
        if (texture is NineRegions) throw IllegalArgumentException(NineRegions.NotOnePicture)
        else error("this canvas can only draw textures it made, not ${texture::class}")

    private companion object {

        fun orthographic(into: FloatArray, width: Float, height: Float, left: Float = 0f) {
            into.fill(0f)
            into[0] = 2f / width
            into[5] = 2f / height
            into[10] = -1f
            into[12] = -1f - left * 2f / width
            into[13] = -1f
            into[15] = 1f
        }

        /** The biggest layer this asks a browser for, each way. Past it the effect is skipped. */
        const val MaxLayerPixels = 4096
    }
}
