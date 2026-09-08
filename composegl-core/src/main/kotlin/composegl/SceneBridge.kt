@file:OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)

package composegl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.PointerEventResult
import androidx.compose.ui.scene.hasInvalidations
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.PlatformTextInputService
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import org.jetbrains.skia.Canvas

/**
 * The one place ComposeGL touches `@InternalComposeUiApi`.
 *
 * Everything Compose calls internal — `ComposeScene`, `CanvasLayersComposeScene`,
 * `FrameRecomposer`, `PlatformContext`, `PointerEventResult` — is used here and nowhere else, so
 * upgrading Compose Multiplatform is a diff against one file plus the integration suite.
 *
 * @see docs/superpowers/specs/2026-09-08-composegl-design.md §5
 */
internal class SceneBridge(
    dispatcher: CoroutineDispatcher,
    host: HostServices,
    fontScale: Float,
    invalidate: () -> Unit,
) {

    private val textInput = GameTextInput(host)

    private val platformContext: PlatformContext = GamePlatformContext(host, textInput)

    private val clipboard = GameClipboard(host)
    private val clipboardManager = GameClipboardManager(host)

    /**
     * Compose's own host-side driver: it owns the frame clock, the `Recomposer`, the two work
     * queues, and the `GlobalSnapshotManager` registration. One per surface, so two surfaces
     * cannot advance each other's animations.
     */
    private val recomposer = FrameRecomposer(dispatcher, invalidate)

    private val scene: ComposeScene = CanvasLayersComposeScene(
        frameRecomposer = recomposer,
        density = Density(host.density, fontScale),
        layoutDirection = LayoutDirection.Ltr,
        size = null,
        platformContext = platformContext,
        invalidateLayout = invalidate,
        invalidateDraw = invalidate,
    )

    private var closed = false

    /**
     * Compose's desktop defaults for the clipboard go through AWT, so ComposeGL provides its own
     * on the way in. Doing it here, rather than hoping [PlatformContext] offers a hook, keeps it
     * working whatever the pinned Compose version does.
     */
    @Suppress("DEPRECATION")
    fun setContent(content: @Composable () -> Unit) {
        scene.setContent {
            CompositionLocalProvider(
                LocalClipboard provides clipboard,
                LocalClipboardManager provides clipboardManager,
            ) {
                content()
            }
        }
    }

    /** Applies state writes, ticks the clock, and lets recomposition run. */
    fun performFrame(frameTimeNanos: Long) {
        recomposer.performFrame(frameTimeNanos)
    }

    fun measureAndLayout() {
        scene.measureAndLayout()
    }

    /** True when layout or draw is outstanding — i.e. the last rendered frame is now stale. */
    val hasInvalidations: Boolean get() = scene.hasInvalidations()

    fun draw(canvas: Canvas) {
        scene.draw(canvas.asComposeCanvas())
    }

    fun setSize(width: Int, height: Int) {
        scene.size = IntSize(width, height)
    }

    fun setDensity(density: Float, fontScale: Float) {
        scene.density = Density(density, fontScale)
    }

    /** True while a Compose node holds keyboard focus. */
    val hasKeyboardFocus: Boolean get() = scene.focusManager.hasFocus

    fun sendPointerEvent(
        type: PointerEventType,
        x: Float,
        y: Float,
        button: PointerButton?,
        buttons: PointerButtons?,
        pointerType: PointerType,
        scrollX: Float,
        scrollY: Float,
        modifiers: PointerKeyboardModifiers,
        timeMillis: Long,
    ): Boolean = scene.sendPointerEvent(
        eventType = type,
        position = Offset(x, y),
        scrollDelta = Offset(scrollX, scrollY),
        timeMillis = timeMillis,
        type = pointerType,
        buttons = buttons,
        keyboardModifiers = modifiers,
        button = button,
    ).changeConsumed()

    fun sendKeyEvent(
        key: Key,
        down: Boolean,
        codePoint: Int,
        modifiers: PointerKeyboardModifiers,
    ): Boolean = scene.sendKeyEvent(
        KeyEvent(
            key = key,
            type = if (down) KeyEventType.KeyDown else KeyEventType.KeyUp,
            codePoint = codePoint,
            isCtrlPressed = modifiers.isCtrlPressed,
            isMetaPressed = modifiers.isMetaPressed,
            isAltPressed = modifiers.isAltPressed,
            isShiftPressed = modifiers.isShiftPressed,
        ),
    )

    /** Commits one typed character into whatever text field is focused. */
    fun sendChar(codePoint: Int): Boolean = textInput.sendChar(codePoint)

    /** True while a text field has an input session open. */
    val isTextInputActive: Boolean get() = textInput.isActive

    fun cancelPointerInput() {
        scene.cancelPointerInput()
    }

    fun close() {
        if (closed) return
        closed = true
        scene.close()
        recomposer.close()
    }
}

/**
 * `PointerEventResult.anyChangeConsumed` is `internal` in Compose, but the constructor that sets
 * it is not. The type is a value class over an `Int`, so comparing against the four constructible
 * values that have the bit set compiles to four integer comparisons.
 */
@OptIn(InternalComposeUiApi::class)
private fun PointerEventResult.changeConsumed(): Boolean =
    this == CONSUMED_00 || this == CONSUMED_01 || this == CONSUMED_10 || this == CONSUMED_11

@OptIn(InternalComposeUiApi::class)
private val CONSUMED_00 = PointerEventResult(false, true, false)

@OptIn(InternalComposeUiApi::class)
private val CONSUMED_01 = PointerEventResult(false, true, true)

@OptIn(InternalComposeUiApi::class)
private val CONSUMED_10 = PointerEventResult(true, true, false)

@OptIn(InternalComposeUiApi::class)
private val CONSUMED_11 = PointerEventResult(true, true, true)

/**
 * What Compose asks about the platform it is running on. Everything ComposeGL cannot answer is
 * left at [PlatformContext.Empty]'s default, which is the honest answer for a game surface:
 * no window manager, no accessibility bridge, no screen reader.
 */
private class GamePlatformContext(
    private val host: HostServices,
    private val textInput: GameTextInput,
) : PlatformContext.Empty() {

    /**
     * The session path. This is the one Material 3's `TextField` actually uses (S1-g); the legacy
     * [textInputService] below is kept for content that has not moved yet.
     *
     * It never returns: Compose cancels the coroutine when the field loses focus, and the
     * `finally` is how we learn about it.
     */
    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        textInput.startSession(request)
        try {
            awaitCancellation()
        } finally {
            textInput.stopSession(request)
        }
    }

    @Suppress("DEPRECATION")
    override val textInputService: PlatformTextInputService get() = textInput.legacyService


    /**
     * `windowInfo.isWindowFocused` stays true. A game surface has no focus concept we could
     * honour without a window toolkit, and reporting false would grey out the UI and hide the
     * text caret forever.
     */

    override fun setPointerIcon(pointerIcon: PointerIcon) {
        host.setCursor(
            when (pointerIcon) {
                PointerIcon.Text -> CursorShape.Text
                PointerIcon.Hand -> CursorShape.Hand
                PointerIcon.Crosshair -> CursorShape.Crosshair
                else -> CursorShape.Default
            },
        )
    }

    override val viewConfiguration: ViewConfiguration =
        object : ViewConfiguration by PlatformContext.DefaultViewConfiguration {
            /** 8dp, read live so a monitor change is picked up without rebuilding the scene. */
            override val touchSlop: Float get() = 8f * host.density
            override val longPressTimeoutMillis: Long get() = 500L
            override val doubleTapTimeoutMillis: Long get() = 300L
        }
}

/**
 * Turns typed characters into Compose edit commands.
 *
 * Key events alone do not insert text: a `KeyDown` for `A` tells a text field that a key went
 * down, not that the letter "a" should appear. Every toolkit has a second channel for the
 * character a keystroke produced after the keyboard layout, dead keys, and modifiers have had
 * their say. This is ComposeGL's.
 *
 * Compose has two shapes for that channel and content in the wild uses both, so both are here.
 * They cannot be open at once, and whichever is live gets the character.
 */
internal class GameTextInput(private val host: HostServices) {

    private var session: PlatformTextInputMethodRequest? = null
    private var legacyCommands: ((List<EditCommand>) -> Unit)? = null

    val isActive: Boolean get() = session != null || legacyCommands != null

    fun startSession(request: PlatformTextInputMethodRequest) {
        session = request
        host.showSoftKeyboard(true)
    }

    fun stopSession(request: PlatformTextInputMethodRequest) {
        if (session !== request) return
        session = null
        if (!isActive) host.showSoftKeyboard(false)
    }

    /**
     * @param codePoint a Unicode code point, so astral characters (emoji) work as one keystroke.
     * @return false when no text field is focused, which is the adapter's cue to give the
     *   character to the game instead.
     */
    fun sendChar(codePoint: Int): Boolean {
        val commands = listOf<EditCommand>(CommitTextCommand(String(Character.toChars(codePoint)), 1))
        session?.let { it.onEditCommand(commands); return true }
        legacyCommands?.let { it(commands); return true }
        return false
    }

    @Suppress("DEPRECATION")
    val legacyService: PlatformTextInputService = object : PlatformTextInputService {
        override fun startInput(
            value: TextFieldValue,
            imeOptions: ImeOptions,
            onEditCommand: (List<EditCommand>) -> Unit,
            onImeActionPerformed: (ImeAction) -> Unit,
        ) {
            legacyCommands = onEditCommand
            host.showSoftKeyboard(true)
        }

        override fun stopInput() {
            legacyCommands = null
            if (!isActive) host.showSoftKeyboard(false)
        }

        override fun showSoftwareKeyboard() = host.showSoftKeyboard(true)
        override fun hideSoftwareKeyboard() = host.showSoftKeyboard(false)
        override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) = Unit
    }
}
