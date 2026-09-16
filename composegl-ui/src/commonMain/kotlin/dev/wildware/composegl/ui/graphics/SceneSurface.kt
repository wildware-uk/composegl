package dev.wildware.composegl.ui.graphics

/**
 * An offscreen picture with a depth buffer that a game's own 3D scene was drawn into, made by
 * [UiCanvas.scene] and drawn back with [UiCanvas.image] like any other picture.
 *
 * Its [width] and [height] are real pixels: what the canvas actually made, which on a small GPU can
 * be less than was asked for.
 *
 * The canvas that made it is the only one that can draw it or fill it again. [close] gives it back
 * to the device, on the thread that holds the context; drawing it afterwards is an error.
 */
interface SceneSurface : TextureHandle, AutoCloseable {

    /** Whether [close] has been called. */
    val closed: Boolean
}

/**
 * What a canvas hands [UiCanvas.scene]'s block while a [SceneSurface] is bound: the two things a
 * scene needs from it, and nothing that would start the interface's own batch.
 *
 * Only good inside that block.
 */
interface SceneTarget {

    /** How wide the bound picture really is, in pixels. */
    val width: Int

    /** How tall the bound picture really is, in pixels. */
    val height: Int

    /** Fills the whole picture with [colour], and its depth buffer with the far plane. */
    fun clear(colour: Colour)

    /**
     * The backend's own drawing object, with the picture already bound and the viewport covering
     * all of it: the same object [UiCanvas.raw] hands over on that backend. The game's state is its
     * own while the block runs, and the canvas puts the engine's back afterwards.
     */
    fun raw(block: (Any) -> Unit)
}
