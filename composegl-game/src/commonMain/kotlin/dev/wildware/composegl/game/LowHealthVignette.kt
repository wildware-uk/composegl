package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Clocks
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.effect.Uniform
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.skin.flatColour
import dev.wildware.composegl.ui.skin.rememberStyle
import kotlin.math.PI
import kotlin.math.cos

/**
 * The red closing in round the edges of the screen when the player is nearly dead.
 *
 * Nothing at all above [threshold]: the widget composes no node, draws nothing and asks for no
 * frames while the player is fine. Below it the ring comes in as health drops, and it pulses like
 * a heartbeat that quickens the closer to dead the player is — which is the part a player reads
 * without looking, because a thing that moves is a thing the eye goes to.
 *
 * It is one shader over the whole box, through the same effect pipeline a `blur` or a
 * `colourGrade` goes through, so the falloff is a real gradient rather than a stack of rectangles.
 * A backend that cannot take a picture of a layer draws a flat wash of the same colour instead:
 * weaker, and still the right thing said.
 *
 * The beat is worked out from the clock as the frame is drawn rather than from an animation, so it
 * freezes exactly when the world does — a pause menu over a dying player is still, not throbbing —
 * and it costs the composition nothing while it is up: it recomposes when health moves and not
 * otherwise. The price of that is that the beat moves on the frames the game draws rather than on
 * the frames the toolkit reports as changed, so a host that skips drawing an unchanged frame shows
 * a still ring rather than a beating one. Every backend here draws every frame, and so does every
 * game with a world behind its interface; one that does not should pass `pulse = false` and say the
 * same thing some other way. A drifting [DamageNumberLayer] number is worked out at drawing time
 * for the same reason and reads the same way on such a host.
 *
 * The skin names the colour: `"<style>"`, whose own alpha is the most the ring ever reaches.
 *
 * ```kotlin
 * LowHealthVignette(health = player.health / player.maxHealth, threshold = 0.3f)
 * ```
 *
 * @param health how much of it is left, from 0 for dead to 1 for untouched.
 * @param threshold the fraction below which the ring starts to show. At the threshold it is
 *   nothing; at no health at all it is [strength].
 * @param strength the most of the colour that ever reaches the edge of the screen. One would be
 *   the colour at its own opacity; less leaves the game visible through it, which a player being
 *   shot at needs.
 * @param inner how far out from the middle the ring starts, as a share of the way to the corner.
 *   Bigger keeps the middle of the screen — where the fight is — clear.
 * @param pulse whether it beats at all. A game that would rather say this some other way, or one
 *   being photographed, turns it off and gets a still ring.
 * @param calmPulseMillis how long one beat takes just under the threshold.
 * @param fastPulseMillis how long one takes at no health at all.
 * @param clock which clock the beat runs on. The world's, so a pause stops it.
 */
@Composable
fun LowHealthVignette(
    health: Float,
    modifier: Modifier = Modifier,
    threshold: Float = 0.3f,
    style: String = "vignette",
    strength: Float = 0.85f,
    inner: Float = 0.3f,
    pulse: Boolean = true,
    calmPulseMillis: Int = 1_100,
    fastPulseMillis: Int = 430,
    clock: Clock = Clock.World,
) {
    val clocks = LocalClocks.current
    val colour = rememberStyle(style).background.flatColour ?: Colour.Transparent

    val hurt = hurtOf(health, threshold)
    // Nothing composed, nothing measured, nothing drawn: a healthy player pays for none of this.
    if (hurt <= 0f || colour.alpha == 0 || strength <= 0f) return

    // The clock has to be one the host is winding on, or the beat never moves.
    remember(clocks, clock) { clocks.register(clock) }

    val painter = remember(clocks, clock, colour, inner, calmPulseMillis, fastPulseMillis) {
        VignettePainter(clocks, clock, colour, inner, calmPulseMillis, fastPulseMillis)
    }
    val draw = remember(painter, hurt, strength, pulse) { painter.drawWith(hurt, strength, pulse) }

    LeafLayout(modifier.fillMaxSize(), name = "vignette", draw = draw)
}

/**
 * How badly hurt the player is: nothing at [threshold] and above, one at no health at all.
 *
 * Its own function so the edges are in one place: a threshold of nothing can only ever be a
 * player who is already dead, and health above one — a game with overshield — is health.
 */
private fun hurtOf(health: Float, threshold: Float): Float {
    if (health.isNaN()) return 0f
    if (threshold <= 0f) return if (health <= 0f) 1f else 0f
    return ((threshold - health) / threshold).coerceIn(0f, 1f)
}

/**
 * The ring, drawn as one shader over the whole box.
 *
 * The two uniforms that never move are worked out once; the one that does is put into a fresh map
 * every frame rather than written over the last one. A canvas is free to record a draw call and
 * carry it out later, and a shared map would have changed under it by then — which is a bug that
 * shows up as the wrong frame's ring rather than as anything that looks like this class. It costs
 * one small map a frame, and only while the player is dying.
 */
private class VignettePainter(
    private val clocks: Clocks,
    private val clock: Clock,
    private val colour: Colour,
    inner: Float,
    private val calmPulseMillis: Int,
    private val fastPulseMillis: Int,
) {

    /** How badly hurt the player is, as the last composition read it. */
    private var hurt = 0f

    /** The colour and the shape of the falloff: the same every frame this painter is alive. */
    private val fixed = mapOf(
        "u_vignette" to Uniform.of(colour),
        "u_inner" to Uniform.Number(inner.coerceIn(0f, 0.99f)),
    )

    /**
     * The drawing for one state of the player's health.
     *
     * A new lambda each time health moves, which is what tells the node it has to be drawn again:
     * a player taking a hit is a change, while the beat between hits is time passing and costs the
     * composition nothing.
     */
    fun drawWith(hurt: Float, strength: Float, pulse: Boolean): UiCanvas.(Rect) -> Unit {
        this.hurt = hurt
        return { bounds -> paint(this, bounds, strength, pulse) }
    }

    private fun paint(canvas: UiCanvas, bounds: Rect, strength: Float, pulse: Boolean) {
        val reach = strength * hurt * beat(pulse)
        if (reach > 0f) {
            val effect = ShaderEffect(VignetteShader, fixed + ("u_strength" to Uniform.Number(reach)))
            // An empty picture: the ring is made by the shader rather than out of anything drawn
            // underneath it. A canvas with no layers answers null, and then the flat wash below is
            // all there is — the same bargain every effect in the toolkit makes.
            val picture = canvas.layer(bounds) { }
            if (picture != null) {
                canvas.drawLayer(picture, bounds, effect)
            } else {
                canvas.rect(bounds, colour.scaleAlpha(reach * FlatShare))
            }
        }
    }

    /**
     * Where the heartbeat is now, from [BeatFloor] between beats to one at the top of one.
     *
     * Measured off the clock's own time rather than counted up frame by frame, so it costs no
     * state, survives a widget that comes and goes with the player's health, and stops dead when
     * the world clock does.
     */
    private fun beat(pulse: Boolean): Float {
        if (!pulse) return 1f
        val millis = calmPulseMillis + (fastPulseMillis - calmPulseMillis) * hurt
        val period = (millis * 1_000_000f).toLong()
        if (period <= 0L) return 1f
        val phase = (clocks.time(clock) % period).toFloat() / period
        // A cosine rather than a sawtooth: a ring that jumps back to the start of its beat reads
        // as a flicker, which is a fault rather than a warning.
        val swell = 0.5f - 0.5f * cos(phase * 2f * PI.toFloat())
        return BeatFloor + (1f - BeatFloor) * swell
    }
}

/** How much of the ring is there between beats. Enough that it never blinks out. */
private const val BeatFloor = 0.62f

/** What is left of the ring on a canvas that cannot take a picture of a layer. */
private const val FlatShare = 0.4f

/**
 * The ring itself: distance from the middle, fading in towards the edge.
 *
 * Squared rather than straight, so the colour hugs the border instead of creeping into the middle
 * of the screen where the player is trying to aim.
 */
private val VignetteShader = ShaderSource(
    name = "low health vignette",
    fragment = """
        uniform vec4 u_vignette;
        uniform float u_strength;
        uniform float u_inner;

        void main() {
            vec4 picture = texture2D(u_texture, v_texCoord);

            // -1 to 1 across the box, so the middle is zero and a corner is the furthest away.
            vec2 offset = (v_texCoord - 0.5) * 2.0;
            float distance = clamp(length(offset), 0.0, 1.0);
            float across = clamp((distance - u_inner) / max(1.0 - u_inner, 0.0001), 0.0, 1.0);
            float ring = across * across;

            float alpha = clamp(u_vignette.a * u_strength * ring, 0.0, 1.0);
            // Premultiplied, and over whatever the layer already had, which is nothing at all in
            // this widget and the game's own picture in anything that reuses the shader.
            vec3 colour = u_vignette.rgb * alpha;
            gl_FragColor = (vec4(colour, alpha) + picture * (1.0 - alpha)) * u_alpha;
        }
    """.trimIndent(),
)
