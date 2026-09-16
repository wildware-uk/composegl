package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
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
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.flatColour
import dev.wildware.composegl.ui.widget.PanZoomState
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.TooltipHost
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * A skill tree, driven the way a player drives it: the mouse leaning on a node until it is bought,
 * the arrows and the pad walking the branches, a hover asking what a skill does. Every answer is
 * read off the screen — where a node was drawn, what colour the line between two of them came out,
 * where focus ended up, where the camera is looking.
 *
 * Driven a frame at a time by hand rather than by `uiTest`, because the whole subject is how long a
 * button was held: settling until nothing moves would run every hold out to the end of itself.
 *
 * The screen is 400 by 300 and the camera starts on world (120, 60), so a world point is drawn at
 * itself plus (80, 90):
 *
 * ```
 *   power (80, 90) ──▶ strike (200, 90) ──▶ combo (320, 90) ──▶ far (680, 90), off the right
 *     │                    │
 *     ▼                    ▼
 *   guard (80, 210) ────▶ fury (200, 210)
 * ```
 */
class SkillTreeTest {

    private val bounds = Rect(0f, 0f, 400f, 300f)

    private val host = UiHost()
    private val canvas = RecordingCanvas(bounds)
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val router = KeyRouter(focus, host.root)
    private val navigator = KeyNavigator(focus)
    private val pad = GamepadNavigator(focus)

    private var wall = 0L

    private val taken = mutableListOf<Any>()

    /** What each node was drawn as, the last time it was composed. */
    private val states = mutableMapOf<Any, SkillState>()

    @AfterEach
    fun tearDown() = host.dispose()

    // --- the harness -------------------------------------------------------------------------------

    private fun frame(millis: Long = 16L) {
        wall += millis * 1_000_000L
        host.frame(wall)
        canvas.clear(bounds)
        MeasurePass().run(host.root, Constraints.atMost(bounds.width, bounds.height))
        focus.refresh()
        pad.frame(wall / 1_000_000L)
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    /** [millis] of interface time, a sixteen-millisecond frame at a time. */
    private fun advance(millis: Long) = frames((millis / 16L).toInt().coerceAtLeast(1))

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frames(2)
    }

    // --- the tree under test -----------------------------------------------------------------------

    private fun tree(
        power: Int = 1,
        strike: Int = 0,
        guard: Int = 0,
        fury: Int = 0,
        furyEnabled: Boolean = true,
        tooltips: Boolean = false,
    ) = listOf(
        SkillNode("power", 0f, 0f, rank = power, label = "P"),
        SkillNode("strike", 120f, 0f, rank = strike, label = "S", tooltip = if (tooltips) "Strike harder" else null),
        SkillNode("combo", 240f, 0f, label = "C"),
        SkillNode("far", 600f, 0f, label = "F"),
        SkillNode("guard", 0f, 120f, rank = guard, label = "G"),
        SkillNode("fury", 120f, 120f, ranks = 3, rank = fury, label = "U", enabled = furyEnabled),
    )

    private val edges = listOf(
        SkillEdge("power", "strike"),
        SkillEdge("strike", "combo"),
        SkillEdge("combo", "far"),
        SkillEdge("power", "guard"),
        SkillEdge("strike", "fury"),
        SkillEdge("guard", "fury"),
    )

    private fun camera(worldBounds: Rect? = Rect(-200f, -140f, 800f, 320f)) =
        PanZoomState(1f, 0.5f, 2f, worldBounds, Offset(120f, 60f))

    @Composable
    private fun Tree(
        nodes: List<SkillNode>,
        state: PanZoomState,
        holdMillis: Int = DefaultHoldMillis,
    ) {
        SkillTree(
            nodes = nodes,
            edges = edges,
            modifier = Modifier.fillMaxSize(),
            state = state,
            onActivate = { taken += it.id },
            holdMillis = holdMillis,
        ) { node, nodeState ->
            states[node.id] = nodeState
            SkillNodeIcon(node, nodeState)
        }
    }

    /** The usual screen: a camera the test can ask about, and the tree on it. */
    private fun open(
        nodes: List<SkillNode> = tree(),
        state: PanZoomState = camera(),
        holdMillis: Int = DefaultHoldMillis,
    ): PanZoomState {
        show { Tree(nodes, state, holdMillis) }
        return state
    }

    // --- input -------------------------------------------------------------------------------------

    private fun press(at: Offset) {
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at, timeMillis = wall / 1_000_000))
        frame()
    }

    private fun release(at: Offset) {
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at, timeMillis = wall / 1_000_000))
        frame()
    }

    private fun click(at: Offset) {
        press(at)
        release(at)
    }

    /** Leans on a node for [millis] and lets go, which is how a skill is bought. */
    private fun hold(at: Offset, millis: Long) {
        press(at)
        advance(millis)
        release(at)
    }

    private fun key(key: Key) {
        val down = KeyEvent(key, KeyEventType.Down)
        if (!router.onKey(down)) navigator.onKey(down)
        frame()
        val up = KeyEvent(key, KeyEventType.Up)
        if (!router.onKey(up)) navigator.onKey(up)
        frame()
    }

    private fun padDown(button: GamepadButton) {
        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, button))
        frame()
    }

    private fun padUp(button: GamepadButton) {
        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, button))
        frame()
    }

    private fun padPress(button: GamepadButton) {
        padDown(button)
        padUp(button)
    }

    // --- reading the screen ------------------------------------------------------------------------

    /** Where focus is, as a point on the screen: the tree tags nothing, so the geometry answers. */
    private fun focusedAt(): Offset? = focus.focused?.boundsInRoot?.centre

    private fun assertAt(expected: Offset, actual: Offset?, message: String) {
        assertNotNull(actual, "$message: nothing has focus")
        val found = actual!!
        assertTrue(
            abs(expected.x - found.x) < 1f && abs(expected.y - found.y) < 1f,
            "$message: expected $expected but was $found",
        )
    }

    private fun colour(name: String): Colour =
        checkNotNull(Skin.Default.resolve(name).background.flatColour) { "$name has no flat colour" }

    private fun fans(name: String): List<DrawCall.Fan> {
        val wanted = colour(name)
        return canvas.calls.filterIsInstance<DrawCall.Fan>().filter { it.colour == wanted }
    }

    private fun text(wanted: String): DrawCall.Text? =
        canvas.calls.filterIsInstance<DrawCall.Text>().firstOrNull { it.text == wanted }

    private val powerAt = Offset(80f, 90f)
    private val strikeAt = Offset(200f, 90f)
    private val comboAt = Offset(320f, 90f)
    private val guardAt = Offset(80f, 210f)
    private val furyAt = Offset(200f, 210f)

    // --- where the nodes are and what they are ------------------------------------------------------

    @Test
    fun `a node sits where the world puts it and knows what state it is in`() {
        val camera = open()

        assertEquals(Offset(80f, 90f), camera.worldToScreen(Offset(0f, 0f)), "world to screen")
        assertEquals(SkillState.Maxed, states["power"], "its one rank is bought")
        assertEquals(SkillState.Available, states["strike"])
        assertEquals(SkillState.Available, states["guard"])
        assertEquals(SkillState.Locked, states["combo"], "two steps from anything bought")
        assertEquals(SkillState.Locked, states["fury"], "guard is not bought yet")
    }

    // --- taking a node ------------------------------------------------------------------------------

    @Test
    fun `holding an open node takes it`() {
        open()

        press(strikeAt)
        advance(300)
        assertEquals(emptyList<Any>(), taken, "half a hold is not a decision")

        advance(300)
        assertEquals(listOf<Any>("strike"), taken, "and a whole one is")
        release(strikeAt)
        assertEquals(listOf<Any>("strike"), taken, "letting go afterwards adds nothing")
    }

    @Test
    fun `a click on its own does not spend a point`() {
        open()

        click(strikeAt)

        assertEquals(emptyList<Any>(), taken, "a mis-click must not buy a skill")
    }

    @Test
    fun `letting go part way through a hold changes nothing`() {
        open()

        hold(strikeAt, 200)
        advance(600)

        assertEquals(emptyList<Any>(), taken)
    }

    @Test
    fun `a locked node cannot be taken however long it is held`() {
        open()

        hold(comboAt, 900)

        assertEquals(emptyList<Any>(), taken)
    }

    @Test
    fun `a maxed node has nothing left to spend on`() {
        open()

        hold(powerAt, 900)

        assertEquals(emptyList<Any>(), taken)
    }

    @Test
    fun `a node part way through its ranks can be taken again`() {
        open(tree(power = 1, strike = 1, guard = 1, fury = 1))
        assertEquals(SkillState.Owned, states["fury"])

        hold(furyAt, 700)

        assertEquals(listOf<Any>("fury"), taken)
    }

    @Test
    fun `a switched-off node cannot be taken even when it is part way through its ranks`() {
        open(tree(power = 1, strike = 1, guard = 1, fury = 1, furyEnabled = false))
        assertEquals(SkillState.Owned, states["fury"], "it keeps what was bought")

        press(furyAt)
        advance(250)
        assertEquals(0, fans("skilltree.hold").size, "a switched-off node must not look buyable")

        advance(700)
        release(furyAt)

        assertEquals(emptyList<Any>(), taken, "the game switched it off; no point may be spent")
    }

    @Test
    fun `a switched-off node cannot be taken with a plain click either`() {
        open(tree(power = 1, strike = 1, guard = 1, fury = 1, furyEnabled = false), holdMillis = 0)

        click(furyAt)

        assertEquals(emptyList<Any>(), taken)
    }

    @Test
    fun `a tree that asks for a plain click gets one`() {
        open(holdMillis = 0)

        click(strikeAt)

        assertEquals(listOf<Any>("strike"), taken)
    }

    @Test
    fun `the hold fills the node while it is held and is gone the moment it is let go`() {
        open()

        press(strikeAt)
        advance(250)
        assertEquals(1, fans("skilltree.hold").size, "the node being leaned on should be filling")

        release(strikeAt)
        frames(2)
        assertEquals(0, fans("skilltree.hold").size, "it must not stay half taken")
    }

    // --- keys and the pad ----------------------------------------------------------------------------

    @Test
    fun `Enter held on the focused node takes it`() {
        open()
        click(strikeAt)
        assertAt(strikeAt, focusedAt(), "a press puts focus on the node")

        val down = KeyEvent(Key.Enter, KeyEventType.Down)
        if (!router.onKey(down)) navigator.onKey(down)
        advance(700)
        val up = KeyEvent(Key.Enter, KeyEventType.Up)
        if (!router.onKey(up)) navigator.onKey(up)
        frame()

        assertEquals(listOf<Any>("strike"), taken)
    }

    @Test
    fun `South held on the focused node takes it`() {
        open()
        click(strikeAt)

        padDown(GamepadButton.South)
        advance(700)
        padUp(GamepadButton.South)

        assertEquals(listOf<Any>("strike"), taken)
    }

    @Test
    fun `a tap of South does not buy anything`() {
        open()
        click(strikeAt)

        padPress(GamepadButton.South)

        assertEquals(emptyList<Any>(), taken)
    }

    @Test
    fun `a direction follows the line out of the node it is on`() {
        open()
        click(powerAt)

        padPress(GamepadButton.DpadRight)
        assertAt(strikeAt, focusedAt(), "right along the line to strike")

        padPress(GamepadButton.DpadDown)
        assertAt(furyAt, focusedAt(), "down along the line to fury")

        padPress(GamepadButton.DpadUp)
        assertAt(strikeAt, focusedAt(), "and back up the same line")
    }

    @Test
    fun `the arrow keys walk the same lines`() {
        open()
        click(powerAt)

        key(Key.Down)
        assertAt(guardAt, focusedAt(), "down along the line to guard")

        key(Key.Right)
        assertAt(furyAt, focusedAt(), "and right along the line to fury")
    }

    @Test
    fun `a direction with no line that way falls back to the nearest node`() {
        open()
        click(comboAt)

        // Nothing leaves combo downwards, so the toolkit's own nearest-in-direction search answers.
        padPress(GamepadButton.DpadDown)

        assertAt(furyAt, focusedAt(), "the nearest node below combo")
    }

    @Test
    fun `the camera follows focus onto a node that is off the screen`() {
        val camera = open()
        click(comboAt)
        assertTrue(camera.worldToScreen(Offset(600f, 0f)).x > bounds.width, "far starts off the right")

        padPress(GamepadButton.DpadRight)
        advance(600)

        val far = camera.worldToScreen(Offset(600f, 0f))
        assertTrue(far.x in 0f..bounds.width, "the camera should have brought it into view, was $far")
    }

    // --- the lines -------------------------------------------------------------------------------------

    @Test
    fun `the lines are tinted by what they join`() {
        open(tree(power = 1, strike = 1))
        frame()

        // power to strike is bought. strike opens combo and power opens guard, and neither is
        // taken yet. The other three lead nowhere a point can go.
        assertEquals(1, fans("skilltree.edge.owned").size, "power to strike")
        assertEquals(2, fans("skilltree.edge.available").size, "strike to combo and power to guard")
        assertEquals(3, fans("skilltree.edge.locked").size, "the other three")
    }

    @Test
    fun `a line fills from the node that opened it when the node it leads to is taken`() {
        val nodes = mutableStateOf(tree())
        val camera = camera()
        show { Tree(nodes.value, camera) }
        assertEquals(0, fans("skilltree.edge.fill").size, "nothing is filling to start with")

        nodes.value = tree(strike = 1)
        advance(120)

        val running = fans("skilltree.edge.fill")
        assertEquals(1, running.size, "the line power to strike should be filling")
        val head = running.first().points.maxOf { it.x }
        assertTrue(head > 81f && head < 199f, "part way from power to strike, was $head")

        advance(600)
        assertEquals(0, fans("skilltree.edge.fill").size, "and it stops when it arrives")
    }

    @Test
    fun `a respec part way through a fill takes the fill down with it`() {
        val nodes = mutableStateOf(tree())
        val camera = camera()
        show { Tree(nodes.value, camera) }

        nodes.value = tree(strike = 1)
        advance(100)
        assertEquals(1, fans("skilltree.edge.fill").size, "it should be filling")

        nodes.value = tree(strike = 0)
        frames(3)

        assertEquals(0, fans("skilltree.edge.fill").size, "a line must not be left halfway along for ever")
    }

    @Test
    fun `a tree opened on a half-spent character does not replay every unlock`() {
        open(tree(power = 1, strike = 1, guard = 1))
        advance(120)

        assertEquals(0, fans("skilltree.edge.fill").size, "the first look at a tree is not an unlock")
    }

    @Test
    fun `lines with no part in view are not drawn`() {
        val camera = open(state = camera(worldBounds = null))
        assertTrue(fans("skilltree.edge.locked").isNotEmpty(), "they are drawn while they are in view")

        camera.snapTo(Offset(9_000f, 9_000f))
        frames(2)

        assertEquals(0, fans("skilltree.edge.locked").size)
        assertEquals(0, fans("skilltree.edge.owned").size)
        assertEquals(0, fans("skilltree.edge.available").size)
    }

    // --- what a node says -------------------------------------------------------------------------------

    @Test
    fun `resting on a node says what the skill does`() {
        show { TooltipHost { Tree(tree(tooltips = true), camera()) } }

        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, strikeAt))
        advance(900)

        assertNotNull(text("Strike harder"), "the tooltip should be up by now")
    }

    @Test
    fun `and so does a pad landing on it with nothing hovered`() {
        show { TooltipHost { Tree(tree(tooltips = true), camera()) } }
        click(powerAt)

        padPress(GamepadButton.DpadRight)
        advance(900)

        assertNotNull(text("Strike harder"), "a pad never hovers, so focus has to be what shows it")
    }

    @Test
    fun `a node with more than one rank says how far through it is`() {
        open(tree(power = 1, strike = 1, guard = 1, fury = 1))
        frame()

        assertNotNull(text("1 / 3"), "a node with ranks should show them")
        assertNull(text("1 / 1"), "and a node with one rank should not")
    }

    // --- right to left ------------------------------------------------------------------------------------

    @Test
    fun `a tree is not mirrored for a right-to-left language`() {
        val camera = camera()
        show { ProvideLayoutDirection(LayoutDirection.Rtl) { Tree(tree(), camera) } }

        // A world is a picture rather than a line of text: a map is not mirrored for a language,
        // and neither is the tree drawn on one.
        assertEquals(Offset(80f, 90f), camera.worldToScreen(Offset(0f, 0f)), "power stays on the left")

        click(powerAt)
        padPress(GamepadButton.DpadRight)

        assertAt(strikeAt, focusedAt(), "right is still the way the line goes")
    }
}
