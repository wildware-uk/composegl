package dev.wildware.composegl.render

/**
 * The one vertex layout every device consumes: 31 floats, described per vertex so that boxes with
 * different radii, borders, shadows and gradients all batch together.
 *
 * A rounded corner, a border and a soft shadow are three ways of asking how far a pixel is from the
 * edge of a rounded box, so they are one shader doing one distance calculation.
 */
object ShapeVertex {

    class Attribute(val name: String, val size: Int, val offset: Int)

    val Attributes: List<Attribute> = listOf(
        // x, y and a w that is one for everything except a tilted picture.
        Attribute("a_position", 3, 0),
        Attribute("a_color", 4, 3),
        Attribute("a_borderColor", 4, 7),
        Attribute("a_shadowColor", 4, 11),
        Attribute("a_texCoord0", 2, 15),
        Attribute("a_local", 2, 17),
        Attribute("a_halfSize", 2, 19),
        // Border width, shadow spread, antialias width. Zero antialias says "a picture".
        //
        // A negative border width draws the border outside the edge rather than inside it, and a
        // negative spread shades inside the shape rather than casting outside it.
        Attribute("a_shape", 3, 21),
        Attribute("a_radii", 4, 24),
        // Kind, then the axis. For a picture the kind says whether its texture is premultiplied.
        // A shape with no gradient and a shade falling inside it carries the shade's offset here.
        Attribute("a_gradient", 3, 28),
    )

    const val Floats = 31

    /** The first gradient float of a shape: a straight gradient, or one outwards from the middle. */
    const val Linear = 1f
    const val Radial = 2f

    /**
     * The same two, filled from a strip of the atlas rather than from two colours in the vertex:
     * a gradient of more than two stops. Where the strip is rides in the shadow colour's slot.
     */
    const val LinearRamp = 3f
    const val RadialRamp = 4f

    /**
     * A lit surface rather than a fill: the shape is given a height along its edge and one light
     * is shone on the normal of it. The edge's shape is the kind; how far it reaches and how strong
     * the light is ride in the gradient's axis; the light itself and how glossy the surface is ride
     * in the shadow colour's slot, which a lit quad never uses.
     */
    const val ReliefChamfer = 5f
    const val ReliefFillet = 6f
    const val ReliefDome = 7f

    /** The first gradient float of a picture: its texture is premultiplied and is straightened first. */
    const val PremultipliedPicture = 1f

    /** An effect's vertex: a clip-space x and y, then a texture coordinate. */
    const val EffectFloats = 4
}
