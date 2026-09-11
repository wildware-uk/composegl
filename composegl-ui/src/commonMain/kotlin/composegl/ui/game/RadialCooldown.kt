package composegl.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import composegl.ui.animation.Animatable
import composegl.ui.animation.Clock
import composegl.ui.animation.Clocks
import composegl.ui.animation.Easings
import composegl.ui.animation.FloatVectoriser
import composegl.ui.animation.LocalClocks
import composegl.ui.animation.Tween
import composegl.ui.geometry.Rect
import composegl.ui.geometry.boxSweep
import composegl.ui.graphics.Colour
import composegl.ui.graphics.UiCanvas
import composegl.ui.layout.Alignment
import composegl.ui.layout.Box
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.drawInFront
import composegl.ui.skin.ResolvedStyle
import composegl.ui.skin.SkinDrawable
import composegl.ui.skin.rememberStyle
import composegl.ui.widget.Text
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * How far through a cooldown an ability is, and the only thing that can start one.
 *
 * A cooldown is a piece of game state rather than a widget: the ability owns it, the interface
 * draws it. Make one with [rememberCooldown], call [trigger] when the ability is used, and read
 * [isReady] to decide whether it can be used again.
 *
 * [trigger] on a cooldown that is already running is **ignored**, and says so by returning false.
 * That is the rule that makes a cooldown safe to wire straight to a button: a player mashing the
 * key does not keep pushing the end of it further away, which is the bug every hand-rolled version
 * of this has had at least once.
 *
 * It runs on a [Clock] — the world's by default — so a paused game does not cool down.
 */
class Cooldown internal constructor(
    val durationMillis: Int,
    val clock: Clock,
    private val clocks: Clocks,
) {

    /** How far through it is, from 0 the instant it was triggered to 1 when it is ready again. */
    var fraction by mutableStateOf(1f)
        private set

    /**
     * How many times it has been triggered.
     *
     * Read by the widget so that it starts watching a new run rather than the end of the last one.
     * A counter rather than a flag because two runs in a row are two different things to watch.
     */
    var runs by mutableStateOf(0)
        private set

    val isRunning: Boolean get() = fraction < 1f

    val isReady: Boolean get() = !isRunning

    /** How long is left, in milliseconds. Zero when it is ready. */
    val remainingMillis: Int get() = ((1f - fraction) * durationMillis).roundToInt()

    private var endsAt = 0L

    /**
     * Starts it, if it is not already running.
     *
     * @return whether the ability was actually used. False means it was still cooling down, so
     *   nothing happened — neither the cooldown nor the ability itself.
     */
    fun trigger(): Boolean {
        if (isRunning) return false
        if (durationMillis <= 0) return true
        clocks.register(clock)
        endsAt = clocks.time(clock) + durationMillis * MillisToNanos
        fraction = 0f
        runs++
        return true
    }

    /** Ready again now: a refresh, a reset between rounds, a new life. */
    fun reset() {
        endsAt = 0L
        fraction = 1f
    }

    /**
     * Moves it to where the clock says it is.
     *
     * Asked where it is rather than stepped by how long a frame was, so a frame the game spent
     * loading something does not leave the cooldown behind, and a stopped clock simply does not
     * move it.
     *
     * @return whether it is still running.
     */
    internal fun tick(): Boolean {
        val left = endsAt - clocks.time(clock)
        fraction = if (left <= 0L) 1f else 1f - left.toFloat() / (durationMillis * MillisToNanos).toFloat()
        return isRunning
    }

    private companion object {
        const val MillisToNanos = 1_000_000L
    }
}

/** A [Cooldown] that lives as long as the thing it belongs to, on the clock of your choice. */
@Composable
fun rememberCooldown(durationMillis: Int, clock: Clock = Clock.World): Cooldown {
    val clocks = LocalClocks.current
    return remember(durationMillis, clock, clocks) { Cooldown(durationMillis, clock, clocks) }
}

/**
 * The sweeping wedge over an ability icon.
 *
 * A dark wedge covers what is left of the cooldown and sweeps away clockwise from twelve o'clock,
 * the seconds remaining sit on top of it, and the icon flashes once when it comes back. It is a
 * [Box]: whatever is passed as [content] is the ability itself — an `Image`, a letter, a whole
 * button — and the cooldown is drawn over it.
 *
 * The wedge covers the corners of a square icon rather than a circle inside it, so a plain square
 * button looks right without a round frame drawn round it.
 *
 * A ready ability draws **nothing** extra: no wedge, no text, no flash, and no frames asked for.
 *
 * The skin decides how it looks: `"<style>.sweep"` is the wedge, `"<style>.flash"` the moment it
 * comes back, and `"<style>.seconds"` the countdown's text.
 *
 * ```kotlin
 * val dash = rememberCooldown(4_000)
 * RadialCooldown(dash) { Image("icon/dash") }
 * if (key.pressed && dash.trigger()) player.dash()
 * ```
 *
 * @param seconds whether to draw the time remaining over the icon.
 * @param flash whether to flash when it comes back. The cheapest way to tell a player something is
 *   usable again without them watching the icon.
 */
@Composable
fun RadialCooldown(
    cooldown: Cooldown,
    modifier: Modifier = Modifier,
    style: String = "cooldown",
    seconds: Boolean = true,
    flash: Boolean = true,
    content: @Composable () -> Unit = {},
) {
    val clocks = LocalClocks.current
    val sweep = rememberStyle("$style.sweep")
    val flashStyle = rememberStyle("$style.flash")

    val flashing = remember(clocks, cooldown.clock) {
        Animatable(0f, FloatVectoriser, cooldown.clock, clocks)
    }

    // Keyed on the run rather than on whether it is running: the flash happens after the last tick,
    // and an effect keyed on "still running" would be cancelled by its own last tick.
    LaunchedEffect(cooldown, cooldown.runs) {
        if (cooldown.runs == 0) return@LaunchedEffect
        while (cooldown.tick()) withFrameNanos { }
        if (flash) {
            flashing.snapTo(1f)
            flashing.animateTo(0f, Tween(FlashMillis, easing = Easings.EaseOut))
        }
    }

    val painter = remember(sweep, flashStyle, cooldown.fraction, flashing.value) {
        CooldownPainter(cooldown.fraction, sweep.fill(), flashStyle.fill(), flashing.value)
    }

    Box(modifier.drawInFront(painter::draw), contentAlignment = Alignment.Centre) {
        content()
        if (seconds && cooldown.isRunning) {
            Text(countdown(cooldown.remainingMillis), style = "$style.seconds")
        }
    }
}

/** How long the flash lasts when an ability comes back. Short: it is a blink, not an animation. */
private const val FlashMillis = 220

/**
 * The time left, as a player reads it out loud.
 *
 * Whole seconds while there is more than one — nobody counts a nine-second cooldown in tenths —
 * and tenths in the last second, where a tenth is the difference between pressing the key now and
 * pressing it twice.
 */
private fun countdown(remainingMillis: Int): String {
    if (remainingMillis >= 1_000) return ceil(remainingMillis / 1_000f).toInt().toString()
    val tenths = (remainingMillis / 100f).roundToInt().coerceAtLeast(1)
    return "0.$tenths"
}

/**
 * The colour a style fills with.
 *
 * A wedge is a shape rather than a box, so it takes a colour and cannot take a nine-patch. A skin
 * that puts art here gets nothing drawn, which is visible, rather than a stretched picture in the
 * shape of a wedge, which is not what anybody meant.
 */
private fun ResolvedStyle.fill(): Colour = (background as? SkinDrawable.Fill)?.colour ?: Colour.Transparent

/** The wedge, and the flash over it. Held apart from the composable so it is a value that compares. */
private class CooldownPainter(
    private val fraction: Float,
    private val sweep: Colour,
    private val flash: Colour,
    private val flashAlpha: Float,
) {

    fun draw(canvas: UiCanvas, bounds: Rect) {
        if (fraction < 1f) {
            // From where it has cooled to, round to the top: what is left, not what is done.
            canvas.fan(boxSweep(bounds, fraction, 1f - fraction), sweep)
        }
        if (flashAlpha > 0f) {
            canvas.rect(bounds, flash.scaleAlpha(flashAlpha))
        }
    }
}
