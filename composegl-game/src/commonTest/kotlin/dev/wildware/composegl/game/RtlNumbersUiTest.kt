package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.DragAndDropHost
import dev.wildware.composegl.ui.widget.PanZoomState
import dev.wildware.composegl.ui.widget.PopupHost
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every number a widget writes for itself read in Hebrew as well as in English.
 *
 * A player in Hebrew gets a screen that reads from the right, and the Unicode bidirectional
 * algorithm then decides where each piece of a line goes. Digits are always drawn left to right,
 * but a space or a slash *between* two numbers is a neutral: it takes the direction of the screen
 * around it, which splits the line into two number runs and draws the second one first. That is how
 * the objective tracker's "3 / 5" came out as "5 / 3" — the right algorithm, and a player reading
 * that they have killed five of three wolves.
 *
 * So the rule every shipped default is held to here: **a number reads the same in both
 * directions**. The words around it mirror, as words in Hebrew do; the numbers themselves do not
 * move and do not come apart. [numbersIn] is that rule written down — every run of digits, in the
 * order a player's eye meets it — and each test simply asks for the same answer twice.
 *
 * The fix is a format with no spaces in it rather than an invisible mark. This toolkit's bidi
 * treats isolate controls as invisible instead of obeying them, so `U+2066` would do nothing at
 * all; and an LRM is a real character that every backend's font atlas would then have to be told
 * to bake, which is the same hole a missing glyph leaves. "3/5" needs neither: a single slash
 * between two digits is joined onto the number by the algorithm itself, so the whole thing is one
 * number and there is nothing left to reorder.
 *
 * Two shipped defaults were read and deliberately left alone, and it is worth writing down why:
 *
 * - **Timers and units.** Nothing this toolkit ships writes `mm:ss`, and no distance carries a
 *   unit: the compass writes "128" and the game writes "m" after it if it wants one. A colon
 *   between two numbers would need the same treatment as the slash, so the day a timer is shipped
 *   it belongs in this file. Chat carries no timestamp either.
 * - **A range in a developer's dump** — `Constraints.toString` and the node dump both write
 *   "10..20", and *two* full stops cannot be joined onto a number the way one can, so a range
 *   really does read backwards on a mirrored screen. Left as it is because these are lines written
 *   for whoever is fixing a layout, printed into a log and read in the inspector — not something a
 *   player is ever shown. Making them bidi-safe would mean an invisible mark in every dump and
 *   every golden file, to fix a line no player reads.
 */
class RtlNumbersUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(direction: LayoutDirection, content: @Composable () -> Unit): UiTest =
        uiTest(Size(600f, 420f)) { ProvideLayoutDirection(direction) { content() } }.also { opened += it }

    /**
     * Every number in what [tag] draws, in the order it is drawn from the left.
     *
     * Read off the drawing rather than off the widget, so it is the order a player's eye meets them
     * in: the pieces of text the canvas was handed, in the order they were handed over, joined back
     * into one line and swept for digits. A decimal point or a slash inside a number counts as part
     * of it, which is exactly the question — "3/5" is one number, "5 / 3" is two in the wrong order.
     */
    private fun UiTest.numbersIn(tag: String): List<String> =
        Number.findAll(texts(tag).joinToString("")).map { it.value }.toList()

    /** Composes [content] twice and fails unless the numbers in it read the same way round. */
    private fun readsTheSameBothWays(tag: String, content: @Composable () -> Unit) {
        val english = open(LayoutDirection.Ltr, content).numbersIn(tag)
        val hebrew = open(LayoutDirection.Rtl, content).numbersIn(tag)

        assertTrue(english.isNotEmpty(), "this widget drew no numbers at all, so it is proving nothing")
        assertEquals(english, hebrew, "a player reading from the right is reading different numbers")
    }

    // ------------------------------------------------------------------ what was read backwards

    @Test
    fun `the objective counter reads three of five in hebrew`() {
        val tracker: @Composable () -> Unit = {
            ObjectiveTracker(
                quests = listOf(QuestName),
                modifier = Modifier.testTag("objectives"),
                keyOf = { it },
                clock = Clock.Ui,
            ) { name ->
                title(name)
                step(StepName, progress = ObjectiveProgress(3, 5))
            }
        }

        readsTheSameBothWays("objectives", tracker)
        assertTrue(
            "3/5" in open(LayoutDirection.Rtl, tracker).texts("objectives"),
            "the counter is one number handed over whole, not a three and a five put back to front",
        )
    }

    @Test
    fun `a skill node's ranks read the right way round in hebrew`() {
        readsTheSameBothWays("skills") {
            SkillTree(
                nodes = listOf(SkillNode("fury", 0f, 0f, ranks = 3, rank = 2, label = "U")),
                edges = emptyList(),
                modifier = Modifier.fillMaxSize().testTag("skills"),
                state = PanZoomState(1f, 0.5f, 2f, Rect(-200f, -140f, 200f, 140f), Offset(0f, 0f)),
                onActivate = {},
            )
        }
    }

    // ------------------------------------------------------- what was already right, and why it is

    @Test
    fun `a distance under a compass pin is one number and stays put`() {
        // A number on its own has nothing to be reordered against, whichever way the screen reads.
        readsTheSameBothWays("compass") {
            CompassBar(
                heading = 0f,
                modifier = Modifier.width(400f).height(64f).testTag("compass"),
                live = false,
            ) {
                pin(bearing = 0f, distance = 128.4f)
            }
        }
    }

    @Test
    fun `a stack count in the bag is one number and stays put`() {
        readsTheSameBothWays("bag") {
            val bag = InventoryState(
                2,
                2,
                listOf(InventoryItem("arrows", kind = "arrow", count = 15, stackLimit = 20)),
            ) { Any() }
            PopupHost {
                DragAndDropHost {
                    Box(Modifier.fillMaxSize()) {
                        InventoryGrid(
                            state = bag,
                            modifier = Modifier.testTag("bag").size(88f, 88f),
                            cellSize = 40f,
                            spacing = 4f,
                            slot = { Text(it.kind.toString()) },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `charges on a hotbar slot are one number and stay put`() {
        readsTheSameBothWays("hotbar") {
            Hotbar(
                slots = listOf(HotbarSlot(label = "Q", prompt = "1", charges = 3)),
                modifier = Modifier.testTag("hotbar"),
                selected = -1,
                onUse = {},
                onSelect = {},
                clock = Clock.Ui,
            )
        }
    }

    @Test
    fun `an item card's stats and differences keep the numbers they belong to`() {
        // The stat lines are where the punctuation is thickest: a decimal point inside a number, a
        // sign in front of one, and an arrow after it. Only the first of those is *part* of the
        // number — a full stop between two digits is joined onto them by the algorithm, the same
        // way the counter's slash now is, so "3.5" and the cooldown's "0.4" are one run each.
        //
        // The sign and the arrow are not part of the number and do mirror: a Hebrew player reads
        // "▲ 6+" where an English one reads "+6 ▲". That is a line of a mirrored language mirroring,
        // not a number read backwards — the six is still a six, and ▲ still points up, because
        // Unicode does not mirror an arrow the way it mirrors a bracket. Left alone deliberately.
        readsTheSameBothWays("card") {
            ItemTooltip(
                item = Repeater,
                modifier = Modifier.fillMaxSize().testTag("card"),
                compareWith = Carbine,
                anchor = Rect(100f, 100f, 140f, 140f),
                rarity = { Colour.rgb(0xB35BEF) },
                compare = ItemCompare.Always,
            ) {
                title(it.name)
                stat("Damage", it.damage)
                stat("Weight", it.weight, higherIsBetter = false)
            }
        }
    }

    @Test
    fun `the folded row counts the quests behind it the same in both directions`() {
        // "+2 more" is one number in a phrase of words. The phrase mirrors and the plus goes with
        // it; nobody reads a different count.
        readsTheSameBothWays("folded") {
            ObjectiveTracker(
                quests = listOf(QuestName, "Two", "Three", "Four"),
                modifier = Modifier.testTag("folded"),
                keyOf = { it },
                maxVisible = 2,
                clock = Clock.Ui,
            ) { name -> title(name) }
        }
    }

    private class Gear(val name: String, val damage: Float, val weight: Float)

    private companion object {

        /** A run of digits with the separators that belong *inside* a number, and nothing else. */
        val Number = Regex("""\d+(?:[./]\d+)*""")

        /** Hebrew, so the tracker is read the way a Hebrew player really gets it. */
        const val QuestName = "כבוש את המגדל"
        const val StepName = "השתק את השומרים"

        val Repeater = Gear("Ash Repeater", damage = 42f, weight = 3.5f)
        val Carbine = Gear("Old Carbine", damage = 36f, weight = 4f)
    }
}
