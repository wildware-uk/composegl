package uk.wildware.composegl.ui.backend

import uk.wildware.composegl.ui.graphics.TextureHandle
import uk.wildware.composegl.ui.graphics.UiCanvas
import uk.wildware.composegl.ui.text.FontProvider

/** Where pictures come from. Names are whatever a game registered; the toolkit never opens a file. */
interface TextureSource {

    /** Null when there is no such picture, so a missing asset is a decision rather than a crash. */
    fun texture(name: String): TextureHandle?
}

/**
 * Everything the toolkit needs from the outside world, in one place.
 *
 * This exists so that "the outside world" is a parameter rather than an assumption. The toolkit
 * draws, measures text, reads the clipboard and raises a keyboard; it does none of those itself and
 * it names no engine to get them done.
 *
 * The practical test of whether this interface is honest: a second, unrelated backend can be
 * written against it without changing a line of `composegl-ui`. There is one deliberately — the raw
 * OpenGL one — and its job is to keep this promise true rather than to be used.
 */
interface UiBackend {

    val canvas: UiCanvas

    val fonts: FontProvider

    val clipboard: Clipboard

    val softKeyboard: SoftKeyboard

    val textures: TextureSource
}
