package dev.wildware.composegl.showcase

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.widget.SceneViewState

/**
 * How the interface asks the game to draw its own world into a `SceneView`.
 *
 * The interface knows nothing about the scene, and this keeps it that way: a view says which camera
 * it wants and hands over the frontend's drawing object, and the game draws. Each call runs inside
 * a `SceneView`'s draw block, with the picture bound and cleared.
 */
interface WorldViews {

    /** The whole world, from a camera orbiting the middle of it at [yaw] and [pitch] degrees. */
    fun orbit(frame: Any, width: Int, height: Int, yaw: Float, pitch: Float, distance: Float)

    /** One drone on its own, turned [turn] degrees about its upright axis. */
    fun model(frame: Any, width: Int, height: Int, drone: Int, turn: Float)

    /** The world from just behind a drone, looking where it is going. */
    fun chase(frame: Any, width: Int, height: Int, drone: Int)

    companion object {
        /** Draws nothing: for a screen composed without a world behind it, as the tests are. */
        val None: WorldViews = object : WorldViews {
            override fun orbit(frame: Any, width: Int, height: Int, yaw: Float, pitch: Float, distance: Float) = Unit
            override fun model(frame: Any, width: Int, height: Int, drone: Int, turn: Float) = Unit
            override fun chase(frame: Any, width: Int, height: Int, drone: Int) = Unit
        }
    }
}

/**
 * The three views into the world the composegl-ui section shows, and the cameras behind them.
 *
 * - **The editor**: the level editor's viewport, in a debug window that moves, resizes and docks.
 *   Drag, the wheel, the arrows or a stick turn its camera.
 * - **The previews**: a small drone beside each row of a list. Only the one that is turning is drawn
 *   again each frame; the others were drawn once and cost nothing since.
 * - **The picture in picture**: a live feed from behind one drone, at half the panel's pixels.
 *
 * Held here rather than in the composition so the game's loop can mark them dirty from outside it,
 * which is the whole rule: the widget never decides to redraw, the game says so. [frame] is that
 * call, once a frame.
 */
class SceneViews {

    /** The editor's picture. Drawn when its camera moves, and every frame while [editorLive]. */
    val editor = SceneViewState()

    /** The picture in picture, at half the panel's pixels each way: a small feed does not need all of them. */
    val pip = SceneViewState(resolutionScale = 0.5f)

    private val previews = ArrayList<SceneViewState>()

    /** The preview beside drone [index]'s row, made the first time it is asked for. */
    fun preview(index: Int): SceneViewState {
        while (previews.size <= index) previews += SceneViewState()
        return previews[index]
    }

    /** Whether the editor's window is open. The section's switch and the window's cross both set it. */
    var editorOpen by mutableStateOf(false)

    /** Whether the editor follows the fight, and so is drawn every frame, or only when its camera moves. */
    var editorLive by mutableStateOf(true)

    /** Whether the editor renders at half its pixels, stretched: what a heavy scene would do. */
    var editorHalf: Boolean
        get() = half
        set(value) {
            half = value
            editor.resolutionScale = if (value) 0.5f else 1f
        }

    private var half by mutableStateOf(false)

    /** Whether the picture in picture is on the screen. */
    var pipOpen by mutableStateOf(false)

    /** Which drone the picture in picture follows. */
    var chasing by mutableIntStateOf(0)

    /** Which preview is turning, or -1 for none. */
    var spinning by mutableIntStateOf(0)

    /** How far the turning preview has turned, in degrees. Written a frame, so not state. */
    var turn = 0f
        private set

    // The editor's camera. Plain numbers rather than state: the draw block reads them, and a camera
    // that moves every frame would otherwise recompose the section every frame to say nothing.

    /** Round the middle of the world, in degrees. */
    var yaw = StartYaw
        private set

    /** Above the floor, in degrees. */
    var pitch = StartPitch
        private set

    /** From the middle of the world, in metres. */
    var distance = StartDistance
        private set

    /** Where the left stick is pushed, for as long as it is: a stick is held, not pressed. */
    var turnX = 0f
    var turnY = 0f

    /** Where the right stick is pushed. */
    var tipX = 0f
    var tipY = 0f

    /** Turns the editor's camera by [dYaw] and [dPitch] degrees, and asks for the view again. */
    fun orbit(dYaw: Float, dPitch: Float) {
        yaw = ((yaw + dYaw) % 360f + 360f) % 360f
        pitch = (pitch + dPitch).coerceIn(MinPitch, MaxPitch)
        editor.invalidate()
    }

    /** Moves the editor's camera [by] metres closer, or further away for a negative number. */
    fun zoom(by: Float) {
        distance = (distance - by).coerceIn(MinDistance, MaxDistance)
        editor.invalidate()
    }

    /** Puts the editor's camera back where it started. */
    fun reset() {
        yaw = StartYaw
        pitch = StartPitch
        distance = StartDistance
        editor.invalidate()
    }

    /** Moves the picture in picture on to the next of [count] drones. */
    fun chaseNext(count: Int) {
        if (count > 0) chasing = (chasing + 1) % count
    }

    /**
     * Once a frame, from the game's loop: moves what is moving and marks dirty what has to be drawn
     * again. [seconds] is how long the frame was.
     *
     * A view nobody can see can still be marked dirty; it renders when it is next on the screen.
     */
    fun frame(seconds: Float) {
        turn = (turn + seconds * TurnSpeed) % 360f
        spinning.takeIf { it >= 0 }?.let { preview(it).invalidate() }

        val x = turnX + tipX
        val y = turnY + tipY
        if (x != 0f || y != 0f) orbit(x * StickSpeed * seconds, -y * StickSpeed * seconds)

        if (editorOpen && editorLive) editor.invalidate()
        if (pipOpen) pip.invalidate()
    }

    companion object {
        const val StartYaw = 35f
        const val StartPitch = 25f
        const val StartDistance = 12f
        const val MinPitch = 5f
        const val MaxPitch = 80f
        const val MinDistance = 4f
        const val MaxDistance = 30f

        /** Degrees a second a preview turns at. */
        const val TurnSpeed = 90f

        /** Degrees a second a stick at full push turns the editor's camera. */
        const val StickSpeed = 120f
    }
}
