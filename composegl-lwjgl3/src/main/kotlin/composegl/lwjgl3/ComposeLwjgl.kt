package composegl.lwjgl3

import composegl.ComposeGlContext
import composegl.ContextConfig
import composegl.ComposeGlUnsupportedException
import composegl.CursorShape
import composegl.HostServices
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL32C

/**
 * The one [ComposeGlContext] for the process's GL context.
 *
 * You do not have to call [init] — the first [ComposeOverlay] creates it. Call it if you would
 * rather hear about an unsupported GL version at startup than at the first frame of UI.
 *
 * Call [dispose] when the window is going away.
 */
object ComposeLwjgl {

    private var context: ComposeGlContext? = null

    /** @throws ComposeGlUnsupportedException if the current GL context is older than 3.0. */
    fun init(config: ContextConfig = ContextConfig()) {
        context ?: run { context = createContext(config) }
    }

    internal fun context(config: ContextConfig = ContextConfig()): ComposeGlContext =
        context ?: createContext(config).also { context = it }

    private fun createContext(config: ContextConfig): ComposeGlContext {
        requireGlVersion()
        return ComposeGlContext.create(config)
    }

    fun dispose() {
        context?.dispose()
        context = null
    }
}

/**
 * Skia's GL backend needs a 3.0-era context. GLFW defaults to whatever the driver feels like
 * giving you, which is often GL 2.1 on Mesa, so this is worth checking with a message that says
 * what to change.
 */
internal fun requireGlVersion() {
    val major = GL32C.glGetInteger(GL32C.GL_MAJOR_VERSION)
    if (major >= 3) return
    val version = GL32C.glGetString(GL32C.GL_VERSION) ?: "unknown"
    throw ComposeGlUnsupportedException(
        "ComposeGL needs OpenGL 3.0+, but this context reports \"$version\". Ask for one before " +
            "creating the window:\n" +
            "  glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)\n" +
            "  glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2)\n" +
            "  glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)",
    )
}

/**
 * Everything ComposeGL needs from a platform, answered by GLFW.
 *
 * @param window the GLFW window handle. Cursor, clipboard and density all come from it.
 */
class GlfwHostServices(private val window: Long) : HostServices {

    private val cursors = HashMap<CursorShape, Long>()

    // Reused: density is read on every mouse move, and this is a hot path.
    private val frameBufferSize = IntArray(1)
    private val windowSize = IntArray(1)
    private val ignored = IntArray(1)

    /**
     * Physical pixels per logical pixel: the ratio between the framebuffer and the window. On a
     * retina display that is 2, and getting it wrong puts every click in the wrong place.
     */
    override val density: Float
        get() {
            GLFW.glfwGetFramebufferSize(window, frameBufferSize, ignored)
            GLFW.glfwGetWindowSize(window, windowSize, ignored)
            return if (windowSize[0] <= 0) 1f else frameBufferSize[0].toFloat() / windowSize[0]
        }

    /** Nothing to do: a GLFW game loop is already drawing every frame. */
    override fun requestFrame() = Unit

    override fun setCursor(cursor: CursorShape) {
        val handle = cursors.getOrPut(cursor) {
            GLFW.glfwCreateStandardCursor(
                when (cursor) {
                    CursorShape.Default -> GLFW.GLFW_ARROW_CURSOR
                    CursorShape.Text -> GLFW.GLFW_IBEAM_CURSOR
                    CursorShape.Hand -> GLFW.GLFW_POINTING_HAND_CURSOR
                    CursorShape.Crosshair -> GLFW.GLFW_CROSSHAIR_CURSOR
                },
            )
        }
        GLFW.glfwSetCursor(window, handle)
    }

    override fun getClipboard(): String? = GLFW.glfwGetClipboardString(window)

    override fun setClipboard(text: String) = GLFW.glfwSetClipboardString(window, text)

    /** Desktop has no soft keyboard. */
    override fun showSoftKeyboard(visible: Boolean) = Unit

    internal fun dispose() {
        cursors.values.forEach(GLFW::glfwDestroyCursor)
        cursors.clear()
    }
}
