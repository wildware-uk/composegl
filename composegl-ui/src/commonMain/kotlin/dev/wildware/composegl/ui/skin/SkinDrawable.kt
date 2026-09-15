package dev.wildware.composegl.ui.skin

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.NinePatch
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.graphics.box
import dev.wildware.composegl.ui.graphics.boxBorder
import dev.wildware.composegl.ui.graphics.refuseNineRegions
import dev.wildware.composegl.ui.layout.Padding

/**
 * Something a skin can put behind a widget.
 *
 * Five kinds, because a real skin is made of all five: most of a game's interface is art cut into
 * nine, a few things are a rounded box the shader draws — flat, or shaded with a gradient — some
 * are a picture at its own size, and plenty of states are simply nothing at all.
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
        val corners: Corners = Corners.None,
        val border: Colour? = null,
        val borderWidth: Float = 0f,
        override val padding: Padding = Padding.None,
    ) : SkinDrawable {

        /** The same box with one radius on every corner, which is what most skins say. */
        constructor(
            colour: Colour,
            corner: Float,
            border: Colour? = null,
            borderWidth: Float = 0f,
            padding: Padding = Padding.None,
        ) : this(colour, Corners.single(corner), border, borderWidth, padding)

        /** Kept so code that read the one radius still compiles. It is the smallest of the four. */
        @Deprecated("A fill has a radius per corner now.", ReplaceWith("corners"))
        val corner: Float get() = corners.smallest

        override val minimumSize: Size get() = corners.minimumSize
        override fun drawInto(canvas: UiCanvas, destination: Rect, tint: Colour) {
            if (!colour.isTransparent) canvas.box(destination, colour.modulate(tint), corners)
            if (border != null && borderWidth > 0f) {
                canvas.boxBorder(destination, border.modulate(tint), borderWidth, corners)
            }
        }
    }

    /**
     * A [Fill] whose colour changes across it: a rounded box painted with a [Brush].
     *
     * Its own kind rather than a brush on [Fill], because [Fill] is a published data class and a
     * widget that reads a fill's colour — a reticle, a minimap marker — should not suddenly be
     * handed something that is not one colour. The outline is still one flat colour: a gradient
     * ring is a picture, and a picture is what [Patch] is for.
     */
    data class Gradient(
        val brush: Brush,
        val corners: Corners = Corners.None,
        val border: Colour? = null,
        val borderWidth: Float = 0f,
        override val padding: Padding = Padding.None,
    ) : SkinDrawable {

        /** The same box with one radius on every corner. */
        constructor(
            brush: Brush,
            corner: Float,
            border: Colour? = null,
            borderWidth: Float = 0f,
            padding: Padding = Padding.None,
        ) : this(brush, Corners.single(corner), border, borderWidth, padding)

        /** Kept so code that read the one radius still compiles. It is the smallest of the four. */
        @Deprecated("A gradient has a radius per corner now.", ReplaceWith("corners"))
        val corner: Float get() = corners.smallest

        override val minimumSize: Size get() = corners.minimumSize
        override fun drawInto(canvas: UiCanvas, destination: Rect, tint: Colour) {
            canvas.box(destination, if (tint == Colour.White) brush else brush.modulate(tint), corners)
            if (border != null && borderWidth > 0f) {
                canvas.boxBorder(destination, border.modulate(tint), borderWidth, corners)
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
        // An atlas can hand back nine separately-cut patch pieces, which are a TextureHandle and
        // so fit here, and are not a picture. Caught where the skin says so, not at the backend.
        init { refuseNineRegions(texture) }

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

/**
 * The one colour a widget that draws shapes rather than boxes takes from this: a crosshair's arms,
 * a cooldown's wedge, a minimap's marker.
 *
 * A [SkinDrawable.Fill]'s colour, or a [SkinDrawable.Gradient]'s first — the same colour a canvas
 * with no gradients would paint, so a skin that gives one of those widgets a gradient gets what
 * it starts from rather than nothing at all. Null for art and for nothing.
 *
 * Public because those widgets live in `composegl-game`, and a game's own shape-drawing widget
 * reads its skin the same way.
 */
val SkinDrawable.flatColour: Colour?
    get() = when (this) {
        is SkinDrawable.Fill -> colour
        is SkinDrawable.Gradient -> brush.first
        else -> null
    }
