package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.backend.Clipboard
import dev.wildware.composegl.ui.backend.MapTextureSource
import dev.wildware.composegl.ui.backend.SoftKeyboard
import dev.wildware.composegl.ui.backend.SystemCursor
import dev.wildware.composegl.ui.backend.TextureSource
import dev.wildware.composegl.ui.backend.UiBackend
import korlibs.render.GameWindow

/**
 * Everything the toolkit needs from the outside world, as KorGE provides it.
 *
 * The pieces a game would otherwise wire up one at a time — the canvas, the fonts, the clipboard,
 * the soft keyboard and the mouse cursor — behind the one interface, so a game object can hold a
 * [UiBackend] rather than a [KorgeCanvas]. Hold the interface and the same object can be given a
 * [dev.wildware.composegl.ui.backend.HeadlessBackend] in a test, with no window and no OpenGL.
 *
 * @param fonts the glyph registry, already carrying the families and sizes the interface uses.
 * @param textures where pictures come from by name: wrap a game's bitmaps in [KorgeTexture].
 * @param window the game's window, for the clipboard, the keyboard and the cursor. A function
 *   because a game builds its interface before KorGE has made one — `{ views.gameWindow }`.
 *   Without one the clipboard lives in memory and the other two do nothing.
 */
class KorgeBackend(
    override val fonts: KorgeFonts,
    override val textures: TextureSource = MapTextureSource(),
    window: () -> GameWindow? = { null },
) : UiBackend, AutoCloseable {

    override val canvas: KorgeCanvas = KorgeCanvas(fonts.atlas)

    override val clipboard: Clipboard = KorgeClipboard(window)

    override val softKeyboard: SoftKeyboard = KorgeSoftKeyboard(window)

    override val cursor: SystemCursor = KorgeSystemCursor(window)

    /** Lets go of the canvas's buffers. The fonts are bitmaps in memory and need nothing. */
    override fun close() = canvas.close()
}
