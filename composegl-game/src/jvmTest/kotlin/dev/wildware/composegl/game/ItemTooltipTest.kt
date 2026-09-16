package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyRouter
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.draggable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.graphics.NinePatch
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.skin.SkinOverride
import dev.wildware.composegl.ui.skin.StateStyle
import dev.wildware.composegl.ui.skin.Style
import dev.wildware.composegl.ui.widget.ProvideFonts
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The item card, driven the way a player drives it.
 *
 * The issue asks for five things, and each has a test here: differences shown as arrows and signs
 * rather than only as colour, a rarity edge and header out of the skin, an equipped card beside it
 * that stays on the screen, a layout that mirrors for Arabic, and a modifier key **or** a pad
 * button that turns the comparison on. Every one of them is driven through a real
 * `PointerRouter`, `KeyRouter` or `GamepadNavigator`, not by calling into the widget.
 */
class ItemTooltipTest {

    private val host = UiHost()
    private val bounds = Rect(0f, 0f, 600f, 400f)
    private val canvas = RecordingCanvas(bounds)
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val router = KeyRouter(focus)
    private val pad = GamepadNavigator(focus)

    private var wall = 0L

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

    private fun frames(count: Int = 2, millis: Long = 16L) = repeat(count) { frame(millis) }

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frames()
    }

    // --- the bag under test ---------------------------------------------------------------------

    /** One thing a player can pick up. A plain game class: the widget knows nothing about it. */
    private data class Gear(
        val name: String,
        val damage: Float,
        val speed: Float,
        val weight: Float,
        val rarity: Colour,
    )

    private val found = Gear("Ash Repeater", damage = 42f, speed = 1.8f, weight = 3f, Colour.rgb(0xB35BEF))

    /** The same speed, six less damage and a pound heavier: one better, one worse, one unchanged. */
    private val worn = Gear("Old Carbine", damage = 36f, speed = 1.8f, weight = 4f, Colour.rgb(0x5B8DEF))

    private var hovered by mutableStateOf<Gear?>(null)
    private var equipped by mutableStateOf<Gear?>(null)

    /** Held as state so a test can turn the screen round without building it again. */
    private var rightToLeft by mutableStateOf(false)

    private fun card(
        compare: ItemCompare = ItemCompare.Held,
        sideBySide: Boolean = true,
        anchor: Rect? = null,
        hint: String? = null,
        skin: Skin = Skin.Nothing,
    ) = show {
        ProvideLayoutDirection(if (rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            SkinOverride(skin) {
                ItemTooltip(
                    item = hovered,
                    compareWith = equipped,
                    anchor = anchor,
                    rarity = { it.rarity },
                    compare = compare,
                    sideBySide = sideBySide,
                    compareHint = hint,
                ) {
                    title(it.name)
                    subtitle("Main hand")
                    stat("Damage", it.damage)
                    stat("Speed", it.speed)
                    stat("Weight", it.weight, higherIsBetter = false)
                    flavour("Ash and iron.")
                }
            }
        }
    }

    // --- driving it -----------------------------------------------------------------------------

    private fun move(x: Float, y: Float) {
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(x, y)))
        frames()
    }

    private fun down(x: Float, y: Float) {
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(x, y), PointerButton.Primary))
        frames()
    }

    private fun leave(x: Float, y: Float) {
        pointer.onPointer(PointerEvent.Exit(PointerId.Mouse, Offset(x, y)))
        frames()
    }

    /** Whether the widget swallowed the key, which is half of what the tests below are about. */
    private fun key(type: KeyEventType): Boolean {
        val taken = router.onKey(KeyEvent(Key.Control, type))
        frames()
        return taken
    }

    private fun bumper(down: Boolean): Boolean {
        val event = if (down) {
            GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.LeftBumper)
        } else {
            GamepadEvent.ButtonUp(GamepadId.First, GamepadButton.LeftBumper)
        }
        val taken = pad.onGamepad(event)
        frames()
        return taken
    }

    // --- reading it off the canvas ----------------------------------------------------------------

    private fun texts() = canvas.calls.filterIsInstance<DrawCall.Text>()

    private fun text(starting: String) = texts().firstOrNull { it.text.startsWith(starting) }

    private fun edges(colour: Colour) = canvas.calls.filterIsInstance<DrawCall.Border>().filter { it.colour == colour }

    private fun borderColours() = canvas.calls.filterIsInstance<DrawCall.Border>().map { it.colour }

    /** The edge the skin gives a card whose game names no colour of its own. */
    private val skinRarity = (Skin.Default.resolve("itemtip.rarity").background as SkinDrawable.Fill).colour

    /** The edge the card frame already has, which is what is left when there is no rarity at all. */
    private val ownEdge = checkNotNull((Skin.Default.resolve("itemtip").background as SkinDrawable.Fill).border)

    private val better = Skin.Default.resolve("itemtip.better").textColour
    private val worse = Skin.Default.resolve("itemtip.worse").textColour
    private val same = Skin.Default.resolve("itemtip.same").textColour

    // --- what the issue asks for ------------------------------------------------------------------

    @Test
    fun `nothing is drawn while the player is not looking at anything`() {
        card()

        assertEquals(emptyList<DrawCall.Text>(), texts(), "a card with no item in it should cost nothing at all")
    }

    @Test
    fun `a better number is marked with a plus and an arrow as well as a colour`() {
        hovered = found
        equipped = worn
        card()
        move(120f, 120f)
        key(KeyEventType.Down)

        val delta = checkNotNull(text("+6")) { "no difference beside the damage: ${texts().map { it.text }}" }
        assertTrue(delta.text.endsWith("▲"), "an arrow is what a colour-blind player reads: '${delta.text}'")
        assertEquals(better, delta.colour, "and it is drawn in the skin's better colour")
    }

    @Test
    fun `a worse number is marked with a minus and a down arrow`() {
        hovered = worn
        equipped = found
        card()
        move(120f, 120f)
        key(KeyEventType.Down)

        val delta = checkNotNull(text("-6")) { "no difference beside the damage: ${texts().map { it.text }}" }
        assertTrue(delta.text.endsWith("▼"), "'${delta.text}' should point down")
        assertEquals(worse, delta.colour)
    }

    @Test
    fun `a lighter weapon is an improvement even though its number went down`() {
        hovered = found
        equipped = worn
        card()
        move(120f, 120f)
        key(KeyEventType.Down)

        // A pound lighter. The sign says the number fell, the arrow says that is good, and those
        // two saying different things is the whole reason the arrow is not simply the sign again.
        val delta = checkNotNull(text("-1")) { "no difference beside the weight: ${texts().map { it.text }}" }
        assertTrue(delta.text.endsWith("▲"), "lighter is better: '${delta.text}'")
        assertEquals(better, delta.colour)
    }

    @Test
    fun `a stat that did not change says so instead of writing plus nothing`() {
        hovered = found
        equipped = worn
        card()
        move(120f, 120f)
        key(KeyEventType.Down)

        assertNull(text("+0"), "a difference of nothing should not be written as a number")
        val unchanged = checkNotNull(text("=")) { "the unchanged speed said nothing at all" }
        assertEquals(same, unchanged.colour)
    }

    @Test
    fun `holding the compare key brings the equipped card up and letting go takes it away`() {
        hovered = found
        equipped = worn
        card()
        move(120f, 120f)

        assertNull(text("Equipped"), "the comparison should wait to be asked for")
        assertNull(text("+6"), "and so should the differences")

        key(KeyEventType.Down)
        assertNotNull(text("Equipped"), "holding the key should have brought the second card up")
        assertNotNull(text("Old Carbine"), "with the equipped item on it")

        key(KeyEventType.Up)
        assertNull(text("Equipped"), "and letting go should have taken it away again")
    }

    @Test
    fun `a pad button does the same thing as the key`() {
        hovered = found
        equipped = worn
        card()
        move(120f, 120f)

        bumper(down = true)
        assertNotNull(text("Equipped"), "nothing is ever hovered or typed on a console")

        bumper(down = false)
        assertNull(text("Equipped"))
    }

    @Test
    fun `a toggle stays on after the key comes back up`() {
        hovered = found
        equipped = worn
        card(compare = ItemCompare.Toggled)
        move(120f, 120f)

        key(KeyEventType.Down)
        key(KeyEventType.Up)
        assertNotNull(text("Equipped"), "a toggle is for a player who would rather not hold anything")

        key(KeyEventType.Down)
        key(KeyEventType.Up)
        assertNull(text("Equipped"), "and the second press turns it off")
    }

    @Test
    fun `an always-on comparison needs no key at all`() {
        hovered = found
        equipped = worn
        card(compare = ItemCompare.Always)
        move(120f, 120f)

        assertNotNull(text("Equipped"), "an inventory screen has nothing else going on behind it")
        assertNotNull(text("+6"))
    }

    @Test
    fun `there is nothing to compare when nothing is equipped`() {
        hovered = found
        equipped = null
        card(compare = ItemCompare.Always)
        move(120f, 120f)

        assertNotNull(text("Ash Repeater"), "the card itself is still drawn")
        assertNull(text("Equipped"), "but there is no second card")
        assertTrue(
            texts().none { "▲" in it.text || "▼" in it.text },
            "and nothing to draw an arrow about: ${texts().map { it.text }}",
        )
    }

    @Test
    fun `the arrows can be shown without a second card`() {
        hovered = found
        equipped = worn
        card(sideBySide = false)
        move(120f, 120f)
        key(KeyEventType.Down)

        assertNotNull(text("+6"), "the differences are the point and they stay")
        assertNull(text("Equipped"), "the second card is what a game with no room gives up")
    }

    @Test
    fun `the rarity colour is the card's title and its edge`() {
        hovered = found
        card()
        move(120f, 120f)

        val title = checkNotNull(text("Ash Repeater"))
        assertEquals(found.rarity, title.colour, "the header should be the tier's colour")
        assertTrue(edges(found.rarity).isNotEmpty(), "and so should the frame round it")
    }

    @Test
    fun `a game that names no colour gets the skin's rarity instead`() {
        hovered = found
        plainCard(Skin.Nothing)
        move(120f, 120f)

        // The other half of "from the skin": a game whose tiers live in its skin file rather than
        // in its code passes nothing, and `itemtip.rarity` is what edges the card.
        val title = checkNotNull(text("Ash Repeater"))
        assertEquals(skinRarity, title.colour, "the header should fall back to the skin's rarity")
        assertTrue(edges(skinRarity).isNotEmpty(), "and so should the frame: ${borderColours()}")
    }

    @Test
    fun `a skin with no rarity colour leaves the card edged the way it drew itself`() {
        val quiet = Skin(mapOf("itemtip.rarity" to Style(StateStyle(background = SkinDrawable.Fill(Colour.Transparent)))))
        hovered = found
        plainCard(quiet)
        move(120f, 120f)

        // A fully transparent fill is a skin saying "no rarity edge", not a skin asking for an
        // invisible one. Recolouring the frame with it would rub out the edge the card already had.
        val title = checkNotNull(text("Ash Repeater"))
        assertNotEquals(Colour.Transparent, title.colour, "the header was written in nothing at all")
        assertTrue(edges(Colour.Transparent).isEmpty(), "the frame was edged in nothing at all")
        assertTrue(edges(ownEdge).isNotEmpty(), "the card's own edge should have been left alone: ${borderColours()}")
    }

    @Test
    fun `a card drawn from art keeps its art whatever the rarity is`() {
        val art = Skin(mapOf("itemtip" to Style(StateStyle(background = SkinDrawable.Patch(NinePatch(Art, Padding.None))))))
        hovered = found
        card(skin = art)
        move(120f, 120f)

        // There is no colour in a nine-patch to change, and painting a line round somebody's frame
        // art is worse than leaving it: a skin with real art keeps it, and the title still says
        // which tier this is.
        assertEquals(found.rarity, checkNotNull(text("Ash Repeater")).colour, "the header is the tier's colour")
        assertTrue(edges(found.rarity).isEmpty(), "the art was re-bordered: ${borderColours()}")
    }

    /** The same card with no `rarity` of its own, under [overrides]. The skin's fallback is the subject. */
    private fun plainCard(overrides: Skin) = show {
        SkinOverride(overrides) {
            ItemTooltip(item = hovered, rarity = { null }) {
                title(it.name)
                stat("Damage", it.damage)
            }
        }
    }

    /** A texture that is never sampled: what a `Patch` needs to exist, and nothing more. */
    private object Art : TextureHandle {
        override val width = 8
        override val height = 8
    }

    // --- staying on the screen --------------------------------------------------------------------

    @Test
    fun `the card sits beside the pointer`() {
        hovered = found
        card()
        move(120f, 120f)

        val title = checkNotNull(text("Ash Repeater"))
        assertTrue(title.at.x > 120f, "it should be to the end side of the pointer: ${title.at}")
        assertTrue(title.at.y > 120f, "and below it: ${title.at}")
    }

    @Test
    fun `a card at the right hand edge is drawn back on screen`() {
        hovered = found
        card()
        move(580f, 100f)

        assertTrue(texts().isNotEmpty(), "the card should still be there")
        assertTrue(texts().all { it.at.x >= 0f }, "part of it is off the left: ${texts().minOf { it.at.x }}")
        val title = checkNotNull(text("Ash Repeater"))
        assertTrue(title.at.x < 580f, "it should have flipped to the other side of the pointer: ${title.at}")
    }

    @Test
    fun `a card at the bottom of the screen is drawn above the pointer`() {
        hovered = found
        card()
        move(120f, 380f)

        val title = checkNotNull(text("Ash Repeater"))
        assertTrue(title.at.y < 380f, "it hangs off the bottom: ${title.at}")
        assertTrue(texts().all { it.at.y <= 400f }, "and none of it should be past the edge")
    }

    @Test
    fun `the card hangs under the slot it was given rather than under the pointer`() {
        hovered = found
        // What a pad player gets: focus is on a bag slot and the pointer has never been anywhere.
        card(anchor = Rect(40f, 40f, 90f, 90f))

        val title = checkNotNull(text("Ash Repeater"))
        assertTrue(title.at.y > 90f, "it should hang under the slot: ${title.at}")
    }

    @Test
    fun `a layer that is not at the root's origin still puts the card under the slot`() {
        hovered = found
        // The slot's own bounds, as `onPlaced { node -> node.boundsInRoot }` reports them. The layer
        // sits a hundred to the right and fifty down from the root, the way one inside a padded
        // panel or one side of a split screen does, and the two are different numbers.
        val slot = Rect(300f, 200f, 340f, 240f)
        show {
            Box(Modifier.fillMaxSize().padding(left = 100f, top = 50f)) {
                ItemTooltip(item = hovered, anchor = slot, rarity = { it.rarity }) {
                    title(it.name)
                    stat("Damage", it.damage)
                }
            }
        }
        // The layer learns where it is when it is first laid out, so the second pass is the one
        // that places the card — which is the pass a player sees, since nothing moves in between.
        frames()

        val title = checkNotNull(text("Ash Repeater"))
        assertTrue(title.at.y > 240f && title.at.y < 290f, "the card should hang just under the slot: ${title.at}")
        assertTrue(title.at.x > 190f && title.at.x < 270f, "and be centred on it, not on the layer: ${title.at}")
    }

    @Test
    fun `a slot at the bottom of the screen puts its card above itself`() {
        hovered = found
        card(anchor = Rect(40f, 350f, 90f, 390f))

        val title = checkNotNull(text("Ash Repeater"))
        assertTrue(title.at.y < 350f, "it should have gone above the slot: ${title.at}")
    }

    // --- Arabic -----------------------------------------------------------------------------------

    @Test
    fun `a right-to-left screen puts the card on the other side of the pointer`() {
        hovered = found
        rightToLeft = true
        card()
        move(400f, 120f)

        val title = checkNotNull(text("Ash Repeater"))
        assertTrue(title.at.x < 400f, "a player reading right to left looks to the left: ${title.at}")
    }

    @Test
    fun `and the equipped card mirrors with it`() {
        hovered = found
        equipped = worn
        card(compare = ItemCompare.Always)
        move(160f, 120f)
        val ltr = checkNotNull(text("Ash Repeater")).at.x to checkNotNull(text("Old Carbine")).at.x
        assertTrue(ltr.first < ltr.second, "left to right the new item comes first: $ltr")

        rightToLeft = true
        move(400f, 120f)
        val rtl = checkNotNull(text("Ash Repeater")).at.x to checkNotNull(text("Old Carbine")).at.x

        assertTrue(rtl.first > rtl.second, "right to left it should be the other way round: $rtl")
    }

    // --- the rest of it ---------------------------------------------------------------------------

    @Test
    fun `the hint only appears while there is something to compare against`() {
        hovered = found
        equipped = null
        card(hint = "Hold Ctrl to compare")
        move(120f, 120f)
        assertNull(text("Hold Ctrl"), "telling a player about a comparison they cannot make")

        equipped = worn
        frames()
        assertNotNull(text("Hold Ctrl"), "and now there is one")
    }

    @Test
    fun `the equipped card is written in the same styles as the new one`() {
        hovered = found
        equipped = worn
        card(compare = ItemCompare.Always)
        move(160f, 120f)

        val mine = checkNotNull(texts().firstOrNull { it.text == "Damage" })
        val theirs = checkNotNull(texts().lastOrNull { it.text == "Damage" })

        assertNotEquals(mine.at.x, theirs.at.x, "there should be two cards")
        assertEquals(mine.colour, theirs.colour, "or the two columns of numbers cannot be read against each other")
    }

    @Test
    fun `the card lets the pointer through to the bag underneath it`() {
        val seen = mutableListOf<Offset>()
        val watcher = PointerHandler { event ->
            if (event is PointerEvent.Move) seen += event.position
            false
        }

        hovered = found
        show {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().onPointer(watcher))
                ItemTooltip(item = hovered, rarity = { it.rarity }) {
                    title(it.name)
                    stat("Damage", it.damage)
                }
            }
        }

        move(120f, 120f)
        assertNotNull(text("Ash Repeater"), "the card should be up")
        // Straight onto where the card is drawn. The bag below is what decides what is hovered, so
        // a card that ate this move would take its own item away and put it back forever.
        seen.clear()
        move(150f, 150f)

        assertTrue(seen.isNotEmpty(), "the slots under the card would never hear about the pointer again")
    }

    @Test
    fun `the bag underneath the card is still hovered`() {
        val slot = InteractionState()

        hovered = found
        show {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(56f).interaction(slot))
                ItemTooltip(item = hovered, rarity = { it.rarity }) {
                    title(it.name)
                    stat("Damage", it.damage)
                }
            }
        }

        // Straight onto the slot, with the card's own layer over the whole screen. Hover is how a
        // game works out what to put in `item`, so a layer that took it would take its own card away.
        move(20f, 20f)

        assertNotNull(text("Ash Repeater"), "the card should be up")
        assertTrue(slot.isHovered, "the layer stood in front of the slot and the mouse path is dead")
    }

    @Test
    fun `the card keeps following the mouse while a bag slot holds the press`() {
        hovered = found
        show {
            Box(Modifier.fillMaxSize()) {
                // A real bag rather than a hover-only one: a slot a player can pick an item out of
                // captures the press, and every move after it belongs to that gesture. The card is
                // not part of the gesture and still has to keep up with the hand.
                Box(Modifier.size(56f).draggable { })
                ItemTooltip(item = hovered, rarity = { it.rarity }) {
                    title(it.name)
                    stat("Damage", it.damage)
                }
            }
        }

        move(20f, 20f)
        val before = checkNotNull(text("Ash Repeater")) { "the card should be up" }.at
        down(20f, 20f)
        move(200f, 200f)

        val during = checkNotNull(text("Ash Repeater")) { "the card went away mid-drag" }.at
        assertNotEquals(before, during, "the card froze where the player pressed and the hand went on")
        assertTrue(during.x > 200f, "it should still be beside the pointer: $during")
    }

    @Test
    fun `a mouse that leaves the window stops the card hanging where it went out`() {
        hovered = found
        card()
        move(120f, 120f)

        val beside = checkNotNull(text("Ash Repeater")) { "the card should be up" }.at
        leave(120f, 0f)

        // Nothing is pointing at anything any more, so there is nowhere for the card to hang off
        // and it goes back to the one place that is never a surprise.
        val after = checkNotNull(text("Ash Repeater")).at
        assertNotEquals(beside, after, "the card is still hanging off a mouse that has gone")
    }

    @Test
    fun `letting the compare key go away from an item takes the comparison down`() {
        hovered = found
        equipped = worn
        card()
        move(120f, 120f)
        key(KeyEventType.Down)
        assertNotNull(text("Equipped"))

        // The pointer slides off the item with the key still held. There is nothing to compare now
        // — which must not mean the release is ignored and the comparison stuck on forever.
        hovered = null
        equipped = null
        frames()
        key(KeyEventType.Up)

        hovered = worn
        equipped = found
        frames()
        assertNull(text("Equipped"), "the comparison is up with nothing held")
        assertNull(text("+6"), "and so are the differences")
    }

    @Test
    fun `letting the pad button go away from an item does the same`() {
        hovered = found
        equipped = worn
        card()
        bumper(down = true)
        assertNotNull(text("Equipped"))

        hovered = null
        equipped = null
        frames()
        bumper(down = false)

        hovered = worn
        equipped = found
        frames()
        assertNull(text("Equipped"), "the bumper came up and nothing heard it")
    }

    @Test
    fun `a player already holding the key sees the arrows on the next item`() {
        hovered = null
        equipped = null
        card()

        // Held down with nothing on the screen at all: the next thing hovered has to come up
        // compared, rather than asking the player to let go and press it again.
        key(KeyEventType.Down)
        hovered = found
        equipped = worn
        frames()

        assertNotNull(text("Equipped"), "the second card waited for a key that was already down")
        assertNotNull(text("+6"), "and so did the differences")
    }

    @Test
    fun `the compare key is the game's own while no card is up`() {
        hovered = null
        equipped = worn
        card()

        assertFalse(key(KeyEventType.Down), "the key is the game's while nothing is being looked at")
        assertFalse(bumper(down = true), "and so is the bumper")

        hovered = found
        frames()
        assertTrue(key(KeyEventType.Down), "and the card's while one is up")
        assertTrue(bumper(down = true), "and so is the bumper")
    }

    @Test
    fun `the release goes the same way as the press that started it`() {
        hovered = null
        equipped = worn
        card()

        assertFalse(key(KeyEventType.Down), "the key was the game's when it went down")
        hovered = found
        frames()
        assertNotNull(text("Equipped"), "and the card that came up next sees it held")

        // Half a key is worse than none: whoever was handed the press has to be handed the release,
        // or a game is left holding a key that never comes up.
        assertFalse(key(KeyEventType.Up), "so the release is the game's too")
        assertNull(text("Equipped"), "even though letting go still takes the comparison down")
    }

    @Test
    fun `a difference too small to write is not written as plus nothing`() {
        // Two hundredths of a second apart, which the card writes to one decimal place — so the
        // difference is real and there is nothing to show for it.
        hovered = found.copy(speed = 1.82f)
        equipped = found
        card(compare = ItemCompare.Always)
        move(120f, 120f)

        assertNull(text("+0"), "a difference that comes out as zero should say nothing: ${texts().map { it.text }}")
        assertEquals(3, texts().count { it.text == "=" }, "all three stats are unchanged as the card writes them")
    }

    @Test
    fun `moving to another item redraws the card for that one`() {
        hovered = found
        equipped = worn
        card()
        move(120f, 120f)
        assertNotNull(text("Ash Repeater"))

        hovered = worn
        frames()

        assertNull(text("Ash Repeater"), "the old card is still up")
        assertNotNull(text("Old Carbine"))
    }
}
