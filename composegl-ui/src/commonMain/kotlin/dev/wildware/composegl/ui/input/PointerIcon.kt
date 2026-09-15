package dev.wildware.composegl.ui.input

/**
 * The shape of the mouse cursor while it is over something.
 *
 * The platform's own cursors, by what they mean rather than what they look like, so a text field
 * says [Text] and gets whatever an I-beam looks like on the machine it is running on. The list is
 * the set every desktop has an answer for — GLFW 3.4 and LibGDX name the same ten — and nothing a
 * game would have to draw itself.
 *
 * A backend that cannot show one of them shows [Default] instead, which is always a correct answer
 * even when it is a less helpful one.
 *
 * @see dev.wildware.composegl.ui.modifier.pointerHoverIcon
 */
enum class PointerIcon {
    /** The ordinary arrow. What everything shows unless it asks for something else. */
    Default,

    /** An I-beam: this is somewhere to type, or text that can be selected. */
    Text,

    /** A pointing hand: this is a link, or something that does a thing when clicked. */
    Hand,

    /** A thin cross, for picking a precise point — a colour picker, a map. */
    Crosshair,

    /** A left-right arrow: this edge drags sideways. */
    ResizeHorizontal,

    /** An up-down arrow: this edge drags up and down. */
    ResizeVertical,

    /** A diagonal arrow from top-left to bottom-right, for that corner of a window. */
    ResizeTopLeftBottomRight,

    /** A diagonal arrow from top-right to bottom-left, for the other corner. */
    ResizeTopRightBottomLeft,

    /** Arrows four ways: this whole thing can be dragged about. */
    Move,

    /** A circle with a line through it: pointing at this is fine, but it will not do anything. */
    NotAllowed,
}
