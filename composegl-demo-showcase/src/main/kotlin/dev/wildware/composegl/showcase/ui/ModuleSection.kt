package dev.wildware.composegl.showcase.ui

import androidx.compose.runtime.Composable
import dev.wildware.composegl.debug.DebugWindowsState
import dev.wildware.composegl.debug.DevConsoleState
import dev.wildware.composegl.showcase.Exhibit
import dev.wildware.composegl.showcase.Module
import dev.wildware.composegl.showcase.ShowcaseState
import dev.wildware.composegl.showcase.WorldViews
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.Spacer
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.CollapsingHeader
import dev.wildware.composegl.ui.widget.Divider
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Toggle

/** How far down the panels along the top start, clear of the menu bar. */
internal const val BelowMenus = 64f

/** How wide a module's section is. Everything inside one is written to fit this. */
internal const val SectionWidth = 440f

/** How tall, on the 1280x720 the showcase runs at: below the menu bar, clear of the bottom. */
internal const val SectionHeight = 616f

/** So a test can find whichever section is open, and read which module it is. */
internal const val SectionTag = "showcase.section"

/** The three buttons along the top, tagged so a test can walk between sections with a click. */
internal fun sectionTabTag(module: Module) = "showcase.section.${module.tab}"

/** The column everything in a section scrolls in, so a test can reach the groups down the bottom. */
internal const val SectionScrollTag = "showcase.section.scroll"

/**
 * What every [Group]'s header is tagged with, so a test can open all of them without being told
 * their names — which is what makes "every group in every section lays out and takes input" a test
 * that stays true as groups are added.
 */
internal const val GroupTagPrefix = "showcase.group."

/**
 * One module's section: a page about what is actually inside `composegl-ui`, `-debug` or `-game`.
 *
 * The rest of the showcase is a fight with everything happening at once, which is the right answer
 * to "what does this look like in a game" and no answer at all to "what is in composegl-game". So
 * each module also gets a page of its own, opened from the **Modules** menu, from Control and a
 * number, from the pad's right stick, or from the three buttons along the top of whichever section
 * is already open.
 *
 * Nothing here is a picture of a widget. Every control on every section writes to [ShowcaseState] —
 * the same state the fight behind it reads — so turning the heat up here spreads the reticle out
 * there, and picking a drone here moves the lock the HUD is drawing.
 *
 * @param world how a scene view in a section asks the game to draw its world. [WorldViews.None]
 *   draws nothing, for a screen with no world behind it.
 */
@Composable
fun ModuleSections(
    state: ShowcaseState,
    budget: FrameBudget,
    windows: DebugWindowsState,
    console: DevConsoleState,
    interfaceRoot: UiNode?,
    world: WorldViews = WorldViews.None,
) {
    val module = state.section ?: return

    Panel(
        Modifier.testTag(SectionTag)
            .align(Alignment.TopStart)
            .padding(left = 28f, top = BelowMenus)
            .width(SectionWidth)
            .height(SectionHeight),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = VerticalAlignment.Centre) {
                Text(module.artifact, style = "label.title")
                Spacer(Modifier.weight(1f))
                Button("Close", { state.section = null }, style = "button.quiet")
            }
            Text(module.blurb, style = "label.dim")

            // The three sections as tabs. Focusable buttons rather than anything clever, so the
            // pad walks them with the d-pad and South opens one, exactly as a mouse does.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6f)) {
                Module.entries.forEach { entry ->
                    Button(
                        entry.tab,
                        { state.section = entry },
                        Modifier.testTag(sectionTabTag(entry)),
                        style = if (entry == module) "tab.selected" else "tab",
                    )
                }
            }
            Divider(Modifier.fillMaxWidth())

            // Everything below scrolls, because a module has more in it than 600 pixels holds.
            ScrollArea(Modifier.testTag(SectionScrollTag).fillMaxWidth().weight(1f)) {
                Column(
                    Modifier.width(SectionBody),
                    verticalArrangement = Arrangement.spacedBy(8f),
                ) {
                    when (module) {
                        Module.Ui -> UiSection(state, windows, world)
                        Module.Debug -> DebugSection(state, budget, windows, console, interfaceRoot)
                        Module.Game -> GameSection(state)
                    }
                }
            }
        }
    }
}

/**
 * How wide the inside of a section is: the panel less its own padding, less the scrollbar.
 *
 * Written down rather than filled, because several things inside a section — the chat box, the
 * objective tracker, the notifications — are told how wide to be rather than asking, and every one
 * of them has to agree or the column is wider than the panel and the words run off the edge.
 */
internal const val SectionBody = 376f

/**
 * One group inside a section: a heading that folds, a line saying what the group is about, and the
 * widgets themselves.
 *
 * Folded away by default apart from the first of each section, so a section opens as a list of what
 * is in the module rather than as a wall — which is the discoverability the sections exist for.
 */
@Composable
internal fun Group(
    title: String,
    blurb: String,
    open: Boolean = false,
    content: @Composable () -> Unit,
) {
    CollapsingHeader(title, Modifier.testTag(GroupTagPrefix + title).fillMaxWidth(), initiallyExpanded = open) {
        Column(
            Modifier.fillMaxWidth().padding(top = 6f, bottom = 6f),
            verticalArrangement = Arrangement.spacedBy(8f),
        ) {
            Text(blurb, style = "label.dim")
            content()
        }
    }
}

/** A label above a control, so a section reads as a list of things rather than a pile of widgets. */
@Composable
internal fun Labelled(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        Text(label, style = "label.dim")
        content()
    }
}

/**
 * One exhibit's switch, as the section offers it.
 *
 * The same [ShowcaseState.toggle] the Show menu and the ON SHOW panel call, so the three always
 * agree: turning an overlay off here turns it off over the scene, and it is still off when the
 * section closes.
 */
@Composable
internal fun ExhibitSwitch(state: ShowcaseState, exhibit: Exhibit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        Column(Modifier.width(SectionBody - 70f), verticalArrangement = Arrangement.spacedBy(2f)) {
            Text(exhibit.title, style = "label")
            Text(exhibit.blurb, style = "label.dim")
        }
        Toggle(state.isOn(exhibit), { state.toggle(exhibit) })
    }
}
