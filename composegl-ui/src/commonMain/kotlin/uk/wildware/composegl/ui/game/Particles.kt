package uk.wildware.composegl.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import uk.wildware.composegl.ui.animation.Clock
import uk.wildware.composegl.ui.animation.LocalClocks
import uk.wildware.composegl.ui.geometry.Rect
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.graphics.TextureHandle
import uk.wildware.composegl.ui.graphics.UiCanvas
import uk.wildware.composegl.ui.layout.LeafLayout
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.fillMaxSize
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * What a particle looks like and how it moves: one recipe, shared by every particle made from it.
 *
 * Held apart from the emitter because one emitter usually has several — the sparks off a hit and
 * the smoke after it are the same pool and two recipes — and because a recipe is a value a game
 * writes down once, next to the rest of its tuning, rather than a pile of arguments at a call site
 * in the middle of a fight.
 *
 * Everything is in design units and seconds. Angles are degrees, zero pointing right and negative
 * pointing up, which is the same direction the interface's own y axis goes.
 *
 * @param life how long a particle lasts, in seconds.
 * @param lifeSpread how much that varies, as a fraction: 0.3 means give or take thirty per cent.
 * @param speed how fast it leaves, in design units a second.
 * @param speedSpread how much that varies, as a fraction.
 * @param direction which way it leaves. -90 is straight up.
 * @param spread how far either side of [direction] it may go, in degrees. 180 is every direction.
 * @param gravity how fast downwards speed builds, in design units a second a second. Zero floats.
 * @param drag the fraction of its speed a particle loses each second. Zero coasts; one stops it
 *   almost at once. Smoke wants some, a spark wants none.
 * @param size how big it starts, in design units.
 * @param sizeSpread how much that varies, as a fraction.
 * @param endSize what it is multiplied by at the end of its life. Below one shrinks, above one
 *   grows, and the change is even across the life.
 * @param colour what it starts as.
 * @param endColour what it finishes as, mixed evenly across its life. Transparent is a fade out,
 *   which is what nearly everything wants — a particle that vanishes at full opacity pops.
 * @param corner the corner radius of the quad. Half the size is a circle.
 * @param texture a picture to draw instead of a plain quad, tinted by the colour. A spark, a leaf,
 *   a puff of smoke.
 */
data class ParticleStyle(
    val life: Float = 0.9f,
    val lifeSpread: Float = 0.3f,
    val speed: Float = 160f,
    val speedSpread: Float = 0.5f,
    val direction: Float = -90f,
    val spread: Float = 40f,
    val gravity: Float = 320f,
    val drag: Float = 0f,
    val size: Float = 6f,
    val sizeSpread: Float = 0.3f,
    val endSize: Float = 0.3f,
    val colour: Colour = Colour.White,
    val endColour: Colour = Colour.Transparent,
    val corner: Float = 0f,
    val texture: TextureHandle? = null,
)

/**
 * A bounded pool of particles: the toolkit's answer to sparks, confetti, embers and dust.
 *
 * A drawing widget rather than a [uk.wildware.composegl.ui.modifier.effect], and the difference is what it is
 * for: an effect filters pixels that already exist, and this makes new ones. It is here rather than
 * in a game because all three of the things it is usually for — the burst off a button press, the
 * confetti on a win, the shower off a hit — are interface, and because a game that writes its own
 * ends up writing it against an engine.
 *
 * Everything is allocated when the emitter is made. A burst takes slots that already exist, a
 * particle that dies gives its slot back, and a frame of simulation allocates nothing at all —
 * which is the only way a hundred sparks a second is not a stutter. Drawing hands the canvas one
 * [Rect] per particle, because that is what the canvas takes; that is the only object a frame
 * makes, and it is the same one a widget makes to draw its own background.
 * When the pool is full the oldest particles give up their places, because the burst the player
 * just caused is the one they are looking at.
 *
 * Positions are in the widget's own coordinates, measured from its top left, so a game says where
 * on the panel the sparks come from and never has to know where the panel is.
 *
 * ```kotlin
 * val sparks = rememberParticles()
 * ParticleLayer(sparks, Modifier.fillMaxSize())
 * // when the player hits the button:
 * sparks.burst(24, at.x, at.y, Sparks)
 * ```
 *
 * Time is a [Clock]'s, so particles freeze when the world does rather than ageing out behind a
 * pause menu.
 *
 * @param capacity how many can be alive at once.
 * @param seed where the randomness starts. The same seed and the same calls give the same picture
 *   on every machine, which is what makes a burst something a golden screenshot can check.
 */
class ParticleEmitter(
    val capacity: Int = 256,
    val clock: Clock = Clock.World,
    seed: Long = 0L,
) {

    private val random = Random(seed)

    // Six arrays and one of styles, rather than a pool of particle objects. A thousand particles is
    // a thousand objects for a collector to walk otherwise, and every one of them is touched every
    // frame.
    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val velocityX = FloatArray(capacity)
    private val velocityY = FloatArray(capacity)
    private val age = FloatArray(capacity)
    private val life = FloatArray(capacity)
    private val size = FloatArray(capacity)
    private val styles = arrayOfNulls<ParticleStyle>(capacity)
    private val alive = BooleanArray(capacity)

    private var next = 0

    /**
     * How many are alive.
     *
     * Snapshot state, and the reason an idle emitter is free: the layer only asks the runtime for
     * frames while this is above zero, so an emitter that has finished costs a comparison.
     */
    var active by mutableStateOf(0)
        private set

    private var streamStyle: ParticleStyle? = null
    private var streamRate = 0f
    private var streamX = 0f
    private var streamY = 0f
    private var carry = 0f

    /**
     * Whether particles are still being made. Says nothing about how many are on screen.
     *
     * Snapshot state for the same reason [active] is: switching a source on has to wake the layer
     * up, and the layer is only woken by something it read changing.
     */
    var isStreaming by mutableStateOf(false)
        private set

    /** Makes [count] particles at once, at [x], [y] in the widget's coordinates. */
    fun burst(count: Int, x: Float, y: Float, style: ParticleStyle) {
        repeat(count) { spawn(x, y, style) }
    }

    /**
     * Keeps making [perSecond] particles until [stop].
     *
     * The emitter counts the fractions itself, so a rate below one a frame still comes out even
     * rather than in clumps — a rocket's exhaust at four a second is four a second, not one every
     * fifteenth frame.
     */
    fun start(perSecond: Float, x: Float, y: Float, style: ParticleStyle) {
        require(perSecond >= 0f) { "A source cannot make $perSecond particles a second." }
        streamStyle = style
        streamRate = perSecond
        streamX = x
        streamY = y
        isStreaming = true
    }

    /** Moves the source without interrupting it: the trail behind something that is moving. */
    fun move(x: Float, y: Float) {
        streamX = x
        streamY = y
    }

    /** Stops making new ones. What is already alive lives out its life. */
    fun stop() {
        streamStyle = null
        carry = 0f
        isStreaming = false
    }

    /** Everything gone at once: a screen change, a reset between rounds. */
    fun clear() {
        alive.fill(false)
        styles.fill(null)
        active = 0
    }

    /**
     * Moves everything on by [deltaSeconds], and makes whatever a running source owes.
     *
     * Public because the simulation is the part worth testing and worth driving from a game's own
     * loop: a test steps it by a tenth of a second at a time and asserts where things are, with no
     * composition and no screen anywhere. [ParticleLayer] calls it once a frame.
     */
    fun update(deltaSeconds: Float) {
        if (deltaSeconds <= 0f) return

        val source = streamStyle
        if (source != null) {
            carry += streamRate * deltaSeconds
            while (carry >= 1f) {
                carry -= 1f
                spawn(streamX, streamY, source)
            }
        }

        var living = 0
        for (index in 0 until capacity) {
            if (!alive[index]) continue
            val style = styles[index] ?: continue

            age[index] += deltaSeconds
            if (age[index] >= life[index]) {
                alive[index] = false
                styles[index] = null
                continue
            }

            velocityY[index] += style.gravity * deltaSeconds
            if (style.drag > 0f) {
                // Per second, so half a second of drag 0.5 takes a quarter of the speed rather than
                // a half: what is left is what was left of what was left.
                val kept = (1f - style.drag * deltaSeconds).coerceAtLeast(0f)
                velocityX[index] *= kept
                velocityY[index] *= kept
            }
            x[index] += velocityX[index] * deltaSeconds
            y[index] += velocityY[index] * deltaSeconds
            living++
        }
        if (living != active) active = living
    }

    /**
     * Draws every living particle, with [bounds]'s top left as the origin.
     *
     * Public for the same reason as [update]: a golden screenshot draws an emitter straight onto a
     * canvas, with no composition anywhere near it.
     */
    fun drawInto(canvas: UiCanvas, bounds: Rect) {
        for (index in 0 until capacity) {
            if (!alive[index]) continue
            val style = styles[index] ?: continue

            val through = (age[index] / life[index]).coerceIn(0f, 1f)
            val across = size[index] * (1f + (style.endSize - 1f) * through)
            if (across <= 0f) continue

            val half = across / 2f
            val at = Rect.of(
                bounds.left + x[index] - half,
                bounds.top + y[index] - half,
                across,
                across,
            )
            val colour = style.colour.lerp(style.endColour, through)
            val texture = style.texture
            if (texture == null) {
                canvas.rect(at, colour, corner = style.corner)
            } else {
                canvas.image(texture, at, tint = colour)
            }
        }
    }

    private fun spawn(atX: Float, atY: Float, style: ParticleStyle) {
        val index = next
        next = (next + 1) % capacity
        if (!alive[index]) active++

        val angle = (style.direction + spread(style.spread)) * Radians
        val speed = style.speed * vary(style.speedSpread)

        x[index] = atX
        y[index] = atY
        velocityX[index] = cos(angle) * speed
        velocityY[index] = sin(angle) * speed
        age[index] = 0f
        // Never zero: a particle with no life at all divides by zero the first time it is drawn.
        life[index] = (style.life * vary(style.lifeSpread)).coerceAtLeast(MinimumLife)
        size[index] = style.size * vary(style.sizeSpread)
        styles[index] = style
        alive[index] = true
    }

    /** A multiplier around one: 0.4 gives somewhere between 0.6 and 1.4. */
    private fun vary(spread: Float): Float =
        if (spread <= 0f) 1f else 1f + (random.nextFloat() * 2f - 1f) * spread

    /** Degrees either side of nothing. */
    private fun spread(degrees: Float): Float =
        if (degrees <= 0f) 0f else (random.nextFloat() * 2f - 1f) * degrees

    private companion object {
        const val Radians = 0.017453292f

        /** A hundredth of a frame at sixty. Short enough to be invisible, long enough to divide by. */
        const val MinimumLife = 0.0001f
    }
}

/** A pool that lives as long as the screen it is on. */
@Composable
fun rememberParticles(
    capacity: Int = 256,
    clock: Clock = Clock.World,
    seed: Long = 0L,
): ParticleEmitter = remember(capacity, clock, seed) {
    ParticleEmitter(capacity = capacity, clock = clock, seed = seed)
}

/**
 * Draws [emitter], and winds it on once a frame.
 *
 * One node for the whole layer rather than one per particle — a hundred sparks is a hundred nodes
 * to make, measure, place and throw away, every one of them as short lived as the spark.
 *
 * It asks for frames only while something is alive or a source is running, so an emitter that has
 * finished is a boolean a frame and nothing else. Put a [uk.wildware.composegl.ui.modifier.effect] on the
 * modifier to run the whole layer through a shader: a blur makes embers glow, and the particles do
 * not have to know.
 */
@Composable
fun ParticleLayer(
    emitter: ParticleEmitter,
    modifier: Modifier = Modifier,
) {
    val clocks = LocalClocks.current

    // The clock has to be one the host is winding on, or nothing ever moves.
    remember(clocks, emitter.clock) { clocks.register(emitter.clock) }

    val running = emitter.active > 0 || emitter.isStreaming
    LaunchedEffect(emitter, running) {
        var last = clocks.time(emitter.clock)
        while (emitter.active > 0 || emitter.isStreaming) {
            withFrameNanos { }
            val now = clocks.time(emitter.clock)
            emitter.update((now - last) / 1_000_000_000f)
            last = now
        }
    }

    LeafLayout(modifier.fillMaxSize(), name = "particles", draw = { bounds ->
        emitter.drawInto(this, bounds)
    })
}
