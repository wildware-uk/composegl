package dev.wildware.composegl.showcase.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.debug.DebugWindowsState
import dev.wildware.composegl.showcase.Exhibit
import dev.wildware.composegl.showcase.Module
import dev.wildware.composegl.showcase.ShowcaseState
import dev.wildware.composegl.showcase.WorldViews
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.ColourPickerButton
import dev.wildware.composegl.ui.widget.IndeterminateBar
import dev.wildware.composegl.ui.widget.MenuBar
import dev.wildware.composegl.ui.widget.PanZoomCanvas
import dev.wildware.composegl.ui.widget.PanZoomReset
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Spinner
import dev.wildware.composegl.ui.widget.Splitter
import dev.wildware.composegl.ui.widget.Table
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TreeView
import dev.wildware.composegl.ui.widget.contextMenu
import dev.wildware.composegl.ui.widget.rememberPanZoomState
import dev.wildware.composegl.ui.widget.rememberTableState
import dev.wildware.composegl.ui.widget.rememberTreeState
import dev.wildware.composegl.ui.widget.worldPosition

/** So a test can reach the thing with a menu hanging off it without knowing where it is. */
internal const val UiContextTag = "showcase.ui.context"

/** The pan and zoom plane in this section, for the test that drags it. */
internal const val UiPlaneTag = "showcase.ui.plane"

/**
 * What is in **composegl-ui**: the toolkit's own widgets, each doing something to the fight behind.
 *
 * Nothing on this page is a sample. The menu bar changes the weapon the wheel equipped, the tree
 * and the table both pick the drone the HUD's reticle is on, the slider is the weapon's heat, and
 * the two swatches are the colours the reticle and the hit sparks are drawn in. The scene views are
 * the fight itself, seen from a camera you steer, from behind a drone, and one drone at a time. Close the section
 * and every one of those changes is still there, because the section never had state of its own.
 */
@Composable
internal fun UiSection(state: ShowcaseState, windows: DebugWindowsState, world: WorldViews) {
    Group(
        "Menu bar and context menus",
        "A bar of its own, and a right-click on anything at all.",
        open = true,
    ) {
        // A second menu bar, laid out inline rather than pinned to the top of the screen — which is
        // the whole point of it taking a modifier. Its menus drop through the same popup host the
        // showcase's own bar uses.
        MenuBar(Modifier.fillMaxWidth()) {
            Menu("&Weapon") {
                UiGuns.forEach { gun ->
                    RadioItem(gun, selected = state.weapon == gun) { state.weapon = gun }
                }
            }
            Menu("&Heat") {
                listOf("Cold" to 0f, "Warm" to 0.5f, "Overheating" to 0.95f).forEach { (name, heat) ->
                    Item(name) { state.heat = heat }
                }
                Separator()
                CheckItem("&Hold fire", checked = state.holdFire) { state.holdFire = it }
            }
        }

        // A right-click, a long press, Shift+F10 or the pad's North opens this one. The modifier
        // goes on an ordinary Box, which is the claim: anything can have a menu.
        Box(
            Modifier.testTag(UiContextTag)
                .fillMaxWidth()
                .height(46f)
                .background(Colour.argb(0x300A1018), corner = 4f)
                .focusable()
                .contextMenu {
                    Item("&Full magazine") { state.ammo = 148 }
                    Item("&Half a magazine") { state.ammo = 74 }
                    Separator()
                    Item("&Release the lock", enabled = state.locked >= 0) { state.locked = -1 }
                },
            contentAlignment = Alignment.Centre,
        ) {
            Text("right-click me — ammo is ${state.ammo}", style = "label.dim")
        }
    }

    // Second, because it is the most demonstrable thing in the module: the game's own world inside
    // a panel, three ways. See SceneViews.kt.
    SceneViewsGroup(state, windows, world)

    Group("Tree, table and splitter", "Two views of the same drones, either side of a draggable bar.") {
        var split by remember { mutableStateOf(0.45f) }
        Splitter(
            fraction = split,
            onFractionChange = { split = it },
            modifier = Modifier.fillMaxWidth().height(190f),
            minFirst = 120f,
            minSecond = 150f,
            first = { DroneTree(state) },
            second = { Box(Modifier.padding(left = 10f)) { DroneTable(state) } },
        )
        Text("Drag the bar, or focus it and nudge it with the arrows.", style = "label.dim")
    }

    Group("Waiting, and how long for", "A spinner and a bar for work with no percentage on it.") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12f),
            verticalAlignment = VerticalAlignment.Centre,
        ) {
            Spinner(Modifier.size(22f))
            IndeterminateBar(Modifier.fillMaxWidth())
        }
        Labelled("Heat") {
            Slider(state.heat, { state.heat = it }, length = 190f)
        }
        Text("The slider is the weapon's heat: watch the reticle spread.", style = "label.dim")
    }

    Group("Picking a colour", "A swatch that opens a picker, on the square and the hue ring.") {
        Labelled("Reticle") {
            ColourPickerButton(
                colour = state.reticleTint,
                onColourChange = { state.reticleTint = it },
                alpha = true,
                presets = UiSwatches,
            )
        }
        Labelled("Hit sparks") {
            ColourPickerButton(
                colour = state.sparkTint,
                onColourChange = { state.sparkTint = it },
                presets = UiSwatches,
            )
        }
        Text("The stick moves round the square, the shoulders turn the hue.", style = "label.dim")
    }

    Group("Dragging and zooming a plane", "Widgets at world positions rather than screen ones.") {
        DronePlane(state)
        Text("Drag it, wheel over it, double-click to fit. Click a drone to lock it.", style = "label.dim")
    }

    Group("What this module draws", "The exhibits composegl-ui owns, over the scene behind.") {
        Exhibit.entries.filter { it.module == Module.Ui }.forEach { ExhibitSwitch(state, it) }
    }
}

/** The drones as a tree: a branch that opens, and choosing a row moves the lock. */
@Composable
private fun DroneTree(state: ShowcaseState) {
    val tree = rememberTreeState("fleet")
    val rows = listOf(DroneRow("fleet", "Contacts")) +
        state.targets.mapIndexed { index, target -> DroneRow("drone-$index", target.callsign, index) }

    TreeView(
        roots = listOf(rows.first()),
        children = { if (it.drone < 0) rows.drop(1) else emptyList() },
        key = { it.id },
        modifier = Modifier.fillMaxWidth().height(190f),
        selected = rows.drop(1).getOrNull(state.locked),
        onSelect = { if (it.drone >= 0) state.locked = it.drone },
        state = tree,
        hasChildren = { it.drone < 0 },
        rowModifier = { row ->
            if (row.drone < 0) Modifier else Modifier.contextMenu {
                Item("&Lock") { state.locked = row.drone }
                Item("&Release", enabled = state.locked == row.drone) { state.locked = -1 }
            }
        },
    ) { row, _ ->
        Text(row.label, style = if (row.drone >= 0) "label" else "label.dim")
    }
}

/** One line of the tree in this section. [drone] is which target it is, or -1 for the branch. */
private class DroneRow(val id: String, val label: String, val drone: Int = -1)

/** The same drones as a table: click a title to sort, drag a divider, click a row to lock. */
@Composable
private fun DroneTable(state: ShowcaseState) {
    val table = rememberTableState(sortColumn = 1)
    Table(
        rows = state.targets,
        modifier = Modifier.fillMaxWidth().height(190f),
        key = { it.callsign },
        state = table,
        selected = state.targets.getOrNull(state.locked),
        onSelect = { state.locked = state.targets.indexOf(it) },
        empty = { Text("No contacts", style = "label.dim") },
    ) {
        column("Callsign", weight = 1f, sortBy = { it.callsign }) { Text(it.callsign) }
        column("Hull", width = 60f, sortBy = { it.integrity }, align = HorizontalAlignment.End) {
            Text("${(it.integrity * 100f).toInt()}%")
        }
    }
}

/**
 * The drones on a plane you can drag and zoom, drawn in the radar's own coordinates.
 *
 * The rings between them are the canvas's background — one drawing pass in world units rather than
 * a node each — and each drone is an ordinary clickable box placed at a world position.
 */
@Composable
private fun DronePlane(state: ShowcaseState) {
    val world = Rect(-14f, -14f, 14f, 14f)
    val camera = rememberPanZoomState(zoom = 8f, minZoom = 3f, maxZoom = 30f, bounds = world)

    // Remembered, as a widget's own draw is: the canvas redraws when the background it was given
    // changes, and a grid of lines never does.
    val grid: UiCanvas.(Rect) -> Unit = remember {
        { _ ->
            val faint = Colour.argb(0x33A0C4FF)
            var at = -12f
            while (at <= 12f) {
                line(Offset(at, -14f), Offset(at, 14f), width = 0.06f, colour = faint)
                line(Offset(-14f, at), Offset(14f, at), width = 0.06f, colour = faint)
                at += 4f
            }
        }
    }

    PanZoomCanvas(
        state = camera,
        modifier = Modifier.testTag(UiPlaneTag).fillMaxWidth().height(180f),
        reset = PanZoomReset.Fit,
        background = grid,
    ) {
        state.targets.forEachIndexed { index, target ->
            Box(
                Modifier.size(if (index == state.locked) 1.6f else 1.1f)
                    .worldPosition(target.mapX, target.mapY, anchor = Alignment.Centre)
                    .focusable()
                    .clickable { state.locked = index }
                    .background(
                        if (index == state.locked) Colour.rgb(0xFF5A4F) else Colour.rgb(0x4CC2FF),
                        corner = 0.8f,
                    ),
            )
            Text(
                target.callsign,
                Modifier.worldPosition(
                    target.mapX,
                    target.mapY - 1.2f,
                    anchor = Alignment.BottomCentre,
                    scaleWithZoom = false,
                ),
                style = "label.dim",
            )
        }
    }
}

/** The guns the section's own menu bar equips. The wheel offers the same four. */
private val UiGuns = listOf("PULSE", "RIFLE", "LANCE", "MINES")

/** The colours offered as swatches on both pickers here. */
private val UiSwatches = listOf(
    Colour.White,
    Colour.rgb(0x4CC2FF),
    Colour.rgb(0x46A758),
    Colour.rgb(0xFFD600),
    Colour.rgb(0xFF5A2A),
    Colour.Magenta,
)
