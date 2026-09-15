package dev.wildware.composegl.gdx

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.utils.Disposable
import dev.wildware.composegl.ui.backend.Clipboard
import dev.wildware.composegl.ui.backend.MapTextureSource
import dev.wildware.composegl.ui.backend.SoftKeyboard
import dev.wildware.composegl.ui.backend.SystemCursor
import dev.wildware.composegl.ui.backend.TextureSource
import dev.wildware.composegl.ui.backend.UiBackend

/**
 * Everything the toolkit needs from the outside world, as LibGDX provides it.
 *
 * The pieces a game would otherwise wire up one at a time — the canvas, the fonts, the clipboard,
 * the soft keyboard and the mouse cursor — behind the one interface, so that a game object can hold a
 * [UiBackend] rather than a [GdxCanvas]. That swap is the whole point: hold the interface and the
 * same object can be given a
 * [dev.wildware.composegl.ui.backend.HeadlessBackend] in a test, with no window, no OpenGL and no
 * engine. Hold the concrete class and every test of your focus, input and lifecycle needs a GPU.
 *
 * Nothing here is required. A game that already makes its own canvas and fonts loses nothing by
 * carrying on.
 *
 * @param fonts the glyph registry, already carrying the families and sizes the interface uses. It
 *   is a parameter rather than something made here because registering a font needs a GL context
 *   and a `.ttf` this class knows nothing about.
 * @param spriteBatch what [UiCanvas.raw][dev.wildware.composegl.ui.graphics.UiCanvas.raw] hands a
 *   game. Optional, and not disposed here: it is the game's.
 * @param textures where pictures come from by name. LibGDX has an asset manager and this toolkit
 *   does not, so a game hands over the map it already has.
 */
class GdxBackend(
    override val fonts: GdxFonts,
    spriteBatch: Batch? = null,
    override val textures: TextureSource = MapTextureSource(),
) : UiBackend, Disposable {

    override val canvas: GdxCanvas = GdxCanvas(spriteBatch, fonts.atlas)

    /** Both take `Gdx.app` and `Gdx.input` when they are asked, so this can be built before either. */
    override val clipboard: Clipboard = GdxClipboard()

    override val softKeyboard: SoftKeyboard = GdxSoftKeyboard()

    /** Reads `Gdx.graphics` when a shape is asked for, like the two above. A no-op on a phone. */
    override val cursor: SystemCursor = GdxSystemCursor()

    /** Lets go of the canvas and the glyph atlas. The sprite batch stays the game's to dispose. */
    override fun dispose() {
        canvas.dispose()
        fonts.dispose()
    }
}
