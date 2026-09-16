package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Clocks
import dev.wildware.composegl.ui.animation.Easing
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.TextZoom
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.saveable.rememberSaveable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Where a [PanZoomCanvas] is looking: how far it is zoomed, and which part of its world is in view.
 *
 * Held outside the canvas, like a [ScrollState], so a screen can move the camera from anywhere — a
 * "centre on me" button, a search that flies to a node, a view restored from a save.
 *
 * The maths is one line each way, in the canvas's own units measured from the corner of its
 * content:
 *
 * ```
 * screen = world × zoom + pan        world = (screen − pan) ÷ zoom
 * ```
 *
 * [zoom] stays within [minZoom] and [maxZoom]. With [bounds] the pan stays where some of the world
 * is in view: a world wider than the view can be panned until an edge of it meets the matching edge
 * of the view, and one narrower is held in the middle. A drag may pull past an edge by a little,
 * against a growing resistance, and it springs back when let go — so the player feels the edge
 * rather than hitting a wall.
 *
 * [zoom], [pan] and everything read from them are snapshot state, so a label reading "150%" is
 * composed again when the zoom changes. The canvas itself reads them only while drawing and finding
 * the pointer, so moving the camera recomposes and measures nothing inside it.
 *
 * @param zoom how far in it starts: one is the world's own size.
 * @param bounds the world's extent in world units, or null for a world with no edges.
 * @param centre the world point in the middle of the view at the start. The middle of [bounds] when
 *   null, or with no bounds, world (0, 0) at the top-left corner.
 */
class PanZoomState(
    zoom: Float = 1f,
    val minZoom: Float = 0.25f,
    val maxZoom: Float = 3f,
    val bounds: Rect? = null,
    centre: Offset? = null,
) {

    init {
        require(minZoom > 0f && maxZoom >= minZoom) { "zoom has to stay above nothing: $minZoom to $maxZoom" }
        require(bounds == null || !bounds.isEmpty) { "a world with bounds needs some area: $bounds" }
    }

    private val startZoom = zoom.coerceIn(minZoom, maxZoom)
    private val startCentre = centre

    /** How much the world is grown by. */
    var zoom: Float by mutableStateOf(startZoom)
        private set

    private var panX: Float by mutableStateOf(0f)
    private var panY: Float by mutableStateOf(0f)

    /** Where world (0, 0) is seen, from the corner of the canvas's content. */
    val pan: Offset get() = Offset(panX, panY)

    /** How big the view is, in the canvas's units. Written by layout; zero before the first. */
    var viewport: Size by mutableStateOf(Size.Zero)
        private set

    /** The world point in the middle of the view. */
    val centre: Offset get() = Offset((viewport.width / 2f - panX) / zoom, (viewport.height / 2f - panY) / zoom)

    /** The part of the world in view, in world units. */
    val visibleWorld: Rect
        get() = Rect(-panX / zoom, -panY / zoom, (viewport.width - panX) / zoom, (viewport.height - panY) / zoom)

    /**
     * The zoom text is made for. The nearest [TextZoom] step of [zoom] once the camera has stopped;
     * while a pinch, a trigger or an animation is moving it, whatever it was when that began, so
     * glyphs are stretched during the gesture and made again when it settles.
     */
    var textZoom: Float by mutableStateOf(TextZoom.snap(startZoom))
        private set

    /** Whether [animateTo] is still on its way. */
    var isAnimating: Boolean by mutableStateOf(false)
        private set

    /** Whether a flick is still carrying the camera. */
    val isFlinging: Boolean get() = velocityX != 0f || velocityY != 0f

    // --- the maths --------------------------------------------------------------------------------

    /** A point in the canvas's units, from the corner of its content, as a world point. */
    fun screenToWorld(point: Offset): Offset = Offset((point.x - panX) / zoom, (point.y - panY) / zoom)

    /** A world point, as where it is seen on the canvas. */
    fun worldToScreen(point: Offset): Offset = Offset(point.x * zoom + panX, point.y * zoom + panY)

    /**
     * Zooms to [newZoom], kept within range, keeping whatever is under [screen] under it — which is
     * what makes a wheel feel like it zooms into what the pointer is on. Stops a fling or an
     * animation.
     */
    fun zoomAbout(screen: Offset, newZoom: Float) {
        stopMoving()
        zoomAround(screen.x, screen.y, screen.x, screen.y, newZoom, soft = holding)
    }

    /** Moves the view by [dx], [dy] of the canvas's units: the world follows, so positive moves it right. */
    fun panBy(dx: Float, dy: Float) {
        stopMoving()
        place(panX + dx, panY + dy, soft = false)
    }

    /** Straight to [centre] at [zoom], clamped. Before the first layout it is where the view starts. */
    fun snapTo(centre: Offset = this.centre, zoom: Float = this.zoom) {
        stopMoving()
        if (!measured) {
            pending = centre
            pendingZoom = zoom.coerceIn(minZoom, maxZoom)
            this.zoom = pendingZoom
            settleText()
            return
        }
        this.zoom = zoom.coerceIn(minZoom, maxZoom)
        place(viewport.width / 2f - centre.x * this.zoom, viewport.height / 2f - centre.y * this.zoom, soft = false)
        settleText()
    }

    /**
     * Eases to [centre] at [zoom] over [durationMillis] of interface time. A drag, a wheel or another
     * call takes over from wherever it has reached. Before the first layout it snaps.
     *
     * Zoom moves by ratio rather than by difference, so going from a quarter to three looks as even
     * as going from one to two.
     */
    fun animateTo(
        centre: Offset = this.centre,
        zoom: Float = this.zoom,
        durationMillis: Int = DefaultDurationMillis,
        easing: Easing = Easings.EaseInOut,
    ) {
        if (!measured || durationMillis <= 0) {
            snapTo(centre, zoom)
            return
        }
        stopMoving()
        val targetZoom = zoom.coerceIn(minZoom, maxZoom)
        // Where it can really end up, so the ease does not aim past an edge and stop short of it.
        val targetPanX = clampX(viewport.width / 2f - centre.x * targetZoom, targetZoom)
        val targetPanY = clampY(viewport.height / 2f - centre.y * targetZoom, targetZoom)
        fromZoom = this.zoom
        fromCentre = this.centre
        toZoom = targetZoom
        toCentre = Offset((viewport.width / 2f - targetPanX) / targetZoom, (viewport.height / 2f - targetPanY) / targetZoom)
        animationNanos = durationMillis * 1_000_000L
        animationElapsed = 0L
        animationEasing = easing
        isAnimating = true
        busy()
    }

    /** Eases so the whole of [bounds] is in view, with [margin] of the canvas's units round it. Does nothing without bounds. */
    fun fit(margin: Float = 0f, animate: Boolean = true) {
        val world = bounds ?: return
        val width = (viewport.width - margin * 2f).coerceAtLeast(1f)
        val height = (viewport.height - margin * 2f).coerceAtLeast(1f)
        val zoom = if (viewport.width <= 0f) this.zoom else minOf(width / world.width, height / world.height)
        if (animate) animateTo(world.centre, zoom) else snapTo(world.centre, zoom)
    }

    /** Eases back to the zoom and centre it started with. */
    fun reset(animate: Boolean = true) {
        val centre = startCentre ?: bounds?.centre ?: Offset(viewport.width / 2f / startZoom, viewport.height / 2f / startZoom)
        if (animate) animateTo(centre, startZoom) else snapTo(centre, startZoom)
    }

    /** Stops a fling, an animation and a spring back where they are. */
    fun stop() = stopMoving()

    // --- what the canvas tells it ------------------------------------------------------------------

    /** The node it is drawn on, redrawn when the camera moves. */
    internal var node: UiNode? = null

    /** The clocks the canvas runs on, told when the camera is moving on its own so a test waits for it. */
    internal var clocks: Clocks? = null
        set(value) {
            if (field === value) return
            if (markedBusy) {
                field?.ended(Clock.Ui)
                markedBusy = false
            }
            field = value
            value?.register(Clock.Ui)
            busy()
        }

    private var measured = false
    private var pending: Offset? = null
    private var pendingZoom = startZoom

    /** Layout's answer: how big the view is. The first one places the camera; later ones keep the centre where it was. */
    internal fun measured(width: Float, height: Float) {
        if (measured && width == viewport.width && height == viewport.height) return
        val keep = if (measured) centre else pending ?: startCentre ?: bounds?.centre
        viewport = Size(width.coerceAtLeast(0f), height.coerceAtLeast(0f))
        measured = true
        pending = null
        if (keep != null) {
            place(viewport.width / 2f - keep.x * zoom, viewport.height / 2f - keep.y * zoom, soft = false)
        } else {
            place(panX, panY, soft = false)
        }
    }

    // A hand on the canvas: while it is there the edge gives rather than stops, nothing springs
    // back, and glyphs are not made again.
    private var holding = false

    /** Whether a finger or the mouse is on the canvas. Told by the gestures, never counted here. */
    internal fun holding(value: Boolean) {
        if (value == holding) return
        holding = value
        if (!value) {
            busy()
            settleText()
        }
    }

    /** A drag moving the view by [dx], [dy]: against the resistance of an edge it is already past. */
    internal fun dragBy(dx: Float, dy: Float) {
        // A hand on the camera takes it over from whatever was moving it.
        stopMoving()
        place(panX + resisted(dx, panX, clampX(panX, zoom)), panY + resisted(dy, panY, clampY(panY, zoom)), soft = true)
    }

    /**
     * Two fingers: what was under ([fromX], [fromY]) at the last step ends up under ([toX], [toY]),
     * with the zoom multiplied by [factor]. A pinch that also moves pans with it.
     */
    internal fun pinch(fromX: Float, fromY: Float, toX: Float, toY: Float, factor: Float) {
        zoomAround(fromX, fromY, toX, toY, zoom * factor, soft = true)
    }

    /** Carries on at this speed, in the canvas's units per second, slowing as a list does. */
    internal fun fling(speedX: Float, speedY: Float) {
        velocityX = if (abs(speedX) < MinimumFling) 0f else speedX
        velocityY = if (abs(speedY) < MinimumFling) 0f else speedY
        busy()
    }

    // The pad, held rather than stepped: the stick is a speed and each trigger a rate of zoom.
    internal var stickX = 0f
    internal var stickY = 0f
    internal var zoomingIn = 0f
    internal var zoomingOut = 0f

    internal fun releasePad() {
        stickX = 0f
        stickY = 0f
        zoomingIn = 0f
        zoomingOut = 0f
        settleText()
    }

    /**
     * Moves [area] — a rectangle in the canvas's content units, a focused node — fully into view,
     * [margin] in from the edge, by the smallest move that does it. One too big for the view is
     * centred. Eases rather than jumps, so a pad walking a skill tree sees the camera follow.
     */
    internal fun reveal(area: Rect, margin: Float = RevealMargin): Boolean {
        if (!measured) return false
        val dx = shift(area.left, area.right, viewport.width, margin)
        val dy = shift(area.top, area.bottom, viewport.height, margin)
        if (dx == 0f && dy == 0f) return false
        val targetX = clampX(panX - dx, zoom)
        val targetY = clampY(panY - dy, zoom)
        if (abs(targetX - panX) < 0.5f && abs(targetY - panY) < 0.5f) return false
        animateTo(Offset((viewport.width / 2f - targetX) / zoom, (viewport.height / 2f - targetY) / zoom), zoom, RevealMillis)
        return true
    }

    /** How far the view has to move along one axis to bring start..end inside it. */
    private fun shift(start: Float, end: Float, visible: Float, margin: Float): Float {
        val room = visible - margin * 2f
        return when {
            end - start > room -> (start + end) / 2f - visible / 2f
            start < margin -> start - margin
            end > visible - margin -> end - (visible - margin)
            else -> 0f
        }
    }

    /** Whether one more pan step towards ([dx], [dy]) would move the view at all. */
    internal fun canPan(dx: Float, dy: Float): Boolean =
        abs(clampX(panX + dx, zoom) - panX) > 0.5f || abs(clampY(panY + dy, zoom) - panY) > 0.5f

    // --- one frame ----------------------------------------------------------------------------------

    private var velocityX = 0f
    private var velocityY = 0f

    private var fromZoom = 1f
    private var toZoom = 1f
    private var fromCentre = Offset.Zero
    private var toCentre = Offset.Zero
    private var animationNanos = 0L
    private var animationElapsed = 0L
    private var animationEasing: Easing = Easings.EaseInOut

    /**
     * One frame of whatever moves the camera on its own: an animation, a fling, the spring back
     * from past an edge, a held stick and held triggers. [nanos] is interface time.
     */
    internal fun advance(nanos: Long) {
        if (nanos <= 0L) return
        val seconds = nanos / 1_000_000_000f

        if (isAnimating) {
            animationElapsed += nanos
            val fraction = (animationElapsed.toFloat() / animationNanos).coerceIn(0f, 1f)
            val eased = animationEasing.transform(fraction)
            val zoom = fromZoom * (toZoom / fromZoom).pow(eased)
            val x = fromCentre.x + (toCentre.x - fromCentre.x) * eased
            val y = fromCentre.y + (toCentre.y - fromCentre.y) * eased
            this.zoom = zoom
            place(viewport.width / 2f - x * zoom, viewport.height / 2f - y * zoom, soft = false)
            if (fraction >= 1f) {
                isAnimating = false
                settleText()
            }
        }

        if (velocityX != 0f || velocityY != 0f) {
            val beforeX = panX
            val beforeY = panY
            place(panX + velocityX * seconds, panY + velocityY * seconds, soft = true)
            // Past an edge a flick stops, and the spring brings it back.
            if (panX == beforeX || panX != clampX(panX, zoom)) velocityX = 0f
            if (panY == beforeY || panY != clampY(panY, zoom)) velocityY = 0f
            val kept = Retained.pow(seconds)
            velocityX *= kept
            velocityY *= kept
            if (abs(velocityX) < MinimumFling) velocityX = 0f
            if (abs(velocityY) < MinimumFling) velocityY = 0f
        }

        val stick = sqrt(stickX * stickX + stickY * stickY)
        if (stick > PadDeadZone) {
            // Squared, so a small push is fine aiming and a full one crosses the map.
            val speed = PadPanSpeed * ((stick - PadDeadZone) / (1f - PadDeadZone)).coerceIn(0f, 1f).let { it * it }
            place(panX - stickX / stick * speed * seconds, panY - stickY / stick * speed * seconds, soft = false)
        }

        val zooming = zoomingIn - zoomingOut
        if (abs(zooming) > PadDeadZone) {
            val factor = exp(zooming * TriggerZoomRate * seconds)
            zoomAround(viewport.width / 2f, viewport.height / 2f, viewport.width / 2f, viewport.height / 2f, zoom * factor, soft = false)
        }

        // Back inside the edges, once nothing is holding it out.
        if (!holding && !isAnimating && velocityX == 0f && velocityY == 0f) {
            val wantX = clampX(panX, zoom)
            val wantY = clampY(panY, zoom)
            if (wantX != panX || wantY != panY) {
                val step = 1f - exp(-SpringRate * seconds)
                val x = if (abs(wantX - panX) < SnapDistance) wantX else panX + (wantX - panX) * step
                val y = if (abs(wantY - panY) < SnapDistance) wantY else panY + (wantY - panY) * step
                place(x, y, soft = true)
            }
        }

        if (!holding && !isAnimating && abs(zooming) <= PadDeadZone) settleText()
        busy()
    }

    // --- the machinery --------------------------------------------------------------------------------

    private fun stopMoving() {
        velocityX = 0f
        velocityY = 0f
        if (isAnimating) {
            isAnimating = false
            settleText()
        }
        busy()
    }

    private fun zoomAround(fromX: Float, fromY: Float, toX: Float, toY: Float, newZoom: Float, soft: Boolean) {
        val worldX = (fromX - panX) / zoom
        val worldY = (fromY - panY) / zoom
        zoom = newZoom.coerceIn(minZoom, maxZoom)
        place(toX - worldX * zoom, toY - worldY * zoom, soft)
        if (!holding) settleText()
    }

    /**
     * The pan, kept within the edges: exactly, or by [soft] no further past one than the stretch a
     * drag can pull. Redraws the canvas when anything moved.
     */
    private fun place(x: Float, y: Float, soft: Boolean) {
        val nextX = if (soft) stretch(x, clampX(x, zoom)) else clampX(x, zoom)
        val nextY = if (soft) stretch(y, clampY(y, zoom)) else clampY(y, zoom)
        if (nextX == panX && nextY == panY && zoom == placedZoom) return
        panX = nextX
        panY = nextY
        placedZoom = zoom
        node?.invalidate()
    }

    /** The zoom the canvas was last redrawn for, so a zoom that leaves the pan alone still redraws. */
    private var placedZoom = startZoom

    private fun stretch(value: Float, inside: Float): Float =
        if (abs(value - inside) <= Stretch) value else inside + Stretch * if (value > inside) 1f else -1f

    /** [delta] less the share an edge already pulled past takes back: nothing left at a full stretch. */
    private fun resisted(delta: Float, at: Float, inside: Float): Float {
        val past = at - inside
        // Towards the inside is free.
        if (past == 0f || (past > 0f) != (delta > 0f)) return delta
        return delta * (1f - abs(past) / Stretch).coerceIn(0f, 1f)
    }

    private fun clampX(x: Float, zoom: Float): Float {
        val world = bounds ?: return x
        return clampAxis(x, world.left * zoom, world.right * zoom, viewport.width)
    }

    private fun clampY(y: Float, zoom: Float): Float {
        val world = bounds ?: return y
        return clampAxis(y, world.top * zoom, world.bottom * zoom, viewport.height)
    }

    /** A world wider than the view reaches from edge to edge; a narrower one is held in the middle. */
    private fun clampAxis(pan: Float, start: Float, end: Float, visible: Float): Float =
        if (end - start >= visible) pan.coerceIn(visible - end, -start) else (visible - start - end) / 2f

    private fun settleText() {
        if (holding || isAnimating || abs(zoomingIn - zoomingOut) > PadDeadZone) return
        val snapped = TextZoom.snap(zoom)
        if (snapped != textZoom) {
            textZoom = snapped
            node?.invalidate()
        }
    }

    /** Whether the camera is moving on its own, and so something a test should wait for. */
    private val moving: Boolean
        get() = isAnimating || velocityX != 0f || velocityY != 0f ||
            (!holding && measured && (clampX(panX, zoom) != panX || clampY(panY, zoom) != panY))

    private var markedBusy = false

    private fun busy() {
        val clocks = clocks ?: return
        val now = moving
        if (now == markedBusy) return
        markedBusy = now
        if (now) clocks.began(Clock.Ui) else clocks.ended(Clock.Ui)
    }

    /**
     * Lets the clocks and the canvas go, for one leaving the screen with the camera still moving.
     *
     * The state outlives the canvas — it is remembered across a screen being put away — so holding
     * on to the node would keep a whole tree nobody can see alive until something used the state
     * again. The next canvas attaches its own.
     */
    internal fun detach() {
        // The setter ends the clock it began, so the count is right whether or not it was moving.
        clocks = null
        node = null
        releasePad()
    }

    internal companion object {
        const val DefaultDurationMillis = 400
        const val RevealMillis = 250
        const val RevealMargin = 16f

        /** How far a drag can pull past an edge, in the canvas's units. */
        const val Stretch = 96f

        /** How quickly the view springs back inside, per second. */
        const val SpringRate = 12f
        const val SnapDistance = 0.5f

        /** The share of a fling's speed left after one second, and the speed below which it has stopped. */
        const val Retained = 0.02f
        const val MinimumFling = 40f

        const val PadDeadZone = 0.2f

        /** How fast a stick pushed all the way pans, in the canvas's units per second. */
        const val PadPanSpeed = 900f

        /** A trigger held all the way doubles the zoom in about this much of a second. */
        val TriggerZoomRate = ln(2f) * 2f
    }
}

/**
 * A [PanZoomState] that is still looking where the player left it when its screen comes back, under a
 * [dev.wildware.composegl.ui.saveable.SaveableStateHolder]. Outside one it is plain `remember`.
 */
@Composable
fun rememberPanZoomState(
    zoom: Float = 1f,
    minZoom: Float = 0.25f,
    maxZoom: Float = 3f,
    bounds: Rect? = null,
    centre: Offset? = null,
): PanZoomState = rememberSaveable { PanZoomState(zoom, minZoom, maxZoom, bounds, centre) }
