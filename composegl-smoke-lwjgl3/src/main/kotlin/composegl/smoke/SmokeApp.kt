package composegl.smoke

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import composegl.ComposeGlContext
import composegl.ComposeSurface
import composegl.ContextConfig
import composegl.RenderTarget
import composegl.SurfaceStats
import org.lwjgl.glfw.GLFW

/**
 * ComposeGL with no game engine: a GLFW window, an OpenGL framebuffer, and Compose drawing into
 * it. Everything LibGDX does for `ComposeOverlay`, done here by hand in about as many lines as
 * this file has.
 *
 * It exists to keep the seam honest. `composegl-core` is supposed to need a framebuffer id, a
 * density and a few callbacks — nothing engine-shaped. If that ever stops being true, this file
 * is where it shows.
 */
class SmokeApp(
    private val width: Int = 800,
    private val height: Int = 600,
    visible: Boolean = true,
) : AutoCloseable {

    private val host = GlHost(width, height, visible)
    private lateinit var context: ComposeGlContext
    private lateinit var surface: ComposeSurface

    private var cursorX = 0f
    private var cursorY = 0f

    val stats: SurfaceStats get() = surface.stats

    fun start(content: @Composable () -> Unit) {
        host.start()
        context = ComposeGlContext.create(ContextConfig(debugChecks = true))
        surface = ComposeSurface(context, host.hostServices)
        surface.setContent(content)
        surface.setRenderTarget(RenderTarget.Gl(host.frameBufferId, host.frameBufferWidth, host.frameBufferHeight))
        installCallbacks()
    }

    /** One frame: let Compose catch up, redraw it if it changed, put GL back, draw the quad. */
    fun frame() {
        surface.update(System.nanoTime())
        if (surface.needsRedraw) {
            surface.render(System.nanoTime())
            host.resetGlState()
        }
        host.clear(0.04f, 0.05f, 0.07f)
        host.blit()
        host.swap()
    }

    fun shouldClose(): Boolean = GLFW.glfwWindowShouldClose(host.window)

    fun run(content: @Composable () -> Unit) {
        start(content)
        while (!shouldClose()) frame()
    }

    // --- The input path. The GLFW callbacks below do nothing but call these, so a test can
    // --- exercise exactly what a real mouse would.

    fun onCursorPos(x: Double, y: Double): Boolean {
        cursorX = x.toFloat()
        cursorY = y.toFloat()
        return surface.sendPointerEvent(PointerEventType.Move, cursorX, cursorY, modifiers = modifiers)
    }

    fun onMouseButton(button: Int, action: Int): Boolean {
        val type = if (action == GLFW.GLFW_PRESS) PointerEventType.Press else PointerEventType.Release
        return surface.sendPointerEvent(
            type = type,
            x = cursorX,
            y = cursorY,
            button = when (button) {
                GLFW.GLFW_MOUSE_BUTTON_RIGHT -> PointerButton.Secondary
                GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> PointerButton.Tertiary
                else -> PointerButton.Primary
            },
            modifiers = modifiers,
        )
    }

    fun onScroll(deltaX: Double, deltaY: Double): Boolean = surface.sendPointerEvent(
        type = PointerEventType.Scroll,
        x = cursorX,
        y = cursorY,
        // GLFW's wheel points up; Compose's scroll delta points down.
        scrollX = -deltaX.toFloat(),
        scrollY = -deltaY.toFloat(),
        modifiers = modifiers,
    )

    fun onKey(glfwKey: Int, action: Int): Boolean {
        if (action == GLFW.GLFW_REPEAT) return surface.hasKeyboardFocus
        val key = composeKeyForGlfw(glfwKey) ?: return false
        return surface.sendKeyEvent(key, down = action == GLFW.GLFW_PRESS, modifiers = modifiers)
    }

    /** GLFW's char callback has already applied the layout and dead keys, which is what we want. */
    fun onChar(codePoint: Int): Boolean = surface.sendChar(codePoint)

    fun onFramebufferSize(newWidth: Int, newHeight: Int) {
        host.resize(newWidth, newHeight)
        surface.setRenderTarget(RenderTarget.Gl(host.frameBufferId, newWidth, newHeight))
    }

    private val modifiers: PointerKeyboardModifiers
        get() = PointerKeyboardModifiers(
            isShiftPressed = pressed(GLFW.GLFW_KEY_LEFT_SHIFT) || pressed(GLFW.GLFW_KEY_RIGHT_SHIFT),
            isCtrlPressed = pressed(GLFW.GLFW_KEY_LEFT_CONTROL) || pressed(GLFW.GLFW_KEY_RIGHT_CONTROL),
            isAltPressed = pressed(GLFW.GLFW_KEY_LEFT_ALT) || pressed(GLFW.GLFW_KEY_RIGHT_ALT),
            isMetaPressed = pressed(GLFW.GLFW_KEY_LEFT_SUPER) || pressed(GLFW.GLFW_KEY_RIGHT_SUPER),
        )

    private fun pressed(key: Int) = GLFW.glfwGetKey(host.window, key) == GLFW.GLFW_PRESS

    private fun installCallbacks() {
        GLFW.glfwSetCursorPosCallback(host.window) { _, x, y -> onCursorPos(x, y) }
        GLFW.glfwSetMouseButtonCallback(host.window) { _, button, action, _ -> onMouseButton(button, action) }
        GLFW.glfwSetScrollCallback(host.window) { _, dx, dy -> onScroll(dx, dy) }
        GLFW.glfwSetKeyCallback(host.window) { _, key, _, action, _ -> onKey(key, action) }
        GLFW.glfwSetCharCallback(host.window) { _, codePoint -> onChar(codePoint) }
        GLFW.glfwSetFramebufferSizeCallback(host.window) { _, w, h -> onFramebufferSize(w, h) }
    }

    override fun close() {
        surface.dispose()
        context.dispose()
        host.close()
    }
}

/**
 * Just enough of GLFW's keycodes for a sample. A real adapter wants the full table — see
 * `composegl-libgdx`'s.
 */
internal fun composeKeyForGlfw(glfwKey: Int): Key? = when (glfwKey) {
    in GLFW.GLFW_KEY_A..GLFW.GLFW_KEY_Z -> Key(Key.A.keyCode + (glfwKey - GLFW.GLFW_KEY_A))
    in GLFW.GLFW_KEY_0..GLFW.GLFW_KEY_9 -> Key(Key.Zero.keyCode + (glfwKey - GLFW.GLFW_KEY_0))
    GLFW.GLFW_KEY_BACKSPACE -> Key.Backspace
    GLFW.GLFW_KEY_DELETE -> Key.Delete
    GLFW.GLFW_KEY_ENTER -> Key.Enter
    GLFW.GLFW_KEY_TAB -> Key.Tab
    GLFW.GLFW_KEY_ESCAPE -> Key.Escape
    GLFW.GLFW_KEY_SPACE -> Key.Spacebar
    GLFW.GLFW_KEY_LEFT -> Key.DirectionLeft
    GLFW.GLFW_KEY_RIGHT -> Key.DirectionRight
    GLFW.GLFW_KEY_UP -> Key.DirectionUp
    GLFW.GLFW_KEY_DOWN -> Key.DirectionDown
    GLFW.GLFW_KEY_HOME -> Key.MoveHome
    GLFW.GLFW_KEY_END -> Key.MoveEnd
    GLFW.GLFW_KEY_LEFT_SHIFT -> Key.ShiftLeft
    GLFW.GLFW_KEY_RIGHT_SHIFT -> Key.ShiftRight
    GLFW.GLFW_KEY_LEFT_CONTROL -> Key.CtrlLeft
    GLFW.GLFW_KEY_RIGHT_CONTROL -> Key.CtrlRight
    else -> null
}
