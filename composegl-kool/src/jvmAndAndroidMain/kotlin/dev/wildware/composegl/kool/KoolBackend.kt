package dev.wildware.composegl.kool

import dev.wildware.composegl.render.AtlasFonts
import dev.wildware.composegl.ui.backend.Clipboard
import dev.wildware.composegl.ui.backend.MapTextureSource
import dev.wildware.composegl.ui.backend.SoftKeyboard
import dev.wildware.composegl.ui.backend.TextureSource
import dev.wildware.composegl.ui.backend.UiBackend

/**
 * Everything the toolkit needs from the outside world, inside a Kool game on the desktop or Android.
 *
 * Hold the interface rather than this class and the same game object can be given a
 * [dev.wildware.composegl.ui.backend.HeadlessBackend] in a test, with no window and no OpenGL.
 *
 * Clipboard, soft keyboard, cursor shapes and haptics are the toolkit's do-nothing ones for now: nothing
 * on a pointer-only screen asks for them.
 *
 * @param fonts the glyph registry, already carrying the families and sizes the interface uses: a
 *   `StbFonts` on the desktop, an `AndroidFonts` on Android.
 * @param textures where pictures come from by name: wrap a game's Kool textures in [KoolTexture].
 */
class KoolBackend(
    override val fonts: AtlasFonts,
    override val textures: TextureSource = MapTextureSource(),
) : UiBackend, AutoCloseable {

    override val canvas: KoolCanvas = KoolCanvas(fonts)

    override val clipboard: Clipboard = Clipboard.None

    override val softKeyboard: SoftKeyboard = SoftKeyboard.None

    /** Lets go of the canvas's GL objects: now on Kool's render thread, or at the next frame drawn. */
    override fun close() = canvas.close()
}
