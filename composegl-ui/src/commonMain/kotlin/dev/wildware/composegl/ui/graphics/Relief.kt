package dev.wildware.composegl.ui.graphics

/**
 * What the edge of a lit box does, which is the whole of its shape as far as a light is concerned.
 *
 * See [UiCanvas.relief]. The names are a joiner's: a chamfer is a flat cut, a fillet is rolled
 * over, and a dome keeps curving across the face instead of stopping.
 */
enum class Relief {

    /** A flat cut at a constant angle: the hard bright band drawn game art has. */
    Chamfer,

    /** Rolled over, steep at the edge and flat by the top: moulded plastic. */
    Fillet,

    /** Curving the whole way across, so the middle is the only part facing straight up: a pill. */
    Dome,
}
