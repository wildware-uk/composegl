package composegl.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import composegl.ui.geometry.Rect
import composegl.ui.graphics.Colour
import composegl.ui.graphics.UiCanvas
import composegl.ui.layout.LeafLayout
import composegl.ui.modifier.Modifier
import composegl.ui.skin.ResolvedStyle
import composegl.ui.skin.Skin
import composegl.ui.skin.LocalSkin
import composegl.ui.skin.SkinDrawable
import composegl.ui.skin.rememberStyle
import composegl.ui.text.FontProvider
import composegl.ui.text.TextLayout
import composegl.ui.widget.rememberFonts
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Something on the map: an objective, a squadmate, a ping.
 *
 * Its place is in the game's own world units, measured from whatever the middle of the map is —
 * usually the player. East is `+x` and north is `+y`, which is the way a game thinks rather than
 * the way a screen does; turning that into pixels the right way up is this widget's job.
 *
 * Mutable, and meant to be kept and moved rather than made again each frame: a squad of four that
 * are all walking is four objects for the life of the screen, not four a frame.
 *
 * @param style a skin style of its own, for a map that draws an objective differently from a
 *   friend. Null takes the frame's `"<style>.marker"`.
 * @param edge whether it is still drawn, pinned to the edge, once it is off the map. Off for
 *   something that is only interesting while it is close.
 */
class MinimapMarker(
    var x: Float = 0f,
    var y: Float = 0f,
    val style: String? = null,
    val edge: Boolean = true,
)

/**
 * The chrome around a minimap: the frame, the hole the game draws its map into, the markers round
 * the edge and the compass.
 *
 * The map itself is deliberately not ours. A game's map is its own — a texture it renders, a mesh,
 * a tile grid — so this clips a rectangle and hands it over through [content], where a game either
 * draws with the toolkit's own canvas or reaches the backend underneath with `raw { }`. Everything
 * outside that rectangle is this widget's: the border and background out of the skin, an arrow at
 * the edge for every objective that is off the map, and the cardinal letters.
 *
 * Markers off the map are pinned to the **edge of the frame**, which is a ray meeting a rectangle
 * rather than a circle. A minimap is hardly ever square — a wide one is normal — and the round
 * version puts an objective due east somewhere in the middle of nothing.
 *
 * ```kotlin
 * MinimapFrame(
 *     Modifier.size(220f, 140f),
 *     heading = player.heading,
 *     markers = objectives,
 *     range = 120f,
 * ) { area -> raw { batch -> game.drawMapInto(batch as SpriteBatch, area) } }
 * ```
 *
 * @param heading which way the player is facing, in degrees clockwise from north.
 * @param rotate whether the map turns with the player, so that forwards is up. When it does the
 *   markers and the compass turn with it; when it does not, north is up and only the compass
 *   needle moves.
 * @param range how far the edge of the map is, in the game's own world units. A marker further
 *   away than this is off the map.
 * @param compass the letters drawn at the cardinal points, north first, going clockwise: `"N"` for
 *   a needle, `"NESW"` for all four, `""` for none.
 * @param live whether the game behind it is moving. A live map is redrawn every frame, because
 *   everything on it — the markers, the heading, the game's own map — changes outside the
 *   composition and nothing here would otherwise know. A map that is not live is not redrawn at
 *   all, which is what a paused game or a menu wants.
 * @param style the skin style of the frame. `"<style>.marker"` is a marker, `"<style>.compass"`
 *   the letters; both fall back to the frame's own.
 */
@Composable
fun MinimapFrame(
    modifier: Modifier = Modifier,
    heading: Float = 0f,
    rotate: Boolean = false,
    markers: List<MinimapMarker> = emptyList(),
    range: Float = 100f,
    compass: String = "N",
    live: Boolean = true,
    style: String = "minimap",
    content: (UiCanvas.(Rect) -> Unit)? = null,
) {
    val fonts = rememberFonts()
    val skin = LocalSkin.current
    val frame = rememberStyle(style)
    val letters = rememberStyle("$style.compass")

    // The skin itself rather than a style, because a marker may name one of its own and how many
    // names there are is the game's business, not something the composition can hold a slot for.
    val painter = remember(fonts, skin, frame, letters, compass, style) {
        MinimapPainter(fonts, skin, frame, letters, compass, "$style.marker")
    }

    // Markers move, the player turns and the game's own map changes underneath — all of it
    // outside the composition, none of it state anybody wrote. So while it is live the frame is
    // redrawn every frame, and while it is not it is not redrawn at all: a map on a pause menu,
    // or one in a screenshot, costs exactly nothing.
    var pulse by remember { mutableStateOf(0) }
    LaunchedEffect(live) {
        while (live) {
            withFrameNanos { }
            pulse++
        }
    }

    val draw = remember(painter, heading, rotate, markers, range, content, pulse) {
        painter.drawWith(heading, rotate, markers, range, content)
    }

    LeafLayout(modifier, name = "minimap", draw = draw)
}

/**
 * Everything the frame draws, in one pass and with no allocation once it is going.
 *
 * The one piece of real arithmetic is [edgeOf]: where a line out of the middle of a rectangle
 * leaves it. Doing that against the rectangle rather than against a circle inside it is what makes
 * an edge marker point at the right thing on a map that is not square.
 */
private class MinimapPainter(
    private val fonts: FontProvider,
    private val skin: Skin,
    private val frame: ResolvedStyle,
    private val letters: ResolvedStyle,
    private val compass: String,
    private val markerStyle: String,
) {

    private val triangle = FloatArray(6)
    private val cardinals = HashMap<Char, TextLayout>()

    /** A marker may name a style of its own; each one is looked up once and kept. */
    private val markerStyles = HashMap<String, ResolvedStyle>()

    // Where the last piece of arithmetic put things. Scratch, because a marker a frame is a
    // hundred and eighty pairs a second for a squad of three.
    private var pointX = 0f
    private var pointY = 0f

    fun drawWith(
        heading: Float,
        rotate: Boolean,
        markers: List<MinimapMarker>,
        range: Float,
        content: (UiCanvas.(Rect) -> Unit)?,
    ): UiCanvas.(Rect) -> Unit = { bounds ->
        frame.background.drawInto(this, bounds, Colour.White)

        val padding = frame.padding
        val inner = Rect(
            bounds.left + padding.left,
            bounds.top + padding.top,
            bounds.right - padding.right,
            bounds.bottom - padding.bottom,
        )

        // The game's own map, and the only clip: everything it draws is inside the frame whatever
        // it does, which is the half of this widget a game cannot write for itself.
        pushClip(inner)
        content?.invoke(this, inner)

        // Turning the map means turning what is on it. Turning nothing means north is up and the
        // compass is the only thing that moves.
        val turn = if (rotate) -heading else 0f
        markers.forEach { drawMarker(this, it, inner, range, turn) }
        drawCompass(this, inner, turn)
        popClip()
    }

    private fun drawMarker(canvas: UiCanvas, entry: MinimapMarker, inner: Rect, range: Float, turn: Float) {
        val middleX = (inner.left + inner.right) / 2f
        val middleY = (inner.top + inner.bottom) / 2f
        val scale = min(inner.right - inner.left, inner.bottom - inner.top) / 2f / range

        // North is up and east is right, so the world's y runs the other way from the screen's.
        rotate(entry.x, -entry.y, turn)
        val towardsX = pointX
        val towardsY = pointY
        val x = middleX + towardsX * scale
        val y = middleY + towardsY * scale

        val style = markerStyles.getOrPut(entry.style ?: markerStyle) { skin.resolve(entry.style ?: markerStyle) }
        val colour = (style.background as? SkinDrawable.Fill)?.colour ?: style.textColour

        if (inner.contains(x, y)) {
            square(canvas, x, y, Size / 2f, colour)
            return
        }
        if (!entry.edge) return

        // Off the map: pinned where the line out to it leaves the frame, and pointing along that
        // same line, so the player can turn towards it.
        edgeOf(inner, towardsX, towardsY, Inset)
        arrow(canvas, pointX, pointY, atan2(towardsY, towardsX), colour)
    }

    private fun drawCompass(canvas: UiCanvas, inner: Rect, turn: Float) {
        if (compass.isEmpty()) return

        compass.forEachIndexed { index, letter ->
            val layout = cardinals.getOrPut(letter) { fonts.measure(letter.toString(), letters.textStyle) }
            // North first and then clockwise: each letter is a quarter turn further round.
            val degrees = turn + index * 90f
            val radians = degrees * Radians
            edgeOf(inner, sin(radians), -cos(radians), layout.size.height / 1.4f)
            canvas.text(
                layout,
                pointX - layout.size.width / 2f,
                pointY - layout.size.height / 2f,
                letters.textColour,
            )
        }
    }

    private fun square(canvas: UiCanvas, x: Float, y: Float, half: Float, colour: Colour) {
        canvas.rect(Rect(x - half, y - half, x + half, y + half), colour, 1f)
    }

    /** A triangle pointing along [radians], with its tip on the edge. */
    private fun arrow(canvas: UiCanvas, x: Float, y: Float, radians: Float, colour: Colour) {
        val nose = Size * 0.8f
        val side = Size * 0.6f
        triangle[0] = x + cos(radians) * nose
        triangle[1] = y + sin(radians) * nose
        triangle[2] = x + cos(radians + Third) * side
        triangle[3] = y + sin(radians + Third) * side
        triangle[4] = x + cos(radians - Third) * side
        triangle[5] = y + sin(radians - Third) * side
        canvas.fan(triangle, colour)
    }

    /**
     * Puts into [pointX] and [pointY] where a line out of the middle of [rect] in direction
     * ([dx], [dy]) leaves it, pulled [inset] pixels back inside so that what is drawn there is not
     * half outside the frame.
     *
     * The rectangle, not a circle inside it. On a map twice as wide as it is tall, the circle
     * version puts a marker due east a third of the way in from the edge, at nothing.
     */
    private fun edgeOf(rect: Rect, dx: Float, dy: Float, inset: Float) {
        val middleX = (rect.left + rect.right) / 2f
        val middleY = (rect.top + rect.bottom) / 2f
        val halfWidth = (rect.right - rect.left) / 2f - inset
        val halfHeight = (rect.bottom - rect.top) / 2f - inset

        // How far along the line each edge is, and the nearer one is the one it leaves by.
        val toSide = if (abs(dx) < Tiny) Float.MAX_VALUE else halfWidth / abs(dx)
        val toTopOrBottom = if (abs(dy) < Tiny) Float.MAX_VALUE else halfHeight / abs(dy)
        val distance = min(toSide, toTopOrBottom)

        pointX = middleX + dx * distance
        pointY = middleY + dy * distance
    }

    private fun rotate(x: Float, y: Float, degrees: Float) {
        if (degrees == 0f) {
            pointX = x
            pointY = y
            return
        }
        val radians = degrees * Radians
        val c = cos(radians)
        val s = sin(radians)
        pointX = x * c - y * s
        pointY = x * s + y * c
    }

    private companion object {
        /** How big a marker is drawn, in interface pixels. */
        const val Size = 7f

        /** How far inside the frame an edge marker sits, so that none of it is clipped away. */
        const val Inset = 6f

        const val Radians = 0.017453292f

        /** A third of a turn, which is what makes the arrow's two back corners. */
        const val Third = 2.0944f

        const val Tiny = 1e-6f
    }
}

private fun Rect.contains(x: Float, y: Float): Boolean =
    x >= left && x <= right && y >= top && y <= bottom
