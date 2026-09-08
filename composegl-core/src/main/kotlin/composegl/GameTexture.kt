package composegl

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.SurfaceOrigin

/**
 * An OpenGL texture the game renders into, wrapped so a Compose node can show it.
 *
 * This is ComposeGL pointed the other way round. Normally Compose draws into the game's frame;
 * here the game's frame is drawn inside the Compose UI — a viewport in an editor, a preview pane,
 * a level view with tool panels around it.
 *
 * The texture stays **live**: the game redraws it whenever it likes and the node shows the new
 * contents, with no copy and no upload, because Compose and the game are on the same GL context.
 *
 * ### Ownership
 *
 * Skia takes ownership of the texture. [close] releases it, and that deletes the GL texture, so
 * **whoever made the texture must not delete it themselves.** The adapters' game-framebuffer
 * helpers already work this way; if you build the texture by hand, hand it over and forget it.
 *
 * The texture must live on the same GL context as [ComposeGlContext], and everything here must
 * happen on that context's thread.
 */
class GameTexture private constructor(
    /** Size in pixels, as the game rendered it. */
    val width: Int,
    val height: Int,
    internal val image: Image,
) : AutoCloseable {

    private var closed = false

    /**
     * Bumped every time the game says it has redrawn. [GameView] reads it while drawing, so a new
     * game frame invalidates the node — which is the only way Compose could know: nothing about a
     * GL texture changing is visible to it.
     */
    private var frame by mutableIntStateOf(0)

    /**
     * Tell Compose the game has drawn a new frame into this texture.
     *
     * Without this the viewport shows the first frame forever, because a static Compose tree does
     * not redraw and ComposeGL's whole point is that it should not have to.
     */
    fun invalidate() {
        frame++
    }

    /** Read while drawing so the node redraws when [invalidate] is called. */
    internal val frameCount: Int get() = frame

    companion object {
        /** Regular 2D texture. Spelled out so core does not have to depend on a GL binding. */
        private const val GL_TEXTURE_2D = 0x0DE1

        /** Eight bits per channel, the format every engine's colour attachment uses. */
        private const val GL_RGBA8 = 0x8058

        /**
         * Wraps an existing GL texture, taking ownership of it.
         *
         * @param textureId the GL texture name, on [context]'s GL context.
         * @param origin whether the texture's first row is its top or its bottom. Engines differ:
         *   a texture the game rendered into with a y-up projection is [SurfaceOrigin.BOTTOM_LEFT].
         */
        fun adopt(
            context: ComposeGlContext,
            textureId: Int,
            width: Int,
            height: Int,
            origin: SurfaceOrigin = SurfaceOrigin.BOTTOM_LEFT,
        ): GameTexture {
            require(width > 0 && height > 0) { "A GameTexture needs a real size, got ${width}x$height" }
            val direct = context.directContext
                ?: throw ComposeGlUnsupportedException(
                    "GameTexture needs a GPU context. This one was created with createRaster().",
                )

            // The game made this texture without telling Skia, so Skia's idea of GL state is stale.
            direct.resetAll()
            val backend = BackendTexture.makeGL(
                width = width,
                height = height,
                isMipmapped = false,
                textureId = textureId,
                textureTarget = GL_TEXTURE_2D,
                textureFormat = GL_RGBA8,
            )
            val image = Image.adoptTextureFrom(direct, backend, origin, ColorType.RGBA_8888)
            return GameTexture(width, height, image)
        }
    }

    /** Releases the texture. Safe to call twice. */
    override fun close() {
        if (closed) return
        closed = true
        image.close()
    }
}

/**
 * Shows a [GameTexture] — the game's own rendering — inside the Compose layout.
 *
 * ```kotlin
 * Row {
 *     ToolPalette(Modifier.width(200.dp))
 *     GameView(viewport, Modifier.weight(1f).fillMaxHeight())
 * }
 * ```
 *
 * The texture is stretched to fill the node, so size the game's framebuffer to match the node if
 * you care about sharpness. Nothing is copied: this draws the live texture straight from the
 * shared GL context.
 */
@Composable
fun GameView(
    texture: GameTexture,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.drawBehind {
            // Reading this is what ties the node's drawing to the game's frames.
            @Suppress("UNUSED_EXPRESSION")
            texture.frameCount
            drawIntoCanvas { canvas ->
                canvas.skiaCanvas.drawImageRect(
                    image = texture.image,
                    src = Rect.makeWH(texture.width.toFloat(), texture.height.toFloat()),
                    dst = Rect.makeWH(size.width, size.height),
                    samplingMode = SamplingMode.LINEAR,
                    paint = null,
                    strict = true,
                )
            }
        },
    )
}
