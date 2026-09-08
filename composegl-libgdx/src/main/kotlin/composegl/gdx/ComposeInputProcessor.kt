package composegl.gdx

import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputProcessor
import composegl.ComposeSurface

/**
 * Turns LibGDX input into Compose input.
 *
 * Every method returns whether Compose consumed the event, which is exactly what LibGDX's
 * `InputMultiplexer` wants: put the overlay first and the game second, and a click on a button
 * stops at the button while a click on the world falls through.
 *
 * Two conversions happen on the way in. Coordinates are scaled from LibGDX's logical window pixels
 * to the framebuffer's physical pixels, which differ on an HDPI display. And modifier state is
 * read from the keyboard at event time, because LibGDX events carry no modifier bits.
 */
internal class ComposeInputProcessor(
    private val surface: ComposeSurface,
    /** Physical pixels per logical pixel, read fresh: a window can move to another monitor. */
    private val scale: () -> Float = { densityOf(Gdx.graphics.backBufferWidth, Gdx.graphics.width) },
    private val pointerType: () -> PointerType = ::defaultPointerType,
    private val modifiers: () -> PointerKeyboardModifiers = ::currentModifiers,
) : InputProcessor {

    private var lastX = 0f
    private var lastY = 0f

    override fun keyDown(keycode: Int): Boolean = sendKey(keycode, down = true)

    override fun keyUp(keycode: Int): Boolean = sendKey(keycode, down = false)

    /**
     * The character a keystroke produced, after the layout and any dead keys. This, not [keyDown],
     * is what puts letters into a text field.
     *
     * LibGDX also routes control characters here — Backspace arrives as `\b`, Enter as `\r`,
     * Escape as `0x1B` — and every one of those already went through [keyDown], where Compose's
     * text field handled it. Committing them as well inserts an unprintable glyph: one square per
     * press, and a row of them if the key is held. So they are dropped, and consumed only if the
     * HUD has focus, because then the whole keystroke was the HUD's.
     */
    override fun keyTyped(character: Char): Boolean {
        if (Character.isISOControl(character)) return surface.hasKeyboardFocus
        return surface.sendChar(character.code)
    }

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean =
        sendPointer(PointerEventType.Press, screenX, screenY, pointer, button)

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean =
        sendPointer(PointerEventType.Release, screenX, screenY, pointer, button)

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean =
        sendPointer(PointerEventType.Move, screenX, screenY, pointer, button = -1)

    override fun mouseMoved(screenX: Int, screenY: Int): Boolean =
        sendPointer(PointerEventType.Move, screenX, screenY, pointer = 0, button = -1)

    /**
     * A cancelled touch — the platform took the gesture away, e.g. a system gesture started. The
     * capture has to go, or the pointer would stay Compose's forever.
     */
    override fun touchCancelled(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        surface.cancelPointerInput()
        return false
    }

    override fun scrolled(amountX: Float, amountY: Float): Boolean =
        surface.sendPointerEvent(
            type = PointerEventType.Scroll,
            x = lastX,
            y = lastY,
            scrollX = amountX,
            scrollY = amountY,
            pointerType = pointerType(),
            modifiers = modifiers(),
        )

    private fun sendKey(keycode: Int, down: Boolean): Boolean {
        val key = composeKeyFor(keycode) ?: return false
        return surface.sendKeyEvent(key, down = down, modifiers = modifiers())
    }

    private fun sendPointer(
        type: PointerEventType,
        screenX: Int,
        screenY: Int,
        pointer: Int,
        button: Int,
    ): Boolean {
        val scale = this.scale()
        lastX = screenX * scale
        lastY = screenY * scale
        return surface.sendPointerEvent(
            type = type,
            x = lastX,
            y = lastY,
            pointerId = pointer,
            button = composeButtonFor(button),
            pointerType = pointerType(),
            modifiers = modifiers(),
        )
    }
}

/**
 * On desktop, LibGDX reports mouse clicks through the touch methods. Telling Compose it is a
 * mouse matters: hover, cursors and right-click all depend on it.
 */
internal fun defaultPointerType(): PointerType = when (Gdx.app.type) {
    Application.ApplicationType.Android, Application.ApplicationType.iOS -> PointerType.Touch
    else -> PointerType.Mouse
}

/** LibGDX's `Input.Buttons` to Compose's. Null means "no button changed state". */
internal fun composeButtonFor(button: Int): PointerButton? = when (button) {
    Input.Buttons.LEFT -> PointerButton.Primary
    Input.Buttons.RIGHT -> PointerButton.Secondary
    Input.Buttons.MIDDLE -> PointerButton.Tertiary
    Input.Buttons.BACK -> PointerButton.Back
    Input.Buttons.FORWARD -> PointerButton.Forward
    else -> null
}
