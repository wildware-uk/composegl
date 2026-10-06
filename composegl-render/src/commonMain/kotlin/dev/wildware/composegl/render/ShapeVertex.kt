package dev.wildware.composegl.render

import dev.wildware.composegl.ui.graphics.Colour

/**
 * The one vertex layout every device consumes: 22 slots of four bytes, described per vertex so that
 * boxes with different radii, borders, shadows and two-colour gradients all batch together.
 *
 * Every slot is a float but three. The fill, the border and the shadow are each one slot holding the
 * colour's four bytes, red first, which the GPU reads as four normalised unsigned bytes: one store to
 * write rather than four floats worked out by division. The batch writes them through a float array,
 * so those slots' bits may look like a NaN — opaque white is one — and every copy between the batch
 * and the GPU must move the bits, never the number.
 *
 * A rounded corner, a border and a soft shadow are three ways of asking how far a pixel is from the
 * edge of a rounded box, so they are one shader doing one distance calculation. Every shape program
 * reads this same layout: see [ShapeProgram].
 */
object ShapeVertex {

    /**
     * One input of the shape shader: [size] components from slot [offset] on. [packed] says the
     * components are bytes in one slot, read as fractions of 255, rather than a float each.
     */
    class Attribute(val name: String, val size: Int, val offset: Int, val packed: Boolean = false) {

        /** How many four-byte slots it takes. */
        val slots: Int get() = if (packed) 1 else size
    }

    val Attributes: List<Attribute> = listOf(
        // x, y and a w that is one for everything except a tilted picture.
        Attribute("a_position", 3, 0),
        Attribute("a_color", 4, 3, packed = true),
        Attribute("a_borderColor", 4, 4, packed = true),
        Attribute("a_shadowColor", 4, 5, packed = true),
        Attribute("a_texCoord0", 2, 6),
        Attribute("a_local", 2, 8),
        Attribute("a_halfSize", 2, 10),
        // Border width, shadow spread, antialias width. Zero antialias says "a picture".
        //
        // A negative border width draws the border outside the edge rather than inside it, and a
        // negative spread shades inside the shape rather than casting outside it.
        Attribute("a_shape", 3, 12),
        Attribute("a_radii", 4, 15),
        // Kind, then the axis. For a picture the kind says whether its texture is premultiplied.
        // A shape with no gradient and a shade falling inside it carries the shade's offset here.
        Attribute("a_gradient", 3, 19),
    )

    /** How many four-byte slots a vertex is: 88 bytes. Named for the float array the batch writes them into. */
    const val Floats = 22

    /**
     * [colour] as the one slot a packed attribute reads: its bytes red, green, blue, alpha in memory,
     * which on every little-endian device the toolkit runs on is the integer `0xAABBGGRR`. Not a
     * number: its bits are the colour, and may be a NaN's.
     */
    internal fun packed(colour: Colour): Float {
        val argb = colour.argb
        return Float.fromBits(argb and 0xFF00FF00.toInt() or (argb ushr 16 and 0xFF) or (argb and 0xFF shl 16))
    }

    /** The first gradient float of a shape: a straight gradient, or one outwards from the middle. */
    const val Linear = 1f
    const val Radial = 2f

    /**
     * The same two, filled from a strip of the atlas rather than from two colours in the vertex:
     * a gradient of more than two stops. The strip lies along one row of the atlas: it starts at the
     * quad's texture coordinate, and the u it ends at rides in the shadow's spread.
     */
    const val LinearRamp = 3f
    const val RadialRamp = 4f

    /**
     * A lit surface rather than a fill: the shape is given a height along its edge and one light
     * is shone on the normal of it. The edge's shape is the kind; how far it reaches and how strong
     * the light is ride in the gradient's axis; the light itself and how glossy the surface is ride
     * in the shadow colour's bytes, which a lit quad never uses.
     */
    const val ReliefChamfer = 5f
    const val ReliefFillet = 6f
    const val ReliefDome = 7f

    /** The first gradient float of a picture: its texture is premultiplied and is straightened first. */
    const val PremultipliedPicture = 1f

    /** An effect's vertex: a clip-space x and y, then a texture coordinate. */
    const val EffectFloats = 4
}
