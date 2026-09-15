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
        // border width, shadow spread, antialias width. Zero antialias says "a picture".
        Attribute("a_shape", 3, 21),
        Attribute("a_radii", 4, 24),
        // kind, then the axis. For a picture the kind says whether its texture is premultiplied.
        Attribute("a_gradient", 3, 28),
    )

    const val Floats = 31

    /** The first gradient float of a shape: a straight gradient, or one outwards from the middle. */
    const val Linear = 1f
    const val Radial = 2f

    /** The first gradient float of a picture: its texture is premultiplied and is straightened first. */
    const val PremultipliedPicture = 1f

    /** An effect's vertex: a clip-space x and y, then a texture coordinate. */
    const val EffectFloats = 4
}
