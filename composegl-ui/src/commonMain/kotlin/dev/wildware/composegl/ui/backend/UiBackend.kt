package dev.wildware.composegl.ui.backend

import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.text.FontProvider

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

    /**
     * The mouse cursor, whose shape a text field or a drag handle asks for. Hand it to the
     * [dev.wildware.composegl.ui.input.PointerRouter] and the router keeps it right.
     *
     * Default [SystemCursor.None], so a backend written before there was a cursor to change — or
     * one for a platform that has none — is still a valid one.
     */
    val cursor: SystemCursor get() = SystemCursor.None

    /**
     * The phone's vibration or the pad's rumble. Defaults to [Haptics.None], so a backend written
     * before this existed, or for a platform with no motor, is still a whole one.
     */
    val haptics: Haptics get() = Haptics.None
}
