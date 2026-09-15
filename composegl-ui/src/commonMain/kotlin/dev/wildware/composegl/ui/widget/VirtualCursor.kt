package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.GamepadCursor
import dev.wildware.composegl.ui.input.InputSource
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.modifier.Modifier

/** The pad's cursor, for a `VirtualCursor` to switch on. Null until a game provides one. */
val LocalGamepadCursor = staticCompositionLocalOf<GamepadCursor?> { null }

@Composable
fun ProvideGamepadCursor(cursor: GamepadCursor, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalGamepadCursor provides cursor, content = content)

/**
 * A screen the pad drives with a cursor instead of with focus.
 *
 * ```kotlin
 * VirtualCursor(enabled = onMapScreen, speed = 900f, snapToTargets = true) { MapScreen() }
 * ```
 *
 * While it is on the screen and [enabled], the left stick (or the d-pad) moves a drawn cursor,
 * South is the mouse's left button wherever the cursor is, and the right stick turns the wheel.
 * Everything underneath hears ordinary pointer events — hover, press, drag, click — so a map, an
 * inventory or a skill tree written for a mouse works on a pad as it is.
 *
 * The cursor itself is a [GamepadCursor] the game makes once, next to its `GamepadNavigator`, and
 * provides with [ProvideGamepadCursor]; this switches it on and off and draws it. It is drawn only
 * while the player is actually on a pad: somebody who picks up the mouse sees the mouse's own
 * cursor, not two. And while it is on, [dev.wildware.composegl.ui.input.InputSourceTracker.isPointing]
 * says so, so focus rings keep out of the way of a cursor as they do for a mouse.
 *
 * Leaving the screen switches the cursor off, which lets go of anything it was dragging without
 * dropping it.
 *
 * @param enabled whether the pad drives the cursor here. False leaves the pad to move focus.
 * @param speed how far a fully pushed stick moves the cursor in a second.
 * @param snapToTargets whether the cursor slows over clickable things and settles on the one it
 *   was let go near.
 * @param colour the cursor's fill.
 * @param targetColour its fill over something it could click.
 * @param outline the dark edge that keeps it readable over a light map.
 * @throws IllegalStateException when [enabled] and no [GamepadCursor] was provided, since a pad
 *   that silently does nothing on the one screen it was meant for is a bug nobody sees in a test.
 */
@Composable
fun VirtualCursor(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    speed: Float = 900f,
    snapToTargets: Boolean = true,
    colour: Colour = Colour.White,
    targetColour: Colour = DefaultTargetColour,
    outline: Colour = Colour.Black,
    content: @Composable () -> Unit,
) {
    val cursor = LocalGamepadCursor.current
    check(!enabled || cursor != null) {
        "VirtualCursor is enabled but there is no GamepadCursor to drive; wrap the screen in " +
            "ProvideGamepadCursor(cursor) and send the pad's events to it"
    }
    val source = LocalInputSource.current

    // Inside a VirtualCursor that is already driving the cursor, this one leaves it to that one:
    // one arrow, one speed, and nothing switched off when the inner screen goes.
    val outer = LocalCursorDriven.current
    val drives = enabled && cursor != null && !outer

    // Two switched on side by side both drive, but the first to come leads: one arrow, one speed.
    val claim = remember { Any() }
    val leads = drives && cursor.claims.firstOrNull() === claim

    if (drives) {
        DisposableEffect(cursor, source) {
            cursor.claims += claim
            cursor.enabled = true
            source.padPoints = true
            onDispose {
                cursor.claims -= claim
                if (cursor.claims.isEmpty()) {
                    cursor.enabled = false
                    source.padPoints = false
                }
            }
        }
    }
    if (leads) {
        // Only the one leading sets these, so a switched-off VirtualCursor elsewhere on the screen
        // cannot slow the cursor down, and cannot switch it off either.
        SideEffect {
            cursor.speed = speed
            cursor.snapToTargets = snapToTargets
        }
    }

    Box(modifier) {
        CompositionLocalProvider(LocalCursorDriven provides (outer || drives), content = content)
        // Last, so it is drawn over everything the screen drew.
        if (leads && source.current == InputSource.Gamepad) {
            CursorArrow(cursor, colour, targetColour, outline)
        }
    }
}

/** Whether a `VirtualCursor` above is already driving the cursor. */
private val LocalCursorDriven = staticCompositionLocalOf { false }

/**
 * The arrow, in a scope of its own.
 *
 * It reads the cursor's position during composition, so a moving cursor recomposes this and only
 * this — and a game checking whether the frame changed sees that it did, where a position read only
 * while drawing would leave the cursor frozen on a screen that decided nothing had changed.
 */
@Composable
private fun CursorArrow(cursor: GamepadCursor, colour: Colour, targetColour: Colour, outline: Colour) {
    val at = cursor.position
    val fill = if (cursor.overTarget) targetColour else colour
    val pressed = cursor.pressed
    val painter = remember(at, fill, outline, pressed) { ArrowPainter(at, fill, outline, if (pressed) PressedScale else 1f) }
    LeafLayout(Modifier, name = "virtual-cursor", draw = painter.draw)
}

/**
 * A pointer arrow with its tip on the cursor's position: an outline, then the fill over it.
 *
 * The draw pass hands a leaf its own rectangle, but the cursor goes wherever the cursor is, so the
 * rectangle is ignored and the arrow is drawn at the position, which is already in the root's
 * coordinates.
 */
private class ArrowPainter(at: Offset, private val fill: Colour, private val outline: Colour, scale: Float) {

    private val edge = shape(OutlineShape, at, scale)
    private val body = shape(FillShape, at, scale)

    val draw: UiCanvas.(Rect) -> Unit = {
        fan(edge, outline)
        fan(body, fill)
    }

    private fun shape(points: FloatArray, at: Offset, scale: Float) = FloatArray(points.size) { index ->
        val origin = if (index % 2 == 0) at.x else at.y
        origin + points[index] * scale
    }
}

/**
 * The arrow, tip first so the fan's hub is the tip: down the left edge, into the notch, out to the
 * right wing. Every other point can be seen from the tip, which is what lets one fan fill a shape
 * with a notch in it.
 */
private val FillShape = floatArrayOf(0f, 0f, 0f, 18f, 5f, 14f, 13f, 13f)

/** The same arrow grown by about a unit and a half all round, drawn first as its edge. */
private val OutlineShape = floatArrayOf(-1.5f, -3.5f, -1.5f, 21.5f, 5f, 16.5f, 17f, 14.5f)

/** How much smaller the arrow is while South is held, which is all the feedback a press needs. */
private const val PressedScale = 0.85f

private val DefaultTargetColour = Colour.rgb(0xFFD54A)
