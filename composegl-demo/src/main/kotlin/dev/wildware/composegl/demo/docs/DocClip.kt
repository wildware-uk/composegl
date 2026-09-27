package dev.wildware.composegl.demo.docs

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.geometry.Offset

/**
 * One moving picture for the documentation.
 *
 * The same idea as [DocShot] with the shutter left open: the interface is composed on the real
 * backend, a hand does something to it one step a frame, and every frame is read back. A still
 * cannot show what a press does, because the whole of a press is the change between two frames.
 *
 * The frames are written to `build/clips/<name>`, and turned into `<name>.gif` beside the wiki's
 * other pictures and an `<name>.mp4` next to the frames — a GIF because that is what a wiki page
 * can show, and an MP4 because a gradient in 256 colours bands and a lit surface is all gradient.
 *
 * @param name the files it becomes: `docs/wiki/images/<name>.gif` and `build/clips/<name>.mp4`.
 * @param fps how fast it plays, and the rate the interface is advanced at between frames.
 * @param hand what the mouse does, one step a frame. A [Hand.Wait] is frames with it held still.
 */
internal class DocClip(
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int = 30,
    val hand: List<Hand>,
    val content: @Composable () -> Unit,
)

/**
 * One thing the mouse does while a moving picture is being taken.
 *
 * Real events through the real router, exactly as in a still: a button that goes down in one of
 * these clips is a button something really pressed, rather than one handed the state it should be
 * drawing.
 */
internal sealed interface Hand {

    /** The pointer moved there, with nothing held. */
    data class Move(val to: Offset) : Hand

    /** Put down there and left down, so the frames after it are drawn with a finger on it. */
    data class Press(val at: Offset) : Hand

    /** Let go there, which is where a click happens. */
    data class Release(val at: Offset) : Hand

    /** Frames with the hand still, so what the step before started can be seen. */
    data class Wait(val frames: Int) : Hand
}
