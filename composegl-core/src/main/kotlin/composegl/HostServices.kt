package composegl

/**
 * What the engine provides to ComposeGL. One per [ComposeSurface].
 *
 * Every member except [density] has a default, so the smallest useful host is:
 *
 * ```kotlin
 * val host = object : HostServices { override val density get() = 1f }
 * ```
 *
 * Nothing here mentions AWT, GL, or a window. That is deliberate: it is the only seam ComposeGL
 * needs to reach a platform, so keeping it plain is what keeps Android and iOS reachable later.
 */
interface HostServices {

    /**
     * Physical pixels per logical pixel. On desktop that is `backBufferWidth / logicalWidth`.
     * Read whenever the render target is set, so a window moved to a different-DPI monitor picks
     * up the change through [ComposeSurface.setRenderTarget].
     */
    val density: Float

    /**
     * Asks the engine to draw another frame. Called when Compose has work pending. Engines that
     * render continuously can ignore it; engines with on-demand rendering must schedule a frame.
     */
    fun requestFrame() {}

    /** Called when the pointer moves over content that wants a different cursor. */
    fun setCursor(cursor: CursorShape) {}

    /** Text on the system clipboard, or null when there is none. */
    fun getClipboard(): String? = null

    fun setClipboard(text: String) {}

    /** Called when a text field takes or loses focus. Meaningful on touch platforms. */
    fun showSoftKeyboard(visible: Boolean) {}
}

/** The cursors Compose can ask for. Anything ComposeGL cannot map becomes [Default]. */
enum class CursorShape { Default, Text, Hand, Crosshair }
