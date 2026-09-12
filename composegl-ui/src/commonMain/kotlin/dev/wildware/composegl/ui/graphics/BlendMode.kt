package dev.wildware.composegl.ui.graphics

/**
 * How what is being drawn is combined with what is already on the screen underneath it.
 *
 * Two of them, because light only does two interesting things. Paint covers what is behind it;
 * light adds to it. A halo, an ember and a muzzle flare are light, and drawing them the ordinary
 * way makes a pale card behind them read as a coloured smudge rather than a glow.
 *
 * Set with [UiCanvas.pushBlend] and put back with [UiCanvas.popBlend]. It is a stack like the clip
 * and the opacity, but unlike them it does not compose: the innermost one wins, and popping goes
 * back to the one underneath. There is no meaningful way to combine "cover" and "add".
 *
 * More may be added later. A `when` over this that has no `else` will stop compiling the day one
 * is, so write the `else`.
 */
enum class BlendMode {

    /** Paint: what is drawn covers what is behind it, in proportion to its opacity. The default. */
    SourceOver,

    /**
     * Light: what is drawn is added to what is behind it, so overlapping glows get brighter and
     * nothing ever gets darker.
     *
     * Black is invisible in this mode, which is why art meant for it is drawn on black rather than
     * cut out. Bright art over a bright background saturates to white; that is the mode working,
     * not a bug.
     */
    Additive,
}
