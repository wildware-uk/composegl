package composegl.ui.skin

import composegl.ui.geometry.Rect
import composegl.ui.geometry.Size
import composegl.ui.graphics.Colour
import composegl.ui.graphics.NinePatch
import composegl.ui.graphics.TextureHandle
import composegl.ui.graphics.UiCanvas
import composegl.ui.layout.Padding

/**
 * Something a skin can put behind a widget.
 *
 * Four kinds, because a real skin is made of all four: most of a game's interface is art cut into
 * nine, a few things are a flat rounded box the shader draws, some are a picture at its own size,
 * and plenty of states are simply nothing at all.
 *
 * Every kind answers the same three questions — how small can you be, how far in do the contents
 * sit, and draw yourself here — so a widget never asks which kind it has.
 */
sealed interface SkinDrawable {

    /** How far the widget's contents are kept from its edge. The art's own opinion, not the code's. */
    val padding: Padding

    /** The smallest this can be drawn without going wrong. A widget's minimum size starts here. */
    val minimumSize: Size

    fun drawInto(canvas: UiCanvas, destination: Rect, tint: Colour)

    /** Art cut into nine. What nearly every panel, button and frame in a shipped game is. */
    data class Patch(val patch: NinePatch) : SkinDrawable {
        override val padding: Padding get() = patch.padding
        override val minimumSize: Size get() = patch.minimumSize
        override fun drawInto(canvas: UiCanvas, destination: Rect, tint: Colour) =
            patch.drawInto(canvas, destination, tint)
    }

    /**
     * A flat rounded box, optionally outlined, drawn by the shader with no art at all.
     *
     * Worth having beside [Patch]: a default skin, a placeholder and a debug overlay all want a
     * box and no artist, and this costs no texture and no atlas slot. The outline is here rather
     * than as a fifth kind because a box with a line round it is one thing to everybody except a
     * renderer — a field, a focus ring, a selected slot — and splitting it would make every skin
     * that wants one write two.
     */
    data class Fill(
        val colour: Colour,
        val corner: Float = 0f,
        val border: Colour? = null,
        val borderWidth: Float = 0f,
        override val padding: Padding = Padding.None,
    ) : SkinDrawable {
        override val minimumSize: Size get() = Size(corner * 2f, corner * 2f)
        override fun drawInto(canvas: UiCanvas, destination: Rect, tint: Colour) {
            if (!colour.isTransparent) canvas.rect(destination, colour.modulate(tint), corner)
            if (border != null && borderWidth > 0f) {
                canvas.border(destination, border.modulate(tint), borderWidth, corner)
            }
        }
    }

    /**
     * One picture, stretched across the widget.
     *
     * For the things that are a picture rather than a frame: an icon, a portrait, a logo.
     */
    data class Image(
        val texture: TextureHandle,
        override val padding: Padding = Padding.None,
    ) : SkinDrawable {
        override val minimumSize: Size get() = Size(0f, 0f)
        override fun drawInto(canvas: UiCanvas, destination: Rect, tint: Colour) {
            canvas.image(texture, destination, tint)
        }
    }

    /**
     * Nothing is drawn.
     *
     * Not the same as a style having no background: this is a state *saying* it has none, which is
     * how a flat button's normal state stops the base style's box from showing through.
     */
    data object Blank : SkinDrawable {
        override val padding: Padding get() = Padding.None
        override val minimumSize: Size get() = Size(0f, 0f)
        override fun drawInto(canvas: UiCanvas, destination: Rect, tint: Colour) = Unit
    }
}
