package dev.wildware.composegl.ui.graphics

/**
 * A picture the backend knows how to draw and the toolkit does not.
 *
 * Deliberately opaque. The toolkit needs to lay out around an image and to pass it back at draw
 * time; it does not need to know that it is a LibGDX `TextureRegion` or an OpenGL name, and the
 * moment it does, the toolkit stops being portable.
 *
 * An earlier draft of this interface passed images as `Any`. That is not an abstraction, it is a
 * hole with a label on it, and it would have leaked the engine type into every call site.
 */
interface TextureHandle {

    /** In texture pixels, so a game can lay out at the art's natural size. */
    val width: Int
    val height: Int
}
