package dev.wildware.composegl.demo.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.widget.CollapsingHeader
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
import dev.wildware.composegl.ui.widget.Toggle
import dev.wildware.composegl.ui.widget.TreeView
import dev.wildware.composegl.ui.widget.contextMenu
import dev.wildware.composegl.ui.widget.rememberPanZoomState
import dev.wildware.composegl.ui.widget.rememberTableState
import dev.wildware.composegl.ui.widget.rememberTreeState
import dev.wildware.composegl.ui.widget.worldPosition

/**
 * The composegl-ui widgets a game's tools and menus are built from rather than its HUD: a menu bar,
 * right-click menus, a tree and a table either side of a splitter, folding sections, waiting, colour
 * and a plane to drag about.
 */
@Composable
fun ToolsPage() {
    Page(Section.Tools, "The widgets an editor, a level tool or a settings screen needs. Right-click is a long press on a touch screen.") {
        MenusCard()
        ContextMenuCard()
        TreeAndTableCard()
        FoldingCard()
        WaitingCard()
        ColourCard()
        PlaneCard()
    }
}

@Composable
private fun MenusCard() = Card("Menu bar", "Click a title, or press Alt. Arrows walk the menus; the ticks and the dot are real state.") {
    val state = LocalShowcase.current
    var autosave by remember { mutableStateOf(true) }
    var saved by remember { mutableIntStateOf(0) }
    MenuBar(Modifier.fillMaxWidth().testTag("menubar")) {
        Menu("&File") {
            Item("&Save", shortcut = null) { saved++ }
            CheckItem("&Autosave", checked = autosave) { autosave = it }
            Separator()
            Item("&Quit", enabled = false) {}
        }
        Menu("&Weapon") {
            ShowcaseState.Weapons.forEach { gun ->
                RadioItem(gun, selected = state.weapon == gun) { state.weapon = gun }
            }
        }
    }
    Text("Saved $saved times · autosave ${if (autosave) "on" else "off"} · ${state.weapon} equipped", Modifier.testTag("menu-readout"), style = "label.dim")
}

@Composable
private fun ContextMenuCard() = Card("Context menus", "Right-click the box, hold a finger on it, or focus it and press Shift+F10.") {
    var ammo by remember { mutableIntStateOf(30) }
    Box(
        Modifier.fillMaxWidth()
            .height(56f)
            .background(Ink, corner = 8f)
            .focusable()
            .contextMenu {
                Item("&Reload") { ammo = 30 }
                Item("&Fire one") { ammo = (ammo - 1).coerceAtLeast(0) }
                Separator()
                Item("&Empty it", enabled = ammo > 0) { ammo = 0 }
            }
            .testTag("context-box"),
        contentAlignment = Alignment.Centre,
    ) {
        Text("Ammo: $ammo", Modifier.testTag("ammo"))
    }
}

/** One crew member, as the tree and the table both show them. */
private class Crew(val name: String, val role: String, val health: Int)

private val CrewList = listOf(
    Crew("Edda", "Pilot", 92),
    Crew("Brann", "Gunner", 64),
    Crew("Ilse", "Engineer", 81),
    Crew("Oren", "Medic", 47),
)

/** A line of the tree: a deck, or a crew member on it. */
private class DeckRow(val id: String, val label: String, val crew: Crew? = null)

@Composable
private fun TreeAndTableCard() = Card("Tree, table and splitter", "Pick someone in either; drag the bar between them. Click a column title to sort.") {
    var chosen by remember { mutableStateOf<Crew?>(null) }
    var split by remember { mutableFloatStateOf(0.45f) }
    Splitter(
        fraction = split,
        onFractionChange = { split = it },
        modifier = Modifier.fillMaxWidth().height(200f).testTag("splitter"),
        minFirst = 90f,
        minSecond = 120f,
        first = { CrewTree(chosen) { chosen = it } },
        second = { Box(Modifier.padding(left = 8f)) { CrewTable(chosen) { chosen = it } } },
    )
    Text(chosen?.let { "${it.name}, ${it.role}" } ?: "Nobody picked", Modifier.testTag("crew-picked"), style = "label.dim")
}

@Composable
private fun CrewTree(chosen: Crew?, onChoose: (Crew) -> Unit) {
    val tree = rememberTreeState("bridge", "hold")
    val decks = remember {
        listOf(
            DeckRow("bridge", "Bridge"),
            DeckRow("hold", "Hold"),
        )
    }
    val rows = remember { CrewList.associateWith { DeckRow(it.name, it.name, it) } }
    TreeView(
        roots = decks,
        children = { deck ->
            when (deck.id) {
                "bridge" -> CrewList.take(2).map { rows.getValue(it) }
                "hold" -> CrewList.drop(2).map { rows.getValue(it) }
                else -> emptyList()
            }
        },
        key = { it.id },
        modifier = Modifier.fillMaxWidth().height(200f).testTag("tree"),
        selected = chosen?.let { rows[it] },
        onSelect = { row -> row.crew?.let(onChoose) },
        state = tree,
        hasChildren = { it.crew == null },
    ) { row, _ ->
        Text(row.label, style = if (row.crew == null) "label.dim" else "label")
    }
}

@Composable
private fun CrewTable(chosen: Crew?, onChoose: (Crew) -> Unit) {
    val table = rememberTableState(sortColumn = 0)
    Table(
        rows = CrewList,
        modifier = Modifier.fillMaxWidth().height(200f).testTag("table"),
        key = { it.name },
        state = table,
        selected = chosen,
        onSelect = onChoose,
        empty = { Text("No crew", style = "label.dim") },
    ) {
        column("Name", weight = 1f, sortBy = { it.name }) { Text(it.name) }
        column("HP", width = 48f, sortBy = { it.health }, align = HorizontalAlignment.End) { Text("${it.health}") }
    }
}

@Composable
private fun FoldingCard() = Card("Collapsing headers", "Sections that fold away, for a long settings screen.") {
    var bloom by remember { mutableStateOf(true) }
    var shadows by remember { mutableFloatStateOf(0.5f) }
    CollapsingHeader("Graphics", Modifier.fillMaxWidth().testTag("fold-graphics"), initiallyExpanded = true) {
        Toggle(bloom, { bloom = it }, label = "Bloom")
        Labelled("Shadow quality") { Slider(shadows, { shadows = it }, length = 180f) }
    }
    CollapsingHeader("Audio", Modifier.fillMaxWidth().testTag("fold-audio")) {
        Text("Music, effects and voices would go here.", style = "label.dim")
    }
}

@Composable
private fun WaitingCard() = Card("Spinner and indeterminate bar", "For work with no percentage on it. Only the drawing moves: nothing is laid out again.") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14f), verticalAlignment = VerticalAlignment.Centre) {
        Spinner(Modifier.size(28f).testTag("spinner"))
        Text("Saving…", Modifier.weight(1f))
    }
    IndeterminateBar(Modifier.fillMaxWidth().testTag("indeterminate"), length = (LocalCardWidth.current - 40f).coerceAtLeast(120f))
}

@Composable
private fun ColourCard() = Card("Colour picker", "Tap a swatch. Drag on the square and the hue ring, or type a hex code. The HUD page's world markers use the first colour.") {
    val state = LocalShowcase.current
    var paint by remember { mutableStateOf(Colour.rgb(0xF2A65A)) }
    Row(horizontalArrangement = Arrangement.spacedBy(20f), verticalAlignment = VerticalAlignment.Centre) {
        Labelled("World markers") {
            ColourPickerButton(state.tint, { state.tint = it }, Modifier.testTag("tint"), presets = Swatches, size = 32f)
        }
        Labelled("Hull paint, with alpha") {
            ColourPickerButton(paint, { paint = it }, alpha = true, presets = Swatches, size = 32f)
        }
    }
}

private val Swatches = listOf(
    Colour.White,
    Colour.rgb(0x4CC2FF),
    Colour.rgb(0x7BE08A),
    Colour.rgb(0xF2C94C),
    Colour.rgb(0xF2A65A),
    Colour.rgb(0xFF5A4F),
)

@Composable
private fun PlaneCard() = Card("Pan and zoom", "Drag, wheel, or pinch with two fingers. Double-click to fit. Tap a planet.") {
    var picked by remember { mutableStateOf<String?>(null) }
    val camera = rememberPanZoomState(zoom = 6f, minZoom = 2f, maxZoom = 30f, bounds = Rect(-16f, -12f, 16f, 12f))
    val grid: UiCanvas.(Rect) -> Unit = remember {
        { _ ->
            val faint = Colour.argb(0x334CC2FF)
            var at = -16f
            while (at <= 16f) {
                line(Offset(at, -12f), Offset(at, 12f), width = 0.05f, colour = faint)
                at += 4f
            }
            at = -12f
            while (at <= 12f) {
                line(Offset(-16f, at), Offset(16f, at), width = 0.05f, colour = faint)
                at += 4f
            }
        }
    }
    PanZoomCanvas(
        state = camera,
        modifier = Modifier.fillMaxWidth().height(200f).testTag("plane"),
        reset = PanZoomReset.Fit,
        background = grid,
    ) {
        Planets.forEach { planet ->
            Box(
                Modifier.size(planet.size)
                    .worldPosition(planet.x, planet.y, anchor = Alignment.Centre)
                    .focusable()
                    .clickable { picked = planet.name }
                    .background(if (picked == planet.name) Warm else Accent, corner = planet.size / 2f),
            )
            Text(
                planet.name,
                Modifier.worldPosition(planet.x, planet.y - planet.size, anchor = Alignment.BottomCentre, scaleWithZoom = false),
                style = "label.dim",
            )
        }
    }
    Text(picked?.let { "Course set for $it" } ?: "No course set", Modifier.testTag("planet"), style = "label.dim")
}

private class Planet(val name: String, val x: Float, val y: Float, val size: Float)

private val Planets = listOf(
    Planet("Oakmere", -9f, -4f, 2.2f),
    Planet("Vessa", 3f, 5f, 1.4f),
    Planet("Tarn", 10f, -6f, 3f),
)
