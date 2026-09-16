package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.animation.Clocks
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.IntrinsicMeasurable
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.zIndex
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.skin.flatColour
import dev.wildware.composegl.ui.skin.rememberStyle
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a marker does on a frame when the point it belongs to is not on the screen.
 *
 * "Not on the screen" includes being behind the camera, which is the case that matters most for a
 * waypoint: the thing a player is being sent to is usually the thing they are not looking at.
 */
sealed interface OffScreen {

    /** Nothing is drawn. What a nameplate wants: a name over an enemy nobody can see is noise. */
    data object Hide : OffScreen

    /**
     * Drawn where it landed, even when that is past the edge.
     *
     * For content meant to hang over a border — a pin on the rim of a map — and for a game that
     * would rather decide for itself what to do with the ones that went off.
     *
     * The one point this is not true of is one behind the camera. A projection mirrors those
     * through the middle of the view, so there is no "where it landed" to honour: the layer turns
     * them back and pushes them out to the nearest border along that direction, leaving a
     * direction, which is the only honest thing left.
     */
    data object Show : OffScreen

    /**
     * Held at the nearest edge, so a player can always see which way to turn.
     *
     * The whole marker is brought inside, not only its point: a waypoint at the left edge has its
     * icon fully on screen rather than half of it. It is the marker's box that is measured against
     * the edge too, so a wide nameplate starts sliding in while its point is still on screen
     * instead of jumping half its own width when the point crosses over. The edge it is held
     * against is the layer's own box, which a [dev.wildware.composegl.ui.layout.Viewport] has
     * already brought in past a notch or a television's overscan — [inset] is extra room on top of
     * that.
     *
     * @param arrow whether the layer draws a small triangle beside the marker, turned to point at
     *   where the thing really is. It is there whenever the marker is being held back from its
     *   point, which is a little before the point itself leaves the screen.
     *   Its colour is the skin's `"<style>.arrow"`. The marker is held
     *   far enough in for the whole triangle to fit beside it, so asking for an arrow costs about
     *   another `arrowSize` of room at the edge — the arrow is the part that must not be clipped.
     * @param inset how far in from the edge to hold it, in interface pixels.
     * @param arrowSize how big the triangle is, tip to back edge.
     */
    data class ClampToEdge(
        val arrow: Boolean = true,
        val inset: Float = 0f,
        val arrowSize: Float = 12f,
    ) : OffScreen
}

/** Where the markers of one [WorldMarkerLayer] are declared. See [marker]. */
interface WorldMarkerScope {

    /**
     * One marker, at a fixed point in the world.
     *
     * @param key what this marker is, so that it keeps its node — and whatever state is inside it
     *   — from frame to frame while the list around it changes. An enemy's id, a quest's name.
     * @param offScreen what happens when the point is not on screen. See [OffScreen].
     * @param fadeDistance the distances between which the marker fades out: solid at the near end,
     *   gone at the far one, so that distant nameplates thin out instead of piling up. Null never
     *   fades. The number compared is whatever the projection wrote as the depth, with its sign
     *   thrown away — see [WorldProjection] — so a waypoint a hundred metres behind the player
     *   fades exactly like one a hundred metres in front. A marker that has faded away entirely
     *   gives up its place to one behind it rather than holding it invisibly.
     * @param scaleDistance the same for size: full size at the near end, [farScale] at the far
     *   one. Null keeps every marker the size it was laid out, which is what a readable nameplate
     *   usually wants — see [WorldMarkerLayer] for what scaling costs.
     * @param farScale how big a marker is drawn at the far end of [scaleDistance].
     * @param priority which markers survive [WorldMarkerLayer]'s `maxVisible` and decluttering.
     *   Higher wins; ties go to whichever is nearer, and something behind the camera is as far
     *   away as something the same distance in front rather than nearer than all of them.
     * @param anchor which point of the marker sits on the world point. [Alignment.Centre] centres
     *   it, [Alignment.BottomCentre] stands it on the point like a label on a post. Start is the
     *   marker's left whichever way the screen reads, because a world is a picture rather than a
     *   line of text — the words inside the marker still lay themselves out the right way round.
     */
    fun marker(
        key: Any,
        x: Float,
        y: Float,
        z: Float = 0f,
        offScreen: OffScreen = OffScreen.Hide,
        fadeDistance: ClosedFloatingPointRange<Float>? = null,
        scaleDistance: ClosedFloatingPointRange<Float>? = null,
        farScale: Float = 0.6f,
        priority: Float = 0f,
        anchor: Alignment = Alignment.Centre,
        content: @Composable () -> Unit,
    )

    /**
     * The same, for something that moves.
     *
     * [position] is asked where it is once a frame, so a nameplate follows a running enemy with
     * the game recomposing nothing. That is the whole difference between the two: the overload
     * above takes a point, this one takes a way of finding the point.
     *
     * ```kotlin
     * marker(key = enemy.id, position = { it.set(enemy.x, enemy.y + 2f, enemy.z) }) { Nameplate(enemy) }
     * ```
     */
    fun marker(
        key: Any,
        position: WorldAnchor,
        offScreen: OffScreen = OffScreen.Hide,
        fadeDistance: ClosedFloatingPointRange<Float>? = null,
        scaleDistance: ClosedFloatingPointRange<Float>? = null,
        farScale: Float = 0.6f,
        priority: Float = 0f,
        anchor: Alignment = Alignment.Centre,
        content: @Composable () -> Unit,
    )
}

/**
 * Interface that belongs to points in the game's world rather than to the corners of the screen:
 * nameplates, health bars over enemies, quest waypoints, interaction prompts, ping markers.
 *
 * ```kotlin
 * WorldMarkerLayer(projection = camera, maxVisible = 12) {
 *     enemies.forEach { enemy ->
 *         marker(
 *             key = enemy.id,
 *             position = { it.set(enemy.x, enemy.y + 2f, enemy.z) },
 *             fadeDistance = 30f..40f,
 *         ) {
 *             Column { Text(enemy.name); Bar(enemy.health, Modifier.width(60f)) }
 *         }
 *     }
 *     marker(
 *         key = "objective",
 *         x = objective.x, y = objective.y, z = objective.z,
 *         offScreen = OffScreen.ClampToEdge(arrow = true, inset = 24f),
 *         priority = 1f,
 *     ) {
 *         WaypointIcon(player.distanceTo(objective))
 *     }
 * }
 * ```
 *
 * Every marker is a real node, so what goes in one is any interface at all — a bar, a portrait, a
 * button — clicked, hovered and reached by a pad the way anything else is.
 *
 * **Moving a marker does not recompose it.** The projection runs once per marker per frame and the
 * answer is used to *place* the node, which is the cheap half of layout: a hundred nameplates
 * following a hundred running enemies recompose nothing at all. A marker that is fading, or being
 * resized by distance, recomposes its own wrapper — opacity and size are drawn rather than placed
 * — and what is inside the wrapper is skipped, so the cost is the wrapper and not the nameplate.
 *
 * **A marker that is not shown is not there.** Hidden, faded, decluttered and over-the-limit
 * markers are drawn at nothing, which is the same early-out any invisible node gets: not drawn,
 * not clickable, not somewhere focus can land.
 *
 * **Nearer draws over further.** The layer sorts by how far away the projection said each marker
 * is and lifts the nearer ones with `zIndex`, so two nameplates that overlap stack the way the
 * world does. Behind the camera is a distance like any other — a waypoint fifty metres behind the
 * player is fifty metres away, not minus fifty — so nothing behind them covers what is in front.
 * It is their order in the pile that is used rather than the depth itself, so two markers whose
 * distance changes every frame recompose nothing until they actually cross.
 *
 * **One layer per viewport.** In split-screen each player has their own host, their own camera and
 * their own layer, and each layer projects into its own box: the size handed to [WorldProjection]
 * is the layer's, not the window's, so one HUD written once is right in every quarter.
 *
 * It asks for a frame only while it has markers on it, and it redraws only on a frame where
 * something actually moved — so a paused game with the camera standing still costs one projection
 * per marker and no drawing at all.
 *
 * **Move the camera before the layer reads it.** The layer projects from inside its own
 * `withFrameNanos`, which is the only place it can both see the new frame and ask for a redraw on
 * it. A game that writes its camera in a frame callback of its own registered *after* the layer's
 * is therefore writing it too late: that frame's markers were placed from the camera as it stood
 * last frame, and a fast pan shows it as the markers sliding a frame behind the world under them.
 * Nothing is lost and nothing drifts — every frame is one frame behind, not two — but the slide is
 * visible, and it is the kind of thing that is blamed on the layer.
 *
 * It is documented rather than fixed because both ways out are worse. Projecting a second time
 * during layout would break what this layer promises — one projection per marker per frame, which
 * is what lets a game do real work in [WorldProjection.project] — and asking for a redraw every
 * frame in case the camera moved would cost a paused game a full redraw a frame for nothing.
 *
 * So: write the camera in the game's own update, before the interface is composed, or from a frame
 * callback registered before the layer exists. A camera held in ordinary state and written from
 * anywhere outside a frame callback is already right, which is the usual case and why this is
 * rarely met.
 *
 * `scaleDistance` is the one thing to be careful with: scaling is drawn through an offscreen
 * picture, as [dev.wildware.composegl.ui.modifier.scale] explains, so it is crisp shrinking and
 * soft growing. Markers that must be sharp at every distance should be laid out at the size they
 * are drawn — change what is inside the marker, not the marker's scale.
 *
 * @param projection how the game turns a place in its world into a place on this layer. The same
 *   one [DamageNumberLayer] takes; markers additionally read the depth it writes.
 * @param maxVisible how many markers may be on screen at once. The rest are dropped, lowest
 *   priority and furthest away first. A screen with forty enemies on it is unreadable long before
 *   it is slow.
 * @param declutter whether a marker that would overlap one already kept is dropped. Off by
 *   default: it is right for waypoints and icons, and wrong for nameplates a player expects to see
 *   over every enemy.
 * @param style the skin style the layer draws its own chrome from. `"<style>.arrow"` is the colour
 *   of the off-screen arrows.
 */
@Composable
fun WorldMarkerLayer(
    modifier: Modifier = Modifier,
    projection: WorldProjection = WorldProjection.Screen,
    maxVisible: Int = Int.MAX_VALUE,
    declutter: Boolean = false,
    style: String = "marker",
    content: WorldMarkerScope.() -> Unit,
) {
    require(maxVisible >= 0) { "a layer cannot show a negative number of markers, was $maxVisible" }

    val markers = remember { WorldMarkers() }
    markers.sync(rememberDeclared(content), maxVisible, declutter)

    val arrowStyle = rememberStyle("$style.arrow")
    val arrows = remember(markers) { ArrowPainter(markers) }
    arrows.colour = arrowStyle.background.flatColour ?: arrowStyle.textColour

    val clocks = LocalClocks.current
    val placed = remember(markers) { PlacedHandler { node -> markers.node = node } }
    val policy = remember(markers, projection, clocks) { MarkerPolicy(markers, projection, clocks) }

    // Nothing is asked of the runtime while the layer is empty, and the loop ends with the last
    // marker. While it runs it only asks for a redraw on a frame where something actually moved,
    // so a still camera over a paused world redraws nothing.
    //
    // This is also the moment the camera is read, and it is the reason the KDoc asks a game to have
    // written its camera by now: a later frame callback's write is a frame too late for this one.
    LaunchedEffect(markers, projection, markers.count > 0) {
        while (markers.count > 0) {
            val nanos = withFrameNanos { it }
            if (markers.update(projection, markers.viewWidth, markers.viewHeight, nanos)) {
                markers.node?.invalidate()
            }
        }
    }

    Layout(
        modifier = modifier.fillMaxSize().onPlaced(placed),
        name = "markers",
        draw = arrows.draw,
        content = {
            val slots = markers.slots
            for (index in slots.indices) {
                val slot = slots[index]
                key(slot.key) { Marker(slot) }
            }
        },
        measurePolicy = policy,
    )
}

/**
 * One marker's wrapper: everything about it that is drawn rather than placed.
 *
 * Its three numbers are state, so the layer changing one of them recomposes this and nothing else.
 * `content` is the caller's own lambda and is the same object from one frame to the next, so what
 * is inside — the nameplate, the bar, the icon — is skipped rather than built again.
 */
@Composable
private fun Marker(slot: MarkerSlot) {
    val content = slot.declaration.content
    Box(Modifier.alpha(slot.alpha).scale(slot.scale).zIndex(slot.order)) { content() }
}

/**
 * The block, run into a fresh list of declarations only when something it reads changes.
 *
 * The same reason a lazy list does it — see its `rememberSections` — and it matters more here:
 * running the block makes a new content lambda for every marker, and a marker handed a new lambda
 * cannot be skipped, so a layer that re-ran its block on every composition would rebuild every
 * nameplate on the screen whenever anything around it changed.
 */
@Composable
private fun rememberDeclared(content: WorldMarkerScope.() -> Unit): List<MarkerDeclaration> {
    val latest = rememberUpdatedState(content)
    val declared by remember { derivedStateOf { Declarations().apply(latest.value).markers } }
    return declared
}

/** One marker as the game declared it. Made when the block runs, never per frame. */
internal class MarkerDeclaration(
    val key: Any,
    val anchor: WorldAnchor?,
    val x: Float,
    val y: Float,
    val z: Float,
    val offScreen: OffScreen,
    val fadeNear: Float,
    val fadeFar: Float,
    val scaleNear: Float,
    val scaleFar: Float,
    val farScale: Float,
    val priority: Float,
    val alignment: Alignment,
    val content: @Composable () -> Unit,
)

/** The scope itself: it collects declarations and does nothing else. */
private class Declarations : WorldMarkerScope {

    val markers = ArrayList<MarkerDeclaration>()

    override fun marker(
        key: Any,
        x: Float,
        y: Float,
        z: Float,
        offScreen: OffScreen,
        fadeDistance: ClosedFloatingPointRange<Float>?,
        scaleDistance: ClosedFloatingPointRange<Float>?,
        farScale: Float,
        priority: Float,
        anchor: Alignment,
        content: @Composable () -> Unit,
    ) = add(key, null, x, y, z, offScreen, fadeDistance, scaleDistance, farScale, priority, anchor, content)

    override fun marker(
        key: Any,
        position: WorldAnchor,
        offScreen: OffScreen,
        fadeDistance: ClosedFloatingPointRange<Float>?,
        scaleDistance: ClosedFloatingPointRange<Float>?,
        farScale: Float,
        priority: Float,
        anchor: Alignment,
        content: @Composable () -> Unit,
    ) = add(key, position, 0f, 0f, 0f, offScreen, fadeDistance, scaleDistance, farScale, priority, anchor, content)

    private fun add(
        key: Any,
        position: WorldAnchor?,
        x: Float,
        y: Float,
        z: Float,
        offScreen: OffScreen,
        fadeDistance: ClosedFloatingPointRange<Float>?,
        scaleDistance: ClosedFloatingPointRange<Float>?,
        farScale: Float,
        priority: Float,
        anchor: Alignment,
        content: @Composable () -> Unit,
    ) {
        // Checked here, where the bad value is next to whatever produced it, rather than when a
        // marker happens to reach the far end of its range and the scale modifier throws.
        require(farScale >= 0f && !farScale.isNaN()) { "a marker cannot be scaled by $farScale" }
        markers += MarkerDeclaration(
            key = key,
            anchor = position,
            x = x,
            y = y,
            z = z,
            offScreen = offScreen,
            // Not-a-number for "never", so asking costs a comparison rather than a null check on a
            // boxed range, for every marker on every frame.
            fadeNear = fadeDistance?.start ?: Float.NaN,
            fadeFar = fadeDistance?.endInclusive ?: Float.NaN,
            scaleNear = scaleDistance?.start ?: Float.NaN,
            scaleFar = scaleDistance?.endInclusive ?: Float.NaN,
            farScale = farScale,
            priority = priority,
            alignment = anchor,
            content = content,
        )
    }
}

/**
 * One marker's place in the world and on the screen, kept from frame to frame.
 *
 * The split down the middle is the whole design. [alpha], [scale] and [order] are drawn, so they
 * are state, and changing one of them recomposes that marker's wrapper. Everything else is a plain
 * field, because it is read by the measure pass — which runs every frame anyway — and making it
 * state would recompose the screen every time the camera moved a pixel.
 */
internal class MarkerSlot(val key: Any, var declaration: MarkerDeclaration) {

    // Float state rather than plain state for all three: a MutableState<Float> boxes the number on
    // every write, and these are written for every marker on every frame. Three boxes a marker at
    // sixty frames a second is rubbish measured in kilobytes a second for a screen of nameplates.

    /** Zero for a marker that is hidden, decluttered or over the limit, as well as a faded one. */
    var alpha by mutableFloatStateOf(1f)

    var scale by mutableFloatStateOf(1f)

    /** Where it sits in the pile: the nearest marker kept gets the highest. */
    var order by mutableFloatStateOf(0f)

    /** How big it turned out. Written by the measure pass, read when clamping and decluttering. */
    var width = 0f
    var height = 0f

    /** Where its anchor point ended up on the layer, after any clamping. */
    var screenX = 0f
    var screenY = 0f

    /** How far away the projection said it is, signed: negative is behind the camera. */
    var depth = 0f

    /**
     * How far away it is with the sign thrown away, which is what the fade, the size and the pile
     * all read.
     *
     * A thing ten metres behind the player is ten metres away, not minus ten: on the raw depth a
     * waypoint behind them would be nearer than everything they can see, take the room under
     * `maxVisible` from the enemies in front of them, never fade, and draw on top of the lot.
     */
    var distance = 0f

    /**
     * Whether the camera can see it at all this frame: projected, not hidden by its off-screen
     * policy, not faded away. Whether it is *shown* is decided afterwards, from the layer's limit
     * and its decluttering, and the two are kept apart so that a marker dropped by the limit does
     * not look like a marker that has just reappeared on the next frame.
     */
    var inView = false

    /** Whether it is being drawn at all this frame. */
    var shown = false

    /** How solid it is at the depth it is at, before it is dropped for any other reason. */
    var fade = 1f

    /** Where the off-screen arrow goes and which way it points. */
    var hasArrow = false
    var arrowX = 0f
    var arrowY = 0f
    var arrowAngle = 0f
}

/**
 * Every marker on one layer: what was declared, where it is now, and which of them are worth
 * showing.
 *
 * Nothing here allocates per frame. The two scratch points, the two orderings and the boxes used
 * for decluttering all belong to this object and are filled in again each time, because a layer
 * with fifty markers on it is asked all of this sixty times a second.
 */
internal class WorldMarkers {

    /** In the order the game declared them, which is the order the children are composed in. */
    val slots = ArrayList<MarkerSlot>()

    private val byKey = HashMap<Any, MarkerSlot>()

    private var declared: List<MarkerDeclaration>? = null

    /** How many markers there are. State, so an empty layer stops asking for frames. */
    var count by mutableIntStateOf(0)
        private set

    private var maxVisible = Int.MAX_VALUE

    private var declutter = false

    /** The layer's node, for asking it to be drawn again on a frame where something moved. */
    var node: UiNode? = null

    var viewWidth = 0f
        private set

    var viewHeight = 0f
        private set

    private var view = Size(0f, 0f)

    private val world = WorldPoint()
    private val screen = WorldPoint()

    // Not Long.MIN_VALUE: that is the time a Clocks that has never seen a frame reports, and a
    // tree laid out before its first frame would then think this frame was already done.
    private var lastNanos = NeverUpdated
    private var lastWidth = Float.NaN
    private var lastHeight = Float.NaN

    /** Which markers are in the running, and then which of them are kept, in the order they won. */
    private var ranked = IntArray(0)
    private var kept = IntArray(0)
    private var keptCount = 0

    /** The boxes already kept this frame, four numbers each: left, top, right, bottom. */
    private var boxes = FloatArray(0)

    /**
     * Takes the declarations the composition just made, keeping the slot — and so the node, and so
     * whatever state is inside the marker — of every key that is still there.
     *
     * Does nothing at all when handed the same list again, which is every composition where the
     * game did not change what its markers are.
     */
    fun sync(declarations: List<MarkerDeclaration>, maxVisible: Int, declutter: Boolean) {
        this.maxVisible = maxVisible
        this.declutter = declutter
        if (declarations === declared) return

        // Whatever is no longer declared goes, so a fight that ends does not leave fifty slots
        // behind for the map screen to walk over. Built only when the list changed, not per frame.
        val live = HashSet<Any>(declarations.size)
        for (index in declarations.indices) live.add(declarations[index].key)
        // Two markers with one key would be one slot in two places: two nodes on top of each
        // other, both moved to wherever the second one is. Worth saying out loud, because the
        // shape of the bug says nothing about the cause. Checked before anything is committed: a
        // composition that threw must not leave `declared` pointing at the bad list, or the next
        // one handed the same list would take the early return above and keep the broken layer.
        require(live.size == declarations.size) {
            "two markers share a key, so only one of them can be placed: " +
                declarations.map { it.key }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        }

        declared = declarations
        slots.clear()
        for (index in declarations.indices) {
            val declaration = declarations[index]
            val slot = byKey.getOrPut(declaration.key) { MarkerSlot(declaration.key, declaration) }
            slot.declaration = declaration
            slots.add(slot)
        }
        if (byKey.size != live.size) byKey.keys.retainAll(live)
        if (count != slots.size) count = slots.size
        // A list that changed is a frame that has to be worked out again whatever the camera is
        // doing: the new markers have never been projected and have no size yet.
        lastNanos = NeverUpdated
    }

    /** Called by the measure pass, which is the only place the layer's real size is known. */
    fun measured(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
    }

    /**
     * Projects every marker, decides which are shown, and works out where they go.
     *
     * Runs once a frame however many times it is asked. The frame loop calls it before layout, so
     * that it can say whether anything moved and ask for a redraw; the measure pass calls it in
     * case the loop could not — the first frame, or one where the layer changed size. What the
     * contract promises runs once per marker per frame is the projection, so the second call is a
     * comparison and a return.
     *
     * @return whether anything a player would see has moved since the last frame.
     */
    fun update(projection: WorldProjection, width: Float, height: Float, nanos: Long): Boolean {
        if (nanos == lastNanos && width == lastWidth && height == lastHeight) return false
        lastNanos = nanos
        lastWidth = width
        lastHeight = height
        if (width <= 0f || height <= 0f) return false
        if (view.width != width || view.height != height) view = Size(width, height)

        var moved = false
        val total = slots.size
        if (ranked.size < total) {
            ranked = IntArray(total)
            kept = IntArray(total)
            boxes = FloatArray(total * 4)
        }

        var running = 0
        for (index in 0 until total) {
            val slot = slots[index]
            if (place(slot, projection, width, height)) moved = true
            slot.shown = slot.inView
            if (slot.inView) ranked[running++] = index
        }

        keptCount = 0
        chooseWhoIsShown(running)
        stack()

        for (index in 0 until total) {
            val slot = slots[index]
            val wanted = if (slot.shown) slot.fade else 0f
            // A marker that lost its place stops being drawn, which is a change like any other —
            // and one the state write below would only report a frame later.
            if (slot.alpha != wanted) moved = true
            slot.alpha = wanted
            slot.scale = scaleOf(slot)
        }
        return moved
    }

    /**
     * Where one marker is this frame: projected, turned round if it is behind the camera, and held
     * at an edge if that is what it asked for.
     *
     * @return whether it moved, or came or went, since the last frame.
     */
    private fun place(slot: MarkerSlot, projection: WorldProjection, width: Float, height: Float): Boolean {
        val declaration = slot.declaration
        val wasX = slot.screenX
        val wasY = slot.screenY
        val wasInView = slot.inView
        val hadArrow = slot.hasArrow
        val wasAngle = slot.arrowAngle
        slot.hasArrow = false

        val anchor = declaration.anchor
        if (anchor == null) world.set(declaration.x, declaration.y, declaration.z) else anchor.positionInto(world)

        // A camera that says no is saying there is nothing sensible to draw — not even a direction
        // — so nothing is drawn, whatever the off-screen policy is.
        if (!projection.project(world, view, screen)) {
            slot.inView = false
            return wasInView
        }

        slot.depth = screen.z
        slot.distance = abs(screen.z)
        var x = screen.x
        var y = screen.y

        // Behind the camera. A point behind the lens comes out of a projection mirrored through the
        // middle of the view, so it is turned back through the middle — and then pushed out to the
        // border along that direction, because a thing behind the player is not somewhere in the
        // middle of their screen however the arithmetic came out. What is left is a direction, and
        // a direction is all an arrow needs.
        val behind = screen.z < 0f
        if (behind) {
            val middleX = width / 2f
            val middleY = height / 2f
            val dx = (width - x) - middleX
            var dy = (height - y) - middleY
            // Dead behind, with nothing to lean either way: straight down, which is where a player
            // reads "turn round".
            if (dx == 0f && dy == 0f) dy = 1f
            val reachX = if (dx == 0f) Float.POSITIVE_INFINITY else middleX / abs(dx)
            val reachY = if (dy == 0f) Float.POSITIVE_INFINITY else middleY / abs(dy)
            val reach = minOf(reachX, reachY)
            x = middleX + dx * reach
            y = middleY + dy * reach
        }

        val onScreen = !behind && x >= 0f && x <= width && y >= 0f && y <= height
        when (val policy = declaration.offScreen) {
            OffScreen.Hide -> if (!onScreen) {
                slot.inView = false
                return wasInView
            }

            OffScreen.Show -> Unit

            is OffScreen.ClampToEdge -> {
                // Run whether or not the *point* is on screen, because what is held inside is the
                // whole marker: a nameplate a hundred pixels wide starts hanging off the edge long
                // before the thing it names does. The arithmetic below is a no-op while the box
                // still fits, so this costs a pair of comparisons on the markers in the middle of
                // the screen and moves the ones at the border in as they reach it, rather than
                // jumping them half a box sideways the moment their point crosses the edge.
                val alignment = declaration.alignment
                val inX = alignment.xIn(slot.width, 0f)
                val inY = alignment.yIn(slot.height, 0f)
                // The arrow is held inside as well as the marker, because an arrow off the
                // edge is the one thing it cannot be: it is what says which way to look. Its
                // furthest corner is ArrowRoom past the marker's box whichever way it turns,
                // so that much extra is kept back from all four sides.
                val room = policy.inset +
                    if (policy.arrow) ArrowRoom * policy.arrowSize else 0f
                val left = (x - inX).coerceIn(room, (width - room - slot.width).coerceAtLeast(room))
                val top = (y - inY).coerceIn(room, (height - room - slot.height).coerceAtLeast(room))
                val clampedX = left + inX
                val clampedY = top + inY
                val dx = x - clampedX
                val dy = y - clampedY
                // Exactly on the point there is no direction to draw, and a triangle pointing
                // nowhere is worse than none.
                if (policy.arrow && (dx != 0f || dy != 0f)) {
                    val angle = atan2(dy, dx)
                    val towardsX = cos(angle)
                    val towardsY = sin(angle)
                    // Out of the middle of the marker's own rectangle and through whichever
                    // side the direction leaves by, rather than off the anchor point: the
                    // triangle belongs level with the middle of the box and just clear of its
                    // edge, whichever corner of it the world point happens to sit on and
                    // whether the box is a wide nameplate or a tall icon.
                    val halfWidth = slot.width / 2f
                    val halfHeight = slot.height / 2f
                    val toSide = minOf(
                        if (towardsX == 0f) Float.POSITIVE_INFINITY else halfWidth / abs(towardsX),
                        if (towardsY == 0f) Float.POSITIVE_INFINITY else halfHeight / abs(towardsY),
                    )
                    val reach = toSide + ArrowGap * policy.arrowSize
                    slot.hasArrow = true
                    slot.arrowAngle = angle
                    slot.arrowX = left + halfWidth + towardsX * reach
                    slot.arrowY = top + halfHeight + towardsY * reach
                }
                x = clampedX
                y = clampedY
            }
        }

        slot.screenX = x
        slot.screenY = y
        slot.fade = fadeOf(slot)
        // A marker that has faded away entirely is not in view, so that it gives its place under
        // the layer's limit to one that would actually be seen.
        slot.inView = slot.fade > 0f
        return slot.inView != wasInView || wasX != x || wasY != y ||
            hadArrow != slot.hasArrow || (slot.hasArrow && wasAngle != slot.arrowAngle)
    }

    /**
     * Which of the markers in the running are actually shown: the best `maxVisible` of them, and,
     * when the layer declutters, only those that do not sit on top of one already kept.
     *
     * Best is the highest priority, and among equal priorities the nearest — because when a screen
     * has more markers than it has room for, the one a player is walking towards is the one they
     * came for.
     */
    private fun chooseWhoIsShown(running: Int) {
        // Insertion sort over an array of indices: there are tens of markers, not thousands, and
        // this is the sort that makes nothing while it runs. An almost-sorted frame — which is
        // every frame after the first — costs a pass over the array.
        for (at in 1 until running) {
            val index = ranked[at]
            var behind = at - 1
            while (behind >= 0 && beats(index, ranked[behind])) {
                ranked[behind + 1] = ranked[behind]
                behind--
            }
            ranked[behind + 1] = index
        }

        for (at in 0 until running) {
            val slot = slots[ranked[at]]
            if (keptCount >= maxVisible || (declutter && overlapsSomethingKept(slot))) {
                slot.shown = false
                continue
            }
            if (declutter) {
                val corner = keptCount * 4
                boxes[corner] = slot.screenX - slot.declaration.alignment.xIn(slot.width, 0f)
                boxes[corner + 1] = slot.screenY - slot.declaration.alignment.yIn(slot.height, 0f)
                boxes[corner + 2] = boxes[corner] + slot.width
                boxes[corner + 3] = boxes[corner + 1] + slot.height
            }
            kept[keptCount++] = ranked[at]
        }
    }

    private fun beats(index: Int, other: Int): Boolean {
        val one = slots[index]
        val two = slots[other]
        val mine = one.declaration.priority
        val theirs = two.declaration.priority
        return if (mine != theirs) mine > theirs else nearer(one, two)
    }

    /**
     * Whether one marker is nearer than another: on how far away it is rather than on the signed
     * depth, so a waypoint behind the player is as far away as one the same distance in front of
     * them and does not walk to the front of the queue. Two the same distance apart, one in front
     * and one behind, go to the one in front — that is the one a player can actually see.
     */
    private fun nearer(one: MarkerSlot, two: MarkerSlot): Boolean =
        if (one.distance != two.distance) one.distance < two.distance else one.depth > two.depth

    private fun overlapsSomethingKept(slot: MarkerSlot): Boolean {
        val alignment = slot.declaration.alignment
        val left = slot.screenX - alignment.xIn(slot.width, 0f)
        val top = slot.screenY - alignment.yIn(slot.height, 0f)
        val right = left + slot.width
        val bottom = top + slot.height
        for (at in 0 until keptCount) {
            val corner = at * 4
            if (left < boxes[corner + 2] && right > boxes[corner] &&
                top < boxes[corner + 3] && bottom > boxes[corner + 1]
            ) {
                return true
            }
        }
        return false
    }

    /**
     * The pile: the nearest marker kept is drawn last, so it is on top.
     *
     * Its rank rather than its depth, because the rank is the number that changes rarely. Two
     * nameplates whose distance moves every frame keep the same two ranks until they actually
     * cross, and only then does anything recompose.
     */
    private fun stack() {
        for (at in 1 until keptCount) {
            val index = kept[at]
            var behind = at - 1
            while (behind >= 0 && nearer(slots[kept[behind]], slots[index])) {
                kept[behind + 1] = kept[behind]
                behind--
            }
            kept[behind + 1] = index
        }
        for (at in 0 until keptCount) slots[kept[at]].order = at.toFloat()
    }

    /** How solid a marker is at the distance it is at. */
    private fun fadeOf(slot: MarkerSlot): Float {
        val near = slot.declaration.fadeNear
        if (near.isNaN()) return 1f
        val far = slot.declaration.fadeFar
        val distance = slot.distance
        if (distance <= near) return 1f
        if (distance >= far) return 0f
        return 1f - (distance - near) / (far - near)
    }

    /** How big it is drawn at the distance it is at. */
    private fun scaleOf(slot: MarkerSlot): Float {
        val near = slot.declaration.scaleNear
        if (near.isNaN()) return 1f
        val far = slot.declaration.scaleFar
        val end = slot.declaration.farScale
        val distance = slot.distance
        if (distance <= near) return 1f
        if (distance >= far) return end
        return 1f + (end - 1f) * ((distance - near) / (far - near))
    }

    private companion object {
        /** A frame time no clock will ever report, so the first update is never mistaken for done. */
        const val NeverUpdated = Long.MIN_VALUE + 1
    }
}

/**
 * The children at the points their markers projected to, and the layer as big as it is allowed: a
 * view onto the world, not something sized by what is in it.
 */
private class MarkerPolicy(
    private val markers: WorldMarkers,
    private val projection: WorldProjection,
    private val clocks: Clocks,
) : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val width = if (constraints.maxWidth.isFinite()) constraints.maxWidth else constraints.minWidth
        val height = if (constraints.maxHeight.isFinite()) constraints.maxHeight else constraints.minHeight
        markers.measured(width, height)

        val count = measurables.size
        val placeables = placeables(count)
        val slots = markers.slots
        for (index in 0 until count) {
            // At whatever size it likes: a nameplate is not stretched across the screen because the
            // layer it is on is the size of the screen.
            val placeable = measurables[index].measure(Unbounded)
            placeables[index] = placeable
            if (index < slots.size) {
                slots[index].width = placeable.width
                slots[index].height = placeable.height
            }
        }

        // Nearly always a comparison and a return: the frame loop has already done this frame's
        // work. This is what catches the first frame, and a layer that has just changed size.
        markers.update(projection, width, height, clocks.frameNanos)

        val placements = placements(count)
        for (index in 0 until count) {
            val placeable = placeables[index] ?: continue
            val slot = if (index < slots.size) slots[index] else null
            val alignment = slot?.declaration?.alignment ?: Alignment.Centre
            placements[index * 2] = (slot?.screenX ?: 0f) - alignment.xIn(placeable.width, 0f)
            placements[index * 2 + 1] = (slot?.screenY ?: 0f) - alignment.yIn(placeable.height, 0f)
        }
        return layout(width, height, count)
    }

    // A view wants no size of its own, and a question must not move anything.
    override fun MeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) = 0f
    override fun MeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) = 0f
    override fun MeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) = 0f
    override fun MeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) = 0f

    private companion object {
        val Unbounded = Constraints()
    }
}

/**
 * The triangles beside the markers being held at an edge.
 *
 * Drawn by the layer rather than composed, because an arrow is one shape per off-screen marker and
 * a node each would be a node made and thrown away every time a waypoint crossed an edge.
 */
private class ArrowPainter(private val markers: WorldMarkers) {

    /** A skin that changed its mind is a frame that has to be drawn again. */
    var colour: Colour = Colour.White
        set(value) {
            if (field == value) return
            field = value
            markers.node?.invalidate()
        }

    /** Filled in again for each triangle, so a screen full of arrows allocates nothing. */
    private val points = FloatArray(6)

    val draw: UiCanvas.(Rect) -> Unit = { bounds ->
        val slots = markers.slots
        for (index in slots.indices) {
            val slot = slots[index]
            if (!slot.shown || !slot.hasArrow || slot.alpha <= 0f) continue
            val policy = slot.declaration.offScreen as? OffScreen.ClampToEdge ?: continue
            val angle = slot.arrowAngle
            val size = policy.arrowSize
            val towardsX = cos(angle)
            val towardsY = sin(angle)
            val x = bounds.left + slot.arrowX
            val y = bounds.top + slot.arrowY
            // The tip in front and the back edge behind, adding up to arrowSize, which is what
            // arrowSize is documented to be — and what the clamp above reserved room for.
            val backX = x - towardsX * (size * ArrowBack)
            val backY = y - towardsY * (size * ArrowBack)
            val outX = -towardsY * (size * ArrowHalfWidth)
            val outY = towardsX * (size * ArrowHalfWidth)
            points[0] = x + towardsX * (size * ArrowNose)
            points[1] = y + towardsY * (size * ArrowNose)
            points[2] = backX + outX
            points[3] = backY + outY
            points[4] = backX - outX
            points[5] = backY - outY
            // The arrow fades with its marker: one that has faded out must not leave a triangle
            // hanging on the edge of the screen.
            fan(points, colour.scaleAlpha(slot.alpha))
        }
    }
}

// The triangle, as fractions of a policy's `arrowSize`, all measured from the point the layer
// works out as the arrow's middle.
//
// [ArrowNose] and [ArrowBack] add up to one, so `arrowSize` really is the length its documentation
// claims: tip to back edge. [ArrowGap] is how far past the marker's own rectangle that middle sits,
// which keeps the triangle beside the marker rather than on top of it.
//
// [ArrowRoom] follows from the other three and is the only one used outside the painter: the
// middle is at most ArrowGap past the box and every corner is at most ArrowNose from the middle
// (the back corners are nearer), so ArrowRoom is the room the clamp must leave at the edge for the
// whole triangle to land on screen whichever way it turns.
private const val ArrowNose = 0.6f
private const val ArrowBack = 0.4f
private const val ArrowHalfWidth = 0.35f
private const val ArrowGap = 0.6f
private const val ArrowRoom = ArrowGap + ArrowNose
