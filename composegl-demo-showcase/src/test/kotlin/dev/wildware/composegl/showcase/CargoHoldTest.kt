package dev.wildware.composegl.showcase

import dev.wildware.composegl.showcase.ui.CargoHold
import dev.wildware.composegl.showcase.ui.CargoTag
import dev.wildware.composegl.showcase.ui.CargoWidth
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.DragAndDropHost
import dev.wildware.composegl.ui.widget.PopupHost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The hold is a panel on the right, not a sheet over the screen.
 *
 * Everything inside it wants to fill the width it is given — a row that spreads its labels, two
 * grids — so a panel that did not say how wide it is would be handed the whole screen and paint the
 * HUD out behind it. Worth a test rather than a screenshot, because the panel is 75% opaque and a
 * screenshot of it covering everything still looks like a screenshot of something.
 */
class CargoHoldTest {

    @Test
    fun `the hold is a panel down the right-hand side rather than the whole screen`() {
        val state = ShowcaseState()
        uiTest {
            PopupHost {
                DragAndDropHost {
                    Box(Modifier.fillMaxSize()) { CargoHold(state) }
                }
            }
        }.use { ui ->
            val panel = ui.node(CargoTag).boundsInRoot

            assertEquals(CargoWidth, panel.width, 0.5f, "as wide as the two grids and its own padding")
            assertTrue(panel.height < ui.size.height / 2f, "and no taller than what is in it:\n" + ui.dump())
            assertTrue(panel.left > ui.size.width / 2f, "on the right-hand half of the screen")
        }
    }

    @Test
    fun `both grids fit inside the hold with nothing squeezed`() {
        val state = ShowcaseState()
        uiTest {
            PopupHost {
                DragAndDropHost {
                    Box(Modifier.fillMaxSize()) { CargoHold(state) }
                }
            }
        }.use { ui ->
            val panel = ui.node(CargoTag).boundsInRoot
            val grids = mutableListOf<Rect>()
            ui.root.forEach { if (it.name == "inventory") grids += it.boundsInRoot }

            assertEquals(2, grids.size, "two grids:\n" + ui.dump())
            grids.forEach {
                assertEquals(148f, it.width, 0.5f, "four 34-pixel squares 4 apart:\n" + ui.dump())
                assertTrue(it.left >= panel.left && it.right <= panel.right, "inside the panel:\n" + ui.dump())
            }
            val first = grids.minBy { it.left }
            val second = grids.maxBy { it.left }
            assertEquals(16f, second.left - first.right, 0.5f, "the locker sits beside the hold")
        }
    }
}
