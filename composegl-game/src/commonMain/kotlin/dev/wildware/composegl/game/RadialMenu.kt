package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Animatable
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.FloatVectoriser
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.backend.Haptic
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadHandler
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.LocalUiSounds
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.SizeChangedHandler
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.onShortcutGamepad
import dev.wildware.composegl.ui.modifier.onShortcutKey
import dev.wildware.composegl.ui.modifier.onSizeChanged
import dev.wildware.composegl.ui.skin.ResolvedStyle
import dev.wildware.composegl.ui.skin.flatColour
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.widget.LocalHaptics
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * When the slice a player is pointing at becomes the thing they chose.
 *
 * [Release] is the weapon wheel every shooter has: hold a bumper, push the stick, let go. The
 * choice is made when the wheel closes, so the game only has to say whether the button is held.
 * [Press] is the emote wheel: the wheel stays up, and South, Enter or a click takes the slice under
 * the pointer.
 */
enum class RadialConfirm { Release, Press }

/** Which stick aims a wheel. The left one unless a game is steering with it. */
enum class RadialStick { Left, Right }

/**
 * The pie menu: a ring of slices chosen by pointing at one, rather than by reaching it.
 *
 * A weapon wheel, an emote wheel, a ping wheel. The thing that makes it a radial menu and not a
 * ring of buttons is that **nothing has to be reached**: the stick's angle picks a slice however
 * far it is pushed past the dead zone, and the mouse picks the slice its direction from the middle
 * points at, however far away the pointer is. Flicking the stick towards a slice is the fastest
 * menu input there is, and it is the only one that works while a player is also driving.
 *
 * It is drawn where it is put, so a game gives it the whole screen and it sits in the middle of it:
 *
 * ```kotlin
 * RadialMenu(
 *     open = holdingWheelButton,
 *     items = weapons,
 *     selected = current,
 *     onSelect = { equip(it) },
 *     centre = { Text(it?.name ?: "", style = "wheel.label") },
 * ) { weapon, highlighted -> Image(weapon.icon) }
 * ```
 *
 * [open] is the game's, never the wheel's: the wheel never closes itself, it says what happened and
 * the game decides. That is what makes hold-to-open work — the button being down *is* the state.
 *
 * Everything it looks like is the skin's: `"<style>.backdrop"` is the dimming over the world,
 * `"<style>.slice"`, `"<style>.slice.selected"` and `"<style>.slice.highlighted"` are the ring,
 * `"<style>.ring"` and `"<style>.ring.highlighted"` are a category's nested ring, and
 * `"<style>.hub"` is the disc in the middle that [centre] is drawn on.
 *
 * @param open whether the wheel is up. Usually "the wheel button is held".
 * @param items one slice each, laid out evenly clockwise from [startAngleTurns] — anticlockwise in
 *   a right-to-left language, so the first slice stays where a player's eye starts.
 * @param selected what the player has now, drawn as the current one. Not the same as the slice
 *   being pointed at, which is [centre]'s argument and [content]'s `highlighted` flag.
 * @param onSelect called with the slice that was pointed at when the choice was confirmed.
 * @param onCancel called instead when the wheel was confirmed with nothing pointed at — the stick
 *   inside its dead zone, the pointer still on the hub — or dismissed with East or Escape, or taken
 *   off the screen while it was still open.
 * @param onOpenChange told when the wheel comes up and goes away. Where a game slows or stops its
 *   world clock: `clocks.setRunning(Clock.World, !open)` for a hard pause, or its own time scale
 *   for the slow-motion every weapon wheel uses. A wheel taken off the screen while it is still
 *   open says it went away as it goes, so a stopped world clock always gets started again — and it
 *   cancels rather than choosing, because a screen swapped underneath a player is not them letting
 *   go of the button. Only [open] going false takes the slice on a [RadialConfirm.Release] wheel.
 * @param children a category's contents, for a wheel of wheels. Pointing at a slice that has
 *   children draws them in a second ring outside the first, and pushing the stick to the edge —
 *   or the pointer past the ring — moves the choice out into it. Called as the wheel draws, so
 *   keep it cheap; the default is a flat wheel with no nested rings.
 * @param confirm whether letting go or pressing makes the choice. See [RadialConfirm].
 * @param radius how far out the ring of slices reaches.
 * @param hubRadius the disc in the middle. It is also the mouse's dead zone: a pointer on the hub
 *   is pointing at nothing.
 * @param ringWidth how thick a category's nested ring is.
 * @param startAngleTurns where the first slice's middle sits, in turns clockwise from straight up.
 *   Zero puts it at twelve o'clock, which is where a player looks first.
 * @param gap the hairline between one slice and the next, in pixels at the ring's middle.
 * @param deadZone how far the stick must travel before it is pointing at anything. A stick at rest
 *   is never quite at zero, and a wheel that picks a slice from a resting thumb is a wheel that
 *   equips the wrong gun.
 * @param stick which stick aims it.
 * @param clock the clock the highlight animation runs on. The interface's, so a wheel that pauses
 *   the world still animates.
 * @param centre what to draw on the hub: the name of the slice being pointed at, and what it does.
 *   Its argument is null while nothing is pointed at.
 * @param content one slice's contents — an icon, a letter, a whole widget — and whether it is the
 *   one being pointed at.
 */
@Composable
fun <T> RadialMenu(
    open: Boolean,
    items: List<T>,
    modifier: Modifier = Modifier.fillMaxSize(),
    selected: T? = null,
    onSelect: (T) -> Unit = {},
    onCancel: () -> Unit = {},
    onOpenChange: (Boolean) -> Unit = {},
    children: (T) -> List<T> = { emptyList() },
    confirm: RadialConfirm = RadialConfirm.Release,
    style: String = "wheel",
    radius: Float = 150f,
    hubRadius: Float = 56f,
    ringWidth: Float = 54f,
    startAngleTurns: Float = 0f,
    gap: Float = 4f,
    deadZone: Float = 0.35f,
    stick: RadialStick = RadialStick.Left,
    clock: Clock = Clock.Ui,
    centre: @Composable (T?) -> Unit = {},
    content: @Composable (T, Boolean) -> Unit,
) {
    val aim = remember { RadialAim() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sounds = LocalUiSounds.current
    val haptics = LocalHaptics.current
    val clocks = LocalClocks.current

    // Worked out from where the stick or the pointer is, and never from [open]: the choice is made
    // on the frame the wheel closes, and a wheel that forgot what it was pointing at the moment it
    // was told to close would confirm nothing every time.
    val aimedIndex = if (items.isEmpty() || !aim.pointing) {
        -1
    } else {
        sliceAt(aim.angleTurns, items.size, startAngleTurns, rtl)
    }

    // Out past the slices, which is where a category's children are chosen instead of the category.
    val reaching = aim.pointing && aim.reach >= 1f

    // The ring belongs to the slice that opened it until the player comes back off it.
    //
    // A category's ring is widened so that two or three children are still big enough to flick at,
    // and a widened ring spills past its own slice on both sides — so out at the ring the aim angle
    // on its own no longer says which category is being chosen inside. Without this latch, aiming
    // at a child drawn past the parent's edge picks the neighbouring slice instead, its children
    // (usually none) replace the ring, and the ring the player was aiming into disappears.
    //
    // It lets go as soon as the aim leaves the ring itself, so a stick held right out can still be
    // swept round from one category's ring into the next.
    val heldRing = reaching &&
        aim.ringSlice in items.indices &&
        withinTurns(aim.angleTurns, aim.ringCentre, aim.ringSpan)
    val sliceIndex = if (heldRing) aim.ringSlice else aimedIndex
    val category = items.getOrNull(sliceIndex)
    val kids = if (category == null) emptyList() else children(category)
    val parentCentre = if (category == null) 0f else sliceCentre(sliceIndex, items.size, startAngleTurns, rtl)
    val span = childSpan(items.size, kids.size)
    val inRing = kids.isNotEmpty() && reaching
    val childIndex = if (!inRing) -1 else childAt(aim.angleTurns, parentCentre, span, kids.size, rtl)
    val highlighted = if (inRing) kids.getOrNull(childIndex) else category

    // Remembered rather than derived, because that is what a latch is: next pass has the angle but
    // no longer has the slice the player came out through.
    aim.ringSlice = if (inRing) sliceIndex else -1
    aim.ringCentre = parentCentre
    aim.ringSpan = span

    val confirmNow = {
        val picked = highlighted
        if (picked == null) {
            onCancel()
        } else {
            sounds.change()
            haptics.perform(Haptic.LightTap)
            onSelect(picked)
        }
    }

    // The handlers are the aim object's own, so they are the same objects every recomposition and
    // a wheel the player is turning does not rebuild its modifiers a frame. What changes each pass
    // is only what they read.
    aim.confirmMode = confirm
    aim.stick = stick
    aim.deadZone = deadZone
    aim.radius = radius
    aim.hubRadius = hubRadius
    aim.onDismiss = onCancel

    // A press asks rather than takes, so this runs on the composition that already knows where the
    // press was aimed. See [RadialAim.confirmRequested].
    LaunchedEffect(aim.confirmRequested) {
        if (aim.confirmRequested > 0) confirmNow()
    }

    // Keyed on the slice rather than on the item, so a wheel holding two of the same thing still
    // ticks when the player moves between them.
    val aimKey = if (highlighted == null) null else sliceIndex to childIndex
    val pop = remember(clocks, clock) { Animatable(0f, FloatVectoriser, clock, clocks) }

    LaunchedEffect(aimKey) {
        if (aimKey == aim.lastHighlight) return@LaunchedEffect
        aim.lastHighlight = aimKey
        if (aimKey == null) return@LaunchedEffect
        // The same pair a pad's focus move makes: the player asked for the next thing along.
        sounds.focusMove()
        haptics.perform(Haptic.Tick)
        pop.snapTo(0f)
        pop.animateTo(1f, Tween(HighlightMillis, easing = Easings.EaseOut))
    }

    // Every way the wheel ends is the same three steps: say what became of the choice, say the
    // wheel went away, and forget where the stick was pointing.
    fun finish(choice: () -> Unit) {
        if (!aim.wasOpen) return
        aim.wasOpen = false
        choice()
        onOpenChange(false)
        aim.reset()
    }

    // The player let the button go, which on a [RadialConfirm.Release] wheel *is* the choice.
    val close = { finish { if (confirm == RadialConfirm.Release) confirmNow() } }

    // Taken off the screen with the wheel still up — a screen swapped, the widget switched off.
    // Nobody chose anything: the wheel was removed, not released. So even a release wheel takes
    // nothing and cancels instead. Equipping whatever the stick happened to be pointing at because
    // a screen changed is a choice the player never made and cannot undo.
    val leave = { finish { onCancel() } }

    LaunchedEffect(open) {
        if (open == aim.wasOpen) return@LaunchedEffect
        if (open) {
            aim.wasOpen = true
            // A wheel opens pointing at nothing, whatever the stick was doing a moment ago.
            aim.reset()
            onOpenChange(true)
        } else {
            close()
        }
    }

    // The other way a wheel goes away: the whole thing leaves the composition — a screen swapped, a
    // widget switched off — while it is still open. Without this, a game that stopped its world
    // clock for the wheel is never told to start it again, and it is stopped forever. Held on the
    // aim object so that the last pass's answers are the ones disposal uses, rather than the first
    // pass's, which is what a lambda captured by [DisposableEffect] would give.
    aim.onLeave = leave
    DisposableEffect(aim) { onDispose { aim.onLeave() } }

    if (!open || items.isEmpty()) return

    val backdrop = rememberStyle("$style.backdrop").fill()
    val slice = rememberStyle("$style.slice").fill()
    val sliceSelected = rememberStyle("$style.slice.selected").fill()
    val sliceHighlighted = rememberStyle("$style.slice.highlighted").fill()
    val ring = rememberStyle("$style.ring").fill()
    val ringHighlighted = rememberStyle("$style.ring.highlighted").fill()
    val hub = rememberStyle("$style.hub").fill()

    // Remembered rather than made per pass: the wheel recomposes on every frame a player turns it,
    // and the array is only ever scratch space for the fan being drawn right now.
    val scratch = remember { FloatArray(ScratchFloats) }

    val painter = RadialPainter(
        scratch = scratch,
        count = items.size,
        highlighted = if (inRing) -1 else sliceIndex,
        selected = if (selected == null) -1 else items.indexOfFirst { it == selected },
        childCount = kids.size,
        childHighlighted = childIndex,
        parentCentre = parentCentre,
        childSpan = span,
        startTurns = startAngleTurns,
        rtl = rtl,
        radius = radius,
        hubRadius = hubRadius,
        ringWidth = ringWidth,
        gap = gap,
        grow = pop.value * HighlightGrow,
        backdrop = backdrop,
        slice = slice,
        sliceSelected = sliceSelected,
        sliceHighlighted = sliceHighlighted,
        ring = ring,
        ringHighlighted = ringHighlighted,
        hub = hub,
    )

    Box(
        modifier = modifier
            .onSizeChanged(aim.sized)
            .onPointer(aim.pointer)
            .onShortcutGamepad(aim.pad)
            .onShortcutKey(aim.keys)
            .drawBehind(painter::draw),
    ) {
        items.forEachIndexed { index, item ->
            val turn = sliceCentre(index, items.size, startAngleTurns, rtl)
            AtAngle(turn, (hubRadius + radius) / 2f) { content(item, !inRing && index == sliceIndex) }
        }

        // A category's ring is drawn only while that category is the one being pointed at, which is
        // what keeps a wheel of wheels readable: one ring at a time, never all of them at once.
        kids.forEachIndexed { index, kid ->
            val turn = childCentre(index, kids.size, parentCentre, span, rtl)
            AtAngle(turn, radius + RingGap + ringWidth / 2f) { content(kid, index == childIndex) }
        }

        Box(Modifier.align(Alignment.Centre), contentAlignment = Alignment.Centre) { centre(highlighted) }
    }
}

/** One slice's contents, centred on the point [turns] round the wheel and [distance] out. */
@Composable
private fun AtAngle(turns: Float, distance: Float, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.Centre)
            .offset(sinTurns(turns) * distance, -cosTurns(turns) * distance),
        contentAlignment = Alignment.Centre,
    ) {
        content()
    }
}

/** How long the highlight takes to swell onto a new slice. Short: it is a flick, not a transition. */
private const val HighlightMillis = 120

/** How far the slice being pointed at reaches past the others. */
private const val HighlightGrow = 10f

/** The breathing space between the ring of slices and a category's ring outside it. */
private const val RingGap = 6f

/**
 * How far a stick must be pushed to reach a category's ring, as a fraction of its travel.
 *
 * A stick has no distance, only a push, so "past the ring" has to be a number. Nearly all the way
 * rather than all the way: a stick pushed into its corner does not read 1 on both axes, and a ring
 * a player cannot reach diagonally is a ring with a hole in it.
 */
private const val NestedStickReach = 0.9f

/** How wide a category's slice is at the very least, so a nested ring stays big enough to flick at. */
private const val MinChildTurns = 1f / 8f

/**
 * How wide a category's ring can get, leaving an eighth of a turn of sky outside it.
 *
 * A ring that went the whole way round would be one a stick pushed right out could never leave,
 * because there would be no angle left that is outside it. See [childSpan].
 */
private const val MaxChildTurns = 7f / 8f

/** How much of a turn one fan covers. Small enough that the straight inner edge does not show. */
private const val DegreesPerFan = 20f

/** How many straight edges the outer side of one fan is drawn with. */
private const val StepsPerFan = 3

/** How many floats one fan's points take: an inner corner, the outer edge, the other inner corner. */
private const val ScratchFloats = (StepsPerFan + 3) * 2

/**
 * The colour a style fills with.
 *
 * A slice is a shape rather than a box, so it takes a colour and cannot take a nine-patch — the
 * same rule, and the same reason, as a cooldown's wedge.
 */
private fun ResolvedStyle.fill(): Colour = background.flatColour ?: Colour.Transparent

// --- where the slices are ------------------------------------------------------------------------

/**
 * The middle of slice [index], in turns clockwise from straight up.
 *
 * Right-to-left runs the other way round. Mirroring a wheel is not a nicety: in Arabic the eye
 * starts at the right, and a wheel laid out clockwise puts the second item where a player who
 * reads right to left expects the last one.
 */
private fun sliceCentre(index: Int, count: Int, startTurns: Float, rtl: Boolean): Float =
    if (rtl) startTurns - index.toFloat() / count else startTurns + index.toFloat() / count

/** Which slice the angle [turns] falls in. */
private fun sliceAt(turns: Float, count: Int, startTurns: Float, rtl: Boolean): Int {
    val relative = if (rtl) startTurns - turns else turns - startTurns
    // Half a slice, because a slice is centred on its angle rather than starting at it.
    val shifted = relative + 0.5f / count
    val wrapped = shifted - floor(shifted)
    return (wrapped * count).toInt().coerceIn(0, count - 1)
}

/**
 * How much of a turn a category's nested ring spans.
 *
 * Its own slice at the very least, but widened for a category with few children: four children
 * squeezed into a forty-five degree slice are eleven degrees each, which no stick can hit.
 *
 * Never the whole turn, however many children there are. A ring all the way round has no outside,
 * so a stick held right out could never leave it: the latch that keeps a widened ring with its own
 * category would hold forever and the wheel would have one category and no way back.
 */
private fun childSpan(count: Int, childCount: Int): Float {
    if (count <= 0 || childCount <= 0) return 0f
    return minOf(MaxChildTurns, maxOf(1f / count, childCount * MinChildTurns))
}

/** The middle of child [index] of the category centred on [sliceCentre]. */
private fun childCentre(
    index: Int,
    childCount: Int,
    sliceCentre: Float,
    span: Float,
    rtl: Boolean,
): Float {
    val along = (index + 0.5f) / childCount * span
    return if (rtl) sliceCentre + span / 2f - along else sliceCentre - span / 2f + along
}

/**
 * Which child the angle [turns] falls in — the one drawn at that angle by [childCentre].
 *
 * An angle outside the ring altogether goes to the child at the end it is nearest, rather than
 * round the long way to the child at the other end, which is what plain wrapping would do to a
 * player who overshot the widened ring by a degree.
 */
private fun childAt(
    turns: Float,
    sliceCentre: Float,
    span: Float,
    childCount: Int,
    rtl: Boolean,
): Int {
    if (childCount <= 0 || span <= 0f) return -1
    val from = if (rtl) sliceCentre + span / 2f - turns else turns - (sliceCentre - span / 2f)
    // Into the half turn either side of the ring's *middle*, so an overshoot stays an overshoot.
    // Wrapping around the first edge instead would send everything past the half turn negative, and
    // a ring wider than half a turn — five children, or a wheel of one slice — would hand its whole
    // far half back to the first child.
    val wrapped = from - floor(from - span / 2f + 0.5f)
    return (wrapped / span * childCount).toInt().coerceIn(0, childCount - 1)
}

/** Whether the angle [turns] is inside the arc of width [span] centred on [centre]. */
private fun withinTurns(turns: Float, centre: Float, span: Float): Boolean {
    if (span <= 0f) return false
    val from = turns - centre
    val wrapped = from - floor(from + 0.5f)
    return wrapped >= -span / 2f && wrapped < span / 2f
}

/** The turn a direction points in, clockwise from straight up, in 0 until 1. */
private fun turnOf(x: Float, y: Float): Float {
    val turn = atan2(x, -y) / (2f * PI.toFloat())
    return if (turn < 0f) turn + 1f else turn
}

private fun sinTurns(turns: Float) = sin(turns * 2f * PI.toFloat())

private fun cosTurns(turns: Float) = cos(turns * 2f * PI.toFloat())

// --- what the player is pointing at --------------------------------------------------------------

/**
 * Where the stick or the pointer is aimed, and the handlers that keep it up to date.
 *
 * Held apart from the composable for two reasons. Its handlers are stable objects, so a wheel the
 * player is turning recomposes without rebuilding its modifiers; and the stick and the mouse
 * disagree about what "far out" means — a stick has a push and a mouse has a distance — so each is
 * turned into the same two numbers where the device is still known, rather than later where it is
 * not.
 */
private class RadialAim {

    // --- what the composition tells it, once a pass ---
    var confirmMode: RadialConfirm = RadialConfirm.Release
    var stick: RadialStick = RadialStick.Left
    var deadZone: Float = 0.35f
    var radius: Float = 150f
    var hubRadius: Float = 56f
    var onDismiss: () -> Unit = {}
    var onLeave: () -> Unit = {}

    // --- what it remembers between passes ---

    /**
     * The slice whose nested ring the player is out at, or -1.
     *
     * Plain fields rather than state: the composition works them out from the aim it already read,
     * and writes them for the next pass to read. See the latch where they are used.
     */
    var ringSlice = -1

    /** Where that ring sits, so the next pass can tell whether the aim is still on it. */
    var ringCentre = 0f
    var ringSpan = 0f

    // --- what it works out ---

    /** Whether the player is pointing at anything at all: past the dead zone, off the hub. */
    var pointing by mutableStateOf(false)
        private set

    /** Which way, in turns clockwise from straight up. */
    var angleTurns by mutableFloatStateOf(0f)
        private set

    /** How far out, where 1 is the edge of the ring of slices and further is a category's ring. */
    var reach by mutableFloatStateOf(0f)
        private set

    /**
     * How many times the player has asked to take the slice they are pointing at.
     *
     * A counter, and not the call itself, because a click both aims and takes: the slice under a
     * pointer that has not moved before is only known once the wheel has composed with the new
     * direction, so the taking waits a frame rather than confirming the last frame's answer.
     */
    var confirmRequested by mutableStateOf(0)
        private set

    private fun askToConfirm() {
        confirmRequested++
    }

    /** Whether the wheel was open last time the open effect ran. Not state: only the effect reads it. */
    var wasOpen = false

    /** The slice that last ticked, so the same slice twice does not tick twice. */
    var lastHighlight: Any? = null

    private var size = Size.Zero
    private var stickX = 0f
    private var stickY = 0f

    fun reset() {
        pointing = false
        angleTurns = 0f
        reach = 0f
        stickX = 0f
        stickY = 0f
        confirmRequested = 0
        ringSlice = -1
        ringCentre = 0f
        ringSpan = 0f
    }

    val sized = SizeChangedHandler { size = it }

    /**
     * The mouse, which aims by direction rather than by what it is over.
     *
     * Everything is taken while the wheel is up, because a wheel is modal: the world below it must
     * not be shot at through it, and the button that closes a wheel is the game's own.
     */
    val pointer = PointerHandler { event ->
        when (event) {
            is PointerEvent.Move -> aimAt(event.position)
            is PointerEvent.Press -> {
                // Aimed first and taken after, because a click that arrives with no move before it
                // is both: where the player is pointing, and the word that they meant it.
                aimAt(event.position)
                if (confirmMode == RadialConfirm.Press) askToConfirm()
            }
            is PointerEvent.Cancel -> pointing = false
            else -> Unit
        }
        true
    }

    private fun aimAt(position: Offset) {
        val dx = position.x - size.width / 2f
        val dy = position.y - size.height / 2f
        val distance = sqrt(dx * dx + dy * dy)
        // The hub is the mouse's dead zone: a pointer sitting in the middle has chosen nothing.
        if (distance < hubRadius) {
            pointing = false
            return
        }
        angleTurns = turnOf(dx, dy)
        reach = distance / radius
        pointing = true
    }

    /**
     * The pad, offered every button and stick wherever focus happens to be.
     *
     * Everything a wheel could mean is taken rather than passed on, the same way the pointer takes
     * everything, because a wheel is modal. The stick would otherwise walk focus along the hotbar
     * behind it, and South would press whatever button is focused back there — a widget the player
     * cannot even see under the wheel. A wheel that confirms on a release has nothing to do with
     * South and East, so it swallows them and does nothing: the choice belongs to the button
     * holding it open.
     */
    val pad = GamepadHandler { event ->
        when (event) {
            is GamepadEvent.Axis -> axis(event)
            is GamepadEvent.ButtonDown -> down(event.button)
            is GamepadEvent.ButtonUp -> takesButton(event.button)
            // Somebody pulled the cable. Let go of the stick rather than leaving it pushed forever.
            is GamepadEvent.Disconnected -> {
                reset()
                false
            }
            is GamepadEvent.Connected -> false
        }
    }

    private fun axis(event: GamepadEvent.Axis): Boolean {
        val left = stick == RadialStick.Left
        val horizontal = if (left) GamepadAxis.LeftX else GamepadAxis.RightX
        val vertical = if (left) GamepadAxis.LeftY else GamepadAxis.RightY
        when (event.axis) {
            horizontal -> stickX = event.value
            vertical -> stickY = event.value
            else -> return false
        }
        val magnitude = sqrt(stickX * stickX + stickY * stickY)
        if (magnitude < deadZone) {
            pointing = false
            return true
        }
        angleTurns = turnOf(stickX, stickY)
        reach = magnitude / NestedStickReach
        pointing = true
        return true
    }

    private fun down(button: GamepadButton): Boolean {
        if (!takesButton(button)) return false
        // A release wheel swallows them and answers neither: it cannot close itself, so a dismiss
        // would leave the wheel up with the choice already cancelled.
        if (confirmMode == RadialConfirm.Press) {
            if (button == GamepadButton.South) askToConfirm() else onDismiss()
        }
        return true
    }

    /** The two buttons a wheel is modal over, whichever way it confirms. */
    private fun takesButton(button: GamepadButton): Boolean =
        button == GamepadButton.South || button == GamepadButton.East

    /** Enter and Space confirm, Escape dismisses — for a wheel a keyboard player left up. */
    val keys = KeyHandler { event ->
        if (confirmMode != RadialConfirm.Press || event.type != KeyEventType.Down) {
            false
        } else {
            when (event.key) {
                Key.Enter, Key.Space -> {
                    askToConfirm()
                    true
                }
                Key.Escape -> {
                    onDismiss()
                    true
                }
                else -> false
            }
        }
    }
}

// --- drawing it ----------------------------------------------------------------------------------

/**
 * The backdrop, the slices, a category's ring and the hub.
 *
 * A slice is an annulus sector, which no canvas primitive draws and which is not convex — so it
 * comes out as a handful of convex fans side by side, each with a straight inner edge short enough
 * that nobody can see it is straight. [scratch] is the points of the fan being drawn right now,
 * refilled for each one rather than made for each one, and it belongs to the composition rather
 * than to the painter so that a wheel a player is turning is not making a new one every frame.
 */
private class RadialPainter(
    private val scratch: FloatArray,
    private val count: Int,
    private val highlighted: Int,
    private val selected: Int,
    private val childCount: Int,
    private val childHighlighted: Int,
    private val parentCentre: Float,
    private val childSpan: Float,
    private val startTurns: Float,
    private val rtl: Boolean,
    private val radius: Float,
    private val hubRadius: Float,
    private val ringWidth: Float,
    private val gap: Float,
    private val grow: Float,
    private val backdrop: Colour,
    private val slice: Colour,
    private val sliceSelected: Colour,
    private val sliceHighlighted: Colour,
    private val ring: Colour,
    private val ringHighlighted: Colour,
    private val hub: Colour,
) {

    fun draw(canvas: UiCanvas, bounds: Rect) {
        if (backdrop.alpha > 0) canvas.rect(bounds, backdrop)
        if (count <= 0) return
        val middle = bounds.centre

        val half = 0.5f / count
        val sliceGap = gapTurns((hubRadius + radius) / 2f)
        for (index in 0 until count) {
            val centre = sliceCentre(index, count, startTurns, rtl)
            val colour = when (index) {
                highlighted -> sliceHighlighted
                selected -> sliceSelected
                else -> slice
            }
            val outer = if (index == highlighted) radius + grow else radius
            canvas.sector(middle, hubRadius, outer, centre - half + sliceGap / 2f, 2f * half - sliceGap, colour, scratch)
        }

        if (childCount > 0) {
            val inner = radius + RingGap
            val childHalf = 0.5f / childCount * childSpan
            val childGap = gapTurns(inner + ringWidth / 2f)
            for (index in 0 until childCount) {
                val centre = childCentre(index, childCount, parentCentre, childSpan, rtl)
                val colour = if (index == childHighlighted) ringHighlighted else ring
                val outer = if (index == childHighlighted) inner + ringWidth + grow else inner + ringWidth
                canvas.sector(
                    middle,
                    inner,
                    outer,
                    centre - childHalf + childGap / 2f,
                    2f * childHalf - childGap,
                    colour,
                    scratch,
                )
            }
        }

        if (hub.alpha > 0) canvas.circle(middle, hubRadius, hub)
    }

    /** A gap given in pixels, as the turn it is at the radius it is drawn at. */
    private fun gapTurns(atRadius: Float): Float =
        if (gap <= 0f || atRadius <= 0f) 0f else gap / (2f * PI.toFloat() * atRadius)
}

/**
 * One slice of a ring: the shape between two radii and two angles.
 *
 * [scratch] is filled and handed to `fan` once per piece, which is allowed to do that because a
 * canvas reads a fan's points during the call and no longer.
 */
private fun UiCanvas.sector(
    centre: Offset,
    inner: Float,
    outer: Float,
    fromTurns: Float,
    sweepTurns: Float,
    colour: Colour,
    scratch: FloatArray,
) {
    if (colour.alpha == 0 || sweepTurns <= 0f || outer <= inner) return

    val pieces = ceil(sweepTurns * 360f / DegreesPerFan).toInt().coerceAtLeast(1)
    val piece = sweepTurns / pieces
    val last = StepsPerFan + 2
    for (index in 0 until pieces) {
        val from = fromTurns + piece * index
        scratch[0] = centre.x + sinTurns(from) * inner
        scratch[1] = centre.y - cosTurns(from) * inner
        for (step in 0..StepsPerFan) {
            val at = from + piece * step / StepsPerFan
            scratch[(step + 1) * 2] = centre.x + sinTurns(at) * outer
            scratch[(step + 1) * 2 + 1] = centre.y - cosTurns(at) * outer
        }
        val to = from + piece
        scratch[last * 2] = centre.x + sinTurns(to) * inner
        scratch[last * 2 + 1] = centre.y - cosTurns(to) * inner
        fan(scratch, colour)
    }
}
