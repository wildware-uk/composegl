package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.Haptic
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.backend.RecordingHaptics
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.GamepadNavigator
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.input.KeyRouter
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.input.ProvideUiSounds
import dev.wildware.composegl.ui.input.UiSounds
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.ProvideHaptics
import dev.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The weapon wheel, and the thing that makes it one rather than a ring of buttons: a slice is
 * chosen by **pointing at it**, from the stick's angle or the mouse's direction from the middle,
 * with nothing to reach.
 *
 * The issue asks for both ways of aiming, both ways of confirming, nested rings for categories, a
 * tick when the slice changes and a layout mirrored for Arabic. Each is driven here the way a
 * player drives it: real axes through a `GamepadNavigator`, real moves through a `PointerRouter`,
 * real keys through a `KeyRouter`.
 */
class RadialMenuTest {

    private val host = UiHost()
    private val bounds = Rect(0f, 0f, 600f, 400f)
    private val canvas = RecordingCanvas(bounds)
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val router = KeyRouter(focus)
    private val navigator = KeyNavigator(focus)
    private val pad = GamepadNavigator(focus)
    private val haptics = RecordingHaptics()

    /** The middle of a wheel given the whole 600 by 400 screen. */
    private val middle = Offset(300f, 200f)

    private var wall = 0L

    /** Held by the test rather than by the wheel, which is how a game holds it. */
    private var open by mutableStateOf(true)

    private val picked = mutableListOf<String>()
    private val cancels = mutableListOf<Boolean>()
    private val opens = mutableListOf<Boolean>()
    private val sounded = mutableListOf<String>()

    private val sounds = object : UiSounds {
        override fun focusMove() {
            sounded += "move"
        }

        override fun change() {
            sounded += "change"
        }
    }

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L) {
        wall += millis * 1_000_000L
        host.frame(wall)
        canvas.clear(bounds)
        MeasurePass().run(host.root, Constraints.atMost(600f, 400f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    private fun show(content: @Composable () -> Unit) {
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                ProvideUiSounds(sounds) {
                    ProvideHaptics(haptics) { content() }
                }
            }
        }
        frames(2)
    }

    // --- the wheel under test ---------------------------------------------------------------

    private val weapons = listOf("PISTOL", "RIFLE", "SHOTGUN", "ROCKET")

    /**
     * Four slices at north, east, south and west, which is where they land with no start angle.
     *
     * The slice being pointed at draws its name with a `>` in front of it, and the hub draws the
     * name on its own, so a test can read both off the canvas the way a player reads them.
     */
    private fun wheel(
        items: List<String> = weapons,
        selected: String? = null,
        confirm: RadialConfirm = RadialConfirm.Release,
        startAngleTurns: Float = 0f,
        children: (String) -> List<String> = { emptyList() },
        rtl: Boolean = false,
        stick: RadialStick = RadialStick.Left,
        deadZone: Float = 0.35f,
    ) = show {
        ProvideLayoutDirection(if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            RadialMenu(
                open = open,
                items = items,
                selected = selected,
                onSelect = { picked += it },
                onCancel = { cancels += true },
                onOpenChange = { opens += it },
                children = children,
                confirm = confirm,
                startAngleTurns = startAngleTurns,
                deadZone = deadZone,
                stick = stick,
                centre = { Text(it ?: "-") },
            ) { weapon, highlighted ->
                Text(if (highlighted) ">$weapon" else weapon)
            }
        }
    }

    /** Whether the wheel is on the screen at all, the way a game keeps an exhibit switchable. */
    private var mounted by mutableStateOf(true)

    /** The same wheel, but one a test can take off the screen while it is still open. */
    private fun switchableWheel(confirm: RadialConfirm = RadialConfirm.Release) = show {
        if (mounted) {
            RadialMenu(
                open = open,
                items = weapons,
                onSelect = { picked += it },
                onCancel = { cancels += true },
                onOpenChange = { opens += it },
                confirm = confirm,
                centre = { Text(it ?: "-") },
            ) { weapon, highlighted ->
                Text(if (highlighted) ">$weapon" else weapon)
            }
        }
    }

    /** Letting go of the button that was holding the wheel open. */
    private fun close() {
        open = false
        frames(2)
    }

    // --- driving it ----------------------------------------------------------------------------

    /** The stick, as a pad reports it: two axes, y positive downwards. */
    private fun stick(x: Float, y: Float) {
        pad.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftX, x))
        pad.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftY, y))
        frame()
    }

    /** The other stick, which a wheel only hears when it has been told to use it. */
    private fun rightStick(x: Float, y: Float) {
        pad.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.RightX, x))
        pad.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.RightY, y))
        frame()
    }

    /** The stick pushed [push] of the way out towards [turns] clockwise from straight up. */
    private fun aim(turns: Float, push: Float = 1f) {
        val radians = turns * 2f * PI.toFloat()
        stick(sin(radians) * push, -cos(radians) * push)
    }

    private fun press(button: GamepadButton) {
        pressTaken(button)
    }

    /** The same press, saying whether anything on the screen took it rather than passing it on. */
    private fun pressTaken(button: GamepadButton): Boolean {
        val taken = pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, button))
        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, button))
        frame()
        return taken
    }

    private fun move(x: Float, y: Float) {
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(x, y)))
        frame()
    }

    private fun click(x: Float, y: Float) {
        val at = Offset(x, y)
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at, timeMillis = wall / 1_000_000))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at, timeMillis = wall / 1_000_000))
        frame()
    }

    private fun key(key: Key) {
        val down = KeyEvent(key, KeyEventType.Down)
        if (!router.onKey(down)) navigator.onKey(down)
        val up = KeyEvent(key, KeyEventType.Up)
        if (!router.onKey(up)) navigator.onKey(up)
        frame()
    }

    // --- reading it ----------------------------------------------------------------------------

    /** Whichever slice is drawn as the one being pointed at, by the marker the test put on it. */
    private fun highlighted(): String? = canvas.calls.filterIsInstance<DrawCall.Text>()
        .map { it.text }
        .firstOrNull { it.startsWith(">") }
        ?.removePrefix(">")

    /** What the hub says, which is the wheel's own answer to "what am I about to take". */
    private fun hub(): String? = canvas.calls.filterIsInstance<DrawCall.Text>().lastOrNull()?.text

    private fun texts() = canvas.calls.filterIsInstance<DrawCall.Text>().map { it.text }

    private fun fillOf(style: String) = (Skin.Default.resolve(style).background as SkinDrawable.Fill).colour

    private fun fans(colour: Colour) = canvas.calls.filterIsInstance<DrawCall.Fan>().filter { it.colour == colour }

    /** How far out a drawn slice reaches: its furthest point from the middle of the wheel. */
    private fun reachOf(fans: List<DrawCall.Fan>): Float =
        fans.flatMap { it.points }.maxOf { middle.distanceTo(it) }

    /** The turn a drawn point sits at, clockwise from straight up. */
    private fun turnOf(point: Offset): Float {
        val turn = atan2(point.x - middle.x, middle.y - point.y) / (2f * PI.toFloat())
        return if (turn < 0f) turn + 1f else turn
    }

    /**
     * Whether the shape drawn in [colour] is the one sitting at [turns].
     *
     * Read off the canvas rather than worked out again from the layout rules, because the question
     * a player asks is "does the thing I am pointing at light up", and that is only answered by
     * where it was actually drawn.
     */
    private fun covers(colour: Colour, turns: Float): Boolean {
        val offsets = fans(colour).flatMap { it.points }.map {
            val from = turnOf(it) - turns
            // Into the half turn either side, so a wedge across the top is still one wedge.
            from - floor(from + 0.5f)
        }
        return offsets.isNotEmpty() && offsets.min() <= 0f && offsets.max() >= 0f
    }

    // --- aiming with a stick ---------------------------------------------------------------------

    @Test
    fun `the stick picks the slice it points at`() {
        wheel()

        stick(1f, 0f)
        assertEquals("RIFLE", highlighted(), "east is the second slice")

        stick(0f, 1f)
        assertEquals("SHOTGUN", highlighted(), "south is the third")

        stick(-1f, 0f)
        assertEquals("ROCKET", highlighted(), "west is the fourth")

        stick(0f, -1f)
        assertEquals("PISTOL", highlighted(), "and north is back to the first")
    }

    @Test
    fun `a slice is picked from a flick rather than from reaching it`() {
        wheel()

        // Barely past the dead zone and nowhere near the ring. A ring of buttons would need the
        // whole push; a wheel only ever needs the direction.
        stick(0.4f, -0.4f)

        assertEquals("RIFLE", highlighted(), "a half-pushed stick between north and east is east")
    }

    @Test
    fun `a stick resting inside the dead zone points at nothing`() {
        wheel()

        stick(0.2f, 0.1f)

        assertNull(highlighted(), "a resting thumb chose a weapon")
        assertEquals("-", hub(), "and the hub should say there is nothing to take")
    }

    @Test
    fun `letting the stick go points at nothing again`() {
        wheel()
        stick(1f, 0f)
        assertEquals("RIFLE", highlighted())

        stick(0f, 0f)

        assertNull(highlighted(), "the stick came back to the middle and the slice stayed lit")
    }

    @Test
    fun `a wheel told to use the right stick hears that one and not the left`() {
        wheel(stick = RadialStick.Right)

        stick(1f, 0f)
        assertNull(highlighted(), "the left stick aimed a wheel that was told to use the right one")

        rightStick(0f, 1f)

        assertEquals("SHOTGUN", highlighted(), "the right stick pointed south and nothing happened")
    }

    @Test
    fun `a bigger dead zone asks for a bigger push`() {
        wheel(deadZone = 0.8f)

        // Half a push is well past the usual dead zone, and nowhere near this one.
        stick(0.5f, 0f)
        assertNull(highlighted(), "half a push crossed a dead zone set at four fifths")

        stick(1f, 0f)

        assertEquals("RIFLE", highlighted(), "a full push should still point east")
    }

    // --- aiming with a mouse ---------------------------------------------------------------------

    @Test
    fun `the mouse picks by its direction from the middle`() {
        wheel()

        // Far outside the ring and well off the slice itself: the direction is the whole answer.
        move(590f, 195f)

        assertEquals("RIFLE", highlighted(), "the pointer pointed east")
    }

    @Test
    fun `the pointer on the hub picks nothing`() {
        wheel()

        move(310f, 205f)

        assertNull(highlighted(), "the middle of a wheel is the mouse's dead zone")
    }

    @Test
    fun `the pointer coming back to the hub puts the wheel back to nothing`() {
        wheel()
        move(590f, 195f)
        assertEquals("RIFLE", highlighted())

        move(300f, 200f)

        assertNull(highlighted(), "a pointer back in the middle is pointing at nothing again")
    }

    @Test
    fun `the mouse and the stick take it in turns`() {
        wheel()

        move(590f, 195f)
        assertEquals("RIFLE", highlighted())

        stick(0f, 1f)
        assertEquals("SHOTGUN", highlighted(), "the stick should win once it is the one moving")

        move(300f, 390f)
        assertEquals("SHOTGUN", highlighted(), "and the mouse again when it moves")
    }

    // --- confirming ------------------------------------------------------------------------------

    @Test
    fun `letting go of the wheel button takes the slice being pointed at`() {
        wheel()

        stick(1f, 0f)
        close()

        assertEquals(listOf("RIFLE"), picked, "the wheel forgot what it was pointing at as it closed")
        assertTrue(cancels.isEmpty())
    }

    @Test
    fun `the mouse's slice is taken when the wheel closes too`() {
        wheel()

        move(300f, 390f)
        close()

        assertEquals(listOf("SHOTGUN"), picked)
    }

    @Test
    fun `letting go with nothing pointed at cancels`() {
        wheel()

        close()

        assertTrue(picked.isEmpty(), "a stick that never left the dead zone equipped something")
        assertEquals(listOf(true), cancels)
    }

    @Test
    fun `taking a slice says so once rather than on every frame`() {
        wheel()

        stick(1f, 0f)
        close()
        frames(10)

        assertEquals(listOf("RIFLE"), picked)
    }

    @Test
    fun `a press does not confirm a wheel that confirms on release`() {
        wheel()

        move(590f, 195f)
        click(590f, 195f)

        assertTrue(picked.isEmpty(), "the click chose for a wheel that only chooses when it closes")
    }

    @Test
    fun `south takes the slice on a wheel that confirms on a press`() {
        wheel(confirm = RadialConfirm.Press)

        stick(0f, 1f)
        press(GamepadButton.South)

        assertEquals(listOf("SHOTGUN"), picked)
        assertEquals(listOf("move", "change"), sounded, "taking a slice should sound different from moving")
    }

    @Test
    fun `east backs out of a wheel that confirms on a press`() {
        wheel(confirm = RadialConfirm.Press)

        stick(0f, 1f)
        press(GamepadButton.East)

        assertTrue(picked.isEmpty(), "backing out equipped something anyway")
        assertEquals(listOf(true), cancels)
    }

    @Test
    fun `a click takes the slice on a wheel that confirms on a press`() {
        wheel(confirm = RadialConfirm.Press)

        click(300f, 390f)

        assertEquals(listOf("SHOTGUN"), picked, "the click pointed south")
    }

    @Test
    fun `enter confirms and escape cancels`() {
        wheel(confirm = RadialConfirm.Press)

        stick(-1f, 0f)
        key(Key.Enter)
        assertEquals(listOf("ROCKET"), picked)

        key(Key.Escape)
        assertEquals(listOf(true), cancels)
    }

    // --- how the slices are laid out ---------------------------------------------------------------

    @Test
    fun `a start angle turns the whole wheel`() {
        // An eighth of a turn puts the first slice's middle north-east instead of north, so north
        // itself becomes the seam between the last slice and the first.
        wheel(startAngleTurns = 0.125f)

        stick(1f, -1f)
        assertEquals("PISTOL", highlighted(), "north-east should be the first slice")

        stick(1f, 1f)
        assertEquals("RIFLE", highlighted(), "and south-east the second")
    }

    @Test
    fun `the slices are laid out evenly round the middle`() {
        wheel()

        val at = canvas.calls.filterIsInstance<DrawCall.Text>().associate { it.text to it.at }
        val north = checkNotNull(at["PISTOL"]) { "nothing was drawn for the first slice" }
        val east = checkNotNull(at["RIFLE"])
        val south = checkNotNull(at["SHOTGUN"])
        val west = checkNotNull(at["ROCKET"])

        assertTrue(north.y < east.y && north.y < west.y, "the first slice should be the highest")
        assertTrue(south.y > east.y && south.y > west.y, "the third should be the lowest")
        assertTrue(east.x > north.x && east.x > south.x, "the second should be the furthest right")
        assertTrue(west.x < north.x && west.x < south.x, "and the fourth the furthest left")
    }

    @Test
    fun `a right to left wheel draws its slices the other way round`() {
        wheel(rtl = true)

        val at = canvas.calls.filterIsInstance<DrawCall.Text>().associate { it.text to it.at }
        val first = checkNotNull(at["PISTOL"])

        assertTrue(checkNotNull(at["RIFLE"]).x < first.x, "the second slice should be left of the first")
        assertTrue(checkNotNull(at["ROCKET"]).x > first.x, "and the last one right of it")
    }

    @Test
    fun `a wheel in a right to left language is mirrored`() {
        wheel(rtl = true)

        stick(-1f, 0f)
        assertEquals("RIFLE", highlighted(), "the second slice should be west of the first in Arabic")

        stick(1f, 0f)
        assertEquals("ROCKET", highlighted(), "and the last slice east of it")
    }

    @Test
    fun `two slices split the wheel in half`() {
        wheel(items = listOf("YES", "NO"))

        stick(0f, -1f)
        assertEquals("YES", highlighted())

        stick(0f, 1f)
        assertEquals("NO", highlighted())
    }

    @Test
    fun `one slice is the whole wheel`() {
        wheel(items = listOf("ONLY"))

        stick(-1f, 1f)

        assertEquals("ONLY", highlighted(), "there is nothing else it could have been")
    }

    // --- nested rings -------------------------------------------------------------------------------

    private val ammo: (String) -> List<String> = { if (it == "RIFLE") listOf("AP", "HE") else emptyList() }

    @Test
    fun `pointing at a category shows its ring`() {
        wheel(children = ammo)

        stick(0.5f, 0f)

        assertTrue("AP" in texts() && "HE" in texts(), "the category's ring never appeared: ${texts()}")
    }

    @Test
    fun `a category's ring goes away when the stick leaves it`() {
        wheel(children = ammo)
        stick(0.5f, 0f)
        assertTrue("AP" in texts())

        stick(0f, 1f)

        assertFalse("AP" in texts(), "two rings were on screen at once")
    }

    @Test
    fun `pushing the stick to the edge chooses out of the nested ring`() {
        wheel(children = ammo)

        // Half way out is still the category itself.
        stick(0.5f, 0f)
        assertEquals("RIFLE", hub())

        // All the way out is one of its children — the one the angle was already pointing at.
        stick(1f, -0.2f)
        assertEquals("AP", hub(), "the stick reached the ring and took nothing out of it")
    }

    @Test
    fun `a wheel with no categories never grows a ring`() {
        wheel()

        stick(1f, 0f)

        assertEquals("RIFLE", hub(), "a flat wheel invented a second ring")
    }

    @Test
    fun `confirming inside a nested ring takes the child`() {
        wheel(confirm = RadialConfirm.Press, children = ammo)

        stick(1f, -0.2f)
        press(GamepadButton.South)

        assertEquals(listOf("AP"), picked)
    }

    // --- nested rings wider than the slice that opened them ------------------------------------

    /**
     * Three rounds under the rifle, which is the shape that breaks a wheel that trusts the angle.
     *
     * Three children ask for three eighths of a turn, and the rifle's own slice is a quarter — so
     * the outer round on each side is drawn over the neighbouring slice, and the player is being
     * asked to aim somewhere the ring's own category is no longer under the stick.
     */
    private val rounds: (String) -> List<String> =
        { if (it == "RIFLE") listOf("AP", "HE", "SABOT") else emptyList() }

    @Test
    fun `a widened ring stays open while the stick is held out past its own slice`() {
        wheel(children = rounds)

        // Into the rifle, then straight out through it, which is what opens the ring.
        aim(0.25f, push = 0.5f)
        assertEquals("RIFLE", hub())
        aim(0.25f)
        assertEquals("HE", hub(), "the middle round is the one straight out through the rifle")

        // A tenth of a turn round is inside the first round's wedge and outside the rifle's slice.
        aim(0.1f)

        assertEquals("AP", hub(), "the ring went back to being a slice as soon as the aim left it")
        assertTrue("AP" in texts() && "SABOT" in texts(), "the ring itself disappeared: ${texts()}")
    }

    /** Children named so a test can say which one it expected: K0 is the first, K1 the next. */
    private fun kids(parent: String, count: Int): (String) -> List<String> =
        { if (it == parent) List(count) { index -> "K$index" } else emptyList() }

    /**
     * Aims at each angle in turn and checks the wheel takes the child that is drawn there.
     *
     * Both halves matter and neither alone is enough: the hub says what the wheel would take, and
     * [covers] says the lit wedge really is under the stick. A wheel can agree with itself about a
     * child it draws somewhere else entirely, which is exactly what a bad wrap does.
     */
    private fun assertChildAt(vararg at: Pair<Float, String>) {
        at.forEach { (turns, child) ->
            aim(turns)

            assertEquals(child, hub(), "aiming at $turns took the wrong child")
            assertTrue(
                covers(fillOf("wheel.ring.highlighted"), turns),
                "$child lit up for $turns but is drawn somewhere else",
            )
        }
    }

    @Test
    fun `every round is chosen by the angle it is drawn at`() {
        wheel(children = rounds)
        aim(0.25f)

        listOf(0.1f to "AP", 0.25f to "HE", 0.4f to "SABOT").forEach { (turns, round) ->
            aim(turns)

            assertEquals(round, hub(), "aiming at $turns took the wrong round")
            assertTrue(
                covers(fillOf("wheel.ring.highlighted"), turns),
                "$round lit up for $turns but is drawn somewhere else",
            )
        }
    }

    @Test
    fun `a ring wider than half a turn is still chosen by the angle it is drawn at`() {
        // Five children widen the rifle's quarter-turn slice to five eighths, which puts the last
        // one due south — past the half turn, where wrapping round the ring's first edge goes
        // negative and hands the whole far half back to the first child.
        wheel(children = kids("RIFLE", 5))
        aim(0.25f, push = 0.5f)
        aim(0.25f)

        assertChildAt(0f to "K0", 0.125f to "K1", 0.25f to "K2", 0.375f to "K3", 0.5f to "K4")
    }

    @Test
    fun `a ring of eight leaves a way out of itself`() {
        // Eight children ask for the whole turn. A ring all the way round is a ring the stick can
        // never leave, so it is capped at seven eighths and the last eighth is the way back out.
        wheel(children = kids("RIFLE", 8))
        aim(0.25f, push = 0.5f)
        aim(0.25f)

        assertChildAt(
            0.8671875f to "K0",
            0.9765625f to "K1",
            0.0859375f to "K2",
            0.1953125f to "K3",
            0.3046875f to "K4",
            0.4140625f to "K5",
            0.5234375f to "K6",
            0.6328125f to "K7",
        )

        aim(0.75f)

        assertEquals("ROCKET", hub(), "the stick was stuck in a ring that went the whole way round")
    }

    @Test
    fun `a one slice wheel can still be swept off its own ring`() {
        // One slice is the whole turn, so its ring would be too without the cap — and a wheel that
        // can only ever answer with a child is a wheel with no way to point at the category.
        wheel(items = listOf("ONLY"), children = kids("ONLY", 3))
        aim(0f, push = 0.5f)
        assertEquals("ONLY", hub())

        aim(0f)
        assertEquals("K1", hub(), "straight out through the middle is the middle child")

        aim(0.48f)

        assertEquals("K2", hub(), "past the ring's end is the child nearest that end")
    }

    @Test
    fun `a right to left ring is chosen by the angle it is drawn at too`() {
        // The rifle sits west in Arabic, and its children run anticlockwise from the ring's other
        // end — so every angle here is the mirror of the left-to-right case.
        wheel(children = kids("RIFLE", 5), rtl = true)
        aim(0.75f, push = 0.5f)
        assertEquals("RIFLE", hub(), "the second slice should be west in Arabic")
        aim(0.75f)

        assertChildAt(0f to "K0", 0.875f to "K1", 0.75f to "K2", 0.625f to "K3", 0.5f to "K4")
    }

    @Test
    fun `a ring twice the width of its slice is chosen by the angle it is drawn at too`() {
        // Eight slices are an eighth of a turn each, and two children widen B's ring to a quarter.
        val eight = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        wheel(items = eight, children = { if (it == "B") listOf("AP", "HE") else emptyList() })

        aim(0.125f, push = 0.5f)
        assertEquals("B", hub())
        aim(0.125f)

        aim(0.0625f)
        assertEquals("AP", hub(), "the first round is drawn a sixteenth of a turn from the top")
        assertTrue(covers(fillOf("wheel.ring.highlighted"), 0.0625f), "AP is drawn somewhere else")

        aim(0.1875f)
        assertEquals("HE", hub(), "pointing straight at a drawn round took a different slice")
        assertTrue(covers(fillOf("wheel.ring.highlighted"), 0.1875f), "HE is drawn somewhere else")
    }

    @Test
    fun `sweeping right round at full push moves from one ring to the next`() {
        wheel(children = { if (it == "RIFLE") listOf("AP", "HE", "SABOT") else listOf("SOLID") })

        aim(0.25f)
        assertEquals("HE", hub(), "the rifle's ring")

        // Held right out the whole way: the latch has to let go once the aim leaves the ring.
        aim(0.5f)

        assertEquals("SOLID", hub(), "the stick was stuck in the ring it started in")
    }

    @Test
    fun `the mouse reaches a nested ring by going past the slices`() {
        wheel(children = ammo)

        // East of the middle but inside the ring of slices: still the category.
        move(400f, 200f)
        assertEquals("RIFLE", hub())

        move(500f, 190f)
        assertEquals("AP", hub(), "past the slices is the category's ring")
    }

    // --- what it says while you turn it ---------------------------------------------------------------

    @Test
    fun `changing slice ticks once`() {
        wheel()

        stick(1f, 0f)
        assertEquals(listOf("move"), sounded)
        assertEquals(listOf(Haptic.Tick), haptics.performed)

        stick(0f, 1f)
        assertEquals(listOf("move", "move"), sounded)
        assertEquals(listOf(Haptic.Tick, Haptic.Tick), haptics.performed)
    }

    @Test
    fun `staying on the same slice does not tick again`() {
        wheel()

        stick(1f, 0f)
        stick(0.9f, 0.1f)
        stick(0.8f, -0.1f)

        assertEquals(listOf("move"), sounded, "the stick wobbled inside one slice and ticked for it")
    }

    @Test
    fun `the hub says what is about to be taken`() {
        wheel()

        stick(0f, 1f)

        assertEquals("SHOTGUN", hub())
    }

    @Test
    fun `the slice being pointed at is drawn differently from the rest`() {
        wheel(selected = "PISTOL")

        stick(0f, 1f)

        assertTrue(fans(fillOf("wheel.slice.highlighted")).isNotEmpty(), "nothing was drawn as pointed at")
        assertTrue(fans(fillOf("wheel.slice.selected")).isNotEmpty(), "the weapon in hand was not marked")
        assertTrue(fans(fillOf("wheel.slice")).isNotEmpty(), "the other slices vanished")
    }

    @Test
    fun `the highlight swells onto the new slice rather than appearing on it`() {
        wheel()

        stick(1f, 0f)
        val early = reachOf(fans(fillOf("wheel.slice.highlighted")))

        frames(12, 16)
        val settled = reachOf(fans(fillOf("wheel.slice.highlighted")))

        assertTrue(settled > early, "the highlight should have grown into place: $early then $settled")
    }

    // --- opening and closing -------------------------------------------------------------------------

    @Test
    fun `the wheel says when it comes up and when it goes away`() {
        wheel()
        stick(1f, 0f)

        close()

        assertEquals(listOf(true, false), opens, "a game slowing its world clock was never told")
    }

    @Test
    fun `a wheel that opens again starts pointing at nothing`() {
        wheel()
        stick(1f, 0f)
        close()

        open = true
        frames(2)

        assertNull(highlighted(), "it came back still pointing where the last one left off")
    }

    @Test
    fun `a wheel taken off the screen while it is open says it went away and takes nothing`() {
        switchableWheel()
        stick(1f, 0f)

        // The exhibit it lives in was switched off, which no game tells the wheel about.
        mounted = false
        frames(2)

        assertEquals(listOf(true, false), opens, "a game left with its world clock stopped forever")
        assertTrue(picked.isEmpty(), "a screen swapping underneath the player equipped a gun")
        assertEquals(listOf(true), cancels, "going away with no choice made is a cancel")
    }

    @Test
    fun `a wheel taken off the screen pointing at nothing cancels`() {
        switchableWheel()

        mounted = false
        frames(2)

        assertEquals(listOf(true, false), opens)
        assertTrue(picked.isEmpty(), "a stick that never left the dead zone equipped something")
        assertEquals(listOf(true), cancels)
    }

    @Test
    fun `a wheel that waits to be told takes nothing when it is taken off the screen`() {
        switchableWheel(confirm = RadialConfirm.Press)
        stick(1f, 0f)

        mounted = false
        frames(2)

        assertTrue(picked.isEmpty(), "going away is not the same as being told to take one")
        assertEquals(listOf(true, false), opens, "it still has to say the wheel is gone")
        assertEquals(listOf(true), cancels)
    }

    @Test
    fun `a wheel closed before it goes away only says so once`() {
        switchableWheel()
        stick(1f, 0f)
        close()

        mounted = false
        frames(2)

        assertEquals(listOf(true, false), opens)
        assertEquals(listOf("RIFLE"), picked, "the slice was taken twice")
    }

    @Test
    fun `a closed wheel draws nothing and answers nothing`() {
        open = false
        wheel()

        stick(1f, 0f)

        assertTrue(canvas.calls.isEmpty(), "a closed wheel drew ${canvas.calls.size} things")
        assertTrue(picked.isEmpty())
        assertTrue(opens.isEmpty(), "a wheel that was never up said it had opened")
    }

    @Test
    fun `a wheel with no slices draws nothing and answers nothing`() {
        wheel(items = emptyList())

        stick(1f, 0f)

        assertTrue(canvas.calls.isEmpty())
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `a wheel nobody is turning costs nothing`() {
        wheel()
        stick(1f, 0f)
        frames(20, 20)

        repeat(30) {
            wall += 20_000_000L
            assertFalse(host.frame(wall), "frame $it redrew a wheel nothing had happened to")
        }
    }

    @Test
    fun `the stick does not also walk the focus behind the wheel`() {
        show {
            Box(Modifier.align(Alignment.TopStart)) {
                Row {
                    Button("left", onClick = {}, initialFocus = true)
                    Button("right", onClick = {})
                }
            }
            RadialMenu(open = open, items = weapons, onSelect = { picked += it }) { weapon, _ -> Text(weapon) }
        }
        val before = focus.focused

        stick(1f, 0f)

        assertTrue(focus.focused === before, "the stick moved focus through an open wheel")
    }

    @Test
    fun `a pad press does not reach a button behind the wheel`() {
        var pressed = 0
        show {
            Box(Modifier.align(Alignment.TopStart)) {
                Button("behind", onClick = { pressed++ }, initialFocus = true)
            }
            RadialMenu(open = open, items = weapons, onSelect = { picked += it }) { weapon, _ -> Text(weapon) }
        }
        stick(1f, 0f)

        val south = pressTaken(GamepadButton.South)
        val east = pressTaken(GamepadButton.East)

        assertTrue(south, "South went past a modal wheel")
        assertTrue(east, "East went past a modal wheel")
        assertEquals(0, pressed, "a pad press activated the button hidden under the wheel")
        assertTrue(picked.isEmpty(), "a release wheel took a slice from a button it only swallows")
    }
}
