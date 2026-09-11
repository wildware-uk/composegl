package composegl.lwjgl3

import composegl.ui.backend.Clipboard
import composegl.ui.backend.MapTextureSource
import composegl.ui.backend.RecordingSoftKeyboard
import composegl.ui.backend.SoftKeyboard
import composegl.ui.backend.TextureSource
import composegl.ui.backend.UiBackend
import org.lwjgl.glfw.GLFW

/** The system clipboard, as GLFW sees it. One call each way. */
class GlfwClipboard(private val window: GlfwWindow) : Clipboard {

    override fun read(): String? = GLFW.glfwGetClipboardString(window.handle)

    override fun write(text: String) = GLFW.glfwSetClipboardString(window.handle, text)
}

/**
 * Everything the toolkit needs from the outside world, with no engine providing any of it.
 *
 * The second backend, and the reason [UiBackend] can be trusted. It is written against the same
 * interface as the LibGDX one, shares no code with it, and draws the same scenes — so anything the
 * toolkit quietly assumes about LibGDX shows up here as a picture that came out wrong or as code
 * that will not compile.
 *
 * Desktop only, and not the one to ship a game on. A game needs audio, assets, a soft keyboard,
 * Android and iOS, and LibGDX already has all of them.
 *
 * @param textures where pictures come from by name. There is no asset loader here, so a game
 *   loads its own with [GlTexture.decode] and hands over the map.
 */
class Lwjgl3Backend(
    val window: GlfwWindow,
    override val fonts: StbFonts,
    override val textures: TextureSource = MapTextureSource(),
) : UiBackend, AutoCloseable {

    override val canvas = GlCanvas(fonts)

    override val clipboard: Clipboard = GlfwClipboard(window)

    /** Desktop has a keyboard already, so asking for one changes nothing but is not an error. */
    override val softKeyboard: SoftKeyboard = RecordingSoftKeyboard()

    /** Lets go of the canvas and the glyph atlas. The window is the caller's to close. */
    override fun close() {
        canvas.close()
        fonts.close()
    }
}
