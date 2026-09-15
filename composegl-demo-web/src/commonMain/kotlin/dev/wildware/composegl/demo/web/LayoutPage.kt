package dev.wildware.composegl.demo.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.FlowRow
import dev.wildware.composegl.ui.layout.Grid
import dev.wildware.composegl.ui.layout.GridCells
import dev.wildware.composegl.ui.layout.IntrinsicSize
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.aspectRatio
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxHeight
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.modifier.widthIn
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Stepper
import dev.wildware.composegl.ui.widget.Text

@Composable
fun LayoutPage() {
    Page(Section.Layout, "The layouts are the toolkit's own: floats in design units, no dp, measured in one pass.") {
        RowColumnBox()
        Weights()
        GridCard()
        Flow()
        Ratio()
        MinMax()
        BaselineCard()
        Intrinsic()
    }
}

@Composable
private fun RowColumnBox() = Card("Row, Column and Box", "Pick an arrangement and watch the row respace.") {
    val arrangements = listOf("Start" to Arrangement.Start, "Centre" to Arrangement.Centre, "End" to Arrangement.End, "SpaceBetween" to Arrangement.SpaceBetween, "SpaceEvenly" to Arrangement.SpaceEvenly)
    var chosen by remember { mutableStateOf(arrangements[3]) }
    Stepper(arrangements, chosen, { chosen = it }, label = { it.first })
    Row(Modifier.fillMaxWidth().background(Ink, corner = 6f).padding(6f), horizontalArrangement = chosen.second) {
        repeat(3) { Box(Modifier.size(52f, 30f).background(Accent, corner = 5f)) {} }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10f)) {
        Column(Modifier.background(Ink, corner = 6f).padding(6f), verticalArrangement = Arrangement.spacedBy(4f)) {
            repeat(3) { Box(Modifier.size(70f, 16f).background(Steel, corner = 4f)) {} }
        }
        Box(Modifier.size(150f, 64f).background(Ink, corner = 6f).padding(6f)) {
            Text("TopStart", Modifier.align(Alignment.TopStart), style = "label.dim")
            Text("Centre", Modifier.align(Alignment.Centre))
            Text("BottomEnd", Modifier.align(Alignment.BottomEnd), style = "label.dim")
        }
    }
}

@Composable
private fun Weights() = Card("Weight", "The first slab's share of the row, from one to four.") {
    var weight by remember { mutableFloatStateOf(1f) }
    Slider(weight, { weight = it }, range = 1f..4f, step = 0.5f, length = 200f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6f)) {
        Slab("weight(${weight})", Modifier.weight(weight), Deep)
        Slab("1f", Modifier.weight(1f), Steel)
        Slab("60 wide", Modifier.width(60f), Steel)
    }
}

@Composable
private fun GridCard() = Card("Grid", "Fixed columns, or as many as fit.") {
    Row(horizontalArrangement = Arrangement.spacedBy(16f)) {
        Labelled("Fixed(4)") {
            Grid(GridCells.Fixed(4), Modifier.width(150f), spacing = 4f) { repeat(10) { Tile(it, Steel) } }
        }
        Labelled("Adaptive(40)") {
            Grid(GridCells.Adaptive(minSize = 40f), Modifier.width((LocalCardWidth.current - 210f).coerceAtLeast(90f)), spacing = 4f) { repeat(9) { Tile(it, Deep) } }
        }
    }
}

@Composable
private fun Flow() = Card("FlowRow", "Chips wrap onto the next line. Add some.") {
    val buffs = remember { mutableStateListOf("HASTE", "REGEN", "SHIELD", "BURNING", "FOCUS") }
    var centred by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
        Button("Add", { buffs.add(Buffs[buffs.size % Buffs.size]) }, style = "button.quiet")
        Button("Remove", { if (buffs.isNotEmpty()) buffs.removeAt(buffs.lastIndex) }, style = "button.quiet")
        Button(if (centred) "Start" else "Centre", { centred = !centred }, style = "button.quiet")
    }
    FlowRow(
        Modifier.fillMaxWidth().background(Ink, corner = 6f).padding(6f),
        horizontalSpacing = 6f,
        verticalSpacing = 6f,
        horizontalArrangement = if (centred) Arrangement.Centre else Arrangement.Start,
    ) {
        buffs.forEach { name ->
            Box(Modifier.height(26f).background(Deep, corner = 4f).padding(horizontal = 8f), contentAlignment = Alignment.Centre) {
                Text(name, style = "label.dim")
            }
        }
    }
}

private val Buffs = listOf("HASTE", "REGEN", "SHIELD", "BURNING", "FOCUS", "POISON", "WARD", "RAGE", "STEALTH", "BLESSED", "CHILL", "SLOWED")

@Composable
private fun Ratio() = Card("Aspect ratio", "Drag the width; the shapes keep theirs.") {
    var width by remember { mutableFloatStateOf(0.8f) }
    Slider(width, { width = it }, range = 0.4f..1f, length = 200f)
    Row(Modifier.fillMaxWidth(width), horizontalArrangement = Arrangement.spacedBy(8f)) {
        Shaped("16:9", Modifier.weight(2f).aspectRatio(16f / 9f), Deep)
        Shaped("1:1", Modifier.weight(1f).aspectRatio(1f), Steel)
    }
}

@Composable
private fun MinMax() = Card("Minimum and maximum", "widthIn(min = 120, max = 260): short text is padded out, long text wraps.") {
    listOf("Short", "A little longer than that", "Long enough that it would run straight off the side, so it wraps instead").forEach { words ->
        Box(Modifier.widthIn(min = 120f, max = 260f).background(Steel, corner = 6f).padding(8f)) { Text(words) }
    }
}

@Composable
private fun BaselineCard() = Card("Baseline alignment", "A big number and a small unit, on one line of type.") {
    Row(horizontalArrangement = Arrangement.spacedBy(20f)) {
        Labelled("Top") { Readout(VerticalAlignment.Top) }
        Labelled("Baseline") { Readout(VerticalAlignment.Baseline) }
    }
}

@Composable
private fun Readout(alignment: VerticalAlignment) {
    Row(Modifier.background(Ink, corner = 6f).padding(10f), horizontalArrangement = Arrangement.spacedBy(6f), verticalAlignment = alignment) {
        Text("120", textStyle = TextStyle(size = 34f))
        Text("HP", style = "label.dim")
    }
}

@Composable
private fun Intrinsic() = Card("Intrinsic size", "Every button as wide as the longest label; a divider as tall as its row.") {
    Row(horizontalArrangement = Arrangement.spacedBy(20f), verticalAlignment = VerticalAlignment.Centre) {
        Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(6f)) {
            Button("Play", {}, Modifier.fillMaxWidth())
            Button("Options", {}, Modifier.fillMaxWidth())
            Button("Quit", {}, Modifier.fillMaxWidth())
        }
        Row(Modifier.height(IntrinsicSize.Min).background(Ink, corner = 6f).padding(10f), horizontalArrangement = Arrangement.spacedBy(10f)) {
            Text("HP")
            Box(Modifier.width(2f).fillMaxHeight().background(Accent)) {}
            Text("SHIELD\nHULL")
        }
    }
}

@Composable
private fun Tile(index: Int, colour: Colour) {
    Box(Modifier.fillMaxWidth().height(32f).background(colour, corner = 5f), contentAlignment = Alignment.Centre) {
        Text("$index", style = "label.dim")
    }
}

@Composable
private fun Slab(label: String, modifier: Modifier, colour: Colour) {
    Box(modifier.height(34f).background(colour, corner = 6f), contentAlignment = Alignment.Centre) { Text(label, style = "label.dim") }
}

@Composable
private fun Shaped(label: String, modifier: Modifier, colour: Colour) {
    Box(modifier.background(colour, corner = 6f), contentAlignment = Alignment.Centre) { Text(label, style = "label.dim") }
}
