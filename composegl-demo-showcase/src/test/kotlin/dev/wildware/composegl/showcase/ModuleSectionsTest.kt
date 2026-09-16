package dev.wildware.composegl.showcase

import androidx.compose.runtime.Composable
import dev.wildware.composegl.debug.DebugWindowHost
import dev.wildware.composegl.debug.rememberDebugWindowsState
import dev.wildware.composegl.debug.rememberDevConsole
import dev.wildware.composegl.showcase.ui.GameCompassTag
import dev.wildware.composegl.showcase.ui.GroupTagPrefix
import dev.wildware.composegl.showcase.ui.ModuleSections
import dev.wildware.composegl.showcase.ui.SectionScrollTag
import dev.wildware.composegl.showcase.ui.SectionTag
import dev.wildware.composegl.showcase.ui.UiContextTag
import dev.wildware.composegl.showcase.ui.sectionTabTag
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.DragAndDropHost
import dev.wildware.composegl.ui.widget.TooltipHost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The three module sections, driven the way a person drives them.
 *
 * The sections are the answer to "what is actually in composegl-game", so what matters about them
 * is not what they look like — it is that every group in each of them composes, is laid out, and
 * survives a mouse, a keyboard and a pad being pointed at it, mirrored as well as the usual way
 * round. A widget that throws when it is measured at zero width, or when a pad button reaches it
 * with nothing focused, is the failure this catches, and it catches it for groups added later
 * without anybody having to remember to come back here.
 */
class ModuleSectionsTest {

    /** The showcase's own three drones, since a section reads them. */
    private fun state(open: Module) = ShowcaseState().apply {
        listOf("RAVEN-2", "KITE-7", "MOTH-1").forEachIndexed { index, callsign ->
            targets.add(
                TargetReadout(callsign).apply {
                    integrity = 1f - index * 0.2f
                    distance = 40f + index * 30f
                    mapX = index * 2f - 2f
                    mapY = index * 3f - 3f
                },
            )
        }
        // A still fight, which is the switch the tuning window already had. Widgets that follow a
        // moving world — the compass strip — redraw every frame while it is on, and a screen that
        // redraws every frame is a screen that never settles, for a test or for a screenshot.
        holdFire = true
        section = open
    }

    /** The section under the same hosts `ShowcaseUi` puts it under, in the direction asked for. */
    private fun section(state: ShowcaseState, direction: LayoutDirection): UiTest = uiTest {
        ProvideLayoutDirection(direction) {
            Hosts {
                ModuleSections(state, FrameBudget(), rememberDebugWindowsState(), rememberDevConsole {}, null)
            }
        }
    }

    @Composable
    private fun Hosts(content: @Composable () -> Unit) {
        DebugWindowHost(state = rememberDebugWindowsState()) {
            DragAndDropHost {
                TooltipHost {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }

    @Test
    fun `every section is a panel down the left-hand side, both ways round`() {
        Module.entries.forEach { module ->
            Directions.forEach { direction ->
                section(state(module), direction).use { ui ->
                    val panel = ui.node(SectionTag).boundsInRoot

                    assertTrue(panel.width > 300f, "$module in $direction is a page, not a strip:\n" + ui.dump())
                    assertTrue(panel.height > 300f, "$module in $direction has room in it:\n" + ui.dump())
                    assertTrue(
                        panel.left >= 0f && panel.right <= ui.size.width,
                        "$module in $direction stays on the screen:\n" + ui.dump(),
                    )
                }
            }
        }
    }

    @Test
    fun `every group in every section opens and lays out, both ways round`() {
        Module.entries.forEach { module ->
            Directions.forEach { direction ->
                section(state(module), direction).use { ui ->
                    // Every header is in the tree from the start, open or not, so this is the whole
                    // list of what the section has in it.
                    val all = ui.groups().mapNotNull { it.testTag }.toSet()
                    assertTrue(all.size >= 5, "$module in $direction has ${all.size} groups, which is too few")

                    val opened = ui.openEveryGroup()

                    assertEquals(all, opened, "$module in $direction has groups nothing could reach")
                    ui.groups().forEach { group ->
                        val box = group.boundsInRoot
                        assertTrue(
                            box.width > 0f && box.height > 0f,
                            "a group header in $module/$direction was measured to nothing:\n" + ui.dump(),
                        )
                    }
                    // Nothing inside the column is wider than the column. A widget told how wide to
                    // be — the chat box, the objective tracker — that disagrees with the panel is
                    // words running off the edge, which a screenshot shows and nothing else does.
                    val column = ui.node(SectionScrollTag).boundsInRoot
                    ui.node(SectionScrollTag).forEach { inside ->
                        val box = inside.boundsInRoot
                        assertTrue(
                            box.isEmpty || box.width <= column.width + 1f,
                            "${inside.name} in $module/$direction is ${box.width} wide inside a " +
                                "${column.width} column:\n" + ui.dump(),
                        )
                    }

                    // A whole frame, drawn: a widget that throws while it is painted rather than
                    // while it is measured is a failure a layout assertion never sees.
                    ui.render()
                }
            }
        }
    }

    @Test
    fun `every section takes a mouse, a keyboard and a pad without throwing, both ways round`() {
        Module.entries.forEach { module ->
            Directions.forEach { direction ->
                section(state(module), direction).use { ui ->
                    ui.openEveryGroup()

                    // The mouse: over the section, a click on the tabs, a right-click, a wheel.
                    ui.moveTo(ui.node(SectionTag).boundsInRoot.centre)
                    ui.click(sectionTabTag(module))
                    ui.click(ui.node(SectionTag).boundsInRoot.centre, PointerButton.Secondary)
                    ui.scroll(SectionScrollTag, Offset(0f, 120f))
                    ui.scroll(SectionScrollTag, Offset(0f, -120f))

                    // The keyboard: walk focus through it and press whatever it lands on.
                    repeat(24) { ui.key(Key.Tab) }
                    ui.key(Key.Enter)
                    ui.key(Key.Left)
                    ui.key(Key.Right)
                    ui.key(Key.Escape)

                    // The pad: a stick push, the d-pad, South to press, East to go back.
                    ui.stick(0.9f, 0.4f)
                    ui.stick(0f, 0f)
                    listOf(
                        GamepadButton.DpadDown,
                        GamepadButton.DpadRight,
                        GamepadButton.South,
                        GamepadButton.North,
                        GamepadButton.East,
                    ).forEach { ui.pad(it) }

                    ui.render()
                }
            }
        }
    }

    @Test
    fun `the tabs along the top move between the modules`() {
        val state = state(Module.Ui)
        section(state, LayoutDirection.Ltr).use { ui ->
            ui.click(sectionTabTag(Module.Game))
            assertEquals(Module.Game, state.section, "the game tab opens the game section")
            assertNotNull(ui.root.findOrNull(GameCompassTag), "and the game section's compass is on it:\n" + ui.dump())

            ui.click(sectionTabTag(Module.Debug))
            assertEquals(Module.Debug, state.section)
        }
    }

    @Test
    fun `a section writes to the showcase's own state rather than to state of its own`() {
        val state = state(Module.Ui)
        section(state, LayoutDirection.Ltr).use { ui ->
            // The right-click menu on the composegl-ui section's own box: the items it offers are
            // the showcase's ammunition and the showcase's lock.
            state.ammo = 3
            ui.click(UiContextTag, PointerButton.Secondary)
            assertTrue(ui.texts(UiContextTag).any { "3" in it }, "the box reads the game's own ammo")
        }
    }

    @Test
    fun `closing a section hands the screen back`() {
        val state = state(Module.Game)
        section(state, LayoutDirection.Ltr).use { ui ->
            assertNotNull(ui.root.findOrNull(SectionTag))

            state.section = null
            ui.settle()

            ui.assertDoesNotExist(SectionTag)
        }
    }

    // ----------------------------------------------------------------------------------------

    /** Every group header on the screen now, in the order they are laid out. */
    private fun UiTest.groups(): List<UiNode> {
        val found = mutableListOf<UiNode>()
        root.forEach { if (it.testTag?.startsWith(GroupTagPrefix) == true) found += it }
        return found
    }

    /**
     * Opens every group in the section, scrolling down to reach the ones past the bottom.
     *
     * By tag prefix rather than by name, so a group added to a section tomorrow is driven by this
     * test without anybody editing it. Returns the tags it opened.
     */
    private fun UiTest.openEveryGroup(): Set<String> {
        val opened = mutableSetOf<String>()
        // A ceiling rather than a count: each turn opens whatever is on screen and scrolls on, and
        // a section is a few screens long at most.
        repeat(MaxScrolls) {
            var clickedSomething = false
            groups().forEach { group ->
                val tag = group.testTag ?: return@forEach
                if (tag in opened) return@forEach
                val box = group.boundsInRoot
                if (box.isEmpty || box.top < 0f || box.bottom > size.height) return@forEach
                opened += tag
                click(box.centre)
                clickedSomething = true
            }
            if (!clickedSomething) scroll(SectionScrollTag, Offset(0f, ScrollStep))
        }
        return opened
    }

    private companion object {
        val Directions = listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)

        /** How far one turn of the wheel carries the section's column. */
        const val ScrollStep = 200f

        /** How many turns of opening-then-scrolling a section is given before the test gives up. */
        const val MaxScrolls = 40
    }
}
