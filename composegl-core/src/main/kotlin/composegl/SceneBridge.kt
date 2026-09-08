@file:OptIn(InternalComposeUiApi::class)

package composegl

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.PointerEventResult
import androidx.compose.ui.scene.hasInvalidations
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineDispatcher
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

    /** ComposeGL's answer to "what platform am I on". Filled in by [GamePlatformContext]. */
    private val platformContext: PlatformContext = PlatformContext.Empty()

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

    fun setContent(content: @Composable () -> Unit) {
        scene.setContent(content = content)
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
