package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.draw.RectCache
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.graphics.textRun
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.skin.LocalSkin
import dev.wildware.composegl.ui.skin.ResolvedStyle
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.flatColour
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.text.FontProvider
import dev.wildware.composegl.ui.text.LocalLocale
import dev.wildware.composegl.ui.text.LocalStrings
import dev.wildware.composegl.ui.text.TextLayout
import dev.wildware.composegl.ui.text.TextOutline
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.LocalTextOutline
import dev.wildware.composegl.ui.widget.LocalTextScale
import dev.wildware.composegl.ui.widget.rememberFonts
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the points of the compass are called, in the player's language.
 *
 * Four names, eight or sixteen, clockwise from north. Which of the three a game wants is a matter
 * of how wide the strip is: sixteen points on a narrow bar is a smear, and four on a wide one
 * leaves an empty stretch between the letters.
 *
 * The names are text rather than letters because they are not letters everywhere. North is `N` in
 * English, `И` in Russian and `شمال` in Arabic, and a compass that says `N` to a Russian player has
 * the same bug as a menu that says "Play". [rememberCompassLabels] takes them from the game's own
 * string table; this is what a game holds when it would rather write them out.
 */
class CompassLabels(val points: List<String>) {

    init {
        require(points.size == 4 || points.size == 8 || points.size == 16) {
            "a compass has 4, 8 or 16 points, not ${points.size}"
        }
    }

    /** How many degrees apart two neighbouring points are. */
    val step: Float get() = 360f / points.size

    companion object {

        /** North, east, south, west. */
        val Cardinals = CompassLabels(englishPoints(4))

        /** The four, with north-east and its three friends between them. The usual choice. */
        val English = CompassLabels(englishPoints(8))

        /** All sixteen, for a strip wide enough to read them. */
        val EnglishSixteen = CompassLabels(englishPoints(16))
    }
}

/**
 * The compass points in the player's language, falling back to the English letters.
 *
 * Each point is looked up in the game's [dev.wildware.composegl.ui.text.Strings] as
 * `<prefix>.<point>` — `compass.n`, `compass.ne` — and a point nobody has translated keeps its
 * English letter. So a game that has translated nothing still has a compass, and one that has
 * translated eight lines has a translated one.
 *
 * ```
 * compass.n = И
 * compass.ne = СВ
 * ```
 *
 * @param points how many names: 4, 8 or 16.
 * @param prefix what the keys start with, for a game whose table is arranged some other way.
 */
@Composable
fun rememberCompassLabels(points: Int = 8, prefix: String = "compass"): CompassLabels {
    val strings = LocalStrings.current
    val locale = LocalLocale.current
    return remember(points, prefix, strings, locale) {
        val english = englishPoints(points)
        CompassLabels(
            List(points) { index ->
                // A key nothing has translated comes back as itself, which is the cue to keep the
                // English one rather than print `compass.ne` across the top of the screen.
                val key = "$prefix.${english[index].lowercase()}"
                strings.get(locale, key).takeIf { it != key } ?: english[index]
            },
        )
    }
}

/**
 * Where a [CompassBar]'s pins are named, one call each.
 *
 * Run while the strip is being drawn rather than while it is being composed, so a pin costs a
 * little arithmetic and nothing else: no list to build, no object per quest marker per frame, and
 * the bearings read are the ones the game has this frame rather than the ones it had when something
 * last recomposed.
 */
interface CompassScope {

    /**
     * One thing out there, put on the strip at the bearing it lies on.
     *
     * @param bearing which way it is, in degrees clockwise from north — the same numbers as the
     *   heading. Anything outside 0 to 360 is wrapped, so a game may hand over whatever its own
     *   arithmetic produced.
     * @param icon a region in the skin's atlas. A pin with no icon is a diamond in its style's
     *   colour, which is what a game with no art yet gets.
     * @param distance how far away it is, in the game's own units: written under the icon when the
     *   bar has a `distanceText`, and read by [fadeWithDistance]. `Float.NaN` for a pin that has no
     *   distance — a wind direction, a sound.
     * @param fadeWithDistance whether it goes faint as it gets further off. For the pins a player
     *   should read as "over there somewhere" rather than as a place to go.
     * @param clamp whether it sticks to the end of the strip once it is outside the field of view,
     *   so a player knows which way to turn. Off for a pin that should simply not be there.
     * @param style a skin style of its own, for a strip that draws an enemy differently from a
     *   quest. Null takes the bar's `"<style>.pin"`.
     */
    @Suppress("LongParameterList")
    fun pin(
        bearing: Float,
        icon: String? = null,
        distance: Float = Float.NaN,
        fadeWithDistance: Boolean = false,
        clamp: Boolean = true,
        style: String? = null,
    )
}

/**
 * The heading strip along the top of the screen: which way the player is facing, and what lies that
 * way.
 *
 * A compass bar is a window onto a circle. [fieldOfView] degrees of it are laid flat across the
 * width, the middle of the strip is where the player is looking, and the names of the compass
 * points and the ticks between them slide past as they turn — wrapping at 360 with no seam, because
 * the strip is drawn from bearings rather than from a position something has to wind back.
 *
 * Pins are named in the trailing lambda, and one beyond the field of view **sticks to the end it
 * left by** rather than vanishing: an objective behind the player is pinned hard left or hard
 * right, with an arrow saying which way to turn. The ends fade out so that a name sliding off is
 * not cut in half, and a pin never sits in the faint part — it stops just inside it.
 *
 * ```kotlin
 * CompassBar(
 *     heading = player.yaw,
 *     fieldOfView = 180f,
 *     Modifier.width(600f).height(48f),
 * ) {
 *     pin(bearing = bearingTo(quest), icon = "icons/quest", distance = distanceTo(quest))
 *     pin(bearing = bearingTo(enemy), icon = "icons/enemy", fadeWithDistance = true)
 * }
 * ```
 *
 * **It does not mirror in a right-to-left language**, and that is deliberate. The rest of the
 * screen does — see [dev.wildware.composegl.ui.layout.LayoutDirection] — but east is to the right
 * of north for an Arabic player standing in the same field as an English one, and a strip that
 * mirrored would slide the wrong way as they turned. The *words* on it are the language's own,
 * through [rememberCompassLabels].
 *
 * **Only the strip redraws.** It is one leaf node with nothing inside it, so a heading that changes
 * every frame lays nothing out again and recomposes nothing else on the HUD. Keep the read of the
 * heading inside a composable of its own and the recomposition stops there too.
 *
 * Everything it looks like is the skin's: `"<style>.tick"` for the ruler, `"<style>.label"` for the
 * names, `"<style>.marker"` for the line down the middle, `"<style>.pin"` for a pin and the number
 * under it, and `"<style>.readout"` for the heading.
 *
 * @param heading which way the player is facing, in degrees clockwise from north.
 * @param fieldOfView how much of the circle the strip shows. 180 is the usual choice: wide enough
 *   to see what is beside you, narrow enough that the names are not on top of each other.
 * @param labels the names of the compass points. Localised by default.
 * @param tickDegrees how often a small tick is drawn between the names, or zero for none.
 * @param readout what to write in the middle of the strip, given the heading, or null for no
 *   readout. A lambda rather than a flag, because `"${it.roundToInt()}°"` is English digits and a
 *   Western symbol, and which of those a game wants is the game's business.
 * @param distanceText how a pin's distance is written under it, or null for a strip with no numbers
 *   on it. The default rounds to a whole unit.
 * @param fadeRange how far away a pin that asked to fade has gone as faint as it goes.
 * @param fade how many pixels at each end the strip fades out over.
 * @param pinSize how big a pin's icon is, in interface pixels. A strip too short for one draws it
 *   smaller rather than over its own names.
 * @param live whether the game behind it is moving. A live strip is redrawn every frame, because
 *   the pins are read from the game as it draws and nothing here would otherwise know they moved.
 *   A strip that is not live is not redrawn at all, which is what a pause menu wants.
 * @param style the skin style of the strip itself.
 * @param pins what is out there, named inside the strip's own drawing. See [CompassScope].
 */
@Suppress("LongParameterList")
@Composable
fun CompassBar(
    heading: Float,
    fieldOfView: Float = 180f,
    modifier: Modifier = Modifier,
    labels: CompassLabels = rememberCompassLabels(),
    tickDegrees: Float = 15f,
    readout: ((Float) -> String)? = null,
    distanceText: ((Float) -> String)? = RoundedDistance,
    fadeRange: Float = 200f,
    fade: Float = 24f,
    pinSize: Float = 16f,
    live: Boolean = true,
    style: String = "compass",
    pins: (CompassScope.() -> Unit)? = null,
) {
    val fonts = rememberFonts()
    val skin = LocalSkin.current
    val frame = rememberStyle(style)

    // The painter keeps every run it measured, so the size the text is drawn at has to be part of
    // what it is built from: a new scale is a new painter, and the words are measured again.
    val scale = LocalTextScale.current
    val outline = LocalTextOutline.current
    val painter = remember(fonts, skin, frame, style, labels, outline, scale) {
        CompassPainter(fonts, skin, frame, style, labels, outline, scale)
    }

    // Pins move and the player turns, all of it outside the composition and none of it state
    // anybody wrote. So while it is live the strip is redrawn every frame, and while it is not it is
    // not redrawn at all: a compass on a pause menu costs exactly nothing.
    var pulse by remember { mutableStateOf(0) }
    LaunchedEffect(live) {
        while (live) {
            withFrameNanos { }
            pulse++
        }
    }

    val draw = remember(
        painter, heading, fieldOfView, tickDegrees, readout, distanceText,
        fadeRange, fade, pinSize, pins, pulse,
    ) {
        painter.drawWith(heading, fieldOfView, tickDegrees, readout, distanceText, fadeRange, fade, pinSize, pins)
    }

    // Keyed on whether there are pins and numbers rather than on the lambdas themselves: a lambda
    // written at the call site is a new object every recomposition, and a new measure policy would
    // lay the strip out again every frame for nothing.
    val hasPins = pins != null
    val hasDistances = distanceText != null
    val measure = remember(painter, pinSize, hasPins, hasDistances) {
        painter.measuring(pinSize, hasDistances, hasPins)
    }

    LeafLayout(modifier, name = "compass", measurePolicy = measure, draw = draw)
}

/** How a distance is written under a pin when a game has not said otherwise. */
private val RoundedDistance: (Float) -> String = { "${it.roundToInt()}" }

/** The sixteen English abbreviations, clockwise from north. Four and eight are every fourth and second. */
private val SixteenPoints = listOf(
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
)

private fun englishPoints(count: Int): List<String> {
    require(count == 4 || count == 8 || count == 16) { "a compass has 4, 8 or 16 points, not $count" }
    val every = 16 / count
    return SixteenPoints.filterIndexed { index, _ -> index % every == 0 }
}

/**
 * A bearing as the nearest way round to it, from -180 up to but not including 180.
 *
 * The whole of what "wrapping at 360" means here, and the reason there is no seam to get wrong:
 * north is 20 degrees left of a player facing 20, and 20 degrees left of one facing 380.
 */
internal fun wrapDegrees(value: Float): Float {
    var wrapped = value % 360f
    if (wrapped < -180f) wrapped += 360f
    if (wrapped >= 180f) wrapped -= 360f
    return wrapped
}

/**
 * Everything the strip draws, in one pass and with no allocation once it is going.
 *
 * The arithmetic is one line — how far round a bearing is from the heading, times pixels per
 * degree, from the middle — and the rest of it is what happens at the ends: names fade out, pins
 * stop.
 */
private class CompassPainter(
    private val fonts: FontProvider,
    private val skin: Skin,
    private val frame: ResolvedStyle,
    private val style: String,
    private val labels: CompassLabels,
    private val outline: TextOutline?,
    private val scale: Float,
) : CompassScope {

    private val names = skin.resolve("$style.label").scaledTo(scale)
    private val readoutStyle = skin.resolve("$style.readout").scaledTo(scale)
    private val tickColour = skin.resolve("$style.tick").colour()
    private val markerColour = skin.resolve("$style.marker").colour()
    private val pinStyle = "$style.pin"

    private val nameRuns = Layouts(fonts, names.textStyle)
    private val readoutRuns = Layouts(fonts, readoutStyle.textStyle)

    /** A pin may name a style of its own; each one is looked up once and kept, with its own runs. */
    private val pinStyles = HashMap<String, ResolvedStyle>()
    private val pinRuns = HashMap<String, Layouts>()
    private val icons = HashMap<String, TextureHandle?>()

    /** How tall a name is, which is the row the whole layout is built up from. */
    private val nameHeight = nameRuns.of(labels.points.firstOrNull { it.isNotEmpty() } ?: "N").size.height

    /**
     * How tall the number under a pin is, in the default pin's style, which is the one the lane is
     * sized for.
     *
     * Worked out when a pin is first drawn rather than when the painter is built: a strip with no
     * pins on it has no business asking a game's fonts for a size it only uses under a pin, and a
     * font provider is entitled to refuse a size nobody registered.
     */
    private val distanceHeight by lazy {
        Layouts(fonts, skin.resolve(pinStyle).scaledTo(scale).textStyle).of("0").size.height
    }

    private val tickRects = Array(TickCache) { RectCache() }
    private val majorRects = Array(SixteenPoints.size) { RectCache() }
    private val iconRects = Array(PinCache) { RectCache() }
    private val markerRect = RectCache()
    private val triangle = FloatArray(6)
    private val diamond = FloatArray(8)

    // What drawing a pin needs to know, put here once a frame rather than passed down through six
    // calls. Scratch: a pin a frame is sixty of these a second for every quest on the strip.
    private var canvas: UiCanvas? = null
    private var left = 0f
    private var right = 0f
    private var centreX = 0f
    private var laneTop = 0f
    private var fieldOfView = 180f
    private var heading = 0f
    private var iconSize = 0f
    private var fadeRange = 0f
    private var fade = 0f
    private var distanceText: ((Float) -> String)? = null
    private var pinsDrawn = 0

    /** How wide the readout is, so that no name is printed over the top of it. */
    private var readoutHalf = 0f

    /** How big the strip asks to be: as wide as it is allowed, and as tall as its rows come to. */
    fun measuring(pinSize: Float, distances: Boolean, pins: Boolean): MeasurePolicy {
        val lane = if (!pins) 0f else pinSize + if (distances) distanceHeight + PinGap else 0f
        val height = frame.padding.vertical + lane + TickMajor * scale + LabelGap + nameHeight
        return MeasurePolicy { _, constraints ->
            layout(constraints.constrainWidth(NaturalWidth), constraints.constrainHeight(height)) {}
        }
    }

    @Suppress("LongParameterList")
    fun drawWith(
        heading: Float,
        fieldOfView: Float,
        tickDegrees: Float,
        readout: ((Float) -> String)?,
        distanceText: ((Float) -> String)?,
        fadeRange: Float,
        fade: Float,
        pinSize: Float,
        pins: (CompassScope.() -> Unit)?,
    ): UiCanvas.(Rect) -> Unit = { bounds ->
        frame.background.drawInto(this, bounds, Colour.White)

        val padding = frame.padding
        val inner = Rect(
            bounds.left + padding.left,
            bounds.top + padding.top,
            bounds.right - padding.right,
            bounds.bottom - padding.bottom,
        )

        if (inner.right > inner.left && inner.bottom > inner.top) {
            painting(inner, heading, fieldOfView, fadeRange, fade)

            // Bottom up: the names have the bottom row, the ruler hangs above them, and whatever is
            // left at the top is the lane the pins ride in. A short bar loses its lane rather than
            // its names, because the names are what a compass is.
            val labelHeight = min(nameHeight, inner.bottom - inner.top)
            val labelTop = inner.bottom - labelHeight
            val tickBottom = labelTop - LabelGap
            val major = min(TickMajor * scale, (tickBottom - inner.top).coerceAtLeast(0f))

            drawReadout(this, readout, labelTop, labelHeight)
            drawTicks(this, tickDegrees, tickBottom, major)
            drawLabels(this, labelTop, labelHeight, tickBottom, major)
            drawMarker(this, tickBottom, major)
            if (pins != null) drawPins(this, pins, inner.top, tickBottom - major, pinSize, distanceText)

            canvas = null
        }
    }

    /** The numbers every part of one frame reads, written down once. */
    private fun painting(inner: Rect, heading: Float, fieldOfView: Float, fadeRange: Float, fade: Float) {
        left = inner.left
        right = inner.right
        centreX = (inner.left + inner.right) / 2f
        this.heading = heading
        // A strip showing none of the circle, or more than all of it, is neither drawable nor what
        // any game meant. The nearest thing it can have is.
        this.fieldOfView = fieldOfView.coerceIn(MinimumField, 360f)
        this.fadeRange = fadeRange
        this.fade = fade
        readoutHalf = 0f
        pinsDrawn = 0
    }

    /** Where on the strip a bearing falls. The one piece of arithmetic the whole widget is. */
    private fun xOf(bearing: Float): Float =
        centreX + wrapDegrees(bearing - heading) / fieldOfView * (right - left)

    /** How solid something drawn at [x] is: full in the middle, nothing at the very ends. */
    private fun fadeAt(x: Float): Float {
        if (fade <= 0f) return 1f
        return min((x - left) / fade, (right - x) / fade).coerceIn(0f, 1f)
    }

    /** The small ticks between the names. The one under a name is drawn with the name instead. */
    private fun drawTicks(canvas: UiCanvas, tickDegrees: Float, bottom: Float, major: Float) {
        if (tickDegrees <= 0f || major <= 0f) return
        val step = labels.step
        val count = (360f / tickDegrees).toInt()
        val length = major * MinorShare
        var slot = 0
        for (index in 0 until count) {
            val bearing = index * tickDegrees
            val past = bearing % step
            if (past < Tiny || step - past < Tiny) continue
            val x = xOf(bearing)
            if (x < left || x > right) continue
            val alpha = fadeAt(x)
            if (alpha <= 0f) continue
            tick(canvas, x, bottom - length, bottom, tickColour.scaleAlpha(alpha), tickRects.getOrNull(slot))
            slot++
        }
    }

    /**
     * The names, each over the taller tick that says exactly where it is.
     *
     * A name that would be printed across the readout is left out instead. The middle is the one
     * place on the strip where two things want the same pixels, and the readout is the one a player
     * looked down for.
     */
    private fun drawLabels(canvas: UiCanvas, top: Float, height: Float, tickBottom: Float, major: Float) {
        labels.points.forEachIndexed { index, name ->
            if (name.isEmpty()) return@forEachIndexed
            val x = xOf(index * labels.step)
            if (x < left || x > right) return@forEachIndexed
            val alpha = fadeAt(x)
            if (alpha <= 0f) return@forEachIndexed

            if (major > 0f) {
                tick(canvas, x, tickBottom - major, tickBottom, tickColour.scaleAlpha(alpha), majorRects[index])
            }
            if (height <= 0f) return@forEachIndexed
            val layout = nameRuns.of(name)
            val half = layout.size.width / 2f
            if (readoutHalf > 0f && abs(x - centreX) < readoutHalf + half + SkipGap) return@forEachIndexed
            canvas.textRun(layout, x - half, top, names.textColour.scaleAlpha(alpha), outline)
        }
    }

    /** The heading itself, written in the middle. Sets [readoutHalf], so no name is drawn over it. */
    private fun drawReadout(canvas: UiCanvas, readout: ((Float) -> String)?, top: Float, height: Float) {
        if (readout == null || height <= 0f) return
        val text = readout(((heading % 360f) + 360f) % 360f)
        if (text.isEmpty()) return
        val layout = readoutRuns.of(text)
        readoutHalf = layout.size.width / 2f
        canvas.textRun(layout, centreX - readoutHalf, top, readoutStyle.textColour, outline)
    }

    /** The line down the middle: what the strip is reading, taller than a tick and its own colour. */
    private fun drawMarker(canvas: UiCanvas, bottom: Float, major: Float) {
        if (major <= 0f) return
        canvas.rect(
            markerRect.of(
                centreX - MarkerWidth / 2f,
                bottom - major - MarkerExtra * scale,
                centreX + MarkerWidth / 2f,
                bottom,
            ),
            markerColour,
        )
    }

    private fun tick(canvas: UiCanvas, x: Float, top: Float, bottom: Float, colour: Colour, cache: RectCache?) {
        val half = TickWidth / 2f
        // Past the last kept rectangle a fresh one is made: a strip with two hundred ticks on it is
        // asking for an allocation, not for its ticks to go missing.
        val rect = cache?.of(x - half, top, x + half, bottom) ?: Rect(x - half, top, x + half, bottom)
        canvas.rect(rect, colour)
    }

    // --- the pins ---------------------------------------------------------------------------

    /**
     * The game's own lambda, run here with the lane worked out.
     *
     * The icon is shrunk to fit a short bar, and the numbers under the pins are the first thing to
     * go when there is no room for both: a smaller icon is still a pin, and a number printed over
     * the ruler is neither.
     */
    private fun drawPins(
        canvas: UiCanvas,
        pins: CompassScope.() -> Unit,
        top: Float,
        bottom: Float,
        pinSize: Float,
        distanceText: ((Float) -> String)?,
    ) {
        val lane = (bottom - top).coerceAtLeast(0f)
        val wanted = if (distanceText == null) 0f else distanceHeight + PinGap
        val room = lane - wanted
        this.distanceText = if (room > 0f) distanceText else null
        iconSize = min(pinSize, if (room > 0f) room else lane)
        if (iconSize <= 0f) return

        laneTop = top
        this.canvas = canvas
        pins(this)
    }

    @Suppress("LongParameterList")
    override fun pin(
        bearing: Float,
        icon: String?,
        distance: Float,
        fadeWithDistance: Boolean,
        clamp: Boolean,
        style: String?,
    ) {
        val canvas = canvas ?: return

        val away = wrapDegrees(bearing - heading)
        val beyond = abs(away) > fieldOfView / 2f
        if (beyond && !clamp) return

        // Never in the faint part at the end: a pin telling the player which way to turn is the
        // last thing that should be hard to see. Coerced rather than snapped, so a pin sliding out
        // to the end glides into its place instead of jumping there.
        val inset = maxOf(fade, iconSize / 2f)
        val room = (right - left) / 2f
        val x = xOf(bearing).coerceIn(centreX - room + min(inset, room), centreX + room - min(inset, room))

        val entry = style ?: pinStyle
        val resolved = pinStyles.getOrPut(entry) { skin.resolve(entry).scaledTo(scale) }
        val alpha = if (!fadeWithDistance || distance.isNaN() || fadeRange <= 0f) {
            1f
        } else {
            (1f - distance / fadeRange).coerceIn(FaintestPin, 1f)
        }

        val texture = icon?.let { icons.getOrPut(it) { skin.art?.region(it) } }
        if (texture == null) {
            drawDiamond(canvas, x, laneTop + iconSize / 2f, iconSize / 2f, resolved.colour().scaleAlpha(alpha))
        } else {
            val cache = iconRects.getOrNull(pinsDrawn)
            val half = iconSize / 2f
            val box = cache?.of(x - half, laneTop, x + half, laneTop + iconSize)
                ?: Rect(x - half, laneTop, x + half, laneTop + iconSize)
            canvas.image(texture, box, Colour.White.scaleAlpha(alpha))
        }

        if (beyond) drawClamped(canvas, x, laneTop + iconSize / 2f, away < 0f, resolved.colour().scaleAlpha(alpha))
        drawDistance(canvas, x, laneTop + iconSize, distance, resolved, entry, alpha)
        pinsDrawn++
    }

    /** The number under a pin, when the bar writes them and the lane has room for one. */
    @Suppress("LongParameterList")
    private fun drawDistance(
        canvas: UiCanvas,
        x: Float,
        top: Float,
        distance: Float,
        style: ResolvedStyle,
        name: String,
        alpha: Float,
    ) {
        val write = distanceText ?: return
        if (distance.isNaN()) return
        val text = write(distance)
        if (text.isEmpty()) return
        val layout = pinRuns.getOrPut(name) { Layouts(fonts, style.textStyle) }.of(text)
        canvas.textRun(layout, x - layout.size.width / 2f, top + PinGap, style.textColour.scaleAlpha(alpha), outline)
    }

    /** A pin with no art: a diamond, which reads as a marker rather than as something to press. */
    private fun drawDiamond(canvas: UiCanvas, x: Float, y: Float, half: Float, colour: Colour) {
        diamond[0] = x
        diamond[1] = y - half
        diamond[2] = x + half
        diamond[3] = y
        diamond[4] = x
        diamond[5] = y + half
        diamond[6] = x - half
        diamond[7] = y
        canvas.fan(diamond, colour)
    }

    /** The arrow beside a pin that has run out of strip: which way to turn to bring it back. */
    private fun drawClamped(canvas: UiCanvas, x: Float, y: Float, toTheLeft: Boolean, colour: Colour) {
        val side = if (toTheLeft) -1f else 1f
        val from = x + side * (iconSize / 2f + ArrowGap)
        triangle[0] = from + side * ArrowLength
        triangle[1] = y
        triangle[2] = from
        triangle[3] = y - ArrowLength * 0.8f
        triangle[4] = from
        triangle[5] = y + ArrowLength * 0.8f
        canvas.fan(triangle, colour)
    }

    /**
     * Runs measured once and kept.
     *
     * The compass points are eight strings for the life of the screen. A distance is a different
     * string every frame, so past a screenful of them the cache starts again rather than growing
     * for as long as the game runs.
     */
    private class Layouts(private val fonts: FontProvider, private val style: TextStyle) {

        private val held = HashMap<String, TextLayout>()

        fun of(text: String): TextLayout {
            held[text]?.let { return it }
            if (held.size >= Limit) held.clear()
            return fonts.measure(text, style).also { held[text] = it }
        }

        private companion object {
            const val Limit = 64
        }
    }

    private companion object {

        /** How wide the strip asks to be when nothing has told it. */
        const val NaturalWidth = 420f

        /** How long the tick under a name is, before the text scale. */
        const val TickMajor = 8f

        /** How much of that a tick between two names gets. */
        const val MinorShare = 0.5f

        const val TickWidth = 1f

        /** How much taller than a tick the middle marker is, and how wide it is. */
        const val MarkerExtra = 3f
        const val MarkerWidth = 2f

        /** The gap between the ruler and the names under it. */
        const val LabelGap = 2f

        /** The gap between a pin's icon and the number under it. */
        const val PinGap = 1f

        /** How close a name may come to the readout before it is left out instead. */
        const val SkipGap = 4f

        /** How faint a pin fading with distance ever gets. Faint, not gone. */
        const val FaintestPin = 0.3f

        const val ArrowGap = 1f
        const val ArrowLength = 4f

        /** How many ticks and pins keep their rectangles between frames. */
        const val TickCache = 32
        const val PinCache = 16

        /** A strip showing less of the circle than this is not showing anything. */
        const val MinimumField = 1f

        const val Tiny = 1e-3f
    }
}

/** A style's colour as a shape wants it: what it fills with, or failing that what it writes in. */
private fun ResolvedStyle.colour(): Colour = background.flatColour ?: textColour

/** This style with its text at the player's size. */
private fun ResolvedStyle.scaledTo(scale: Float): ResolvedStyle =
    if (scale == 1f) this else copy(textStyle = textStyle.scaled(scale))
