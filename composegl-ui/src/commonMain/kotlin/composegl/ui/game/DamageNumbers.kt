package composegl.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import composegl.ui.animation.Clock
import composegl.ui.animation.Clocks
import composegl.ui.animation.Easings
import composegl.ui.animation.LocalClocks
import composegl.ui.geometry.Rect
import composegl.ui.graphics.Colour
import composegl.ui.graphics.UiCanvas
import composegl.ui.layout.LeafLayout
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.fillMaxSize
import composegl.ui.skin.rememberStyle
import composegl.ui.text.FontProvider
import composegl.ui.text.TextLayout
import composegl.ui.text.TextStyle
import composegl.ui.widget.rememberFonts

/**
 * A place in the game's world. Mutable on purpose: the layer owns one and lends it out.
 *
 * Two hundred numbers on screen means two hundred positions a frame, and a new object for each of
 * them is two hundred pieces of rubbish sixty times a second.
 */
class WorldPoint(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {
    fun set(x: Float, y: Float, z: Float = 0f) {
        this.x = x
        this.y = y
        this.z = z
    }
}

/** Where a damage number is, asked once a frame so that it can follow something that moves. */
fun interface WorldAnchor {

    /** Writes where this is now into [point]. The point is the layer's, and is reused. */
    fun positionInto(point: WorldPoint)

    companion object {

        /** Somewhere that does not move: the spot where the hit landed. */
        fun at(x: Float, y: Float, z: Float = 0f): WorldAnchor = WorldAnchor { it.set(x, y, z) }
    }
}

/**
 * The game's camera, as far as this layer is concerned.
 *
 * The toolkit has no idea what a camera is and does not want one. A game hands over the one
 * function that matters: where on screen is this point in the world, and is it on screen at all.
 */
fun interface WorldProjection {

    /**
     * @param onto filled in with where [point] is on screen, in the interface's coordinates.
     * @return false if it is not on screen at all — behind the camera, past the far plane, off the
     *   edge — and then nothing is drawn for it.
     */
    fun project(point: WorldPoint, onto: WorldPoint): Boolean

    companion object {

        /** For a game whose world is already the screen: x and y straight through. */
        val Screen: WorldProjection = WorldProjection { point, onto ->
            onto.set(point.x, point.y)
            true
        }
    }
}

/**
 * The numbers that fly off a thing when it is hit.
 *
 * A pool, not a list. Every number a game will ever show at once is allocated when this is made,
 * and [show] takes the next one rather than making one: a fight is the worst possible moment to
 * ask for memory, and a hundred numbers a second is exactly the shape of thing that turns into a
 * pause. Nothing here allocates per frame — not the positions, not the text, not the list of what
 * is alive.
 *
 * A number that has run out of life is simply not drawn any more and its slot goes back in the
 * pool. When they have all gone the layer stops asking for frames, so a quiet game costs nothing.
 *
 * Time is a [Clock]'s, so numbers freeze when the world does and do not all expire at once behind
 * a pause menu.
 *
 * ```kotlin
 * val numbers = rememberDamageNumbers()
 * DamageNumberLayer(numbers, projection = camera)
 * // when something is hit:
 * numbers.show("142", WorldAnchor { it.set(enemy.x, enemy.y + 2f, enemy.z) }, critical = true)
 * ```
 *
 * @param capacity how many can be on screen at once. The oldest gives up its place when a game
 *   shows more than this, because the newest number is the one the player is looking for.
 * @param lifeMillis how long an ordinary number lives.
 * @param criticalLifeMillis how long a critical lives. Longer, because it matters more.
 * @param rise how far up the screen a number floats over its life, in interface pixels.
 * @param drift how far sideways. A little, so that two numbers in the same place do not sit
 *   exactly on top of each other — alternating left and right rather than random, which is
 *   readable rather than noisy.
 */
class DamageNumbers(
    val capacity: Int = 256,
    val lifeMillis: Int = 900,
    val criticalLifeMillis: Int = 1_400,
    val rise: Float = 46f,
    val drift: Float = 14f,
    val clock: Clock = Clock.World,
) {

    internal val entries = Array(capacity) { Entry() }

    /** How many are alive. Snapshot state, so a layer with nothing to draw stops drawing. */
    var active by mutableStateOf(0)
        private set

    private var next = 0
    private var shown = 0

    /**
     * Shows a number.
     *
     * @param text what it says. The game's own formatting: this layer does not know whether 142 is
     *   damage, healing, armour or experience.
     * @param critical bigger, longer lived, and drawn in the skin's critical style. A game with
     *   more kinds than two — healing green, mana blue, a miss in grey — runs a second layer with
     *   its own style rather than naming a style per number, so that nothing has to look a style
     *   up while a fight is going on.
     */
    fun show(text: String, anchor: WorldAnchor, critical: Boolean = false) {
        val entry = entries[next]
        next = (next + 1) % capacity
        if (!entry.alive) active++

        entry.text = text
        entry.anchor = anchor
        entry.critical = critical
        entry.layout = null
        entry.bornNanos = NotBornYet
        entry.side = if (shown % 2 == 0) 1f else -1f
        entry.alive = true
        shown++
    }

    /** Everything gone at once: a screen change, a death, a reset between rounds. */
    fun clear() {
        entries.forEach { it.alive = false; it.anchor = null; it.layout = null }
        active = 0
    }

    /** Retires whatever has run out of life. Called by the layer, once a frame. */
    internal fun expire(nowNanos: Long) {
        var alive = 0
        entries.forEach { entry ->
            if (!entry.alive) return@forEach
            if (entry.bornNanos != NotBornYet && nowNanos - entry.bornNanos >= entry.lifeNanos(this)) {
                entry.alive = false
                entry.anchor = null
                entry.layout = null
            } else {
                alive++
            }
        }
        if (alive != active) active = alive
    }

    /** One number. Made once, filled in again and again. */
    internal class Entry {
        var alive = false
        var text: String = ""
        var anchor: WorldAnchor? = null
        var critical = false
        var layout: TextLayout? = null
        var bornNanos = NotBornYet
        var side = 1f

        fun lifeNanos(numbers: DamageNumbers): Long =
            (if (critical) numbers.criticalLifeMillis else numbers.lifeMillis) * 1_000_000L
    }

    internal companion object {
        /** A number born on the frame it is first drawn, so one shown mid-frame is not half over. */
        const val NotBornYet = Long.MIN_VALUE
    }
}

/** A pool that lives as long as the screen it is on. */
@Composable
fun rememberDamageNumbers(
    capacity: Int = 256,
    clock: Clock = Clock.World,
): DamageNumbers = remember(capacity, clock) { DamageNumbers(capacity = capacity, clock = clock) }

/**
 * Draws [numbers], wherever the game's camera says they are.
 *
 * One node for the whole layer rather than one per number. A hundred numbers appearing is a
 * hundred nodes to make, measure, place and throw away, every one of them as short lived as the
 * number itself — so this measures each number once, when it first appears, and draws all of them
 * straight onto the canvas after that.
 *
 * It asks for frames only while something is alive.
 *
 * @param projection how the game turns a place in its world into a place on screen.
 * @param style the skin style for an ordinary number, and `"<style>.critical"` for a critical.
 */
@Composable
fun DamageNumberLayer(
    numbers: DamageNumbers,
    modifier: Modifier = Modifier,
    projection: WorldProjection = WorldProjection.Screen,
    style: String = "damage",
) {
    val clocks = LocalClocks.current
    val fonts = rememberFonts()
    val ordinary = rememberStyle(style)
    val critical = rememberStyle("$style.critical")

    // The clock has to be one the host is winding on, or every number is forever a frame old.
    remember(clocks, numbers.clock) { clocks.register(numbers.clock) }

    val painter = remember(numbers, projection, fonts, ordinary, critical, clocks, style) {
        NumberPainter(numbers, projection, fonts, clocks)
    }
    painter.ordinary(ordinary.textStyle, ordinary.textColour)
    painter.critical(critical.textStyle, critical.textColour)

    // Nothing is asked of the runtime while the screen is quiet: the effect only exists while
    // something is alive, and it ends with the last number.
    LaunchedEffect(numbers, numbers.active > 0) {
        while (numbers.active > 0) {
            withFrameNanos { }
            numbers.expire(clocks.time(numbers.clock))
        }
    }

    LeafLayout(modifier.fillMaxSize(), name = "damage", draw = painter.draw)
}

/**
 * Every number on screen, drawn in one pass.
 *
 * It holds the two scratch points and nothing else that changes size, so drawing a frame allocates
 * nothing whatever is on screen. A number is measured the first time it is drawn rather than when
 * it is shown, because that is where the fonts are.
 */
private class NumberPainter(
    private val numbers: DamageNumbers,
    private val projection: WorldProjection,
    private val fonts: FontProvider,
    private val clocks: Clocks,
) {

    private val world = WorldPoint()
    private val screen = WorldPoint()

    private var ordinaryStyle = TextStyle.Default
    private var ordinaryColour = Colour.White
    private var criticalStyle = TextStyle.Default
    private var criticalColour = Colour.White

    fun ordinary(style: TextStyle, colour: Colour) {
        ordinaryStyle = style
        ordinaryColour = colour
    }

    fun critical(style: TextStyle, colour: Colour) {
        criticalStyle = style
        criticalColour = colour
    }

    val draw: UiCanvas.(Rect) -> Unit = { bounds ->
        val now = clocks.time(numbers.clock)
        numbers.entries.forEach { entry ->
            if (entry.alive) drawOne(this, entry, now, bounds)
        }
    }

    private fun drawOne(canvas: UiCanvas, entry: DamageNumbers.Entry, now: Long, bounds: Rect) {
        if (entry.bornNanos == DamageNumbers.NotBornYet) entry.bornNanos = now

        val anchor = entry.anchor ?: return
        anchor.positionInto(world)
        if (!projection.project(world, screen)) return

        val layout = entry.layout ?: measure(entry).also { entry.layout = it }

        val life = entry.lifeNanos(numbers).toFloat()
        val age = ((now - entry.bornNanos).toFloat() / life).coerceIn(0f, 1f)

        // Quick at first and slowing down, which is what thrown things look like, and the fade
        // saved for the end so that the number is readable for most of its life.
        val lift = Easings.EaseOut.transform(age) * numbers.rise
        val sway = entry.side * numbers.drift * age
        val fade = if (age < FadeFrom) 1f else 1f - (age - FadeFrom) / (1f - FadeFrom)

        val colour = if (entry.critical) criticalColour else ordinaryColour
        canvas.text(
            layout,
            bounds.left + screen.x - layout.size.width / 2f + sway,
            bounds.top + screen.y - lift,
            colour.scaleAlpha(fade),
        )
    }

    private fun measure(entry: DamageNumbers.Entry): TextLayout =
        fonts.measure(entry.text, if (entry.critical) criticalStyle else ordinaryStyle)
}

/** How far through its life a number starts fading. Late: a number that is fading is hard to read. */
private const val FadeFrom = 0.65f
