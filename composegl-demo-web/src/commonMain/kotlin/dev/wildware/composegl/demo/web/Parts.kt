package dev.wildware.composegl.demo.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import dev.wildware.composegl.debug.DebugWindowsState
import dev.wildware.composegl.debug.DevConsoleState
import dev.wildware.composegl.debug.MemoryDebugWindowStore
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.FlowRow
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.widget.Text

/** How wide a card is on this screen: two or three across on a desktop, the whole width on a phone. */
val LocalCardWidth = staticCompositionLocalOf { 380f }

/** The showcase's frame, for a page that wants to change page or open the dialog. */
val LocalShowcase = staticCompositionLocalOf { ShowcaseState() }

/** The debug windows' state, so the debug page can dock and float them. */
val LocalWindows = staticCompositionLocalOf { DebugWindowsState(MemoryDebugWindowStore()) }

/** The tour's developer console, so the debug page can open it and run commands through it. */
val LocalConsole = staticCompositionLocalOf<DevConsoleState?> { null }

/** The node the whole tour is built into, once it has been laid out. For the node tree. */
val LocalInterfaceRoot = staticCompositionLocalOf<State<UiNode?>> { mutableStateOf(null) }

/** A page: a heading, a line saying what it shows, and its cards wrapping to the width. */
@Composable
fun Page(section: Section, intro: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("page-${section.tag}"), verticalArrangement = Arrangement.spacedBy(14f)) {
        Text(section.title, style = "label.title")
        Text(intro, Modifier.fillMaxWidth(), style = "label.dim")
        FlowRow(Modifier.fillMaxWidth(), horizontalSpacing = 14f, verticalSpacing = 14f) { content() }
    }
}

/** One thing on show: a title, a line of what to try, and the thing itself. */
@Composable
fun Card(title: String, hint: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.width(LocalCardWidth.current).styled("card")) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f)) {
            Text(title, style = "label.heading")
            if (hint != null) Text(hint, Modifier.fillMaxWidth(), style = "label.dim")
            content()
        }
    }
}

/** A small caption over something. */
@Composable
fun Labelled(name: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4f)) {
        Text(name, style = "label.dim")
        content()
    }
}

val Ink = Colour.rgb(0x10151D)
val Steel = Colour.rgb(0x2C3545)
val Accent = Colour.rgb(0x4CC2FF)
val Deep = Colour.rgb(0x1D4F70)
val Paper = Colour.rgb(0xE6EDF5)
val Warm = Colour.rgb(0xF2A65A)
