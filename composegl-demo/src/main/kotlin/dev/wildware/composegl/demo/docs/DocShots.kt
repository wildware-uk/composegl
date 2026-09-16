package dev.wildware.composegl.demo.docs

import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.blend
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.debug.DebugWindow
import dev.wildware.composegl.debug.DebugWindowHost
import dev.wildware.composegl.debug.FrameBudgetOverlay
import dev.wildware.composegl.debug.Histogram
import dev.wildware.composegl.debug.MemoryDebugWindowStore
import dev.wildware.composegl.debug.Plot
import dev.wildware.composegl.debug.PlotBuffer
import dev.wildware.composegl.debug.rememberPlotBuffer
import dev.wildware.composegl.debug.rememberDebugWindowsState
import dev.wildware.composegl.ui.debug.FrameBudget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.AnimatedVisibility
import dev.wildware.composegl.ui.animation.Crossfade
import dev.wildware.composegl.ui.animation.AnimatedContent
import dev.wildware.composegl.ui.animation.slideLeft
import dev.wildware.composegl.ui.animation.slideRight
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.animation.Spring
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.animateColour
import dev.wildware.composegl.ui.animation.animateFloat
import dev.wildware.composegl.ui.animation.animateSize
import dev.wildware.composegl.ui.animation.updateTransition
import dev.wildware.composegl.ui.animation.fadeOut
import dev.wildware.composegl.ui.animation.animateFloatAsState
import dev.wildware.composegl.ui.animation.scaleOut
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.wait
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.modifier.animateContentSize
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.SizeChangedHandler
import dev.wildware.composegl.ui.modifier.repeatingClickable
import dev.wildware.composegl.game.Bar
import dev.wildware.composegl.game.BarThreshold
import dev.wildware.composegl.game.CompassBar
import dev.wildware.composegl.game.Hotbar
import dev.wildware.composegl.game.HotbarSlot
import dev.wildware.composegl.game.MinimapFrame
import dev.wildware.composegl.game.MinimapMarker
import dev.wildware.composegl.game.OffScreen
import dev.wildware.composegl.game.WorldMarkerLayer
import dev.wildware.composegl.game.WorldPoint
import dev.wildware.composegl.game.WorldProjection
import dev.wildware.composegl.game.RadialCooldown
import dev.wildware.composegl.game.Reticle
import dev.wildware.composegl.game.rememberCompassLabels
import dev.wildware.composegl.game.rememberCooldown
import dev.wildware.composegl.game.rememberReticleState
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Shape
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.BorderSide
import dev.wildware.composegl.ui.graphics.BorderStyle
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.Hsv
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.InputBinding
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.widget.KeyBindButton
import dev.wildware.composegl.ui.widget.KeyBindState
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.FlowRow
import dev.wildware.composegl.ui.layout.Grid
import dev.wildware.composegl.ui.layout.GridCells
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.IntrinsicSize
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.layout.layoutId
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.PointerParallax
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.animatePlacement
import dev.wildware.composegl.ui.modifier.aspectRatio
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.border
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clipShape
import dev.wildware.composegl.ui.modifier.draggable
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.tint
import dev.wildware.composegl.ui.modifier.debugBounds
import dev.wildware.composegl.debug.ConsoleLevel
import dev.wildware.composegl.debug.DevConsole
import dev.wildware.composegl.debug.arg
import dev.wildware.composegl.debug.rememberDevConsole
import dev.wildware.composegl.debug.Inspector
import dev.wildware.composegl.debug.NodeTree
import dev.wildware.composegl.debug.rememberInspectorState
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.debug.LayoutOverlay
import dev.wildware.composegl.debug.OverdrawOverlay
import dev.wildware.composegl.debug.FocusOverlay
import dev.wildware.composegl.ui.focus.FocusRequester
import dev.wildware.composegl.ui.modifier.focusOrder
import dev.wildware.composegl.ui.modifier.focusRequester
import dev.wildware.composegl.ui.modifier.hitShape
import dev.wildware.composegl.debug.RedrawOverlay
import dev.wildware.composegl.debug.TextMetricsOverlay
import dev.wildware.composegl.ui.modifier.fillMaxHeight
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.layoutId
import dev.wildware.composegl.ui.modifier.marquee
import dev.wildware.composegl.ui.modifier.mirror
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onSizeChanged
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.rememberShake
import dev.wildware.composegl.ui.modifier.parallax
import dev.wildware.composegl.ui.modifier.shadow
import dev.wildware.composegl.ui.modifier.shake
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.skew
import dev.wildware.composegl.ui.modifier.rotate3d
import dev.wildware.composegl.ui.modifier.perspective
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.modifier.widthIn
import dev.wildware.composegl.ui.modifier.zIndex
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.wrapContentWidth
import dev.wildware.composegl.ui.saveable.SaveableStateHolder
import dev.wildware.composegl.ui.saveable.rememberSaveable
import dev.wildware.composegl.ui.skin.ProvideSkin
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.widget.AnimatedImage
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.rememberSpriteAnimation
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.text.TextFieldValue
import dev.wildware.composegl.ui.text.TextRange
import dev.wildware.composegl.ui.widget.GamepadKeyboard
import dev.wildware.composegl.ui.widget.KeyboardTarget
import dev.wildware.composegl.ui.widget.ProvideGamepadKeyboard
import dev.wildware.composegl.ui.widget.Checkbox
import dev.wildware.composegl.ui.widget.CollapsingHeader
import dev.wildware.composegl.ui.widget.Orientation
import dev.wildware.composegl.ui.widget.Splitter
import dev.wildware.composegl.ui.widget.Table
import dev.wildware.composegl.ui.widget.rememberTableState
import dev.wildware.composegl.ui.widget.Divider
import dev.wildware.composegl.ui.widget.ColourPicker
import dev.wildware.composegl.ui.widget.ColourPickerButton
import dev.wildware.composegl.ui.widget.ColourSwatch
import dev.wildware.composegl.ui.widget.Dropdown
import dev.wildware.composegl.ui.widget.PopupHost
import dev.wildware.composegl.ui.widget.MenuBar
import dev.wildware.composegl.ui.widget.contextMenu
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.input.plus
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.widget.DragAndDropHost
import dev.wildware.composegl.ui.widget.DropTargetState
import dev.wildware.composegl.ui.widget.Image
import dev.wildware.composegl.ui.widget.NumberStepper
import dev.wildware.composegl.ui.widget.LazyColumn
import dev.wildware.composegl.ui.widget.LazyGridState
import dev.wildware.composegl.ui.widget.LazyListState
import dev.wildware.composegl.ui.widget.LazyVerticalGrid
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.widget.PanZoomCanvas
import dev.wildware.composegl.ui.widget.PanZoomReset
import dev.wildware.composegl.ui.widget.rememberPanZoomState
import dev.wildware.composegl.ui.widget.worldPosition
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.Spinner
import dev.wildware.composegl.ui.widget.IndeterminateBar
import dev.wildware.composegl.ui.widget.ProvideTextScale
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.SelectionContainer
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Stepper
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TreeView
import dev.wildware.composegl.ui.widget.rememberTreeState
import dev.wildware.composegl.ui.widget.TextField
import dev.wildware.composegl.ui.widget.Toggle
import dev.wildware.composegl.ui.widget.Tooltip
import dev.wildware.composegl.ui.widget.TooltipHost
import dev.wildware.composegl.ui.widget.VirtualCursor
import dev.wildware.composegl.ui.layout.Baseline
import dev.wildware.composegl.ui.modifier.paddingFrom
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.text.Locale
import dev.wildware.composegl.ui.text.ProvideLocale
import dev.wildware.composegl.ui.text.Strings
import dev.wildware.composegl.ui.text.stringOf
import dev.wildware.composegl.ui.widget.rememberScrollState
import dev.wildware.composegl.ui.widget.dragSource
import dev.wildware.composegl.ui.widget.dropTarget

/**
 * Every picture in the wiki, and the interface each one is a photograph of.
 *
 * Kept in one list so that adding a picture to the documentation is adding an entry here, and so
 * that a widget which changes shape is caught by a person looking at the regenerated pictures
 * rather than by a reader wondering why their screen does not match.
 *
 * The sizes are deliberately small and snug. A picture of a button in a 1280x720 screenshot is a
 * button nobody can see.
 */
@Suppress("LongMethod")
internal fun docShots(): List<DocShot> = buildList {
    scenes()
    layout()
    widgets()
    game()
    modifiers()
    animation()
    console()
    debugWindows()
    plots()
    nodeTree()
    worldMarkers()
    compasses()
}

// ---------------------------------------------------------------- whole screens

private fun MutableList<DocShot>.scenes() {
    // Two players, one window: the same menu twice, a UiHost each. Pad 1 stepped down twice
    // through an InputRouter, so the right-hand focus is somewhere the left-hand one is not.
    add(
        DocShot(
            "split-screen", 620, 260,
            players = 2,
            pads = listOf(GamepadId(1) to GamepadButton.DpadDown, GamepadId(1) to GamepadButton.DpadDown),
        ) {
            val player = LocalDocPlayer.current
            Box(Modifier.fillMaxSize().background(Colour.rgb(0x0A0D12)).padding(12f), contentAlignment = Alignment.Centre) {
                Panel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                        Text("PLAYER ${player + 1}", style = "label.dim")
                        Bar(if (player == 0) 0.8f else 0.35f, Modifier.fillMaxWidth())
                        Button("RESUME", onClick = {}, initialFocus = true, modifier = Modifier.fillMaxWidth())
                        Button("LOADOUT", onClick = {}, modifier = Modifier.fillMaxWidth())
                        Button("LEAVE", onClick = {}, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
    )

    // Through Skin.Default rather than the example's skin, because the point of the picture is
    // what you get before you have written a skin at all.
    add(DocShot("first-screen", 440, 260, stock = true) {
        Box(Modifier.fillMaxSize().background(Colour.rgb(0x08090C)), contentAlignment = Alignment.Centre) {
            Panel(Modifier.width(280f)) {
                Column(verticalArrangement = Arrangement.spacedBy(12f)) {
                    Text("HELLO")
                    Text("Clicked 3 times")
                    Button("CLICK ME", onClick = {})
                }
            }
        }
    })

    // The front page's snippet, run.
    add(DocShot("home-hud", 560, 300) {
        Box(Modifier.fillMaxSize().background(Colour.rgb(0x0A0D12))) {
            Panel(Modifier.align(Alignment.BottomStart).padding(24f).width(260f)) {
                Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                    Text("HULL", style = "label.dim")
                    Bar(0.64f, Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("AMMO", style = "label.dim")
                        Text("148")
                    }
                }
            }
            Reticle(rememberReticleState(), gap = 8f, arm = 14f, thickness = 3f)
        }
    })
}

// ---------------------------------------------------------------- layout

private fun MutableList<DocShot>.layout() {
    add(DocShot("layout-row", 420, 90) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(10f)) {
                Swatch("one")
                Swatch("two")
                Swatch("three")
            }
        }
    })

    add(DocShot("layout-column", 200, 200) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                Swatch("one")
                Swatch("two")
                Swatch("three")
            }
        }
    })

    add(DocShot("layout-box", 300, 200) {
        Frame {
            Box(Modifier.fillMaxSize().background(Ink, corner = 6f)) {
                Text("TopStart", Modifier.align(Alignment.TopStart), style = "label.dim")
                Text("TopEnd", Modifier.align(Alignment.TopEnd), style = "label.dim")
                Text("Centre", Modifier.align(Alignment.Centre))
                Text("BottomStart", Modifier.align(Alignment.BottomStart), style = "label.dim")
                Text("BottomEnd", Modifier.align(Alignment.BottomEnd), style = "label.dim")
            }
        }
    })

    add(DocShot("layout-weight", 420, 80) {
        Frame {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8f)) {
                Slab("weight(1f)", Modifier.weight(1f), Steel)
                Slab("weight(2f)", Modifier.weight(2f), Deep)
                Slab("width(80f)", Modifier.width(80f), Steel)
            }
        }
    })

    add(DocShot("layout-aspect-ratio", 420, 290) {
        Frame {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8f)) {
                    Shaped("16:9", Modifier.weight(2f).aspectRatio(16f / 9f), Deep)
                    Shaped("1:1", Modifier.weight(1f).aspectRatio(1f), Steel)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8f)) {
                    repeat(4) { Shaped("1:1", Modifier.weight(1f).aspectRatio(1f), Steel) }
                }
            }
        }
    })

    add(DocShot("layout-size-in", 420, 250) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                Text("widthIn(min = 160f, max = 300f)", style = "label.dim")
                for (words in listOf(
                    "Short",
                    "A little longer than that",
                    "Long enough that it would run straight off the side, so it wraps at 300 instead",
                )) {
                    Box(Modifier.widthIn(min = 160f, max = 300f).background(Steel, corner = 6f).padding(10f)) {
                        Text(words)
                    }
                }
            }
        }
    })

    add(DocShot("layout-grid", 420, 200) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(24f)) {
                Column(verticalArrangement = Arrangement.spacedBy(6f)) {
                    Text("Fixed(4)", style = "label.dim")
                    Grid(GridCells.Fixed(4), Modifier.width(184f), spacing = 4f) {
                        repeat(10) { index -> GridTile(index, Steel) }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6f)) {
                    Text("Adaptive(minSize = 40f)", style = "label.dim")
                    Grid(GridCells.Adaptive(minSize = 40f), Modifier.width(140f), spacing = 4f) {
                        repeat(7) { index -> GridTile(index, Deep) }
                    }
                }
            }
        }
    })

    // A menu whose buttons all stop at the longest label, and a divider exactly as tall as the
    // row it splits — both sized by asking the contents first.
    add(DocShot("layout-intrinsic", 420, 170) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(28f), verticalAlignment = VerticalAlignment.Centre) {
                Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(8f)) {
                    Button("PLAY", onClick = {}, modifier = Modifier.fillMaxWidth())
                    Button("OPTIONS", onClick = {}, modifier = Modifier.fillMaxWidth())
                    Button("QUIT", onClick = {}, modifier = Modifier.fillMaxWidth())
                }
                Row(
                    Modifier.height(IntrinsicSize.Min).background(Ink, corner = 6f).padding(12f),
                    horizontalArrangement = Arrangement.spacedBy(12f),
                ) {
                    Text("HP")
                    Box(Modifier.width(2f).fillMaxHeight().background(Accent)) {}
                    Text("SHIELD\nHULL")
                }
            }
        }
    })

    add(DocShot("layout-lazy-grid", 420, 220) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(6f)) {
                Text("LazyVerticalGrid: 1,000 items, only these rows built", style = "label.dim")
                // Part of the way into a row, so the picture shows the edge clipping it.
                val state = remember { LazyGridState(initialPosition = 1_000f) }
                LazyVerticalGrid(
                    count = 1_000,
                    columns = GridCells.Adaptive(minSize = 56f),
                    modifier = Modifier.fillMaxWidth().height(170f),
                    state = state,
                    spacing = 4f,
                ) { index -> GridTile(index, if (index % 7 == 0) Deep else Steel) }
            }
        }
    })

    add(DocShot("layout-sticky-headers", 420, 260) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(6f)) {
                Text("LazyColumn { stickyHeader { } items(…) { } }", style = "label.dim")
                // Caught as Armour arrives, so the picture shows one header pushing the other off.
                val state = remember { LazyListState(initialPosition = 186f) }
                LazyColumn(Modifier.fillMaxWidth().height(210f), state = state, spacing = 4f) {
                    listOf(
                        "WEAPONS" to listOf("Iron sword", "Longbow", "War hammer", "Dagger", "Crossbow"),
                        "ARMOUR" to listOf("Leather cap", "Chain mail", "Tower shield", "Greaves", "Gauntlets"),
                        "POTIONS" to listOf("Healing", "Stamina", "Night eye", "Fire ward", "Swiftness"),
                    ).forEach { (section, names) ->
                        stickyHeader(key = section) {
                            Box(
                                Modifier.fillMaxWidth().height(26f).background(Accent, corner = 4f).padding(left = 10f),
                                contentAlignment = Alignment.CentreStart,
                            ) { Text(section, colour = Ink) }
                        }
                        items(names.size, key = { "$section$it" }) { index ->
                            Box(
                                Modifier.fillMaxWidth().height(30f).background(Steel, corner = 4f).padding(left = 14f),
                                contentAlignment = Alignment.CentreStart,
                            ) { Text(names[index], style = "label.dim") }
                        }
                    }
                }
            }
        }
    })

    add(DocShot("layout-arrangements", 420, 300) {
        Frame {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12f)) {
                ArrangementRow("Start", Arrangement.Start)
                ArrangementRow("Centre", Arrangement.Centre)
                ArrangementRow("End", Arrangement.End)
                ArrangementRow("SpaceBetween", Arrangement.SpaceBetween)
                ArrangementRow("SpaceAround", Arrangement.SpaceAround)
                ArrangementRow("SpaceEvenly", Arrangement.SpaceEvenly)
            }
        }
    })

    add(DocShot("layout-alignment-cross", 400, 120) {
        Frame {
            Row(
                Modifier.fillMaxWidth().height(72f).background(Ink, corner = 6f).padding(8f),
                horizontalArrangement = Arrangement.spacedBy(10f),
                verticalAlignment = VerticalAlignment.Centre,
            ) {
                Slab("Centre", Modifier.width(110f).height(24f), Steel)
                Slab("Top", Modifier.width(110f).height(46f).align(Alignment.TopStart), Deep)
                Slab("Bottom", Modifier.width(110f).height(34f).align(Alignment.BottomStart), Steel)
            }
        }
    })

    add(DocShot("layout-flow", 420, 310) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                Text("FlowRow", style = "label.dim")
                FlowRow(
                    Modifier.width(380f).background(Ink, corner = 6f).padding(6f),
                    horizontalSpacing = 6f,
                    verticalSpacing = 6f,
                ) {
                    Buffs.forEach { Buff(it) }
                }
                Text("horizontalArrangement = Arrangement.Centre", style = "label.dim")
                FlowRow(
                    Modifier.width(380f).background(Ink, corner = 6f).padding(6f),
                    horizontalSpacing = 6f,
                    verticalSpacing = 6f,
                    horizontalArrangement = Arrangement.Centre,
                ) {
                    Buffs.forEach { Buff(it) }
                }
            }
        }
    })

    add(DocShot("layout-wrap-content", 420, 170) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(6f)) {
                Text("weight(1f).width(40f): stretched across the share anyway", style = "label.dim")
                WrapRow { Modifier.weight(1f) }
                Text("weight(1f).wrapContentWidth(Start / Centre / End).width(40f)", style = "label.dim")
                WrapRow { index ->
                    Modifier.weight(1f).wrapContentWidth(
                        listOf(HorizontalAlignment.Start, HorizontalAlignment.Centre, HorizontalAlignment.End)[index],
                    )
                }
            }
        }
    })

    add(DocShot("layout-baseline", 460, 150) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(24f)) {
                Labelled("VerticalAlignment.Top") { Readout(VerticalAlignment.Top) }
                Labelled("VerticalAlignment.Baseline") { Readout(VerticalAlignment.Baseline) }
            }
        }
    })

    // Two sizes side by side, because that is where the difference shows: the same top padding
    // leaves their letters on two different lines, the same baseline padding puts them on one.
    add(DocShot("layout-padding-from-baseline", 360, 220) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(12f)) {
                Labelled("padding(top = 10f)") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                        Heading(Modifier.padding(left = 10f, top = 10f), Display)
                        Heading(Modifier.padding(left = 10f, top = 10f), Body)
                    }
                }
                Labelled("paddingFrom(Baseline.First, before = 44f)") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                        Heading(Modifier.padding(left = 10f).paddingFrom(Baseline.First, before = 44f), Display)
                        Heading(Modifier.padding(left = 10f).paddingFrom(Baseline.First, before = 44f), Body)
                    }
                }
            }
        }
    })

    add(DocShot("layout-overflow", 480, 230) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(16f)) {
                Column(verticalArrangement = Arrangement.spacedBy(6f)) {
                    Text("A column with no room", style = "label.dim")
                    Panel(Modifier.width(200f).height(150f)) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f)) {
                            repeat(8) { Text("line ${it + 1}") }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6f)) {
                    Text("The same, in a ScrollArea", style = "label.dim")
                    Panel(Modifier.width(200f).height(150f)) {
                        ScrollArea {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f)) {
                                repeat(8) { Text("line ${it + 1}") }
                            }
                        }
                    }
                }
            }
        }
    })

    // Custom-layouts: the same slot layout twice, the second with a badge written before the icon.
    // The icon staying put is the whole picture.
    add(DocShot("layout-slots", 420, 130) {
        Frame {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f)) {
                SlotItem("no badge", badge = false)
                SlotItem("badge written first", badge = true)
            }
        }
    })
}

/** A list item's three named parts, found by name, so a badge that comes and goes moves nothing. */
private val ListItemSlots = MeasurePolicy { measurables, constraints ->
    val placeables = measurables.map { it.measure(constraints.loosen()) }
    fun slot(id: String) =
        measurables.indexOfFirst { it.layoutId == id }.takeIf { it >= 0 }?.let { placeables[it] }

    val icon = slot("icon")
    val label = slot("label")
    val badge = slot("badge")
    val width = constraints.maxWidth
    val height = placeables.maxOfOrNull { it.height } ?: 0f

    layout(width, height) {
        icon?.at(0f, (height - icon.height) / 2f)
        label?.at((icon?.width ?: 0f) + 10f, (height - label.height) / 2f)
        badge?.at(width - badge.width, (height - badge.height) / 2f)
    }
}

@Composable
private fun SlotItem(text: String, badge: Boolean) {
    Layout(
        Modifier.fillMaxWidth().background(Ink, corner = 6f).padding(6f),
        measurePolicy = ListItemSlots,
        content = {
            if (badge) Slab("badge", Modifier.width(70f).layoutId("badge"), Deep)
            Slab("icon", Modifier.width(48f).layoutId("icon"), Steel)
            Text(text, Modifier.layoutId("label"))
        },
    )
}

// ---------------------------------------------------------------- widgets

private fun MutableList<DocShot>.widgets() {
    add(DocShot("widget-button", 180, 70) {
        Frame { Button("ENGAGE", onClick = {}) }
    })

    add(DocShot("widget-button-hover", 180, 70, pointer = Offset(90f, 35f)) {
        Frame { Button("ENGAGE", onClick = {}) }
    })

    add(DocShot("widget-button-pressed", 180, 70, pointer = Offset(90f, 35f), press = true) {
        Frame { Button("ENGAGE", onClick = {}) }
    })

    add(DocShot("widget-button-focused", 180, 70) {
        Frame { Button("ENGAGE", onClick = {}, initialFocus = true) }
    })

    add(DocShot("widget-button-disabled", 180, 70) {
        Frame { Button("ENGAGE", onClick = {}, enabled = false) }
    })

    add(DocShot("widget-text", 380, 170) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                Text("label.title", style = "label.title")
                Text("label — the default", style = "label")
                Text("label.dim, for anything secondary", style = "label.dim")
                Text("label.body wraps when it runs out of room, which is what a paragraph in a briefing does.", Modifier.width(320f), style = "label.body")
            }
        }
    })

    // The same row three times, only the text size changing: the words and the button round them
    // grow, the crest beside them and the panel's padding do not.
    add(DocShot("widget-text-scale", 420, 230) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                listOf(1f, 1.25f, 1.5f).forEach { scale ->
                    ProvideTextScale(scale) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10f), verticalAlignment = VerticalAlignment.Centre) {
                            Image("icon/crest", Modifier.size(24f))
                            Text("${(scale * 100).toInt()}%", Modifier.width(56f), style = "label.dim")
                            Text("HULL 148", style = "label")
                            Button("ENGAGE", onClick = {})
                        }
                    }
                }
            }
        }
    })

    // A chat panel: every line mixes the skin's own font with characters it does not have, taken
    // one at a time from the fallbacks Main registers — Chinese, Japanese, Korean and emoji.
    add(DocShot("widget-text-fallback", 360, 190) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                Text("Ace: GG 👍", style = "label")
                Text("玩家: 你好！欢迎来到游戏 🎮", style = "label")
                Text("プレイヤー: こんにちは 😀", style = "label")
                Text("플레이어: 안녕하세요 ❤️", style = "label")
                Text("Nova: 🚀🔥", style = "label.dim")
            }
        }
    })

    add(DocShot("widget-field", 320, 150) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(14f)) {
                TextField("Commander", onValueChange = {}, modifier = Modifier.width(240f))
                TextField("", onValueChange = {}, modifier = Modifier.width(240f), placeholder = "Call sign")
                TextField("Focused", onValueChange = {}, modifier = Modifier.width(240f), initialFocus = true)
            }
        }
    })

    // A real press and drag through the router, from the start of the code to past its end.
    add(DocShot("widget-selection", 320, 110, pointer = Offset(117f, 44f), dragTo = Offset(300f, 44f)) {
        Frame {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                    Text("Seed: 8F3A-22C1")
                    Text("Drag to select, Ctrl+C to copy.", style = "label.dim")
                }
            }
        }
    })

    // One options panel from one set of strings, twice: in English, and in Hebrew, which mirrors it.
    // The last line is a Hebrew sentence with an English name in it, drawn in reading order.
    add(DocShot("localisation-rtl", 600, 200) {
        val strings = remember {
            Strings(
                mapOf(
                    Locale.English to mapOf(
                        "title" to "OPTIONS",
                        "music" to "Music",
                        "name" to "Name",
                        "welcome" to "Welcome back, Ada!",
                    ),
                    Locale("he") to mapOf(
                        "title" to "אפשרויות",
                        "music" to "מוזיקה",
                        "name" to "שם",
                        "welcome" to "ברוך שובך, Ada!",
                    ),
                ),
            )
        }
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(48f)) {
                for (language in listOf(Locale.English, Locale("he"))) {
                    ProvideLocale(language, strings) {
                        Column(Modifier.width(240f), verticalArrangement = Arrangement.spacedBy(10f)) {
                            Text(stringOf("title"), style = "label")
                            Checkbox(true, onCheckedChange = {}, label = stringOf("music"))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10f),
                                verticalAlignment = VerticalAlignment.Centre,
                            ) {
                                Text(stringOf("name"))
                                TextField(if (language == Locale.English) "Ada" else "עדה", onValueChange = {}, modifier = Modifier.weight(1f))
                            }
                            Text(stringOf("welcome"), style = "label.dim")
                        }
                    }
                }
            }
        }
    })

    // Through the stock skin, which is where the keyboard's own styles are. Opened by hand with the
    // shift lit, because a picture has no pad to open it with.
    add(DocShot("widget-gamepad-keyboard", 640, 330, stock = true, seconds = 0.2f) {
        val keyboard = remember {
            GamepadKeyboard().apply {
                open(
                    object : KeyboardTarget {
                        override val value = TextFieldValue("Nova", TextRange(4))
                        override val multiline = false
                        override fun onText(event: TextEvent) = false
                        override fun onKey(event: KeyEvent) = false
                    },
                    // No device: the shot has no pad, and one opened "from a pad" would close itself.
                    from = null,
                )
                toggleShift()
            }
        }
        Frame {
            ProvideGamepadKeyboard(keyboard, modifier = Modifier.width(600f)) {
                Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                    Text("NAME YOUR SQUAD")
                    TextField("Nova", onValueChange = {}, modifier = Modifier.width(260f))
                }
            }
        }
    })

    add(DocShot("widget-slider", 300, 110) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(16f)) {
                Slider(0.35f, onValueChange = {}, modifier = Modifier.width(220f))
                Slider(0.8f, onValueChange = {}, modifier = Modifier.width(220f), initialFocus = true)
            }
        }
    })

    // Dragged by a real press, twelve moves and a release — not placed there by hand.
    add(DocShot("input-drag", 440, 260, pointer = Offset(70f, 50f), dragTo = Offset(230f, 130f)) {
        var at by remember { mutableStateOf(Offset(30f, 30f)) }
        Box(Modifier.fillMaxSize().background(Colour.rgb(0x0B0E13))) {
            Box(Modifier.offset(30f, 30f).size(220f, 120f).border(Steel, width = 2f, corner = 6f)) {}
            Panel(Modifier.offset(at.x, at.y).size(220f, 120f).draggable { at += it }) {
                Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                    Text("INVENTORY")
                    Text("Drag me by the frame", style = "label.dim")
                }
            }
        }
    })

    add(DocShot("widget-stepper", 300, 160) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(12f)) {
                Stepper(listOf("Low", "Medium", "High"), "Medium", onSelect = {})
                NumberStepper(7, onValueChange = {}, range = 0..10, initialFocus = true)
                Stepper(listOf("Story", "Normal", "Veteran"), "Story", onSelect = {})
            }
        }
    })

    // The same options panel twice, one in each shipped skin: what a player sees before and after
    // moving the skin stepper. Same sizes, same places, only the look changed.
    add(DocShot("skins-switch", 560, 200, stock = true) {
        Row(Modifier.padding(12f), horizontalArrangement = Arrangement.spacedBy(12f)) {
            listOf(Skin.Default, Skin.HighContrast).forEach { skin ->
                ProvideSkin(skin) {
                    Box(Modifier.size(262f, 176f).styled("panel")) {
                        Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                            Text("OPTIONS", style = "label.heading")
                            Checkbox(true, onCheckedChange = {}, label = "Subtitles")
                            Stepper(listOf(Skin.Default, Skin.HighContrast), skin, onSelect = {}, label = { it.name }, initialFocus = true)
                            Button("APPLY", onClick = {}, style = "button.primary")
                        }
                    }
                }
            }
        }
    })

    // Picked up out of the first slot by a real press and carried over the third, still held.
    add(DocShot("input-drag-drop", 440, 200, pointer = Offset(92f, 112f), dragTo = Offset(232f, 118f), hold = true) {
        val slots = remember { mutableStateListOf<String?>("SWORD", "BOW", null, null) }
        val colours = mapOf("SWORD" to Colour.rgb(0xE0303A), "BOW" to Colour.rgb(0x3070E0))
        DragAndDropHost {
            Box(Modifier.fillMaxSize().background(Colour.rgb(0x0B0E13))) {
                Panel(Modifier.offset(30f, 40f).width(380f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                        Text("BACKPACK")
                        Row(horizontalArrangement = Arrangement.spacedBy(10f)) {
                            slots.forEachIndexed { index, item ->
                                val look = remember { DropTargetState() }
                                var slot = Modifier.size(70f, 70f)
                                if (item != null) {
                                    slot = slot.dragSource(payload = item) {
                                        Box(Modifier.size(50f, 50f).background(colours.getValue(item), corner = 6f).border(Colour.White, width = 2f, corner = 6f))
                                    }
                                }
                                slot = slot.dropTarget<String>(state = look, onDrop = { moved ->
                                    slots[slots.indexOf(moved)] = slots[index]
                                    slots[index] = moved
                                })
                                val edge = when {
                                    look.isHovered -> Colour.rgb(0x30C060)
                                    look.isOffered -> Steel
                                    else -> Colour.rgb(0x252B34)
                                }
                                Box(slot.background(Colour.rgb(0x151A21), corner = 6f).border(edge, width = 2f, corner = 6f), contentAlignment = Alignment.Centre) {
                                    if (item != null) Box(Modifier.size(50f, 50f).background(colours.getValue(item), corner = 6f))
                                }
                            }
                        }
                    }
                }
            }
        }
    })

    add(DocShot("widget-toggle", 300, 175) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(14f)) {
                Toggle(true, onCheckedChange = {}, label = "Grid lines")
                Toggle(false, onCheckedChange = {}, label = "Invert Y")
                Checkbox(true, onCheckedChange = {}, label = "Remember me")
                Checkbox(false, onCheckedChange = {}, label = "Hardcore")
            }
        }
    })

    // Opened by a real click on the field, which sits at the top of the frame, so the picture is
    // the list the toolkit actually opens and lays over the controls underneath.
    add(DocShot("widget-dropdown", 320, 250, pointer = Offset(124f, 41f), click = true, stock = true) {
        Frame {
            PopupHost {
                Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                    Dropdown(
                        options = listOf("1280 x 720", "1600 x 900", "1920 x 1080", "2560 x 1440"),
                        selected = "1920 x 1080",
                        onSelect = {},
                        modifier = Modifier.width(220f),
                    ) { Text(it) }
                    Toggle(true, onCheckedChange = {}, label = "V-Sync")
                    Checkbox(false, onCheckedChange = {}, label = "Show frame rate")
                    Slider(0.6f, onValueChange = {}, modifier = Modifier.width(220f))
                }
            }
        }
    })

    // A colour picker with alpha and team colours, focus on its square as a pad player lands on it.
    add(DocShot("widget-colour-picker", 290, 300, focus = true, stock = true) {
        Frame {
            var tint by remember { mutableStateOf(Colour.argb(0xC04CC2FF)) }
            ColourPicker(
                colour = tint,
                onColourChange = { tint = it },
                alpha = true,
                presets = DocTeamColours,
                initialFocus = true,
            )
        }
    })

    // A settings row whose swatch is really clicked, so the picture is the picker it opens under itself.
    add(DocShot("widget-colour-picker-button", 290, 330, pointer = Offset(222f, 26f), click = true, stock = true) {
        Box(Modifier.fillMaxSize().background(Colour.rgb(0x0B0E13)).padding(14f)) {
            PopupHost {
                var crosshair by remember { mutableStateOf(Colour.rgb(0x46A758)) }
                Row(Modifier.width(220f), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = VerticalAlignment.Centre) {
                    Text("Crosshair")
                    ColourPickerButton(colour = crosshair, onColourChange = { crosshair = it }, presets = DocTeamColours)
                }
            }
        }
    })

    // An armourer's tint mixed by hand: the pointer presses low down the left of the square and drags
    // up and to the right, and is still held at the shutter, so the ring is under the finger and the
    // plate beside it is already wearing the colour the drag has reached.
    add(DocShot(
        "widget-colour-picker-drag",
        370,
        272,
        pointer = Offset(49f, 143f),
        dragTo = Offset(148f, 60f),
        hold = true,
        stock = true,
    ) {
        Box(Modifier.fillMaxSize().background(Colour.rgb(0x0B0E13)).padding(14f)) {
            var armour by remember { mutableStateOf(Hsv(28f, 0.18f, 0.55f).toColour()) }
            Row(horizontalArrangement = Arrangement.spacedBy(14f)) {
                ColourPicker(colour = armour, onColourChange = { armour = it }, presets = DocArmourColours)
                Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                    Text("ARMOUR TINT", style = "label.heading")
                    ColourSwatch(armour, size = 72f)
                    Text(armour.toHex(), style = "label.dim")
                }
            }
        }
    })

    // The hue turned by a pad alone. The right shoulder goes down on the first frame and is still held
    // a second later, when the shutter goes, so the bar has walked a long way down the strip and the
    // square has gone with it. Focus stays on the square, where a pad player starts.
    add(DocShot(
        "widget-colour-picker-pad",
        234,
        300,
        stock = true,
        focus = true,
        seconds = 1f,
        padHold = listOf(GamepadId(0) to GamepadButton.RightBumper),
    ) {
        Frame {
            var squad by remember { mutableStateOf(Hsv(0f, 0.85f, 0.95f).toColour()) }
            Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                Text("SQUAD COLOUR", style = "label.heading")
                ColourPicker(
                    colour = squad,
                    onColourChange = { squad = it },
                    presets = DocTeamColours,
                    initialFocus = true,
                )
            }
        }
    })

    // A level editor's light, right to left in the high-contrast skin: the square mirrors to the right
    // and the strips run down its left, hue then alpha. The pointer really drags down the alpha strip
    // and stays there, so the light is half see-through and the checkerboard shows through its swatch.
    add(DocShot(
        "widget-colour-picker-rtl",
        270,
        296,
        pointer = Offset(42f, 74f),
        dragTo = Offset(42f, 155f),
        hold = true,
        stock = true,
    ) {
        ProvideSkin(Skin.HighContrast) {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize().background(Colour.rgb(0x0B0E13)).padding(14f)) {
                    var light by remember { mutableStateOf(Colour.rgb(0xFFC53D)) }
                    Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                        Text("LAMP COLOUR", style = "label.heading")
                        ColourPicker(
                            colour = light,
                            onColourChange = { light = it },
                            alpha = true,
                            presets = DocLampColours,
                        )
                    }
                }
            }
        }
    })

    // Opened by a real click on File, so the picture is the menu the bar actually drops: shortcuts
    // written beside their items, a greyed-out one, a tick and a line between the groups.
    add(DocShot("widget-menu-bar", 420, 260, pointer = Offset(22f, 13f), click = true, stock = true) {
        PopupHost { EditorMenuBar() }
    })

    // A click on View and then a rest on its Render row, which opens the submenu beside it.
    add(DocShot("widget-menu-submenu", 420, 230, pointer = Offset(70f, 13f), click = true, then = Offset(90f, 108f), seconds = 0.5f, stock = true) {
        PopupHost { EditorMenuBar() }
    })

    // A real right-click on the second slot, and the menu opens where the pointer is.
    add(DocShot("widget-context-menu", 380, 190, pointer = Offset(136f, 58f), click = true, button = PointerButton.Secondary, stock = true) {
        Frame {
            PopupHost {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                        listOf("Potion", "Sword", "Rope", "Map").forEach { item ->
                            Box(
                                Modifier.size(76f, 64f).styled("panel").contextMenu {
                                    Item("&Use") {}
                                    Item("S&plit stack", enabled = item == "Potion") {}
                                    Separator()
                                    Item("&Drop") {}
                                },
                                contentAlignment = Alignment.Centre,
                            ) { Text(item, style = "label.dim") }
                        }
                    }
                }
            }
        }
    })

    // The same bar right to left, in the high-contrast skin: it reads from the right, the menu hangs
    // from its title's right edge, and the submenu opens to the left.
    add(DocShot("widget-menu-rtl", 420, 230, pointer = Offset(350f, 13f), click = true, then = Offset(330f, 108f), seconds = 0.5f, stock = true) {
        ProvideSkin(Skin.HighContrast) {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                PopupHost { EditorMenuBar() }
            }
        }
    })

    // Three sections of a settings page: Physics open with its slider and toggle, the other two folded.
    add(DocShot("widget-collapsing-header", 380, 250, stock = true) {
        Frame { SettingsSections() }
    })

    // A real click on the folded Audio header, taken part way through, so its contents are still
    // growing in and Graphics is sliding down to make room.
    add(DocShot("widget-collapsing-header-opening", 380, 250, pointer = Offset(120f, 146f), click = true, stock = true) {
        Frame { SettingsSections() }
    })

    // Right to left in the high-contrast skin, with focus on a folded header as a pad or Tab leaves
    // it: the triangle sits at the right, and the closed ones point left.
    add(DocShot("widget-collapsing-header-rtl", 380, 250, focus = true) {
        ProvideSkin(Skin.HighContrast) {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                Frame { SettingsSections(focusAudio = true) }
            }
        }
    })

    // Working, nobody knows how long: a loading card with a bar under its header, a "Connecting..."
    // button with a spinner in it, a saving note, a few sizes and a vertical bar, and the same two in
    // the high-contrast skin. Taken part way into a turn, so the arc is stretched and the blocks are
    // mid-track.
    add(DocShot("widget-spinner", SpinnerShotWidth, SpinnerShotHeight, stock = true, seconds = 0.3f) {
        Frame { WorkingScene() }
    })

    // The same screen at evenly spaced moments across one second, for the animated picture. Only when
    // asked for, because they are frames to be joined into a GIF, not pictures of their own:
    // `COMPOSEGL_DOC_FRAMES=1`, then join `widget-spinner-frame-*.png` in order, 1/20 s each.
    if (System.getenv("COMPOSEGL_DOC_FRAMES") != null) {
        repeat(SpinnerFrames) { i ->
            val name = "widget-spinner-frame-${i.toString().padStart(2, '0')}"
            // Three frames at sixty a second apart: 20 of them are exactly one second, a turn and a sweep.
            add(DocShot(name, SpinnerShotWidth, SpinnerShotHeight, stock = true, seconds = i * 3 / 60f) {
                Frame { WorkingScene() }
            })
        }
    }

    // A level editor in nested splitters: the hierarchy beside a map stacked over a log. The divider
    // beside the hierarchy is really pressed and dragged right, and still held, so it is the pressed
    // colour and the hierarchy has grown to where the pointer is.
    add(DocShot("widget-splitter", 460, 280, pointer = Offset(143f, 140f), dragTo = Offset(200f, 140f), hold = true, stock = true) {
        EditorSplit()
    })

    // Right to left in the high-contrast skin, with focus on the divider as Tab or a pad leaves it:
    // the hierarchy has moved to the right, and the arrows would move the ringed divider.
    add(DocShot("widget-splitter-rtl", 460, 280, focus = true) {
        ProvideSkin(Skin.HighContrast) {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                EditorSplit(focusDivider = true)
            }
        }
    })

    // A scoreboard sorted by kills, highest first, so the Kills title is lit with its arrow pointing
    // down, and the player's own row is the selected one.
    add(DocShot("widget-table-scoreboard", 460, 250, stock = true) {
        Frame { Scoreboard() }
    })

    // A server browser scrolled well down its list, with the header still at the top. The divider
    // between Server and Map is really pressed and dragged right, in one quick flick between frames,
    // and still held, so it is lit and Server has grown into Map up to where the pointer is.
    add(
        DocShot(
            "widget-table-resize", 480, 260,
            pointer = Offset(TableDividerFrom, 32f), dragTo = Offset(TableDividerTo, 32f), hold = true,
            stock = true, seconds = 0.2f,
        ) {
            Frame { ServerBrowser() }
        },
    )

    // Right to left in the high-contrast skin: a backpack sorted by value, lowest first, with the pad
    // stepped down into the rows, so a focus ring sits on one of them.
    add(
        DocShot(
            "widget-table-rtl", 460, 250,
            focus = true,
            pads = listOf(GamepadId(0) to GamepadButton.DpadDown, GamepadId(0) to GamepadButton.DpadDown),
        ) {
            ProvideSkin(Skin.HighContrast) {
                ProvideLayoutDirection(LayoutDirection.Rtl) {
                    Frame { Inventory() }
                }
            }
        },
    )

    add(DocShot("widget-panel", 420, 150) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(14f)) {
                Panel(Modifier.width(180f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6f)) {
                        Text("panel")
                        Text("the default surface", style = "label.dim")
                    }
                }
                Panel(Modifier.width(180f), style = "panel.flat") {
                    Column(verticalArrangement = Arrangement.spacedBy(6f)) {
                        Text("panel.flat")
                        Text("a second style, same widget", style = "label.dim")
                    }
                }
            }
        }
    })

    add(DocShot("widget-divider", 360, 190) {
        Frame {
            Panel(Modifier.width(300f)) {
                Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                    Text("AUDIO")
                    Text("Music 80%   Effects 65%", style = "label.dim")
                    Divider()
                    Text("VIDEO")
                    Row(Modifier.height(22f), horizontalArrangement = Arrangement.spacedBy(10f)) {
                        Text("1920x1080", style = "label.dim")
                        Divider(vertical = true)
                        Text("V-Sync", style = "label.dim")
                        Divider(vertical = true, thickness = 2f)
                        Text("144 Hz", style = "label.dim")
                    }
                }
            }
        }
    })

    add(DocShot("widget-scroll", 260, 220) {
        Frame {
            Panel(Modifier.width(220f).height(180f)) {
                ScrollArea {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f)) {
                        repeat(12) { Text("Save ${it + 1} — Sector ${it + 3}") }
                    }
                }
            }
        }
    })

    // A scene hierarchy with two levels open, the guides running down beside them and one row chosen.
    add(DocShot("widget-tree", 260, 250, stock = true) {
        class Entry(val name: String, vararg val kids: Entry)
        val roots = listOf(
            Entry(
                "World",
                Entry("Player", Entry("Camera"), Entry("Weapon")),
                Entry("Enemies", Entry("Drone"), Entry("Turret")),
            ),
            Entry("Lights", Entry("Sun")),
            Entry("Sky"),
        )
        Frame {
            Panel(Modifier.width(220f).height(210f)) {
                TreeView(
                    roots = roots,
                    children = { it.kids.toList() },
                    key = { it.name },
                    modifier = Modifier.fillMaxWidth(),
                    selected = roots[0].kids[0].kids[1],
                    state = rememberTreeState("World", "Player"),
                ) { entry, _ -> Text(entry.name) }
            }
        }
    })

	// A quest log on a pad: the pad stepped down to A Crown of Thorns and pressed South to choose it,
	// then went on down to Side quests and pressed Right, which opened it. Done stays shut.
	add(
		DocShot(
			"widget-tree-quest-log", 340, 280,
			focus = true,
			stock = true,
			pads = listOf(
				GamepadId(0) to GamepadButton.DpadDown,
				GamepadId(0) to GamepadButton.DpadDown,
				GamepadId(0) to GamepadButton.DpadDown,
				GamepadId(0) to GamepadButton.South,
				GamepadId(0) to GamepadButton.DpadDown,
				GamepadId(0) to GamepadButton.DpadRight,
			),
		) {
			Frame { QuestLog() }
		},
	)

	// A save picker, clicked on the arrow of a shut folder: the click really opened it, and the
	// pointer rests there, so the arrow is lit.
	add(DocShot("widget-tree-click", 300, 230, pointer = Offset(TreeClickArrowX, TreeClickArrowY), click = true, stock = true) {
		Frame { SavePicker() }
	})

	// Right to left in the high-contrast skin: a bestiary with the indent coming in from the right, the
	// shut rows' arrows pointing left, and the pad's focus on a row.
	add(
		DocShot(
			"widget-tree-rtl", 300, 230,
			focus = true,
			pads = listOf(GamepadId(0) to GamepadButton.DpadDown, GamepadId(0) to GamepadButton.DpadDown),
		) {
			ProvideSkin(Skin.HighContrast) {
				ProvideLayoutDirection(LayoutDirection.Rtl) {
					Frame { Bestiary() }
				}
			}
		},
	)

    // Left and come back to. The script inside picks an entry and scrolls, goes to the map, and
    // comes back, so what is photographed is the state the holder really kept rather than a screen
    // that was never left.
    add(DocShot("widget-saveable", 320, 230, seconds = 0.6f) {
        var screen by remember { mutableStateOf("codex") }
        var returned by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            repeat(10) { withFrameNanos {} }
            screen = "map"
            repeat(5) { withFrameNanos {} }
            screen = "codex"
            returned = true
        }
        Frame {
            Panel(Modifier.width(280f).height(190f)) {
                Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                    Text(if (returned) "CODEX, BACK FROM THE MAP" else "CODEX")
                    SaveableStateHolder(screen) { key ->
                        if (key == "codex") CodexPage() else Text("MAP")
                    }
                }
            }
        }
    })

    // The pointer lands in the middle of the frame, which is where the button is, and the shot
    // runs long enough for the tooltip's own dwell and fade to finish.
    add(DocShot("widget-tooltip", 360, 170, pointer = Offset(180f, 85f), seconds = 1.4f) {
        Frame {
            TooltipHost {
                Box(Modifier.fillMaxSize()) {
                    Tooltip("Costs 40 energy. Cools down in 12 seconds.", Modifier.align(Alignment.Centre)) {
                        Button("OVERCHARGE", onClick = {})
                    }
                }
            }
        }
    })

    // The same menu twice: at rest, and a fifth of a second into closing — still on screen, fading
    // and shrinking, which is the thing `if (open)` cannot do.
    add(DocShot("widget-animated-visibility", 460, 200, seconds = 0.2f) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(20f)) {
                listOf("OPEN" to false, "CLOSING" to true).forEach { (title, closes) ->
                    var open by remember { mutableStateOf(true) }
                    LaunchedEffect(closes) { if (closes) open = false }
                    Box(Modifier.size(200f, 160f), contentAlignment = Alignment.Centre) {
                        val out = Tween(400, easing = Easings.Linear)
                        AnimatedVisibility(open, exit = fadeOut(spec = out) + scaleOut(to = 0.6f, spec = out)) {
                            Panel(Modifier.width(200f)) {
                                Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                                    Text(title)
                                    Button("RESUME", onClick = {})
                                }
                            }
                        }
                    }
                }
            }
        }
    })

    // A menu page a fifth of a second into fading to the options page: both on screen at once, one
    // going and one coming, which is the thing `when (page)` cannot do.
    add(DocShot("widget-crossfade", 460, 200, seconds = 0.2f) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(20f)) {
                listOf("MAIN" to false, "FADING" to true).forEach { (_, switches) ->
                    var options by remember { mutableStateOf(false) }
                    LaunchedEffect(switches) { if (switches) options = true }
                    Crossfade(
                        options,
                        Modifier.size(200f, 160f),
                        spec = Tween(400, easing = Easings.Linear),
                        contentAlignment = Alignment.Centre,
                    ) { showingOptions ->
                        Panel(Modifier.width(200f)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                                if (showingOptions) {
                                    Text("OPTIONS")
                                    Button("VOLUME", onClick = {})
                                } else {
                                    Text("MAIN MENU")
                                    Button("PLAY", onClick = {})
                                }
                            }
                        }
                    }
                }
            }
        }
    })

    // Three cards off the same transition: resting, a tenth of a second into being picked — grown,
    // lifted and lit part way, all by the same amount — and picked.
    add(DocShot("widget-transition", 460, 170, seconds = 0.1f) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(20f), verticalAlignment = VerticalAlignment.Bottom) {
                listOf("RESTING" to null, "PICKING" to Tween(200, easing = Easings.Linear), "PICKED" to Tween(1)).forEach { (title, spec) ->
                    var picked by remember { mutableStateOf(false) }
                    LaunchedEffect(spec) { if (spec != null) picked = true }
                    val t = updateTransition(picked)
                    val lift by t.animateFloat({ spec ?: Tween() }) { if (it) -20f else 0f }
                    val size by t.animateSize({ spec ?: Tween() }) { if (it) Size(130f, 110f) else Size(110f, 90f) }
                    val colour by t.animateColour({ spec ?: Tween() }) { if (it) Colour.rgb(0xE8A33D) else Colour.rgb(0x2A2F3A) }
                    Box(Modifier.size(130f, 130f), contentAlignment = Alignment.BottomCentre) {
                        Box(
                            Modifier.offset(y = lift).size(size.width, size.height).background(colour),
                            contentAlignment = Alignment.Centre,
                        ) { Text(title) }
                    }
                }
            }
        }
    })

    // A card carousel at rest beside the same carousel a fifth of a second into sliding to the next
    // card: the old card half gone to the left, the new one half in from the right, both cut off at
    // the carousel's edge.
    add(DocShot("widget-animated-content", 460, 150, seconds = 0.2f) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(20f)) {
                listOf(false, true).forEach { slides ->
                    var card by remember { mutableStateOf(1) }
                    LaunchedEffect(slides) { if (slides) card = 2 }
                    AnimatedContent(
                        card,
                        transition = { from, to ->
                            if (to > from) slideLeft(Tween(400, easing = Easings.Linear)) else slideRight(Tween(400, easing = Easings.Linear))
                        },
                    ) { shown ->
                        Panel(Modifier.size(200f, 110f)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                                Text("CARD $shown")
                                Button(if (shown == 1) "FIRE BOLT" else "ICE LANCE", onClick = {})
                            }
                        }
                    }
                }
            }
        }
    })

    add(DocShot("widget-chips", 420, 80) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                Button("GENTLE", onClick = {}, style = "chip")
                Button("NORMAL", onClick = {}, style = "chip.chosen")
                Button("BRISK", onClick = {}, style = "chip")
                Button("ABORT", onClick = {}, style = "chip.danger")
            }
        }
    })

    // The sheet's eight frames laid out as the atlas holds them, and the same frames playing. A
    // quarter of a second in, so the playing coin is caught partway round rather than on frame 0.
    add(DocShot("widget-animated", 420, 150, seconds = 0.25f) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                Text("coin_0 … coin_7", style = "label.dim")
                Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                    repeat(8) { Image("coin_$it", Modifier.size(32f)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12f), verticalAlignment = VerticalAlignment.Centre) {
                    AnimatedImage(rememberSpriteAnimation("coin_", fps = 12f), Modifier.size(48f))
                    Text("AnimatedImage at 12 fps")
                }
            }
        }
    })

    // A controls screen mid-rebind: one binding waiting for a press, the others showing a key, a
    // mouse button and a pad button. Through the stock skin, which is where the amber waiting
    // state is defined.
    add(DocShot("widget-keybind", 360, 200, stock = true) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                BindRow("JUMP", InputBinding.Keyboard(Key.Space))
                BindRow("CROUCH", InputBinding.Keyboard(Key.C), listening = true)
                BindRow("FIRE", InputBinding.Mouse(dev.wildware.composegl.ui.input.PointerButton.Primary))
                BindRow("INTERACT", InputBinding.Gamepad(GamepadButton.West))
            }
        }
    })

    // A world map really zoomed and really dragged: three notches of the wheel at the pointer, and
    // then a drag down and left that brings Castle Vey in from off the right. Half again the size,
    // and the names and the pin edges are as sharp as at their own — they are drawn at that size
    // rather than magnified from a picture of themselves.
    add(
        DocShot(
            "widget-panzoom-map", 460, 280,
            pointer = Offset(300f, 150f),
            wheel = 3,
            dragTo = Offset(218f, 187f),
            stock = true,
            seconds = 0.5f,
        ) {
            Frame { WorldMapScene() }
        },
    )

    // The same map, the same wheel, turned the other way: five notches back is the overview, the
    // whole coast in the window at half size — except the "you are here" pin, which keeps its own
    // size at every zoom because that is what a pin is for.
    add(
        DocShot(
            "widget-panzoom-overview", 460, 280,
            pointer = Offset(230f, 150f),
            wheel = -5,
            stock = true,
            seconds = 0.5f,
        ) {
            Frame { WorldMapScene() }
        },
    )

    // A crafting graph in the high-contrast skin, right to left, on a pad: down then right walked
    // focus from Ore to Ingot to Rod, and the camera eased along to keep the ringed node in view —
    // Ore has gone off the left edge. The panel reads right to left; the graph does not, because a
    // diagram is a picture rather than a line of text.
    add(
        DocShot(
            "widget-panzoom-graph-rtl", 460, 280,
            focus = true,
            seconds = 0.6f,
            pads = listOf(GamepadId(0) to GamepadButton.DpadDown, GamepadId(0) to GamepadButton.DpadRight),
        ) {
            ProvideSkin(Skin.HighContrast) {
                ProvideLayoutDirection(LayoutDirection.Rtl) {
                    Frame { CraftingGraph() }
                }
            }
        },
    )

}

// ---------------------------------------------------------------- game widgets

private fun MutableList<DocShot>.game() {
    add(DocShot("game-bars", 320, 230) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(13f)) {
                Labelled("HEALTH — the default \"bar\"") { Bar(0.72f, length = 190f) }
                Labelled("SHIELD — \"bar.shield\", in six segments") {
                    Bar(0.5f, style = "bar.shield", length = 190f, segments = 6)
                }
                Labelled("STAMINA — \"bar.stamina\"") {
                    Bar(0.62f, style = "bar.stamina", length = 190f)
                }
                Labelled("the same bar under a threshold") {
                    Bar(
                        0.18f,
                        style = "bar.stamina",
                        length = 190f,
                        thresholds = listOf(BarThreshold(0.25f, "bar.fill.critical")),
                    )
                }
            }
        }
    })

    // Drawn large: the crosshair is a few thin lines, and at its real size in a screenshot it is
    // a speck. The arms and the gap are parameters, so this is the same widget either way.
    add(DocShot("game-reticle", 360, 200, seconds = 0.6f) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(24f)) {
                Labelled("still") {
                    Box(Modifier.size(140f).background(Ink, corner = 6f)) {
                        Reticle(rememberReticleState(), gap = 10f, arm = 22f, thickness = 4f, dot = 3f)
                    }
                }
                Labelled("moving, over something hostile") {
                    val state = rememberReticleState()
                    // Set from an effect rather than in composition: writing state while
                    // composing is what a recomposition loop is made of.
                    LaunchedEffect(state) {
                        state.spread = 0.8f
                        state.hostile = true
                    }
                    Box(Modifier.size(140f).background(Ink, corner = 6f)) {
                        Reticle(state, gap = 10f, arm = 22f, thickness = 4f, spreadDistance = 26f, dot = 3f)
                    }
                }
            }
        }
    })

    add(DocShot("game-hotbar", 400, 110) {
        Frame {
            Hotbar(
                slots = listOf(
                    HotbarSlot(icon = "icon/crest", charges = 3),
                    HotbarSlot(icon = "icon/crest"),
                    HotbarSlot(),
                    HotbarSlot(icon = "icon/crest", charges = 1),
                    HotbarSlot(),
                ),
                selected = 1,
            )
        }
    })

    // The sweep is a dark wedge drawn over the ability, so a shot of one with nothing underneath
    // is a black square. The icon and the slot behind it are what it is covering.
    add(DocShot("game-cooldown", 180, 180, seconds = 1.1f) {
        Frame {
            Box(Modifier.fillMaxSize()) {
                val cooldown = rememberCooldown(4_000)
                LaunchedEffect(cooldown) { cooldown.trigger() }
                RadialCooldown(
                    cooldown,
                    Modifier.align(Alignment.Centre).size(96f).background(Steel, corner = 8f)
                        .border(Accent, width = 2f, corner = 8f),
                ) {
                    Image("icon/crest", Modifier.size(52f))
                }
            }
        }
    })

    // A real hold: the pointer goes down on + and stays down for the second and a half before the
    // shutter, so the count is whatever repeatingClickable actually reached on the clock.
    add(DocShot("input-hold-to-repeat", 300, 110, pointer = Offset(230f, 55f), press = true, seconds = 1.5f) {
        Frame {
            var count by remember { mutableStateOf(1) }
            Row(verticalAlignment = VerticalAlignment.Centre, horizontalArrangement = Arrangement.spacedBy(12f)) {
                Box(
                    Modifier.size(56f, 48f).background(Steel, corner = 6f).repeatingClickable { if (count > 1) count-- },
                    contentAlignment = Alignment.Centre,
                ) { Text("-", style = "label") }
                Box(Modifier.size(80f, 48f).background(Ink, corner = 6f), contentAlignment = Alignment.Centre) {
                    Text("x$count", style = "label")
                }
                Box(
                    Modifier.size(56f, 48f).background(Accent, corner = 6f).repeatingClickable { count++ },
                    contentAlignment = Alignment.Centre,
                ) { Text("+", style = "label") }
            }
        }
    })

    // A map with nowhere to hop: the pad drives a cursor, which has settled on the harbour and
    // turned gold because there is something there to click.
    add(DocShot("input-virtual-cursor", 340, 200, padCursor = Offset(236f, 118f)) {
        Frame {
            VirtualCursor(enabled = true) {
                Box(Modifier.fillMaxSize().background(Ink, corner = 6f)) {
                    listOf(
                        Triple("Keep", 40f, 40f),
                        Triple("Mill", 90f, 130f),
                        Triple("Harbour", 180f, 90f),
                        Triple("Pass", 250f, 30f),
                    ).forEach { (name, x, y) ->
                        Box(
                            Modifier.offset(x, y).size(84f, 28f).background(Steel, corner = 14f).clickable { },
                            contentAlignment = Alignment.Centre,
                        ) { Text(name, style = "label") }
                    }
                }
            }
        }
    })

    add(DocShot("game-minimap", 220, 220) {
        Frame {
            Box(Modifier.fillMaxSize()) {
                MinimapFrame(
                    Modifier.align(Alignment.Centre).size(170f),
                    heading = 0.6f,
                    range = 100f,
                    markers = listOf(
                        MinimapMarker(20f, -30f),
                        MinimapMarker(-40f, 10f),
                        MinimapMarker(35f, 45f, style = "minimap.objective"),
                    ),
                )
            }
        }
    })

    // The toolkit's own skin, because the strip's look is one of the things the picture is of, and
    // a quest thirty degrees off is what a player actually sees while they are walking towards it.
    add(DocShot("game-compass", 520, 92, stock = true) {
        Frame {
            Box(Modifier.fillMaxSize()) {
                CompassBar(
                    heading = 24f,
                    fieldOfView = 180f,
                    Modifier.align(Alignment.Centre).width(480f),
                    readout = { "${it.toInt()}°" },
                    distanceText = { "${it.toInt()}m" },
                    live = false,
                ) {
                    pin(bearing = 58f, distance = 140f)
                    pin(bearing = 340f, distance = 62f, style = "label.danger")
                    // Behind the player, so it is pinned to the end with an arrow on it.
                    pin(bearing = 200f, distance = 410f, fadeWithDistance = true)
                }
            }
        }
    })
}

// ---------------------------------------------------------------- modifiers

private fun MutableList<DocShot>.modifiers() {
    add(DocShot("modifier-decoration", 460, 120) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(14f)) {
                Tile("background") { Modifier.background(Steel, corner = 6f) }
                Tile("border") { Modifier.border(Accent, width = 2f, corner = 6f) }
                // In the accent rather than black: a black shadow on a black page photographs
                // as nothing at all. The modifier is the same one either way.
                Tile("shadow") { Modifier.shadow(Accent.scaleAlpha(0.7f), spread = 14f, corner = 6f).background(Steel, corner = 6f) }
                Tile("alpha") { Modifier.alpha(0.35f).background(Accent, corner = 6f) }
            }
        }
    })

    add(DocShot("modifier-corners", 420, 150) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(22f)) {
                Labelled("a tab") {
                    // Rounded along the top only, so the selected tab and its panel read as one.
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(4f)) {
                            Box(Modifier.size(52f, 22f).background(Accent, Corners.top(8f))) {}
                            Box(Modifier.size(52f, 22f).background(Steel, Corners.top(8f))) {}
                        }
                        Box(Modifier.size(126f, 50f).background(Accent, Corners(topRight = 8f, bottomRight = 8f, bottomLeft = 8f))) {}
                    }
                }
                Labelled("a bubble") {
                    val speech = Corners(topLeft = 14f, topRight = 14f, bottomRight = 14f, bottomLeft = 0f)
                    Box(
                        Modifier.size(110f, 60f)
                            .shadow(Accent.scaleAlpha(0.5f), spread = 10f, corners = speech)
                            .background(Steel, speech)
                            .border(Accent, width = 2f, corners = speech),
                    ) {}
                }
                Labelled("docked") {
                    Box(
                        Modifier.size(76f, 72f)
                            .background(Steel, Corners.left(16f))
                            .border(Accent, width = 2f, corners = Corners.left(16f)),
                    ) {}
                }
            }
        }
    })

    // Five panels knocked at the same moment, as hard as each label says, and photographed a
    // tenth of a second in. The same seed for all five, so they lean the same way and the only
    // difference is how far: trauma squared, which is why half a knock barely moves.
    add(DocShot("modifier-shake", 520, 110, seconds = 0.1f) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(18f)) {
                Knocked("trigger(1f)", 1f)
                Knocked("trigger(0.8f)", 0.8f)
                Knocked("trigger(0.6f)", 0.6f)
                Knocked("trigger(0.4f)", 0.4f)
                Knocked("still", 0f)
            }
        }
    })

    // The same tile three ways, so the picture is of the slant and nothing else.
    add(DocShot("modifier-skew", 520, 150) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(36f)) {
                Labelled("no skew") { SkewTile("RANK 1") { Modifier } }
                Labelled("skew(x = -12f)") { SkewTile("RANK 2") { Modifier.skew(x = -12f) } }
                Labelled("skew(y = 8f)") { SkewTile("RANK 3") { Modifier.skew(y = 8f) } }
            }
        }
    })

    // The four things a tint is for, on the same little card: as it is, a damage flash caught
    // halfway, a locked one, and a team colour on white art.
    add(DocShot("modifier-tint", 460, 120) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(14f)) {
                Tile("plain") { Modifier.background(Accent, corner = 6f).border(Paper, width = 2f, corner = 6f) }
                Tile("flash") {
                    Modifier.tint(Colour.Red.scaleAlpha(0.6f))
                        .background(Accent, corner = 6f).border(Paper, width = 2f, corner = 6f)
                }
                Tile("locked") {
                    Modifier.tint(Colour.Grey).background(Accent, corner = 6f).border(Paper, width = 2f, corner = 6f)
                }
                Tile("team") {
                    Modifier.tint(Colour.Orange).background(Paper, corner = 6f).border(Paper, width = 2f, corner = 6f)
                }
            }
        }
    })

    add(DocShot("modifier-gradients", 460, 120) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(14f)) {
                Tile("vertical") { Modifier.background(Brush.vertical(Accent, Deep), corner = 6f) }
                Tile("horizontal") {
                    Modifier.background(Brush.horizontal(Colour.rgb(0x4CD964), Colour.rgb(0xFF3B30)), corner = 6f)
                }
                // Over a pale box, because a vignette over a dark page photographs as nothing.
                Tile("radial") {
                    Modifier.background(Colour.rgb(0xE6EDF5), corner = 6f)
                        .background(Brush.radial(Colour.Transparent, Colour.argb(0xE0000000)), corner = 6f)
                }
                Tile("fade") {
                    Modifier.background(Steel, corner = 6f)
                        .background(Brush.linear(Accent, Accent.withAlpha(0), degrees = 45f), corner = 6f)
                }
            }
        }
    })

    add(DocShot("modifier-borders", 460, 200) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(14f)) {
                Text(
                    "INVENTORY",
                    modifier = Modifier.width(430f).border(bottom = BorderSide(1f, Steel)).padding(bottom = 6f),
                )
                Row {
                    listOf("MAP", "GEAR", "QUESTS").forEachIndexed { index, name ->
                        val underline = if (index == 1) BorderSide(3f, Accent) else BorderSide(1f, Steel)
                        Box(Modifier.border(bottom = underline).padding(horizontal = 18f, vertical = 6f)) {
                            Text(name, style = if (index == 1) "label" else "label.dim")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14f)) {
                    Tile("dashed") { Modifier.border(Accent, width = 2f, style = BorderStyle.Dashed(on = 6f, off = 4f)) }
                    Tile("rounded") { Modifier.border(Accent, width = 2f, corner = 10f, style = BorderStyle.Dashed(on = 6f, off = 4f)) }
                    Tile("dotted") { Modifier.border(Accent, width = 2f, corner = 8f, style = BorderStyle.Dotted) }
                    Tile("left only") { Modifier.background(Steel).border(left = BorderSide(4f, Accent)) }
                }
            }
        }
    })

    add(DocShot("modifier-debug-bounds", 420, 150) {
        Frame {
            // The same padded panel with a button in it, boxed at three depths, so the picture shows
            // the panel, what its padding left, and the button — and that none of them moved.
            Column(
                Modifier.debugBounds(label = true).background(Ink, corner = 6f)
                    .padding(18f).debugBounds(Colour.Yellow),
                verticalArrangement = Arrangement.spacedBy(10f),
            ) {
                Text("SETTINGS", style = "label.dim")
                Row(horizontalArrangement = Arrangement.spacedBy(12f)) {
                    Button("APPLY", onClick = {}, modifier = Modifier.debugBounds(Colour.Cyan, label = true))
                    Box(Modifier.size(0f, 36f).debugBounds(Colour.Orange, label = true)) {}
                }
            }
        }
    })

    // The same tile flat, swung about y, and tipped about x, with a close camera so the depth
    // reads at this size: the near edge grows and the far edge shrinks, text and all.
    add(DocShot("modifier-rotate3d", 560, 170) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(40f)) {
                Labelled("no rotate3d") { SkewTile("CARD 1") { Modifier } }
                Labelled("rotate3d(y = 50f)") {
                    SkewTile("CARD 2") { Modifier.rotate3d(y = 50f, cameraDistance = 3f) }
                }
                Labelled("rotate3d(x = 45f)") {
                    SkewTile("CARD 3") { Modifier.rotate3d(x = 45f, cameraDistance = 3f) }
                }
            }
        }
    })

    // Four name slots photographed at the same moment, three seconds in. The delays and speeds
    // differ so each one is at a different point of its trip: still because it fits, resting,
    // part way across, and coming round with the copy following it in.
    add(DocShot("modifier-marquee", 520, 190, seconds = 3f) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                Scrolling("fits: never moves", "Iron Sword", delayMillis = 0, speed = 30f)
                Scrolling("resting: delayMillis", "Sword of a Thousand Truths", delayMillis = 60_000, speed = 30f)
                Scrolling("scrolling", "Sword of a Thousand Truths", delayMillis = 1_500, speed = 40f)
                Scrolling("coming round", "Sword of a Thousand Truths", delayMillis = 0, speed = 55f)
            }
        }
    })

    // A tuning window over a game, through the toolkit's own skin, since that is the one that names
    // the window's styles. Nothing is clicked: this is what a game gets for writing seven lines.
    add(DocShot("debug-window", 420, 300, stock = true) {
        val physics = remember { DocPhysics() }
        Box(Modifier.fillMaxSize().background(Colour.rgb(0x0B0E13))) {
            DebugWindowHost(state = rememberDebugWindowsState(MemoryDebugWindowStore())) {
                Box(Modifier.fillMaxSize())
                DebugWindow("Physics", initialPosition = Offset(16f, 16f), onClose = {}) {
                    tweak("Gravity", physics::gravity, 0f..50f, step = 0.1f)
                    tweak("Enemies", physics::enemies, 0..40)
                    toggle("God mode", physics::godMode)
                    choice("Difficulty", physics::difficulty, DocDifficulty.entries)
                    colour("Fog", physics::fog, alpha = false)
                    button("Spawn wave") {}
                }
            }
        }
    })

    // Clicked on the APPLY button's edge, so the picture shows a pinned button rather than its label.
    add(DocShot("inspector", 640, 460, pointer = Offset(46f, 94f), click = true) {
        Inspector(enabled = true) {
            Box(Modifier.fillMaxSize().background(Colour.rgb(0x0B0E13))) {
                Column(
                    Modifier.offset(24f, 40f).background(Ink, corner = 6f).padding(18f),
                    verticalArrangement = Arrangement.spacedBy(10f),
                ) {
                    Text("SETTINGS", style = "label.dim")
                    Row(horizontalArrangement = Arrangement.spacedBy(16f)) {
                        Button("APPLY", onClick = {})
                        Button("CANCEL", onClick = {})
                    }
                }
            }
        }
    })

    add(DocShot("layout-overlay", 420, 170) {
        Box(Modifier.fillMaxSize()) {
            Frame {
                // A padded panel with a row of two buttons spaced apart and a label, so the picture has
                // every kind of mark on it: boxes, padding, gaps, and text ink inside its line box.
                Column(
                    Modifier.background(Ink, corner = 6f).padding(18f),
                    verticalArrangement = Arrangement.spacedBy(10f),
                ) {
                    Text("SETTINGS", style = "label.dim")
                    Row(horizontalArrangement = Arrangement.spacedBy(16f)) {
                        Button("APPLY", onClick = {})
                        Button("CANCEL", onClick = {})
                    }
                }
            }
            LayoutOverlay(enabled = true)
        }
    })

    // The same three cards turned the same way twice: each with a camera of its own, so three
    // identical shapes, then under one shared camera, so they recede towards one point.
    add(DocShot("modifier-perspective", 560, 250) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(24f)) {
                Labelled("rotate3d(y = 45f) on each") {
                    Row(horizontalArrangement = Arrangement.spacedBy(40f)) {
                        repeat(3) { index ->
                            SkewTile("CARD ${index + 1}") { Modifier.rotate3d(y = 45f, cameraDistance = 4f) }
                        }
                    }
                }
                Labelled("perspective(300f) on the row") {
                    Row(Modifier.perspective(300f), horizontalArrangement = Arrangement.spacedBy(40f)) {
                        repeat(3) { index ->
                            SkewTile("CARD ${index + 1}") { Modifier.rotate3d(y = 45f) }
                        }
                    }
                }
            }
        }
    })

    // A panel, a card on it, a button on the card, and a translucent scrim over the right half:
    // every depth of stack at once, so the picture has blue, green, pink and red on it.
    add(DocShot("overdraw-overlay", 420, 190) {
        Box(Modifier.fillMaxSize()) {
            Frame {
                Column(
                    Modifier.background(Ink, corner = 6f).padding(18f),
                    verticalArrangement = Arrangement.spacedBy(10f),
                ) {
                    Text("INVENTORY", style = "label.dim")
                    Box(Modifier.background(Ink, corner = 4f).padding(12f)) {
                        Button("EQUIP", onClick = {})
                    }
                }
            }
            Box(Modifier.offset(210f, 0f).size(210f, 190f).background(Colour.argb(0x60000000)))
            OverdrawOverlay(enabled = true)
        }
    })

    // A hotbar with one slot glowing and a clipped panel under it: two nodes that each cost the
    // batch two cuts, and the overlay beside them saying so. Traced by the budget the shot renders
    // with, published every frame so the picture is of the frame it was taken on.
    val culpritBudget = FrameBudget(publishEveryMillis = 0L)
    add(DocShot("frame-budget-culprits", 560, 250, budget = culpritBudget) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(24f), verticalAlignment = VerticalAlignment.Centre) {
                Column(verticalArrangement = Arrangement.spacedBy(12f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                        repeat(4) { slot ->
                            Box(
                                Modifier.size(44f, 44f)
                                    .background(Ink, corner = 6f)
                                    .padding(8f)
                                    .then(if (slot == 1) Modifier.blend(BlendMode.Additive).testTag("glow") else Modifier)
                                    .background(if (slot == 1) Accent else Colour.rgb(0x3A4458), corner = 4f),
                            )
                        }
                    }
                    Box(Modifier.size(200f, 60f).background(Ink, corner = 6f).clip().testTag("quests").padding(10f)) {
                        Text("Find the lost map")
                    }
                }
                FrameBudgetOverlay(culpritBudget)
            }
        }
    })

    add(DocShot("focus-overlay", 420, 200, focus = true) {
        Box(Modifier.fillMaxSize()) {
            Frame {
                // A two by two menu with PLAY focused, a focus order that sends Down from PLAY past
                // CREDITS to QUIT, and a round button, so the picture has both kinds of arrow, the
                // focused edge, green outlines, tints and the corners of a hit shape.
                val quit = remember { FocusRequester() }
                Row(horizontalArrangement = Arrangement.spacedBy(24f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12f)) {
                            Button("PLAY", onClick = {}, initialFocus = true, modifier = Modifier.focusOrder(down = quit))
                            Button("OPTIONS", onClick = {})
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12f)) {
                            Button("CREDITS", onClick = {})
                            Button("QUIT", onClick = {}, modifier = Modifier.focusRequester(quit))
                        }
                    }
                    Box(
                        Modifier.size(72f, 72f).clipShape(Shapes.Circle).background(Ink)
                            .hitShape(Shapes.Circle).clickable { },
                    )
                }
            }
            FocusOverlay(enabled = true)
        }
    })

    add(DocShot("redraw-overlay", 420, 170, seconds = 1.1f) {
        Box(Modifier.fillMaxSize()) {
            Frame {
                // A menu that stands still beside a score that ticks five times a second, so the
                // picture has one node flashing and everything round it quiet.
                Row(horizontalArrangement = Arrangement.spacedBy(24f)) {
                    Column(
                        Modifier.background(Ink, corner = 6f).padding(18f),
                        verticalArrangement = Arrangement.spacedBy(10f),
                    ) {
                        Text("PAUSED", style = "label.dim")
                        Button("RESUME", onClick = {})
                        Button("QUIT", onClick = {})
                    }
                    var score by remember { mutableStateOf(0) }
                    val clocks = LocalClocks.current
                    LaunchedEffect(Unit) {
                        while (true) {
                            clocks.wait(Clock.Ui, 200)
                            score += 10
                        }
                    }
                    Text("SCORE $score")
                }
            }
            RedrawOverlay(enabled = true)
        }
    })

    add(DocShot("text-metrics-overlay", 420, 150) {
        Box(Modifier.fillMaxSize()) {
            Frame {
                // Two sizes of one face placed on one baseline, and a wrapped line in another size, so
                // the picture shows the lines lining up across sizes and a set of lines per line.
                Column(verticalArrangement = Arrangement.spacedBy(14f)) {
                    Row(verticalAlignment = VerticalAlignment.Baseline, horizontalArrangement = Arrangement.spacedBy(10f)) {
                        Text("148", textStyle = TextStyle(family = "display", size = 34f))
                        Text("HP", textStyle = TextStyle(family = "body", size = 16f))
                    }
                    Text("Jumpy quartz glyphs", textStyle = TextStyle(family = "body", size = 20f))
                }
            }
            TextMetricsOverlay(enabled = true)
        }
    })

    add(DocShot("modifier-padding", 340, 150) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(20f)) {
                Labelled("no padding") {
                    Box(Modifier.background(Ink, corner = 6f)) {
                        Box(Modifier.background(Accent, corner = 4f).size(70f, 44f)) {}
                    }
                }
                Labelled("padding(16f)") {
                    Box(Modifier.background(Ink, corner = 6f).padding(16f)) {
                        Box(Modifier.background(Accent, corner = 4f).size(70f, 44f)) {}
                    }
                }
            }
        }
    })

    // The same hand twice, written in the same order both times. Only the zIndex differs, so
    // the middle card coming forward over both neighbours is the whole difference.
    add(DocShot("modifier-zindex", 420, 170) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(40f)) {
                Labelled("as written") { Hand(lifted = -1) }
                Labelled("zIndex(1f) on the middle") { Hand(lifted = 1) }
            }
        }
    })

    // The same quest entry three times, opened at the same moment. The middle one's resize runs on
    // a clock that is stopped half-way, so the picture holds the moment the other two pass through:
    // the entry part-way down, and the line that does not fit yet cut off at its edge.
    add(DocShot("modifier-animate-content-size", 460, 170, seconds = 1f) {
        val clocks = LocalClocks.current
        val halfway = remember { Clock("halfway") }
        var open by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            // After the first layout rather than before it: an entry that is open the first time
            // it is laid out appears open, because appearing is not resizing.
            clocks.wait(Clock.Ui, 100)
            open = true
            clocks.wait(Clock.Ui, QuestOpenMillis / 2)
            clocks.stop(halfway)
        }
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(16f)) {
                Labelled("closed") { QuestEntry(open = false, clock = Clock.Ui) }
                Labelled("on the way") { QuestEntry(open, halfway) }
                Labelled("open") { QuestEntry(open, Clock.Ui) }
            }
        }
    })

    // The same four scores, sorted a couple of frames in: on the left without animatePlacement,
    // where the sort is already over, and on the right with it, caught most of the way through.
    // Bo climbs past everyone, so it is lifted to be drawn over the rows it passes.
    add(DocShot("modifier-animate-placement", 420, 210, seconds = 0.42f) {
        var sorted by remember { mutableStateOf(false) }
        // From an effect a frame or two in, so the rows have a first place to slide away from.
        LaunchedEffect(Unit) {
            withFrameNanos { }
            withFrameNanos { }
            sorted = true
        }
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(40f)) {
                Labelled("as it was: jumps") { Scores(sorted, animated = false) }
                Labelled("animatePlacement()") { Scores(sorted, animated = true) }
            }
        }
    })

    // A popup hung under a button by the button's own onPlaced, and a panel reading out the size
    // its onSizeChanged was last handed. Nothing in it polls.
    add(DocShot("modifier-on-placed", 420, 200, seconds = 0.2f) {
        var anchor by remember { mutableStateOf<Rect?>(null) }
        var size by remember { mutableStateOf<Size?>(null) }
        val placed = remember { PlacedHandler { node -> anchor = node.boundsInRoot } }
        val sized = remember { SizeChangedHandler { size = it } }
        Box(Modifier.fillMaxSize().background(Ink)) {
            Box(Modifier.offset(24f, 20f).size(372f, 44f).background(Steel, corner = 6f).onSizeChanged(sized)) {
                Row(Modifier.padding(6f), horizontalArrangement = Arrangement.spacedBy(8f)) {
                    Button("File", onClick = {})
                    Button("Options", onClick = {}, modifier = Modifier.onPlaced(placed))
                }
            }
            size?.let { Text("toolbar ${it.width.toInt()} x ${it.height.toInt()}", Modifier.offset(24f, 168f), style = "label.dim") }
            anchor?.let { under ->
                Box(Modifier.offset(under.left, under.bottom + 6f).size(150f, 86f).background(Deep, corner = 6f).border(Accent, width = 1f, corner = 6f)) {
                    Column(Modifier.padding(10f), verticalArrangement = Arrangement.spacedBy(6f)) {
                        Text("Sound")
                        Text("Controls")
                        Text("Video")
                    }
                }
            }
        }
    })

    // The same two pieces of art twice, the second time through mirror(). The word inside the
    // portrait is there on purpose: text flips with everything else, and the picture should say so.
    add(DocShot("modifier-mirror", 420, 170) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(28f)) {
                Labelled("as drawn") {
                    Row(horizontalArrangement = Arrangement.spacedBy(14f), verticalAlignment = VerticalAlignment.Centre) {
                        Profile(Modifier)
                        Arrow(Modifier)
                    }
                }
                Labelled("mirror()") {
                    Row(horizontalArrangement = Arrangement.spacedBy(14f), verticalAlignment = VerticalAlignment.Centre) {
                        Profile(Modifier.mirror())
                        Arrow(Modifier.mirror())
                    }
                }
            }
        }
    })

    // The same square art four times, cut four ways at draw time.
    add(DocShot("modifier-clip-shape", 420, 150) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(20f)) {
                Labelled("Circle") { Portrait(Shapes.Circle) }
                Labelled("Diamond") { Portrait(Shapes.Diamond) }
                Labelled("Hexagon") { Portrait(Shapes.Hexagon) }
                Labelled("roundedRect") { Portrait(Shapes.roundedRect(18f)) }
            }
        }
    })

    // The same three layers twice: once with the pointer in the middle, once with it pushed to the
    // right-hand edge. The source is fed a real pointer move, so the picture is what the modifier
    // does with one rather than offsets typed in to look like it.
    add(DocShot("modifier-parallax", 500, 190) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(20f)) {
                Labelled("pointer in the middle") { ParallaxScene(pointerX = 0f) }
                Labelled("pointer at the right edge") { ParallaxScene(pointerX = 96f) }
            }
        }
    })
}

/** Three overlapping cards, fanned like a hand; [lifted] is the one given a zIndex. */
@Composable
private fun Hand(lifted: Int) {
    Box(Modifier.size(150f, 110f)) {
        listOf(Steel, Accent, Deep).forEachIndexed { index, colour ->
            Box(
                Modifier
                    .offset(index * 35f, index * 12f)
                    .size(80f, 86f)
                    .zIndex(if (index == lifted) 1f else 0f)
                    .shadow(Colour.argb(0xAA000000), spread = 8f, corner = 6f)
                    .background(colour, corner = 6f)
                    .border(Ink, width = 2f, corner = 6f),
            ) {}
        }
    }
}

/** Square stripes, cut to [shape]: what a portrait from a sprite sheet looks like through it. */
@Composable
private fun Portrait(shape: Shape) {
    Box(Modifier.size(80f).clipShape(shape)) {
        Column {
            Box(Modifier.size(80f, 27f).background(Accent)) {}
            Box(Modifier.size(80f, 26f).background(Colour.rgb(0xE6EDF5))) {}
            Box(Modifier.size(80f, 27f).background(Deep)) {}
        }
    }
}

/**
 * A menu backdrop in three layers, each leaning away from the pointer at its own rate.
 *
 * [pointerX] is how far right of the scene's middle the pointer is.
 */
@Composable
private fun ParallaxScene(pointerX: Float) {
    val centre = Offset(110f, 60f)
    val pointer = remember(pointerX) {
        PointerParallax(centre).apply { saw(PointerEvent.Move(PointerId.Mouse, centre + Offset(pointerX, 0f))) }
    }
    Box(Modifier.size(220f, 120f).background(Ink, corner = 6f).clip(corner = 6f)) {
        // The hills are painted well past the scene's edges on both sides: a layer that fills the
        // screen needs a margin to drift into, and the clip above trims it.
        // Far: pale hills that barely move.
        Box(Modifier.fillMaxSize().parallax(pointer, factor = -0.1f).drawBehind { bounds ->
            repeat(7) { i -> rect(Rect.of(bounds.left - 80f + i * 60f, bounds.top + 44f, 70f, 60f), Steel, corner = 30f) }
        }) {}
        // Middle: a band of deeper hills, three times as fast.
        Box(Modifier.fillMaxSize().parallax(pointer, factor = -0.3f).drawBehind { bounds ->
            repeat(9) { i -> rect(Rect.of(bounds.left - 110f + i * 54f, bounds.top + 74f, 64f, 50f), Deep, corner = 24f) }
        }) {}
        // Near: the title, which runs.
        Box(Modifier.offset(x = 70f, y = 20f).parallax(pointer, factor = -0.6f).background(Accent, corner = 4f).padding(horizontal = 12f, vertical = 4f)) {
            Text("PLAY", style = "label")
        }
        // Where the pointer is, so the picture says what the layers are reacting to.
        Box(Modifier.offset(x = centre.x + pointerX - 4f, y = centre.y - 4f).size(8f).background(Colour.White, corner = 4f)) {}
    }
}

/** A head in profile, looking right: a face, an eye and a nose on the right, a word on its cheek. */
@Composable
private fun Profile(modifier: Modifier) {
    Box(modifier.size(80f, 96f).background(Ink, corner = 6f)) {
        Box(Modifier.offset(10f, 14f).size(54f, 66f).background(Steel, corner = 20f)) {}
        Box(Modifier.offset(60f, 40f).size(14f, 12f).background(Steel, corner = 4f)) {}
        Box(Modifier.offset(44f, 30f).size(8f, 8f).background(Accent, corner = 4f)) {}
        // Not a word made of symmetric letters: flipped, those only look reordered.
        Box(Modifier.offset(12f, 56f)) { Text("HEY", style = "label.dim") }
    }
}

/** An arrow pointing right: a shaft and a head. */
@Composable
private fun Arrow(modifier: Modifier) {
    Row(modifier, verticalAlignment = VerticalAlignment.Centre) {
        Box(Modifier.size(30f, 10f).background(Accent)) {}
        Box(Modifier.size(18f, 30f).background(Accent, corner = 3f)) {}
    }
}

// ---------------------------------------------------------------- animation

private fun MutableList<DocShot>.animation() {
    // A bouncy spring on paused clocks, stepped a frame at a time, with every frame it drew left
    // behind. At full speed the overshoot is gone before anyone sees it; stepped, it is all there.
    add(DocShot("clock-debug", 440, 110, seconds = 0.6f) {
        Frame { SteppedSpring() }
    })
}

@Composable
private fun SteppedSpring() {
    val clocks = LocalClocks.current
    var go by remember { mutableStateOf(false) }
    val trail = remember { mutableStateListOf<Float>() }
    LaunchedEffect(Unit) {
        clocks.debug.speed = 0.5f
        clocks.debug.pause()
        clocks.debug.step(frames = 24)
        go = true
    }
    val x by animateFloatAsState(if (go) 1f else 0f, Spring(damping = 0.3f, stiffness = Spring.Medium))
    LaunchedEffect(x) { if (go) trail += x }

    Column(verticalArrangement = Arrangement.spacedBy(8f)) {
        Text("PAUSED - STEPPED ${trail.size} FRAMES AT 0.5x", style = "label.dim")
        Box(Modifier.size(400f, 40f).background(Ink, corner = 4f)) {
            // Where the spring is going. Everything right of this line is overshoot.
            Box(Modifier.offset(x = 300f).size(2f, 40f).background(Steel)) {}
            trail.forEachIndexed { frame, at ->
                val fade = 0.2f + 0.8f * (frame + 1) / trail.size
                Box(Modifier.offset(x = at * 300f - 7f, y = 13f).size(14f, 14f).alpha(fade).background(Accent, corner = 7f)) {}
            }
        }
    }
}

// ---------------------------------------------------------------- the developer console

private fun MutableList<DocShot>.console() {
    // Down over a running game and typed at for real: ` opened it, `give sword 10` was typed and
    // run — the pack in the HUD behind says so — and the next command is half written at the prompt.
    add(
        DocShot(
            "console-down", ConsoleShotWidth, ConsoleShotHeight,
            stock = true,
            typed = listOf(
                Typing.Press(Key.Grave),
                Typing.Wait(ConsoleSlide),
                Typing.Write("give sword 10"),
                Typing.Press(Key.Enter),
                Typing.Wait(4),
                Typing.Write("nocl"),
            ),
        ) {
            ConsoleScene()
        },
    )

    // A typo run, and then Tab. The log has the echo of `gove sword` and what the console said back
    // about it, and the list over the prompt is what Tab is offering for the item.
    add(
        DocShot(
            "console-complete", ConsoleShotWidth, ConsoleShotHeight,
            stock = true,
            typed = listOf(
                Typing.Press(Key.Grave),
                Typing.Wait(ConsoleSlide),
                Typing.Write("gove sword"),
                Typing.Press(Key.Enter),
                Typing.Wait(4),
                Typing.Write("give s"),
                Typing.Press(Key.Tab),
                Typing.Wait(4),
            ),
        ) {
            // Fewer lines behind it than the picture above, so the list Tab opens has room without
            // pushing what the console said about `gove` out of sight.
            ConsoleScene(loaded = 2)
        },
    )

    // Right to left in the high-contrast skin: the title and the filter swap sides, the prompt's `>`
    // is on the right, and the log reads from there. `timescale fast` was run and refused, and Up
    // brought the line back to be fixed.
    add(
        DocShot(
            "console-rtl", ConsoleShotWidth, ConsoleShotHeight,
            typed = listOf(
                Typing.Press(Key.Grave),
                Typing.Wait(ConsoleSlide),
                Typing.Write("timescale fast"),
                Typing.Press(Key.Enter),
                Typing.Wait(4),
                Typing.Press(Key.Up),
                Typing.Wait(4),
            ),
        ) {
            ProvideSkin(Skin.HighContrast) {
                ProvideLayoutDirection(LayoutDirection.Rtl) {
                    ConsoleScene(loaded = 2)
                }
            }
        },
    )

    // The same console coming down and being typed at, at evenly spaced moments, for the animated
    // picture. Only when asked for, because they are frames to be joined into a GIF rather than
    // pictures of their own: `COMPOSEGL_DOC_FRAMES=1`, then join `console-drop-frame-*.png` in
    // order, 1/20 s each.
    if (System.getenv("COMPOSEGL_DOC_FRAMES") != null) {
        val script = listOf(
            Typing.Press(Key.Grave),
            Typing.Wait(ConsoleSlide),
            Typing.Write("give sword 10"),
            Typing.Press(Key.Enter),
            Typing.Wait(ConsoleSlide),
        )
        repeat(ConsoleDropFrames) { frame ->
            val name = "console-drop-frame-${frame.toString().padStart(2, '0')}"
            // Three frames of the interface between one picture and the next: twenty of them are a
            // second of something drawn at sixty, which is what the GIF plays back.
            add(
                DocShot(name, ConsoleShotWidth, ConsoleShotHeight, stock = true, typed = firstFrames(script, frame * 3)) {
                    ConsoleScene()
                },
            )
        }
    }
}

/** How wide and tall the console pictures are: a game's screen, small enough to read on a page. */
private const val ConsoleShotWidth = 620
private const val ConsoleShotHeight = 440

/** Frames to let the console finish sliding down before anything is typed at it. */
private const val ConsoleSlide = 14

/** How many frames the animated picture of the console dropping down is made of. */
private const val ConsoleDropFrames = 20

/** How far under the middle of the screen the HUD's crosshair sits, clear of the console. */
private const val ReticleBelowMiddle = 70f

/**
 * The first [frames] frames of a typing script, cutting a word off in the middle if that is where
 * the count lands — which is what a picture of somebody halfway through typing is.
 */
private fun firstFrames(script: List<Typing>, frames: Int): List<Typing> {
    var left = frames
    val taken = mutableListOf<Typing>()
    for (step in script) {
        if (left <= 0) break
        when (step) {
            is Typing.Press -> {
                taken += step
                left -= 1
            }
            is Typing.Write -> {
                val written = step.text.take(left)
                taken += Typing.Write(written)
                left -= written.length
            }
            is Typing.Wait -> {
                taken += Typing.Wait(minOf(step.frames, left))
                left -= step.frames
            }
        }
    }
    return taken
}

/** What the console's commands poke, so a line that is run really changes the game behind it. */
private class DocGame {
    var timescale by mutableStateOf(1f)
    var noclip by mutableStateOf(false)
    var pack by mutableStateOf("empty")
}

/** What the game said while it was loading, so the log has something in it before a word is typed. */
private val DocLoadingLines = listOf(
    "Loaded level 3 - Saltmere" to ConsoleLevel.Info,
    "streamed 214 chunks in 812 ms" to ConsoleLevel.Debug,
    "no spawn point; using the origin" to ConsoleLevel.Warn,
    "torch.frag: undeclared identifier 'uTime'" to ConsoleLevel.Error,
)

/**
 * A game with a console over it: the commands change the HUD behind, which is the whole point of
 * having one.
 *
 * @param loaded how many of [DocLoadingLines] are already in the log when the picture starts.
 */
@Composable
private fun ConsoleScene(loaded: Int = DocLoadingLines.size) {
    val game = remember { DocGame() }
    val console = rememberDevConsole {
        command("noclip", help = "Walk through walls") { game.noclip = !game.noclip }
        command("timescale", arg<Float>("scale"), help = "How fast the world runs") { game.timescale = it }
        command(
            "give",
            arg<String>("item", suggest = { listOf("sword", "shield", "sapphire") }),
            arg<Int>("count", default = 1),
            help = "Put an item in the pack",
        ) { item, count -> game.pack = "$item x$count" }
    }

    // Once, not every recomposition: a console with something in it is what one really looks like.
    LaunchedEffect(console) {
        DocLoadingLines.take(loaded).forEach { (text, level) -> console.log(text, level) }
    }

    Box(Modifier.fillMaxSize()) {
        DocHud(game)
        // Last, like the overlays: a console goes over everything the game drew. A little deeper
        // than the default, so the picture has a log in it worth reading.
        DevConsole(console, heightFraction = 0.52f)
    }
}

/** The game under the console: a world, a reticle, bars, a hotbar, and what the commands changed. */
@Composable
private fun DocHud(game: DocGame) {
    Box(Modifier.fillMaxSize().background(Colour.rgb(0x0C1017))) {
        // A horizon: pale hills at the back, deeper ones in front, and the ground under them. Set
        // against the bottom of the screen rather than the top, so the console has room above it.
        Box(Modifier.fillMaxSize().drawBehind { bounds ->
            val ground = bounds.bottom - bounds.height * 0.3f
            repeat(8) { hill ->
                rect(Rect.of(bounds.left - 40f + hill * 90f, ground - 150f, 120f, 150f), Steel, corner = 60f)
            }
            repeat(7) { hill ->
                rect(Rect.of(bounds.left - 70f + hill * 96f, ground - 60f, 110f, 100f), Deep, corner = 44f)
            }
            rect(Rect.of(bounds.left, ground, bounds.width, bounds.bottom - ground), Ink)
        }) {}

        // Where the player is looking, down in the part of the screen the console leaves alone.
        Reticle(
            rememberReticleState(),
            Modifier.align(Alignment.Centre).offset(y = ReticleBelowMiddle),
            gap = 7f,
            arm = 12f,
            thickness = 2f,
            dot = 2f,
        )

        // Low on the screen with the rest of the HUD: the console covers the top of it.
        MinimapFrame(
            Modifier.align(Alignment.BottomEnd).padding(14f).size(86f),
            heading = 0.6f,
            range = 100f,
            markers = listOf(MinimapMarker(20f, -30f), MinimapMarker(-40f, 10f)),
        )

        Column(
            Modifier.align(Alignment.BottomStart).padding(14f),
            verticalArrangement = Arrangement.spacedBy(6f),
        ) {
            Bar(0.72f, length = 150f)
            Bar(0.4f, length = 150f, thickness = 6f)
            // What the console did, read straight off the game's own state.
            Text("PACK ${game.pack}", style = "label.dim")
            Text("TIME ${game.timescale}x   NOCLIP ${if (game.noclip) "ON" else "OFF"}", style = "label.dim")
        }

        Hotbar(
            slots = listOf(HotbarSlot(charges = 3), HotbarSlot(), HotbarSlot(charges = 1), HotbarSlot()),
            selected = 0,
            modifier = Modifier.align(Alignment.BottomCentre).padding(14f),
            slotSize = 40f,
        )
    }
}

// ---------------------------------------------------------------- the small pieces

private val Ink = Colour.rgb(0x12161D)
private val Steel = Colour.rgb(0x2C3545)
private val Accent = Colour.rgb(0x4CC2FF)
private val Paper = Colour.rgb(0xE6EDF5)

/** The accent, dark enough that [Slab]'s dim label is still readable on top of it. */
private val Deep = Colour.rgb(0x1D4F70)

/** A box with a word in it, for showing where a layout put things rather than what a widget is. */
@Composable
private fun Swatch(label: String) {
    Box(Modifier.size(90f, 48f).background(Steel, corner = 6f), contentAlignment = Alignment.Centre) {
        Text(label, style = "label.dim")
    }
}

/** One cell of a grid picture: as wide as the cell it was given, numbered so the order shows. */
@Composable
private fun GridTile(index: Int, colour: Colour) {
    Box(Modifier.fillMaxWidth().height(40f).background(colour, corner = 5f), contentAlignment = Alignment.Centre) {
        Text("$index", style = "label.dim")
    }
}

/** The same, but sized by its modifier, for weights and alignments. */
@Composable
private fun Slab(label: String, modifier: Modifier, colour: Colour) {
    Box(modifier.height(36f).background(colour, corner = 6f), contentAlignment = Alignment.Centre) {
        Text(label, style = "label.dim")
    }
}

/** A box whose shape comes entirely from its modifier, with the ratio written on it. */
@Composable
private fun Shaped(label: String, modifier: Modifier, colour: Colour) {
    Box(modifier.background(colour, corner = 6f), contentAlignment = Alignment.Centre) {
        Text(label, style = "label.dim")
    }
}

private val Buffs = listOf("HASTE", "REGEN", "SHIELD", "BURNING", "FOCUS", "POISON", "WARD", "RAGE", "STEALTH", "BLESSED", "CHILL", "SLOWED")

/** A status effect chip, as wide as its word, for showing a flow row wrap. */
@Composable
private fun Buff(name: String) {
    Box(Modifier.height(26f).background(Deep, corner = 4f).padding(horizontal = 8f), contentAlignment = Alignment.Centre) {
        Text(name, style = "label.dim")
    }
}

/**
 * A row cut into three equal shares, each marked out in steel, with a round badge in each whose
 * modifier [slot] decides. What it shows is the difference between the share and the badge.
 */
@Composable
private fun WrapRow(slot: (Int) -> Modifier) {
    Row(
        Modifier.width(380f).height(40f).background(Ink, corner = 6f).drawBehind { bounds ->
            repeat(3) { index ->
                val third = bounds.width / 3f
                border(Rect.of(bounds.left + third * index + 2f, bounds.top + 2f, third - 4f, bounds.height - 4f), Steel, 1f, 6f)
            }
        }.padding(8f),
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        repeat(3) { index ->
            Box(slot(index).width(40f).height(24f).background(Accent, corner = 12f)) {}
        }
    }
}

private val Display = TextStyle(family = "display", size = 34f)
private val Body = TextStyle(family = "body", size = 16f)

/** A title in a panel, so where its letters land against the panel's top edge can be seen. */
@Composable
private fun Heading(modifier: Modifier, style: TextStyle) {
    Box(Modifier.background(Ink, corner = 6f).width(150f).height(60f)) {
        Text("Title", modifier, textStyle = style)
    }
}

/** A big number and a small unit, the case baseline alignment exists for. */
@Composable
private fun Readout(alignment: VerticalAlignment) {
    Row(
        Modifier.background(Ink, corner = 6f).padding(10f),
        horizontalArrangement = Arrangement.spacedBy(6f),
        verticalAlignment = alignment,
    ) {
        Text("120", textStyle = Display)
        Text("HP", style = "label.dim", textStyle = Body)
    }
}

@Composable
private fun ArrangementRow(name: String, arrangement: Arrangement) {
    Column(verticalArrangement = Arrangement.spacedBy(3f)) {
        Text(name, style = "label.dim")
        Row(Modifier.fillMaxWidth().background(Ink, corner = 4f), horizontalArrangement = arrangement) {
            repeat(3) { Box(Modifier.size(48f, 16f).background(Accent, corner = 3f)) {} }
        }
    }
}

/** How long a quest entry takes to open, in the picture of `animateContentSize`. */
private const val QuestOpenMillis = 600

@Composable
private fun QuestEntry(open: Boolean, clock: Clock) {
    Column(
        Modifier.width(130f)
            .animateContentSize(Tween(QuestOpenMillis, easing = Easings.Linear), clock = clock)
            .background(Steel, corner = 6f)
            .padding(10f),
        verticalArrangement = Arrangement.spacedBy(6f),
    ) {
        Text("The lost ring")
        if (open) {
            Text("Search the well", style = "label.dim")
            Text("at Oakmere, then", style = "label.dim")
            Text("return to Edda.", style = "label.dim")
        }
    }
}

@Composable
private fun Labelled(name: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4f)) {
        Text(name, style = "label.dim")
        content()
    }
}

/** A caption, then a name in a 180-wide slot that scrolls when the name is too long for it. */
@Composable
private fun Scrolling(caption: String, name: String, delayMillis: Int, speed: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(12f), verticalAlignment = VerticalAlignment.Centre) {
        Text(caption, Modifier.width(150f), style = "label.dim")
        Box(Modifier.width(180f).background(Steel, corner = 4f).padding(horizontal = 8f, vertical = 4f)) {
            Text(name, Modifier.marquee(speed = speed, delayMillis = delayMillis))
        }
    }
}

/** A panel knocked [intensity] hard the moment it appears, over an outline of the slot it left. */
@Composable
private fun Knocked(name: String, intensity: Float) {
    // Seed 8 leans hard right and up at the moment the shutter goes, so the movement is readable.
    val shake = rememberShake(maxOffset = 24f, seed = 8)
    LaunchedEffect(shake) { shake.trigger(intensity) }
    Labelled(name) {
        Box(Modifier.border(Accent, width = 1f, corner = 6f).size(80f, 44f)) {
            Box(Modifier.shake(shake).background(Accent.scaleAlpha(0.6f), corner = 6f).size(80f, 44f)) {}
        }
    }
}

/** A banner with a word and a bar in it, so a slant shows on the text as well as the edges. */
@Composable
private fun SkewTile(label: String, modifier: @Composable () -> Modifier) {
    Box(modifier().size(130f, 56f).background(Deep, corner = 4f).padding(10f)) {
        Column(verticalArrangement = Arrangement.spacedBy(8f)) {
            Text(label)
            Box(Modifier.size(90f, 8f).background(Accent, corner = 3f)) {}
        }
    }
}

@Composable
private fun Tile(name: String, modifier: @Composable () -> Modifier) {
    Column(horizontalAlignment = HorizontalAlignment.Centre, verticalArrangement = Arrangement.spacedBy(6f)) {
        Box(modifier().size(80f, 44f)) {}
        Text(name, style = "label.dim")
    }
}


/** An entry picked and a list scrolled, once, on the first visit only. */
@Composable
private fun CodexPage() {
    var entry by rememberSaveable { mutableStateOf(0) }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) {
        if (entry != 0) return@LaunchedEffect
        // A frame or two first: a list that has not been measured has nowhere to scroll to.
        repeat(3) { withFrameNanos {} }
        entry = 4
        scroll.scrollTo(y = 72f)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6f)) {
        Text("Entry $entry of 12", style = "label.dim")
        ScrollArea(Modifier.fillMaxWidth().height(110f), scroll) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6f)) {
                repeat(12) { Text(if (it + 1 == entry) "> Wraith ${it + 1}" else "Wraith ${it + 1}") }
            }
        }
    }
}

/** A leaderboard of four, sorted highest first once [sorted]. Bo, who climbs to the top, is lit. */
@Composable
private fun Scores(sorted: Boolean, animated: Boolean) {
    val rows = listOf("Ada" to 120, "Cy" to 90, "Di" to 210, "Bo" to 340)
    val shown = if (sorted) rows.sortedByDescending { it.second } else rows
    Column(Modifier.width(170f), verticalArrangement = Arrangement.spacedBy(6f)) {
        shown.forEach { (name, score) ->
            key(name) {
                val slide = if (animated) Modifier.animatePlacement(Tween(600, easing = Easings.Linear)) else Modifier
                Row(Modifier.width(170f).then(slide).zIndex(if (name == "Bo") 1f else 0f).background(if (name == "Bo") Accent else Steel, corner = 6f).padding(8f)) {
                    Text(name, Modifier.width(110f))
                    Text("$score")
                }
            }
        }
    }
}

/** One action on a controls screen: its name, then the button that rebinds it. */
@Composable
private fun BindRow(action: String, binding: InputBinding, listening: Boolean = false) {
    val state = remember { KeyBindState().also { if (listening) it.listen() } }
    Row(Modifier.width(300f), verticalAlignment = VerticalAlignment.Centre) {
        Text(action, Modifier.weight(1f))
        KeyBindButton(binding, onBind = {}, state = state, modifier = Modifier.width(150f))
    }
}

/** A level editor's menu bar over an empty editor, for the menu pictures. */
@Composable
private fun EditorMenuBar() {
    Column(Modifier.fillMaxSize().background(Colour.rgb(0x0B0E13))) {
        MenuBar {
            Menu("&File") {
                Item("&New level", shortcut = Modifiers.Primary + Key.N) {}
                Item("&Open...", shortcut = Modifiers.Primary + Key.O) {}
                Item("&Save", shortcut = Modifiers.Primary + Key.S) {}
                Item("Save &as...", shortcut = Modifiers.Primary + Modifiers.Shift + Key.S, enabled = false) {}
                Separator()
                CheckItem("Auto&save", checked = true) {}
                Separator()
                Item("&Quit", shortcut = Modifiers.Primary + Key.Q) {}
            }
            Menu("&View") {
                CheckItem("&Grid", checked = true, shortcut = Modifiers.Primary + Key.G) {}
                CheckItem("S&nap to grid", checked = false) {}
                Separator()
                Submenu("&Render") {
                    RadioItem("&Wireframe", selected = false) {}
                    RadioItem("&Shaded", selected = true) {}
                    RadioItem("&Lit", selected = false) {}
                }
            }
            Menu("&Help") { Item("&About") {} }
        }
    }
}

/** A settings page in three folding sections, Physics open, for the collapsing header pictures. */
@Composable
private fun SettingsSections(focusAudio: Boolean = false) {
    Column(Modifier.width(320f)) {
        CollapsingHeader("Physics", initiallyExpanded = true) {
            Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                Slider(0.6f, onValueChange = {}, modifier = Modifier.width(220f))
                Toggle(true, onCheckedChange = {}, label = "Ragdolls")
            }
        }
        CollapsingHeader("Audio", initialFocus = focusAudio) {
            Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                Slider(0.8f, onValueChange = {}, modifier = Modifier.width(220f))
                Checkbox(true, onCheckedChange = {}, label = "Subtitles")
            }
        }
        CollapsingHeader("Graphics") {
            Toggle(false, onCheckedChange = {}, label = "V-Sync")
        }
    }
}

/** A level editor split three ways, the hierarchy beside a map over a log, for the splitter pictures. */
@Composable
private fun EditorSplit(focusDivider: Boolean = false) {
    var side by remember { mutableStateOf(0.3f) }
    var stack by remember { mutableStateOf(0.65f) }
    Box(Modifier.fillMaxSize().background(Colour.rgb(0x0B0E13)).padding(10f)) {
        Splitter(
            fraction = side,
            onFractionChange = { side = it },
            modifier = Modifier.fillMaxSize(),
            minFirst = 100f,
            minSecond = 180f,
            initialFocus = focusDivider,
            first = {
                SplitPane("Hierarchy") {
                    listOf("Level 1", "  Player", "  Camera", "  Enemies", "  Pickups", "  Lights").forEach {
                        Text(it, style = if (it == "  Player") "label" else "label.dim")
                    }
                }
            },
            second = {
                Splitter(
                    fraction = stack,
                    onFractionChange = { stack = it },
                    modifier = Modifier.fillMaxSize(),
                    orientation = Orientation.Vertical,
                    minFirst = 60f,
                    minSecond = 50f,
                    first = {
                        SplitPane("Map") {
                            Box(Modifier.fillMaxSize().background(Colour.rgb(0x1E3A2B)))
                        }
                    },
                    second = {
                        SplitPane("Log") {
                            Text("Loaded level 1 in 0.4s", style = "label.dim")
                            Text("3 enemies spawned", style = "label.dim")
                        }
                    },
                )
            },
        )
    }
}

/** One titled pane of [EditorSplit]. */
@Composable
private fun SplitPane(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().styled("panel").padding(8f), verticalArrangement = Arrangement.spacedBy(4f)) {
        Text(title)
        content()
    }
}

private class DocPlayer(val name: String, val kills: Int, val deaths: Int, val score: Int, val ping: Int)

/** A deathmatch scoreboard sorted by kills, the player's own row picked, for the table pictures. */
@Composable
private fun Scoreboard() {
    val players = remember {
        listOf(
            DocPlayer("Nightjar", 14, 9, 1620, 38),
            DocPlayer("Vex", 22, 6, 2410, 21),
            DocPlayer("Brannock", 9, 12, 1080, 64),
            DocPlayer("Mirela", 17, 8, 1890, 45),
            DocPlayer("Oskar", 6, 15, 720, 112),
            DocPlayer("Kestrel", 19, 10, 2050, 29),
        )
    }
    Table(
        rows = players,
        modifier = Modifier.width(420f).height(210f),
        key = { it.name },
        state = rememberTableState(sortColumn = 1, descending = true),
        selected = players[3],
        onSelect = {},
    ) {
        column("Player", weight = 1f, sortBy = { it.name }) { Text(it.name) }
        column("Kills", width = 64f, sortBy = { it.kills }, align = HorizontalAlignment.End) { Text("${it.kills}") }
        column("Deaths", width = 72f, sortBy = { it.deaths }, align = HorizontalAlignment.End) { Text("${it.deaths}") }
        column("Score", width = 72f, sortBy = { it.score }, align = HorizontalAlignment.End) { Text("${it.score}") }
        column("Ping", width = 60f, sortBy = { it.ping }, align = HorizontalAlignment.End) { Text("${it.ping}") }
    }
}

private class DocServer(val name: String, val map: String, val players: String, val ping: Int)

/** Where the divider after the server browser's first column is in its picture, before and after the drag. */
private const val TableDividerFrom = 189f
private const val TableDividerTo = 229f

/** A long server list scrolled down, for the picture of the frozen header and a column drag. */
@Composable
private fun ServerBrowser() {
    val servers = remember {
        val names = listOf(
            "EU West Casual", "Frag Fest 24/7", "Night Owls", "Rookie Friendly", "Ranked NA 2", "Old School CTF",
            "Mod Madness", "Tokyo Speedrun", "Hardcore Only", "Weekend Warriors", "Sydney Scrims", "Nordic Rail",
            "Clan Wars EU", "Pistols at Dawn", "Chill Builds", "Ranked EU 4",
        )
        val maps = listOf("Harbour", "Foundry", "Dunes", "Glacier", "Canal")
        names.mapIndexed { i, name -> DocServer(name, maps[i % maps.size], "${(i * 7) % 17}/16", 18 + (i * 37) % 140) }
    }
    val state = rememberTableState()
    // After the first layout, once the list knows how many rows it has and how tall they are.
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos {} }
        state.list.scrollToItem(7)
    }
    Table(rows = servers, modifier = Modifier.width(450f).height(230f), key = { it.name }, state = state) {
        column("Server", weight = 1f, sortBy = { it.name }) { Text(it.name) }
        column("Map", width = 130f, sortBy = { it.map }) { Text(it.map, style = "label.dim") }
        column("Players", width = 76f, align = HorizontalAlignment.End) { Text(it.players) }
        column("Ping", width = 60f, sortBy = { it.ping }, align = HorizontalAlignment.End) { Text("${it.ping}") }
    }
}

private class DocItem(val name: String, val weight: String, val value: Int)

/** A backpack sorted by value, lowest first, for the right-to-left table picture. */
@Composable
private fun Inventory() {
    val items = remember {
        listOf(
            DocItem("Iron sword", "3.5", 120),
            DocItem("Healing potion", "0.5", 45),
            DocItem("Leather boots", "1.2", 60),
            DocItem("Silver ring", "0.1", 300),
            DocItem("Torch", "0.8", 5),
            DocItem("Rope", "2.0", 15),
        )
    }
    Table(
        rows = items,
        modifier = Modifier.width(420f).height(210f),
        key = { it.name },
        state = rememberTableState(sortColumn = 2),
    ) {
        column("Item", weight = 1f, sortBy = { it.name }) { Text(it.name) }
        column("Weight", width = 80f, sortBy = { it.weight.toFloat() }, align = HorizontalAlignment.End) { Text(it.weight) }
        column("Value", width = 80f, sortBy = { it.value }, align = HorizontalAlignment.End) { Text("${it.value}") }
    }
}

private class DocQuest(val name: String, val where: String = "", vararg val steps: DocQuest)

/** A quest log with the main story open, for the picture of a tree on a pad. */
@Composable
private fun QuestLog() {
	val quests = remember {
		listOf(
			DocQuest(
				"Main story", "",
				DocQuest("The Drowned Bell", "Saltmere"),
				DocQuest("Ashes of Kharn", "Kharn Ruins"),
				DocQuest("A Crown of Thorns", "Castle Vey"),
			),
			DocQuest(
				"Side quests", "",
				DocQuest("Lost Cat", "Saltmere"),
				DocQuest("Smuggler's Cache", "Old Docks"),
			),
			DocQuest("Done", "", DocQuest("Rats in the Cellar", "Inn")),
		)
	}
	var chosen by remember { mutableStateOf<DocQuest?>(null) }
	Panel(Modifier.width(300f).height(240f)) {
		TreeView(
			roots = quests,
			children = { it.steps.toList() },
			key = { it.name },
			modifier = Modifier.fillMaxWidth(),
			selected = chosen,
			onSelect = { chosen = it },
			state = rememberTreeState("Main story"),
		) { quest, _ ->
			Row(horizontalArrangement = Arrangement.spacedBy(8f), verticalAlignment = VerticalAlignment.Centre) {
				Text(quest.name)
				if (quest.where.isNotEmpty()) Text(quest.where, style = "label.dim")
			}
		}
	}
}

/** Where the arrow of the save picker's shut "Chapter 2" folder is, in its picture. */
private const val TreeClickArrowX = 46f
private const val TreeClickArrowY = 70f

private class DocSave(val name: String, vararg val files: DocSave)

/** Save folders by chapter, for the picture of a click on an arrow. */
@Composable
private fun SavePicker() {
	val saves = remember {
		listOf(
			DocSave("Chapter 1", DocSave("autosave-01.sav"), DocSave("before-boss.sav")),
			DocSave("Chapter 2", DocSave("autosave-07.sav"), DocSave("the-bridge.sav"), DocSave("quick.sav")),
			DocSave("Chapter 3"),
		)
	}
	Panel(Modifier.width(260f).height(190f)) {
		TreeView(
			roots = saves,
			children = { it.files.toList() },
			hasChildren = { it.name.startsWith("Chapter") },
			key = { it.name },
			modifier = Modifier.fillMaxWidth(),
		) { save, _ -> Text(save.name) }
	}
}

private class DocBeast(val name: String, vararg val kinds: DocBeast)

/** A bestiary with one family open, for the right-to-left tree picture. */
@Composable
private fun Bestiary() {
	val beasts = remember {
		listOf(
			DocBeast("Undead", DocBeast("Skeleton"), DocBeast("Wraith"), DocBeast("Lich")),
			DocBeast("Beasts", DocBeast("Dire wolf"), DocBeast("Cave bear")),
			DocBeast("Dragons", DocBeast("Wyvern")),
		)
	}
	Panel(Modifier.width(260f).height(190f)) {
		TreeView(
			roots = beasts,
			children = { it.kinds.toList() },
			key = { it.name },
			modifier = Modifier.fillMaxWidth(),
			selected = beasts[0].kinds[2],
			state = rememberTreeState("Undead"),
		) { beast, _ -> Text(beast.name) }
	}
}

private const val SpinnerShotWidth = 540
private const val SpinnerShotHeight = 250
private const val SpinnerFrames = 20

/**
 * Work of unknown length in the places a game shows it, for the spinner pictures. The bars sweep once a
 * second, as the spinners turn, so the animated picture loops on one second. The arcs each start a
 * turn somewhere new, so the loop's one jump is where they are shortest.
 */
@Composable
private fun WorkingScene() {
    Column(verticalArrangement = Arrangement.spacedBy(12f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12f)) {
            // A loading screen's card: the bar sits under the header while the world streams in.
            Panel(Modifier.width(270f).height(116f)) {
                Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                    Text("LOADING WORLD", style = "label.heading")
                    IndeterminateBar(Modifier.fillMaxWidth(), sweepMillis = 1000)
                    Text("Tip: crouching steadies your aim.", style = "label.dim")
                }
            }
            Panel(Modifier.width(222f).height(116f)) {
                Column(verticalArrangement = Arrangement.spacedBy(14f)) {
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8f), verticalAlignment = VerticalAlignment.Centre) {
                            Spinner(Modifier.size(16f))
                            Text("Connecting...")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8f), verticalAlignment = VerticalAlignment.Centre) {
                        Spinner(Modifier.size(20f))
                        Text("Saving", style = "label.dim")
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12f)) {
            // Sizes, a thin arc, and a bar standing up, in the stock skin.
            Panel(Modifier.width(270f).height(84f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(18f), verticalAlignment = VerticalAlignment.Centre) {
                    Spinner(Modifier.size(16f))
                    Spinner(Modifier.size(24f))
                    Spinner(Modifier.size(40f))
                    Spinner(Modifier.size(40f), thickness = 2f)
                    IndeterminateBar(orientation = Orientation.Vertical, thickness = 8f, length = 56f, sweepMillis = 1000)
                }
            }
            // The same pair in the high-contrast skin.
            ProvideSkin(Skin.HighContrast) {
                Panel(Modifier.width(222f).height(84f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14f), verticalAlignment = VerticalAlignment.Centre) {
                        Spinner(Modifier.size(32f))
                        IndeterminateBar(Modifier.width(130f), thickness = 8f, sweepMillis = 1000)
                    }
                }
            }
        }
    }
}

/** Six team colours, for the colour picker pictures. */
private val DocTeamColours = listOf(
    Colour.rgb(0xE5484D), Colour.rgb(0x5B8DEF), Colour.rgb(0x46A758),
    Colour.rgb(0xFFD600), Colour.rgb(0xFF8000), Colour.rgb(0xB06CF0),
)

/** The armoury's stock finishes, for the picture of a tint being mixed by hand. */
private val DocArmourColours = listOf(
    Colour.rgb(0x6E7B8B), Colour.rgb(0x8C6239), Colour.rgb(0x2F4F4F),
    Colour.rgb(0xB08D57), Colour.rgb(0x7A2E2E), Colour.rgb(0x1B1B1B),
)

/** Lamp colours an editor offers, see-through ones included, for the right-to-left picture. */
private val DocLampColours = listOf(
    Colour.rgb(0xFFC53D), Colour.rgb(0xFF6B3D), Colour.rgb(0x6BD5FF),
    Colour.argb(0x9946A758), Colour.argb(0x99B06CF0), Colour.rgb(0xFFFFFF),
)

/** What the debug window's picture tunes: ordinary properties, as a game's own systems have. */
private class DocPhysics {
    var gravity by mutableStateOf(9.8f)
    var enemies by mutableStateOf(12)
    var godMode by mutableStateOf(true)
    var difficulty by mutableStateOf(DocDifficulty.Normal)
    var fog by mutableStateOf(Colour.rgb(0x4A7FD4))
}

private enum class DocDifficulty { Easy, Normal, Hard }

// ---------------------------------------------------------------- panning and zooming

/** One place on the world map: where it is in the world, and whether it is on the water. */
private class MapPlace(val name: String, val x: Float, val y: Float, val port: Boolean = false)

/** The world the map pictures are taken of, in world units. */
private val MapWorld = Rect(0f, 0f, 800f, 440f)

private val MapPlaces = listOf(
    MapPlace("Saltmere", 90f, 120f, port = true),
    MapPlace("Old Docks", 150f, 265f, port = true),
    MapPlace("Millbrook", 235f, 170f),
    MapPlace("Kharn Ruins", 335f, 85f),
    MapPlace("Fenwick", 300f, 300f),
    MapPlace("Greywater", 215f, 375f),
    MapPlace("Thornfell", 395f, 230f),
    MapPlace("Redhollow", 470f, 130f),
    MapPlace("Castle Vey", 565f, 205f),
    MapPlace("Ashmoor", 515f, 350f),
    MapPlace("Highgate", 665f, 110f),
    MapPlace("Stonewatch", 705f, 275f),
    MapPlace("Coldharbour", 735f, 390f, port = true),
)

/** Which places a road joins, as pairs of indices into [MapPlaces]. */
private val MapRoads = listOf(
    0 to 2, 0 to 1, 1 to 5, 1 to 4, 2 to 3, 2 to 6, 4 to 6, 5 to 4,
    3 to 7, 6 to 7, 6 to 9, 7 to 8, 8 to 10, 8 to 11, 9 to 11, 11 to 12,
)

/** The land the roads run over, as rounded rectangles in world units. */
private val MapLand = listOf(
    Rect(40f, 60f, 620f, 420f),
    Rect(420f, 70f, 780f, 320f),
    Rect(560f, 290f, 780f, 425f),
)

private val MapSea = Colour.rgb(0x0E1A2B)
private val MapGrid = Colour.argb(0x1AA0C4FF)
private val MapGround = Colour.rgb(0x27352B)
private val MapCoast = Colour.argb(0x99486B52)
private val MapRoad = Colour.argb(0x88C9A227)

/**
 * A world map with pins on it, for the pictures of panning and zooming.
 *
 * The sea, the grid, the coast and the roads are the canvas's background, drawn in world units in
 * one pass. Every pin is an ordinary widget put at a world position rather than a screen one, and
 * the "you are here" marker is the one thing that keeps its size whatever the camera does.
 */
@Composable
private fun WorldMapScene() {
    val camera = rememberPanZoomState(zoom = 1f, minZoom = 0.4f, maxZoom = 3f, bounds = MapWorld)
    val terrain: UiCanvas.(Rect) -> Unit = remember {
        { visible ->
            rect(MapWorld, MapSea)
            var at = 80f
            while (at < MapWorld.right) {
                line(Offset(at, MapWorld.top), Offset(at, MapWorld.bottom), 1f, MapGrid)
                at += 80f
            }
            at = 80f
            while (at < MapWorld.bottom) {
                line(Offset(MapWorld.left, at), Offset(MapWorld.right, at), 1f, MapGrid)
                at += 80f
            }
            MapLand.forEach { land ->
                rect(land, MapGround, corner = 70f)
                border(land, MapCoast, width = 2f, corner = 70f)
            }
            MapRoads.forEach { (from, to) ->
                val a = MapPlaces[from]
                val b = MapPlaces[to]
                // The background is one pass, but it is still the game's own drawing, and it is
                // handed the visible world so it can leave out what is nowhere near the window.
                val around = Rect(minOf(a.x, b.x) - 4f, minOf(a.y, b.y) - 4f, maxOf(a.x, b.x) + 4f, maxOf(a.y, b.y) + 4f)
                if (around.overlaps(visible)) line(Offset(a.x, a.y), Offset(b.x, b.y), 3f, MapRoad)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8f)) {
        Text("Drag to pan, wheel to zoom, double click to go back", style = "label.dim")
        PanZoomCanvas(state = camera, modifier = Modifier.size(400f, 224f), background = terrain) {
            MapPlaces.forEach { place ->
                key(place.name) {
                    Column(
                        Modifier.worldPosition(place.x, place.y, anchor = Alignment.TopCentre).clickable {},
                        horizontalAlignment = HorizontalAlignment.Centre,
                        verticalArrangement = Arrangement.spacedBy(3f),
                    ) {
                        Box(
                            Modifier.size(if (place.port) 13f else 11f)
                                .background(if (place.port) Colour.rgb(0x6BD5FF) else Colour.rgb(0xFFC53D), corner = 7f)
                                .border(Colour.rgb(0x0B0E14), width = 2f, corner = 7f),
                        )
                        Text(place.name)
                    }
                }
            }
            // Follows the camera and stays its own size, which is what a pin wants.
            Box(
                Modifier.worldPosition(
                    MapPlaces[6].x,
                    MapPlaces[6].y - 10f,
                    anchor = Alignment.BottomCentre,
                    scaleWithZoom = false,
                ).background(Colour.rgb(0xE5484D), corner = 5f).padding(left = 6f, right = 6f, top = 2f, bottom = 2f),
            ) {
                Text("you are here")
            }
        }
    }
}

/** One step of a crafting chain: what it is called, and where it sits in the graph. */
private class CraftNode(val name: String, val x: Float, val y: Float)

private val CraftWorld = Rect(0f, 0f, 700f, 300f)

private val CraftNodes = listOf(
    CraftNode("Ore", 60f, 150f),
    CraftNode("Dust", 190f, 70f),
    CraftNode("Ingot", 190f, 230f),
    CraftNode("Plate", 330f, 150f),
    CraftNode("Rod", 330f, 235f),
    CraftNode("Armour", 470f, 75f),
    CraftNode("Gear", 470f, 220f),
    CraftNode("Engine", 620f, 150f),
)

/** Which node feeds which, as pairs of indices into [CraftNodes]. */
private val CraftLinks = listOf(0 to 1, 0 to 2, 2 to 3, 2 to 4, 3 to 5, 4 to 6, 3 to 6, 5 to 7, 6 to 7)

/**
 * A crafting graph, for the high-contrast right-to-left picture.
 *
 * The d-pad walks focus from node to node and the camera eases along to keep the focused one in
 * view, so a graph is reachable on a pad with nothing written for it.
 */
@Composable
private fun CraftingGraph() {
    val camera = rememberPanZoomState(zoom = 1f, minZoom = 0.5f, maxZoom = 2.5f, bounds = CraftWorld)
    val links: UiCanvas.(Rect) -> Unit = remember {
        { visible ->
            CraftLinks.forEach { (from, to) ->
                val a = CraftNodes[from]
                val b = CraftNodes[to]
                val around = Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
                if (around.overlaps(visible)) line(Offset(a.x, a.y), Offset(b.x, b.y), 3f, Colour.rgb(0x707070))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8f)) {
        Text("Crafting — the d-pad walks the graph", style = "label.dim")
        PanZoomCanvas(
            state = camera,
            modifier = Modifier.size(400f, 224f),
            reset = PanZoomReset.Fit,
            background = links,
        ) {
            CraftNodes.forEachIndexed { index, node ->
                key(node.name) {
                    // Ordinary buttons, put at world points rather than screen ones: the ring the
                    // pad leaves on them is the skin's, with nothing written for the plane.
                    Button(
                        node.name,
                        onClick = {},
                        modifier = Modifier.worldPosition(node.x, node.y, anchor = Alignment.Centre),
                        initialFocus = index == 0,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- debug windows

/**
 * The floating windows, over a game that answers them.
 *
 * Every one of these is taken over the same arena: a drone raid with a throw arc, a fog wash and a
 * shield ring, all of it drawn from the very properties the window's lines are wired to. The
 * pointer in them is a real one, so the slider in the picture is a slider a hand is really holding
 * and the arc behind it is the arc that gravity really produces.
 */
private fun MutableList<DocShot>.debugWindows() {
    // A hand on the Gravity slider, still holding it, dragged to the right: the readout says what it
    // reached and the throw arc behind the window has collapsed to match. Nothing in the arena knows
    // the window exists — it reads `arena.gravity`, and the window writes it.
    add(
        DocShot(
            "debug-window-tuning",
            ArenaWidth,
            ArenaHeight,
            stock = true,
            pointer = Offset(430f, 55f),
            dragTo = Offset(508f, 55f),
            hold = true,
        ) {
            val arena = remember { DocArena() }
            DebugWindowHost(state = rememberDebugWindowsState(MemoryDebugWindowStore())) {
                ArenaScene(arena)
                ArenaWindow(arena)
            }
        },
    )

    // Two at once, and one of them being carried: the pointer took Physics by its title bar and is
    // still holding it over Spawns, so it is the window in front and the one with the lit title bar.
    add(
        DocShot(
            "debug-window-two",
            ArenaWidth,
            ArenaHeight,
            stock = true,
            pointer = Offset(380f, 27f),
            dragTo = Offset(330f, 110f),
            hold = true,
        ) {
            val arena = remember { DocArena() }
            DebugWindowHost(state = rememberDebugWindowsState(MemoryDebugWindowStore())) {
                ArenaScene(arena)
                ArenaWindow(arena)
                SpawnWindow(arena, Offset(20f, 150f))
            }
        },
    )

    // The high-contrast skin, right to left: the windows are measured from the top-right corner and
    // their title bars read from the right. Spawns has had its triangle clicked, so it is folded to
    // its title bar and the triangle points left, the way a folded thing points in this direction.
    add(
        DocShot(
            "debug-window-rtl",
            ArenaWidth,
            ArenaHeight,
            pointer = Offset(272f, 247f),
            click = true,
        ) {
            val arena = remember { DocArena() }
            ProvideSkin(Skin.HighContrast) {
                ProvideLayoutDirection(LayoutDirection.Rtl) {
                    DebugWindowHost(state = rememberDebugWindowsState(MemoryDebugWindowStore())) {
                        // The world itself is not mirrored — a game's scene is drawn where its own
                        // code puts it. It is the interface over it that reads from the right.
                        ProvideLayoutDirection(LayoutDirection.Ltr) { ArenaScene(arena) }
                        ArenaWindow(arena, Offset(20f, 16f))
                        SpawnWindow(arena, Offset(330f, 236f))
                    }
                }
            }
        },
    )

    // The same picture with the hand further along the slider each time, for the animated one. Only
    // when asked for, because they are frames to be joined into a GIF rather than pictures of their
    // own: `COMPOSEGL_DOC_FRAMES=1`, then join `debug-window-drag-frame-*.png` in order, 1/12 s each.
    if (System.getenv("COMPOSEGL_DOC_FRAMES") != null) {
        repeat(DragFrames) { i ->
            val name = "debug-window-drag-frame-${i.toString().padStart(2, '0')}"
            // A real drag to a real place, a few pixels further along than the frame before it.
            val to = Offset(430f + i * (92f / (DragFrames - 1)), 55f)
            add(
                DocShot(
                    name,
                    ArenaWidth,
                    ArenaHeight,
                    stock = true,
                    pointer = Offset(430f, 55f),
                    dragTo = to,
                    hold = true,
                ) {
                    val arena = remember { DocArena() }
                    DebugWindowHost(state = rememberDebugWindowsState(MemoryDebugWindowStore())) {
                        ArenaScene(arena)
                        ArenaWindow(arena)
                    }
                },
            )
        }
    }
}

/** How wide and tall every debug-window picture is. The arena is drawn to these numbers. */
private const val ArenaWidth = 620
private const val ArenaHeight = 340

/** Frames in the animated picture of a slider being dragged. */
private const val DragFrames = 13

/** Where the ground starts, and where the thrown thing leaves the player's hand. */
private const val GroundY = 286f
private const val ThrowX = 86f
private const val ThrowY = 248f

/** How fast the thrown thing leaves the hand, and what one unit of gravity does to it. */
private const val ThrowSpeed = 210f
private const val ThrowLift = 255f
private const val ThrowScale = 66f

/** How far right the arc is drawn: where the window starts, so no dot is hidden behind one. */
private const val ArcEdge = 292f

/**
 * The window the pictures tune: one line per property of [DocArena], which the arena already reads.
 */
@Composable
private fun ArenaWindow(arena: DocArena, at: Offset = Offset(296f, 16f)) {
    DebugWindow("Physics", initialPosition = at, onClose = {}) {
        tweak("Gravity", arena::gravity, 3f..24f, step = 0.1f)
        tweak("Drones", arena::drones, 0..16)
        toggle("God mode", arena::godMode)
        choice("Threat", arena::threat, DocThreat.entries)
        colour("Fog", arena::fog, alpha = false)
        button("Spawn wave") {}
        text("Alive", arena.drones.toString())
    }
}

/** A second window, for the pictures of two of them. */
@Composable
private fun SpawnWindow(arena: DocArena, at: Offset) {
    DebugWindow("Spawns", initialPosition = at, id = "Spawns", onClose = {}, labelWidth = 74f) {
        tweak("Every", arena::interval, 0.5f..6f, step = 0.1f)
        tweak("Drones", arena::drones, 0..16)
        toggle("Paused", arena::paused)
    }
}

/**
 * The game under the windows: a drone raid over a ridge, with a throw arc, a fog wash and a shield.
 *
 * Every number it draws with comes from [arena], so a slider moved in a window over it changes the
 * picture — the arc bends, drones appear, the wash changes colour — without the scene knowing that
 * anything but its own properties changed.
 */
@Composable
private fun ArenaScene(arena: DocArena) {
    Box(Modifier.fillMaxSize().background(Brush.vertical(Colour.rgb(0x151C33), Colour.rgb(0x3B2A46)))) {
        // The ridge behind everything, three overlapping rounded humps.
        Box(Modifier.offset(-40f, 162f).size(280f, 220f).background(Colour.rgb(0x1E2742), corner = 100f))
        Box(Modifier.offset(190f, 186f).size(330f, 220f).background(Colour.rgb(0x18203A), corner = 120f))
        Box(Modifier.offset(450f, 170f).size(280f, 220f).background(Colour.rgb(0x1E2742), corner = 110f))

        // The ground, and the line along the top of it.
        Box(
            Modifier.offset(0f, GroundY).fillMaxWidth().height(ArenaHeight - GroundY)
                .background(Colour.rgb(0x10141F)),
        )
        Box(Modifier.offset(0f, GroundY).fillMaxWidth().height(2f).background(Colour.rgb(0x38455F)))

        Drones(arena)
        ThrowArc(arena)
        Player(arena)

        // The fog, over the lot: the colour line in the window is this wash and nothing else.
        Box(Modifier.fillMaxSize().background(arena.fog.withAlpha(0x2B)))

        // A HUD, so the picture reads as a game rather than a drawing.
        Column(Modifier.offset(14f, 12f), verticalArrangement = Arrangement.spacedBy(6f)) {
            Text("WAVE 3", style = "label.heading")
            Text("${arena.threat.name.uppercase()} · ${arena.drones} DRONES", style = "label.dim")
            Box(Modifier.size(118f, 8f).background(Colour.rgb(0x222B3C), corner = 4f)) {
                Box(Modifier.size(82f, 8f).background(Colour.rgb(0x46A758), corner = 4f))
            }
        }
    }
}

/** The raid itself: [DocArena.drones] of them, in ranks, wearing the colour of the threat chosen. */
@Composable
private fun Drones(arena: DocArena) {
    val colour = arena.threat.colour
    repeat(arena.drones) { i ->
        val x = 152f + (i % 4) * 50f
        val y = 70f + (i / 4) * 40f
        Box(Modifier.offset(x, y).size(34f, 15f).background(colour, corner = 7f))
        Box(Modifier.offset(x + 4f, y - 5f).size(9f, 3f).background(colour.scaleAlpha(0.6f), corner = 2f))
        Box(Modifier.offset(x + 21f, y - 5f).size(9f, 3f).background(colour.scaleAlpha(0.6f), corner = 2f))
        Box(Modifier.offset(x + 13f, y + 5f).size(7f, 5f).background(Colour.rgb(0x0B0E13), corner = 2f))
    }
}

/**
 * Where a thrown grenade goes, a dot along its flight, for the gravity line to bend.
 *
 * The same sum a game's own physics does: it leaves the hand at a fixed speed and the only thing
 * that changes between one picture and the next is `arena.gravity`.
 */
@Composable
private fun ThrowArc(arena: DocArena) {
    val steps = 40
    var landed: Offset? = null
    repeat(steps + 1) { i ->
        val t = i / steps.toFloat()
        val x = ThrowX + ThrowSpeed * t
        val y = ThrowY - (ThrowLift * t - 0.5f * ThrowScale * arena.gravity * t * t)
        if (landed == null && x <= ArcEdge && y <= GroundY) {
            val fade = 0.35f + 0.65f * (1f - t)
            Box(
                Modifier.offset(x, y).size(5f, 5f)
                    .background(Colour.rgb(0xFFD166).scaleAlpha(fade), corner = 3f),
            )
            if (y >= GroundY - 6f) landed = Offset(x, y)
        }
    }
    // Where it comes down, when it comes down in front of the window.
    landed?.let { at ->
        Box(
            Modifier.offset(at.x - 11f, GroundY - 11f).size(24f, 24f)
                .border(Colour.rgb(0xFFD166), width = 2f, corner = 12f),
        )
    }
}

/** Who is throwing it, with the ring god mode puts round them. */
@Composable
private fun Player(arena: DocArena) {
    if (arena.godMode) {
        Box(
            Modifier.offset(44f, 224f).size(60f, 60f)
                .background(Colour.argb(0x2246A758), corner = 30f)
                .border(Colour.rgb(0x7BE08F), width = 2f, corner = 30f),
        )
    }
    Box(Modifier.offset(62f, 248f).size(24f, 38f).background(Colour.rgb(0x5B8DEF), corner = 5f))
    Box(Modifier.offset(66f, 232f).size(16f, 16f).background(Colour.rgb(0xE8ECF2), corner = 8f))
}

/** What the debug-window pictures tune: ordinary properties, the kind a game's own systems have. */
private class DocArena {
    var gravity by mutableStateOf(9.8f)
    var drones by mutableStateOf(8)
    var godMode by mutableStateOf(true)
    var threat by mutableStateOf(DocThreat.Raid)
    var fog by mutableStateOf(Colour.rgb(0x4A7FD4))
    var interval by mutableStateOf(2.5f)
    var paused by mutableStateOf(false)
}

/** How hard the raid is, and what colour that paints it. */
private enum class DocThreat(val colour: Colour) {
    Scout(Colour.rgb(0x46A758)),
    Raid(Colour.rgb(0xE8A33D)),
    Swarm(Colour.rgb(0xE5484D)),
}

// ---------------------------------------------------------------- live plots

/**
 * The graphs, over numbers that really move.
 *
 * Every picture here is taken over the same raid as the debug windows above, ticked a frame at a
 * time while the shutter is open: a wave lands part way through, the frame it lands on costs three
 * times what the frames around it cost, and the drones are shot down one at a time afterwards. The
 * graphs are fed those numbers a frame each, and the scene behind them is drawn from the very same
 * ones — so the step in the drone graph is the row of drones appearing in the picture.
 */
private fun MutableList<DocShot>.plots() {
    // One graph among ordinary lines, which is where most of them live: a window that says what the
    // frame costs now and what it just did. The spike is the wave landing.
    add(
        DocShot("plot-window", ArenaWidth, ArenaHeight, stock = true, seconds = PlotSeconds) {
            val raid = rememberRaidPlots()
            DebugWindowHost(state = rememberDebugWindowsState(MemoryDebugWindowStore())) {
                ArenaScene(raid.arena)
                DebugWindow("Telemetry", initialPosition = Offset(296f, 16f), onClose = {}, labelWidth = 74f) {
                    Plot(
                        raid.frames,
                        Modifier.size(280f, 78f),
                        range = 0f..33f,
                        guides = listOf(BudgetMillis),
                        label = "frame ms",
                    )
                    text("Alive", raid.arena.drones.toString())
                    text("Wave", "3")
                }
            }
        },
    )

    // Three of them stacked, which is how a game watches several things at once: what the frame
    // cost, how many drones are up, and what the guns landed. The step in the middle graph and the
    // spike in the top one are the same moment.
    add(
        DocShot("plot-stack", ArenaWidth, ArenaHeight, stock = true, seconds = PlotSeconds) {
            val raid = rememberRaidPlots()
            DebugWindowHost(state = rememberDebugWindowsState(MemoryDebugWindowStore())) {
                ArenaScene(raid.arena)
                DebugWindow("Telemetry", initialPosition = Offset(286f, 16f), onClose = {}) {
                    Plot(
                        raid.frames,
                        Modifier.size(292f, 72f),
                        range = 0f..33f,
                        guides = listOf(BudgetMillis),
                        label = "frame ms",
                    )
                    Plot(
                        raid.drones,
                        Modifier.size(292f, 72f),
                        label = "drones",
                        format = { if (it.isFinite()) it.toInt().toString() else "-" },
                    )
                    Histogram(raid.hits, Modifier.size(292f, 72f), label = "hits")
                }
            }
        },
    )

    // A hand on the spike. The pointer picks the sample under it, draws the upright line and the dot
    // through it, and writes that sample's own number in the corner — which is how the stutter two
    // seconds ago gets a number put on it rather than a shrug.
    add(
        DocShot(
            "plot-hover", 460, 170,
            stock = true,
            seconds = PlotSeconds,
            pointer = Offset(PlotHoverX, 92f),
        ) {
            val raid = rememberRaidPlots()
            Frame {
                Plot(
                    raid.frames,
                    Modifier.size(420f, 128f),
                    range = 0f..33f,
                    guides = listOf(BudgetMillis),
                    label = "frame ms",
                )
            }
        },
    )

    // The high-contrast skin, reading from the right: the window is measured from the top-right
    // corner and the graphs run the other way, newest sample on the left.
    add(
        DocShot("plot-rtl", ArenaWidth, ArenaHeight, seconds = PlotSeconds) {
            val raid = rememberRaidPlots()
            ProvideSkin(Skin.HighContrast) {
                ProvideLayoutDirection(LayoutDirection.Rtl) {
                    DebugWindowHost(state = rememberDebugWindowsState(MemoryDebugWindowStore())) {
                        // The world is not mirrored: a game draws its scene where its own code puts
                        // it. It is the window over it that reads from the right.
                        ProvideLayoutDirection(LayoutDirection.Ltr) { ArenaScene(raid.arena) }
                        DebugWindow("Telemetry", initialPosition = Offset(20f, 16f), onClose = {}, labelWidth = 74f) {
                            Plot(
                                raid.frames,
                                Modifier.size(280f, 78f),
                                range = 0f..33f,
                                guides = listOf(BudgetMillis),
                                label = "frame ms",
                            )
                            text("Alive", raid.arena.drones.toString())
                        }
                    }
                }
            }
        },
    )

    // The same window a little later each time, for the animated one: the wave lands, the graph
    // takes the spike, and the trace carries it away to the left while the drones are shot down.
    // Only when asked for, because they are frames to be joined into a GIF rather than pictures of
    // their own: `COMPOSEGL_DOC_FRAMES=1`, then join `plot-live-frame-*.png` in order, 0.15 s each,
    // which is the real time between them and so the speed the raid really ran at.
    if (System.getenv("COMPOSEGL_DOC_FRAMES") != null) {
        repeat(PlotFrames) { i ->
            val name = "plot-live-frame-${i.toString().padStart(2, '0')}"
            add(
                DocShot(name, ArenaWidth, ArenaHeight, stock = true, seconds = PlotFirstSecond + i * PlotFrameStep) {
                    val raid = rememberRaidPlots()
                    DebugWindowHost(state = rememberDebugWindowsState(MemoryDebugWindowStore())) {
                        ArenaScene(raid.arena)
                        DebugWindow("Telemetry", initialPosition = Offset(296f, 16f), onClose = {}, labelWidth = 74f) {
                            Plot(
                                raid.frames,
                                Modifier.size(280f, 78f),
                                range = 0f..33f,
                                guides = listOf(BudgetMillis),
                                label = "frame ms",
                            )
                            text("Alive", raid.arena.drones.toString())
                            text("Wave", "3")
                        }
                    }
                },
            )
        }
    }
}

/** The frame budget the graphs draw a line across: sixty frames a second. */
private const val BudgetMillis = 16.6f

/** How long every plot picture runs before the shutter. Long enough to fill the ring and scroll it. */
private const val PlotSeconds = 2.3f

/** How many samples the graphs hold: two seconds of frames. */
private const val PlotWindow = 120

/** Where the pointer rests in the hover picture — on the spike the wave made. */
private const val PlotHoverX = 193.5f

/** The animated picture: how many frames, when the first one is taken, and how far apart they are. */
private const val PlotFrames = 16
private const val PlotFirstSecond = 0.85f
private const val PlotFrameStep = 0.15f

/**
 * The raid the graphs are of, and the buffers they read.
 *
 * One tick a frame, from a frame callback rather than from the composition: each sample moves the
 * buffer's revision, which is Compose state, and writing state a plot is reading while that plot is
 * being composed is a screen that never settles. The drone count is written into the same [DocArena]
 * the scene is drawn from, so the picture and the graph can never disagree about it.
 */
@Composable
private fun rememberRaidPlots(): RaidPlots {
    val plots = remember {
        RaidPlots(DocArena(), DocRaid(), PlotBuffer(PlotWindow), PlotBuffer(PlotWindow), PlotBuffer(PlotWindow))
    }
    LaunchedEffect(plots) {
        while (true) {
            withFrameNanos {
                plots.raid.step()
                plots.arena.drones = plots.raid.drones
                plots.frames.add(plots.raid.frameMillis)
                plots.drones.add(plots.raid.drones.toFloat())
                plots.hits.add(plots.raid.hits)
            }
        }
    }
    return plots
}

/** What one plot picture holds: the arena the scene reads, the raid that moves it, and the graphs. */
private class RaidPlots(
    val arena: DocArena,
    val raid: DocRaid,
    val frames: PlotBuffer,
    val drones: PlotBuffer,
    val hits: PlotBuffer,
)

/**
 * A wave of drones, a frame at a time.
 *
 * Ordinary numbers of the kind a game already has, worked out from the tick rather than from a die,
 * so the same picture comes out of the same command every time. The wave lands on [WaveTick]: the
 * drones arrive, the frame they arrive on costs what a frame costs when a pile of work turns up on
 * it, and the guns take them down again.
 */
private class DocRaid {

    private var at = 0

    /** How many drones are up. The scene draws this many. */
    var drones = HoldingDrones
        private set

    /** What the last frame cost, in milliseconds. */
    var frameMillis = 0f
        private set

    /** What the guns landed on that frame. */
    var hits = 0f
        private set

    fun step() {
        at++
        val since = at - WaveTick
        drones = if (since < 0) HoldingDrones else (WaveDrones - since / ShotDownEvery).coerceAtLeast(2)
        // The work the wave brings with it: the frame it lands on pays for all of it, and the two
        // after it are the renderer catching up. This is the shape of spike a graph is bought for —
        // an average over the same second hides it completely.
        val spike = when (since) {
            0 -> 18f
            1 -> 7.5f
            2 -> 2.5f
            else -> 0f
        }
        val wobble = sin(at * 0.9f) * 0.3f + sin(at * 0.31f) * 0.22f
        frameMillis = BaseMillis + drones * MillisPerDrone + spike + wobble
        hits = if (since < 0) 0f else (sin(at * 0.42f) * 3f).coerceAtLeast(0f)
    }
}

/** How many drones are up before the wave lands, and how many it brings. */
private const val HoldingDrones = 3
private const val WaveDrones = 14

/** Which tick the wave lands on, and how many ticks each drone after that takes to go down. */
private const val WaveTick = 72
private const val ShotDownEvery = 14

/** What a frame costs with nothing on the screen, and what each drone adds to it. */
private const val BaseMillis = 4.6f
private const val MillisPerDrone = 0.42f

// ---------------------------------------------------------------- the live node tree

/**
 * The whole screen as a tree you can browse, over a game that has something worth finding in it.
 *
 * All three are taken over the same paused raid: a HUD, a card of buttons, and an autosave spinner
 * turning in the corner, which is what puts a redraw count on a row and keeps it glowing. The
 * pointer and the keyboard in them are real, so the row that is chosen is a row something really
 * chose and the letters in the filter box are letters somebody really typed.
 */
private fun MutableList<DocShot>.nodeTree() {
    // A real click on the OPTIONS button, through an inspector the tree shares its state with. One
    // click does the lot: the node is pinned, so it is outlined on the screen and the inspector's
    // panel is filled in; the rows opened down to it on the way, leaving the branches beside it
    // folded; and the row for it is the one drawn chosen.
    add(
        DocShot(
            "node-tree",
            NodeShotWidth,
            NodeShotHeight,
            stock = true,
            pointer = Offset(195f, 248f),
            click = true,
            seconds = 1f,
        ) {
            val inspection = rememberInspectorState()
            DebugWindowHost(state = rememberDebugWindowsState(MemoryDebugWindowStore())) {
                // The card up where the inspector's panel down the right side is not over it, and the
                // window under it, which is where a hand drags a window it wants out of the way.
                Inspector(enabled = true, state = inspection) { NodeScene(Offset(-74f, -87f)) }
                DebugWindow("UI tree", initialPosition = Offset(14f, 340f), onClose = {}) {
                    NodeTree(inspection, Modifier.width(300f).height(250f))
                }
            }
        },
    )

    // `#` typed into the filter box for real — the box was clicked first, which is why it has the
    // focus ring — so the rows left are the nodes somebody gave a test tag and the nodes above them,
    // opened so there is a way down to each one. That is how a widget is found by its tag.
    add(
        DocShot(
            "node-tree-filter",
            TreeShotWidth,
            TreeShotHeight,
            stock = true,
            pointer = Offset(400f, 62f),
            click = true,
            typed = listOf(Typing.Write("#"), Typing.Wait(24)),
        ) {
            // The card moved out from under the window, so the game it is over is still a game.
            NodeTreeOverGame(Offset(330f, 14f), cardOffset = Offset(-150f, 0f))
        },
    )

    // The high-contrast skin reading from the right: the window is measured from the top-right
    // corner, the filter box and the `0x0` switch swap sides, and the rows step in from the right,
    // with each node's size before its name rather than after it.
    add(
        DocShot(
            "node-tree-rtl",
            TreeShotWidth,
            TreeShotHeight,
            pointer = Offset(230f, 62f),
            click = true,
            typed = listOf(Typing.Write("#"), Typing.Wait(24)),
        ) {
            ProvideSkin(Skin.HighContrast) {
                ProvideLayoutDirection(LayoutDirection.Rtl) {
                    // The window is measured from the other corner in this direction, so it lands on
                    // the left and the card goes the other way to stay out from under it.
                    NodeTreeOverGame(Offset(330f, 14f), mirrored = true, cardOffset = Offset(150f, 0f))
                }
            }
        },
    )
}

/** How wide and tall the picture with the inspector beside the tree is. */
private const val NodeShotWidth = 640
private const val NodeShotHeight = 640

/** How wide and tall the pictures of the tree on its own are. */
private const val TreeShotWidth = 640
private const val TreeShotHeight = 400

/**
 * The tree in a window over the game, pointed at the game's own root rather than at an inspector.
 *
 * The second way the page shows: a `PlacedHandler` keeps the node the game is composed into, and the
 * window is written outside it, so the tree lists the game and never the tool looking at it.
 *
 * @param mirrored whether the game under it is put back the right way round, for the right-to-left
 *   picture: a game's scene is drawn where its own code puts it, and it is the interface over it
 *   that reads from the right.
 * @param cardOffset where the game's card sits, so the window is not over it.
 */
@Composable
private fun NodeTreeOverGame(at: Offset, mirrored: Boolean = false, cardOffset: Offset = Offset(0f, 0f)) {
    var root by remember { mutableStateOf<UiNode?>(null) }
    val placed = remember { PlacedHandler { root = it } }
    DebugWindowHost(state = rememberDebugWindowsState(MemoryDebugWindowStore())) {
        Box(Modifier.fillMaxSize().onPlaced(placed)) {
            if (mirrored) {
                ProvideLayoutDirection(LayoutDirection.Ltr) { NodeScene(cardOffset) }
            } else {
                NodeScene(cardOffset)
            }
        }
        DebugWindow("UI tree", initialPosition = at, onClose = {}) {
            // Tall enough for every row the filter leaves, so the picture is the whole answer rather
            // than the top of it.
            NodeTree(root, Modifier.width(275f).height(300f))
        }
    }
}

/**
 * The game every node-tree picture is taken over: a raid, paused, with a card of buttons on it.
 *
 * Written the way a game writes a screen — a HUD in one corner, a card in the middle, an autosave
 * in another — and tagged the way a game tags what its tests reach for, because a tag is what the
 * rows show after the name and what the filter box searches.
 */
@Composable
private fun NodeScene(cardOffset: Offset = Offset(0f, 0f)) {
    // A number a game really does tick over: it goes up every few frames, which is what puts a
    // rebuild count on one row and leaves it red when the shutter goes.
    val salvage = remember { DocSalvage() }
    LaunchedEffect(salvage) {
        var frames = 0
        while (true) {
            withFrameNanos { }
            frames++
            // Read and written here rather than in the composition, so only the line showing it is
            // built again: a screen where everything is red says nothing about what costs anything.
            if (frames % SalvageEvery == 0) salvage.amount += 5
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.vertical(Colour.rgb(0x141B2E), Colour.rgb(0x33253F)))) {
        // A ridge and a floor, so the picture reads as a place rather than a swatch.
        Box(
            Modifier.align(Alignment.BottomStart).offset(-40f, 0f).size(300f, 190f)
                .background(Colour.rgb(0x1C2540), corner = 110f),
        )
        Box(
            Modifier.align(Alignment.BottomEnd).offset(40f, 0f).size(320f, 210f)
                .background(Colour.rgb(0x18203A), corner = 120f),
        )
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(44f).background(Colour.rgb(0x10141F)))

        // The raid itself, a rank of drones across the top.
        Row(
            Modifier.align(Alignment.TopCentre).offset(0f, 86f).testTag("raid"),
            horizontalArrangement = Arrangement.spacedBy(22f),
        ) {
            repeat(4) { Box(Modifier.size(34f, 15f).background(Colour.rgb(0xE8A33D), corner = 7f)) }
        }

        // The HUD in the corner, the part of a screen a player reads without looking at it.
        Column(
            Modifier.align(Alignment.TopStart).padding(14f).testTag("hud"),
            verticalArrangement = Arrangement.spacedBy(6f),
        ) {
            Text("WAVE 3", style = "label.heading")
            Text("RAID · 4 DRONES", style = "label.dim")
            Box(Modifier.size(118f, 8f).background(Colour.rgb(0x222B3C), corner = 4f).testTag("health")) {
                Box(Modifier.size(82f, 8f).background(Colour.rgb(0x46A758), corner = 4f))
            }
        }

        // What the pictures point at: three buttons a test would reach for by tag. A width of its
        // own, or a column of buttons told to fill it is a card as wide as the screen.
        Column(
            Modifier.align(Alignment.Centre).offset(cardOffset.x, cardOffset.y).width(152f)
                .background(Colour.rgb(0x1A1F28), corner = 8f)
                .border(Colour.rgb(0x2E3644), width = 1f, corner = 8f)
                .padding(16f).testTag("pause"),
            verticalArrangement = Arrangement.spacedBy(8f),
            horizontalAlignment = HorizontalAlignment.Centre,
        ) {
            Text("PAUSED", style = "label.heading")
            Button("RESUME", onClick = {}, modifier = Modifier.fillMaxWidth().testTag("resume"))
            Button("OPTIONS", onClick = {}, modifier = Modifier.fillMaxWidth().testTag("options"))
            Button("QUIT", onClick = {}, modifier = Modifier.fillMaxWidth().testTag("quit"))
        }

        // Two things that keep costing frames, written straight into the screen rather than tucked
        // inside a row, so their rows are top ones and the picture has the counts on it without
        // anything being opened first: a spinner that only ever redraws, and a number that is
        // rebuilt every few frames.
        Spinner(Modifier.align(Alignment.BottomEnd).offset(-78f, -16f).size(14f, 14f).testTag("saving"))
        Text("SAVING", Modifier.align(Alignment.BottomEnd).offset(-16f, -15f), style = "label.dim")
        // Beside the card rather than always in the middle, so a window parked over the middle of
        // a narrow picture does not cut it in half.
        Salvage(salvage, Modifier.align(Alignment.TopCentre).offset(cardOffset.x, 16f).testTag("salvage"))
    }
}

/** How many frames go by between one tick of the salvage counter and the next. */
private const val SalvageEvery = 8

/** A number the game keeps changing. Its own object, so ticking it rebuilds one line and not a screen. */
private class DocSalvage {
    var amount by mutableStateOf(1240)
}

/** The line that shows it, and the only thing that reads it, so it is the only thing rebuilt. */
@Composable
private fun Salvage(state: DocSalvage, modifier: Modifier) {
    Text("SALVAGE ${state.amount}", modifier, style = "label.heading")
}

// ---------------------------------------------------------------- world markers

/**
 * Interface pinned to points in the world, over a patrol seen through a camera that really turns.
 *
 * Every picture here is the same walk: the camera starts facing away from the objective, turns
 * left and steps forward, and each shot is that one camera a different number of seconds in. The
 * perspective divide is a real one — the same projection draws the figures on the ground and
 * places the markers over them — so a nameplate sits over its enemy because the sums agree, not
 * because anything was nudged into place.
 */
private fun MutableList<DocShot>.worldMarkers() {
    // Three quarters of a second in, where the camera has swung round far enough for the patrol to
    // spread out in front of it. Nameplates over nine of them at nine different ranges, shrinking
    // and thinning with distance; the objective is still off to the left, held at the edge with a
    // triangle turned towards it and the metres left under it.
    add(DocShot("game-world-markers", MarkerWidth, MarkerHeight, seconds = MarkerStillSecond) {
        MarkerScene(Size(MarkerWidth.toFloat(), MarkerHeight.toFloat()))
    })

    // The same moment twice, one above the other: every marker on top, and underneath the same
    // layer told to keep five and to drop anything sitting on top of something it has already
    // kept. What survives is the objective, which asked for the highest priority, and the nearest
    // four of the patrol; the far ones along the horizon are the ones that go.
    add(DocShot("game-world-markers-declutter", MarkerStackWidth, MarkerStackHeight, seconds = MarkerStillSecond) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(MarkerGap)) {
                MarkerPanel("every marker") { size -> MarkerScene(size, hud = false) }
                MarkerPanel("maxVisible = 5, declutter = true") { size ->
                    MarkerScene(size, maxVisible = 5, declutter = true, hud = false)
                }
            }
        }
    })

    // The high-contrast skin reading from the right. The world is not mirrored — a game draws its
    // scene where its own code puts it — and neither is where a marker lands, because a world is a
    // picture rather than a line of text. What does turn round is the writing: the strip along the
    // top, and the range under the waypoint.
    add(DocShot("game-world-markers-rtl", MarkerWidth, MarkerHeight, seconds = MarkerRtlSecond) {
        ProvideSkin(Skin.HighContrast) {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                MarkerScene(Size(MarkerWidth.toFloat(), MarkerHeight.toFloat()), mirrored = true)
            }
        }
    })

    // The same camera a little later each time, for the animated one. Only when asked for, because
    // they are frames to be joined into a GIF rather than pictures of their own:
    // `COMPOSEGL_DOC_FRAMES=1`, then join `game-world-markers-frame-*.png` in order, 0.12 s each,
    // which is the real time between them and so the speed the camera really turned at.
    if (System.getenv("COMPOSEGL_DOC_FRAMES") != null) {
        repeat(MarkerFrames) { i ->
            val name = "game-world-markers-frame-${i.toString().padStart(2, '0')}"
            add(DocShot(name, MarkerWidth, MarkerHeight, seconds = MarkerFirstSecond + i * MarkerFrameStep) {
                MarkerScene(Size(MarkerWidth.toFloat(), MarkerHeight.toFloat()))
            })
        }
    }
}

/** How big every world-marker picture is. */
private const val MarkerWidth = 560
private const val MarkerHeight = 300

/** The decluttering picture: the same scene twice, with a line of writing over each. */
private const val MarkerStackWidth = 588
private const val MarkerStackHeight = 640

/** The gap between the two halves of the decluttering picture. */
private const val MarkerGap = 8f

/** When the still pictures are taken, in seconds into the camera's turn. */
private const val MarkerStillSecond = 0.75f
private const val MarkerRtlSecond = 1.35f

/** The animated picture: how many frames, when the first is taken, and how far apart they are. */
private const val MarkerFrames = 20
private const val MarkerFirstSecond = 0.05f
private const val MarkerFrameStep = 0.12f

/**
 * One half of the decluttering picture: a caption, and a scene in a box of its own.
 *
 * The scene is given the size of that box rather than the size of the window, because a
 * `WorldMarkerLayer` projects into its own box: this is the split-screen case with the split drawn
 * by hand, and neither half is told the other one exists.
 */
@Composable
private fun MarkerPanel(caption: String, content: @Composable (Size) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5f)) {
        Text(caption, style = "label.dim")
        Box(Modifier.width(MarkerWidth.toFloat()).height(MarkerPanelHeight).clip(6f)) {
            content(Size(MarkerWidth.toFloat(), MarkerPanelHeight))
        }
    }
}

/** How tall each half of the decluttering picture is, once its caption has had its line. */
private const val MarkerPanelHeight = 278f

/**
 * The patrol, the camera, and the markers over it.
 *
 * The camera's turn is driven from a frame callback rather than from the composition, and it is
 * started before the layer is composed so that the layer's own frame callback reads the angle this
 * one has already written: a nameplate a frame behind its enemy is a nameplate that visibly slides
 * about.
 *
 * @param view how big the layer's box is. The projection needs it — the last step of a perspective
 *   divide is turning a number between -1 and 1 into a pixel — and so does the scene under it, so
 *   that both are drawing the same world.
 * @param maxVisible how many markers may be on screen at once.
 * @param declutter whether a marker landing on one already kept is dropped.
 * @param hud whether the strip along the top is drawn.
 * @param mirrored whether the scene under the markers is put back the right way round, for the
 *   right-to-left picture.
 */
@Composable
private fun MarkerScene(
    view: Size,
    maxVisible: Int = Int.MAX_VALUE,
    declutter: Boolean = false,
    hud: Boolean = true,
    mirrored: Boolean = false,
) {
    val world = remember { MarkerWorld() }
    LaunchedEffect(world) {
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            // Written here rather than in the composition: writing state a layer is reading while
            // that layer is being composed is a screen that never settles.
            world.turnTo((now - start) / NanosPerSecond)
        }
    }

    val camera = remember(world) { world.camera() }

    Box(Modifier.fillMaxSize().background(Brush.vertical(Colour.rgb(0x111A2E), Colour.rgb(0x2C1F3C)))) {
        if (mirrored) {
            ProvideLayoutDirection(LayoutDirection.Ltr) { MarkerGround(world, view) }
        } else {
            MarkerGround(world, view)
        }

        WorldMarkerLayer(projection = camera, maxVisible = maxVisible, declutter = declutter) {
            for (foe in MarkerPatrol) {
                marker(
                    key = foe.name,
                    x = foe.x,
                    y = MarkerPlateHeight,
                    z = foe.z,
                    // Solid up close, gone by the far end, so the ones at the back of the field
                    // thin out instead of piling up along the horizon.
                    fadeDistance = FadeNear..FadeFar,
                    // And smaller with it, so the depth in the picture is the depth in the world.
                    scaleDistance = ScaleNear..ScaleFar,
                    farScale = FarScale,
                    anchor = Alignment.BottomCentre,
                    priority = foe.priority,
                ) {
                    Nameplate(foe)
                }
            }

            // The one that is not on the screen. Held just inside the edge with a triangle turned
            // towards where it really is — including while it is behind the camera, which is where
            // it starts — and first in the queue when there is only room for a few.
            marker(
                key = "objective",
                x = ObjectiveX,
                y = ObjectiveY,
                z = ObjectiveZ,
                offScreen = OffScreen.ClampToEdge(arrow = true, inset = 6f),
                priority = ObjectivePriority,
            ) {
                Waypoint(world.objectiveMetres)
            }
        }

        if (hud) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12f, vertical = 10f),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("PATROL — 9 CONTACTS", style = "label.dim")
                Text("RELAY MAST ${world.objectiveMetres} m", style = "label")
            }
        }
    }
}

/** A name and what is left of the thing wearing it. Anything at all goes in a marker; this is a column. */
@Composable
private fun Nameplate(foe: MarkerFoe) {
    Column(horizontalAlignment = HorizontalAlignment.Centre, verticalArrangement = Arrangement.spacedBy(3f)) {
        Text(foe.name, style = "label")
        Bar(foe.health, length = 56f, thickness = 4f, trail = false)
    }
}

/** Where the player is being sent, and how far away it is, counted down as they walk towards it. */
@Composable
private fun Waypoint(metres: Int) {
    Column(horizontalAlignment = HorizontalAlignment.Centre, verticalArrangement = Arrangement.spacedBy(3f)) {
        Box(
            Modifier.size(20f).background(Colour.rgb(0xF2C94C), corner = 5f)
                .border(Colour.rgb(0x0B0E13), width = 2f, corner = 5f),
        ) {
            Box(Modifier.align(Alignment.Centre).size(6f).background(Colour.rgb(0x0B0E13), corner = 3f))
        }
        Box(Modifier.background(Colour.argb(0xB00B0E13), corner = 4f).padding(horizontal = 5f, vertical = 1f)) {
            Text("$metres m", style = "label.dim")
        }
    }
}

/**
 * The ground the patrol is standing on, drawn through the same camera the markers are placed by.
 *
 * That is the whole point of the picture: if the scene were drawn one way and the markers placed
 * another, a nameplate sitting over an enemy would be a coincidence that the first turn of the
 * camera undoes.
 */
@Composable
private fun MarkerGround(world: MarkerWorld, view: Size) {
    val horizon = view.height / 2f
    // Its own box, so that the scene is measured from its own left-hand corner. Everything in here
    // is placed by an offset from the corner its parent started it at, and in a right-to-left
    // screen that corner is the other one — a world drawn from the far side comes out mirrored.
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.offset(0f, horizon).fillMaxWidth().height(view.height - horizon)
                .background(Colour.rgb(0x0C1019)),
        )
        Box(Modifier.offset(0f, horizon).fillMaxWidth().height(1.5f).background(Colour.rgb(0x3C4C6A)))

        for (ridge in MarkerRidge) {
            Slab(world, view, ridge.x, ridge.z, ridge.width, ridge.height, Colour.rgb(0x1A2340), ridge.width / 2f)
        }
        // Patches of the ground itself, biggest at the player's feet, so the bottom of the picture
        // is somewhere to stand rather than a blank. Each one is a circle lying on the ground, and
        // it flattens into the distance because its near and far edges are projected separately.
        for (patch in MarkerPatches) {
            Decal(world, view, patch.x, patch.z, patch.width, if (patch.height > 0f) GroundPale else GroundDark)
        }
        for (post in MarkerPosts) {
            Slab(world, view, post.x, post.z, post.width, post.height, Colour.rgb(0x222C46), post.width / 3f)
        }
        // Furthest first, so that a figure nearer the camera is drawn over one behind it — the same
        // order the layer puts their nameplates in.
        for (foe in MarkerPatrol.sortedByDescending { it.z }) {
            Figure(world, view, foe)
        }
        // The mast the waypoint belongs to, so that when the camera comes round to it there is
        // something really standing there.
        Slab(world, view, ObjectiveX, ObjectiveZ, 0.7f, ObjectiveY, Colour.rgb(0xF2C94C), 0.35f)
    }
}

/** One upright thing in the world: a ridge, a post, a mast. Nothing is drawn behind the camera. */
@Composable
private fun Slab(
    world: MarkerWorld,
    view: Size,
    x: Float,
    z: Float,
    width: Float,
    height: Float,
    colour: Colour,
    corner: Float,
) {
    val left = world.screen(x - width / 2f, 0f, z, view) ?: return
    val right = world.screen(x + width / 2f, 0f, z, view) ?: return
    val top = world.screen(x, height, z, view) ?: return
    val across = (right.x - left.x).coerceAtLeast(1f)
    val tall = (left.y - top.y).coerceAtLeast(1f)
    Box(Modifier.offset(left.x, top.y).size(across, tall).background(colour, corner = across * corner / width))
}

/** A circle lying flat on the ground, projected edge by edge so that distance flattens it. */
@Composable
private fun Decal(world: MarkerWorld, view: Size, x: Float, z: Float, radius: Float, colour: Colour) {
    val left = world.screen(x - radius, 0f, z, view) ?: return
    val right = world.screen(x + radius, 0f, z, view) ?: return
    val back = world.screen(x, 0f, z + radius, view) ?: return
    val front = world.screen(x, 0f, z - radius, view) ?: return
    val across = (right.x - left.x).coerceAtLeast(1f)
    val tall = (front.y - back.y).coerceAtLeast(1f)
    Box(Modifier.offset(left.x, back.y).size(across, tall).background(colour, corner = tall / 2f))
}

/** The two colours the ground is patched with: a little lighter than it, and a little darker. */
private val GroundPale = Colour.rgb(0x141C2C)
private val GroundDark = Colour.rgb(0x080B12)

/** One of the patrol: a shadow on the ground, a body, and a head. */
@Composable
private fun Figure(world: MarkerWorld, view: Size, foe: MarkerFoe) {
    val left = world.screen(foe.x - FigureWidth / 2f, 0f, foe.z, view) ?: return
    val right = world.screen(foe.x + FigureWidth / 2f, 0f, foe.z, view) ?: return
    val top = world.screen(foe.x, FigureHeight, foe.z, view) ?: return
    val across = (right.x - left.x).coerceAtLeast(2f)
    val tall = (left.y - top.y).coerceAtLeast(3f)
    val head = across * 0.62f

    Box(
        Modifier.offset(left.x - across * 0.3f, left.y - across * 0.22f).size(across * 1.6f, across * 0.44f)
            .background(Colour.argb(0x55000000), corner = across),
    )
    Box(
        Modifier.offset(left.x, top.y + head * 0.7f).size(across, (tall - head * 0.7f).coerceAtLeast(2f))
            .background(foe.colour, corner = across * 0.36f),
    )
    Box(
        Modifier.offset(left.x + (across - head) / 2f, top.y).size(head)
            .background(Colour.rgb(0xE8ECF2), corner = head / 2f),
    )
}

/** How wide and how tall one of the patrol is, in metres. */
private const val FigureWidth = 0.62f
private const val FigureHeight = 1.8f

/** How high above the ground a nameplate stands: over the head, not through it. */
private const val MarkerPlateHeight = 2.15f

/** Where the objective is, how high its beacon sits, and how hard it fights for a place. */
private const val ObjectiveX = -24f
private const val ObjectiveY = 2.6f
private const val ObjectiveZ = 8f
private const val ObjectivePriority = 3f

/** The distances a marker fades between, and the ones it shrinks between. */
private const val FadeNear = 28f
private const val FadeFar = 46f
private const val ScaleNear = 8f
private const val ScaleFar = 40f
private const val FarScale = 0.45f

/** The camera's turn: from, to, and how long it takes. */
private const val TurnFrom = 0.45f
private const val TurnTo = -0.95f
private const val TurnSeconds = 2.4f

/** How far the player walks while turning, so the range on the waypoint really counts down. */
private const val WalkX = -4f
private const val WalkZ = 1.5f

/** How wide the lens is, and how close a point may get before the divide is held off. */
private const val Fov = 1.05f
private const val NearPlane = 0.35f

/** How high the camera is off the ground: somebody's eyes. */
private const val EyeHeight = 1.7f

private const val NanosPerSecond = 1_000_000_000f

/**
 * Where the camera is and which way it is looking, and the sums that turn that into pixels.
 *
 * An ordinary game camera, and the toolkit knows nothing about it: what the layer is handed is one
 * function that answers where on the screen a point in the world is, and how far away.
 */
private class MarkerWorld {
    var yaw by mutableStateOf(TurnFrom)
    var x by mutableStateOf(0f)
    var z by mutableStateOf(0f)

    /** Rounded to whole metres, so the waypoint's label is rebuilt when it changes and not before. */
    var objectiveMetres by mutableStateOf(0)

    /** Where the camera has got to [seconds] into the turn. It stops when the turn is done. */
    fun turnTo(seconds: Float) {
        val along = (seconds / TurnSeconds).coerceIn(0f, 1f)
        yaw = TurnFrom + (TurnTo - TurnFrom) * along
        x = WalkX * along
        z = WalkZ * along
        val dx = ObjectiveX - x
        val dz = ObjectiveZ - z
        objectiveMetres = sqrt(dx * dx + dz * dz).roundToInt()
    }

    /** The one function the layer is given. Behind the camera answers true with a negative depth. */
    fun camera(): WorldProjection = WorldProjection { point, view, onto ->
        project(point.x, point.y, point.z, view, onto)
        true
    }

    /** Where a point lands, for the scene to draw itself by. Null when it is behind the camera. */
    fun screen(x: Float, y: Float, z: Float, view: Size): Offset? {
        val onto = WorldPoint()
        if (!project(x, y, z, view, onto)) return null
        return Offset(onto.x, onto.y)
    }

    /** True when the point is in front of the camera. Fills [onto] either way. */
    private fun project(px: Float, py: Float, pz: Float, view: Size, onto: WorldPoint): Boolean {
        val dx = px - x
        val dy = py - EyeHeight
        val dz = pz - z
        val turn = cos(yaw)
        val swing = sin(yaw)
        val forward = dx * swing + dz * turn
        val across = dx * turn - dz * swing
        val focal = view.height / (2f * tan(Fov / 2f))
        // Held off the lens, so a point on the camera plane is a big number rather than an
        // infinite one. A point behind it keeps its sign and so comes back mirrored, which is
        // exactly what the layer expects to be told about something behind the player.
        val depth = if (forward >= 0f) forward.coerceAtLeast(NearPlane) else forward.coerceAtMost(-NearPlane)
        val range = sqrt(dx * dx + dz * dz)
        onto.set(
            view.width / 2f + focal * across / depth,
            view.height / 2f - focal * dy / depth,
            if (forward < 0f) -range else range,
        )
        return forward >= NearPlane
    }
}

/** One of the patrol: where it is standing, what is left of it, and what colour it is. */
private class MarkerFoe(
    val name: String,
    val x: Float,
    val z: Float,
    val health: Float,
    val colour: Colour,
    val priority: Float = 0f,
)

/**
 * Nine of them, at nine ranges, with two lined up one behind the other so that the picture shows
 * the nearer plate drawn over the further one.
 */
private val MarkerPatrol = listOf(
    MarkerFoe("VEX", -4.3f, 6.5f, 0.78f, Colour.rgb(0xE05A4F), priority = 1f),
    MarkerFoe("HOLLOW", 2.9f, 9.5f, 0.35f, Colour.rgb(0xE08A3C)),
    MarkerFoe("SCRAP-7", -6.2f, 14f, 0.62f, Colour.rgb(0x5B8DEF)),
    MarkerFoe("TALLOW", 0.2f, 19f, 0.9f, Colour.rgb(0x46A758)),
    MarkerFoe("DRIFTER", 17.5f, 26f, 0.5f, Colour.rgb(0x9B6BE0)),
    MarkerFoe("GRIST", -18.7f, 27f, 0.25f, Colour.rgb(0x3FB6C4)),
    MarkerFoe("REMNANT", 24.8f, 30f, 0.4f, Colour.rgb(0x7C8798)),
    MarkerFoe("CINDER", 7.8f, 34f, 0.7f, Colour.rgb(0xD4B24C)),
    MarkerFoe("ASH", -6.5f, 39f, 0.55f, Colour.rgb(0xC46BA0)),
)

/** Something standing in the world, for the turn of the camera to sweep past. */
private class MarkerSolid(val x: Float, val z: Float, val width: Float, val height: Float)

/** The hills along the back, far enough away that they barely move. */
private val MarkerRidge = listOf(
    MarkerSolid(-70f, 150f, 130f, 11f),
    MarkerSolid(30f, 175f, 160f, 15f),
    MarkerSolid(140f, 140f, 120f, 9f),
    MarkerSolid(-180f, 190f, 150f, 13f),
)

/** Posts across the field, which is what makes the camera's turn read as a turn. */
private val MarkerPosts = listOf(
    MarkerSolid(-11f, 9f, 0.5f, 2.6f),
    MarkerSolid(9.5f, 15f, 0.5f, 3.1f),
    MarkerSolid(-16f, 21f, 0.5f, 2.8f),
    MarkerSolid(21f, 30f, 0.5f, 3.4f),
    MarkerSolid(-27f, 36f, 0.5f, 3f),
    MarkerSolid(31f, 48f, 0.5f, 3.6f),
    MarkerSolid(-38f, 55f, 0.5f, 3.2f),
)

/**
 * Patches of ground, near ones big enough to fill the bottom of the picture.
 *
 * `height` is only which of the two colours it is: above zero is the paler one.
 */
private val MarkerPatches = listOf(
    MarkerSolid(1.2f, 5f, 1.3f, 1f),
    MarkerSolid(-4.5f, 6.5f, 1.8f, 0f),
    MarkerSolid(5.5f, 8f, 2f, 0f),
    MarkerSolid(-8f, 10.5f, 2.3f, 1f),
    MarkerSolid(9f, 13f, 2.6f, 1f),
    MarkerSolid(-12f, 16f, 2.8f, 0f),
    MarkerSolid(13f, 19f, 3f, 0f),
    MarkerSolid(-4f, 22f, 2.6f, 1f),
    MarkerSolid(17f, 26f, 3.6f, 1f),
    MarkerSolid(-19f, 30f, 3.8f, 0f),
    MarkerSolid(4f, 36f, 4.6f, 0f),
    MarkerSolid(-28f, 44f, 5f, 1f),
)

// ---------------------------------------------------------------- the compass bar

/**
 * The heading strip, over a world that really turns under it.
 *
 * One patrol: a player standing on a ridge with a camp off to the north-east, raiders to the
 * north-west and the pickup behind them, turning steadily to the right at sixty degrees a second.
 * Nothing here is placed by hand. The hills, the camp and the raiders in the picture are drawn from
 * their own bearings through the same arithmetic the strip uses, so a pin sits over the thing it is
 * a pin for — and when the player turns far enough that the thing walks off the end of the strip,
 * the pin stops there with an arrow on it instead of vanishing.
 */
private fun MutableList<DocShot>.compasses() {
    // Part way through the turn: the camp is off to the right with the raiders to the left, and the
    // pickup is behind, so its pin is stuck to the end with an arrow saying which way to turn.
    add(
        DocShot("game-compass-hud", PatrolWidth, PatrolHeight, stock = true, seconds = PatrolStill) {
            PatrolHud(rememberPatrol().heading)
        },
    )

    // The same three things out there, from three headings. A pin that has run out of strip stops at
    // the end it left by rather than disappearing and leaving the player to guess which way to turn.
    add(
        DocShot("game-compass-clamped", 600, 350, stock = true) {
            Frame {
                Column(verticalArrangement = Arrangement.spacedBy(14f)) {
                    ClampStep("facing the camp: the pickup is behind you, pinned to the right-hand end", CampBearing)
                    ClampStep("turned left to the raiders: the pickup is off the left-hand end instead", 350f)
                    ClampStep("facing the pickup: the other two are behind you now, one at each end", PickupBearing)
                }
            }
        },
    )

    // The high-contrast skin, and the same strip in two languages. The words are the player's; the
    // strip is not mirrored, because east is to the right of north wherever anybody is from and a
    // mirrored strip would slide the wrong way as they turned. The line under each one is an
    // ordinary row, and that one does mirror.
    add(
        DocShot("game-compass-rtl", 600, 275) {
            val strings = remember {
                Strings(
                    mapOf(
                        Locale.English to mapOf("objective" to "The camp"),
                        Locale("he") to mapOf(
                            "compass.n" to "צפ",
                            "compass.e" to "מז",
                            "compass.s" to "דר",
                            "compass.w" to "מע",
                            "objective" to "המחנה",
                        ),
                    ),
                )
            }
            ProvideSkin(Skin.HighContrast) {
                Frame {
                    Column(verticalArrangement = Arrangement.spacedBy(20f)) {
                        LocalisedStrip("English, read left to right", Locale.English, strings, LayoutDirection.Ltr)
                        LocalisedStrip("Hebrew, read right to left: same strip, its own words", Locale("he"), strings, LayoutDirection.Rtl)
                    }
                }
            }
        },
    )

    // The same patrol a little later each time, for the moving picture: the player turns right, the
    // ridge and the camp slide left with the names, and each pin rides over the thing it is a pin
    // for until that thing runs off the end. Only when asked for, because they are frames to be
    // joined into a GIF rather than pictures of their own: `COMPOSEGL_DOC_FRAMES=1`, then join
    // `game-compass-turn-frame-*.png` in order, 0.12 s each, which is the speed the turn really ran.
    if (System.getenv("COMPOSEGL_DOC_FRAMES") != null) {
        repeat(PatrolFrames) { i ->
            val name = "game-compass-turn-frame-${i.toString().padStart(2, '0')}"
            add(
                DocShot(name, PatrolWidth, PatrolHeight, stock = true, seconds = PatrolFirst + i * PatrolStep) {
                    PatrolHud(rememberPatrol().heading)
                },
            )
        }
    }
}

/** How wide and tall every compass picture of the patrol is. */
private const val PatrolWidth = 620
private const val PatrolHeight = 260

/** How much of the circle the strip shows, and how wide the strip and its ruler are inside it. */
private const val PatrolField = 180f
private const val PatrolStripWidth = PatrolWidth - 20f
private const val PatrolStripInner = PatrolWidth - 40f

/** Where the hills stand on the picture, and where the strip hangs. */
private const val Horizon = 178f
private const val StripTop = 12f

/** What is out there, in degrees clockwise from north and metres away. */
private const val CampBearing = 42f
private const val CampRange = 180f
private const val RaidBearing = 315f
private const val RaidRange = 65f
private const val PickupBearing = 208f
private const val PickupRange = 410f

/** How far a pin that asked to fade has gone as faint as it goes: further off than the pickup is. */
private const val PickupFade = 900f

/** Where the player starts looking, and how fast they turn: sixty degrees a second. */
private const val PatrolStart = 318f
private const val PatrolTurnPerFrame = 1f

/** The moment the still picture is taken, and the frames the moving one is made of. */
private const val PatrolStill = 0.9f
private const val PatrolFrames = 20
private const val PatrolFirst = 0.1f
private const val PatrolStep = 0.12f

/**
 * The player turning, a frame at a time.
 *
 * From a frame callback rather than from the composition, like every other moving picture here: the
 * heading is a plain number the game owns, and the strip is handed whatever it is this frame.
 */
@Composable
private fun rememberPatrol(): DocPatrol {
    val patrol = remember { DocPatrol() }
    LaunchedEffect(patrol) {
        while (true) {
            withFrameNanos { patrol.step() }
        }
    }
    return patrol
}

/** A player standing on a ridge and turning to their right. */
private class DocPatrol {

    private var frames = 0

    var heading by mutableStateOf(PatrolStart)
        private set

    fun step() {
        frames++
        heading = turnWrapped(PatrolStart + frames * PatrolTurnPerFrame)
    }
}

/** A HUD as a game has one: the world, the strip across the top of it, and a crosshair. */
@Composable
private fun PatrolHud(heading: Float) {
    Box(Modifier.fillMaxSize()) {
        PatrolScene(heading)
        CompassBar(
            heading = heading,
            fieldOfView = PatrolField,
            Modifier.align(Alignment.TopCentre).offset(0f, StripTop).width(PatrolStripWidth),
            readout = { "${it.roundToInt()}°" },
            distanceText = { "${it.roundToInt()}m" },
            fadeRange = PickupFade,
        ) {
            pin(bearing = CampBearing, distance = CampRange)
            pin(bearing = RaidBearing, distance = RaidRange, style = "label.danger")
            pin(bearing = PickupBearing, distance = PickupRange, fadeWithDistance = true)
        }

        // The middle of the screen, which is the middle of the strip: what the player is looking at.
        Box(Modifier.align(Alignment.Centre).size(14f, 2f).background(Colour.rgb(0xE8ECF2)))
        Box(Modifier.align(Alignment.Centre).size(2f, 14f).background(Colour.rgb(0xE8ECF2)))

        Column(Modifier.offset(16f, PatrolHeight - 56f), verticalArrangement = Arrangement.spacedBy(6f)) {
            Text("PATROL - RIDGE ROAD", style = "label.heading")
            Box(Modifier.size(128f, 8f).background(Colour.rgb(0x222B3C), corner = 4f)) {
                Box(Modifier.size(96f, 8f).background(Colour.rgb(0x46A758), corner = 4f))
            }
        }
    }
}

/**
 * What the player can see from the ridge, drawn from bearings.
 *
 * Every hill and every landmark is put on the screen by [screenAt], which is the strip's own
 * arithmetic — so the camp under the camp's pin is the camp, rather than a drawing arranged to look
 * as though it were.
 */
@Composable
private fun PatrolScene(heading: Float) {
    Box(Modifier.fillMaxSize().background(Brush.vertical(Colour.rgb(0x131C2E), Colour.rgb(0x3C2C41)))) {
        Hills.forEach { hill ->
            val x = screenAt(hill.bearing, heading) ?: return@forEach
            Box(
                Modifier.offset(x - hill.width / 2f, Horizon - hill.height)
                    .size(hill.width, hill.height)
                    .background(hill.colour, corner = hill.height / 1.6f),
            )
        }

        // The ground, and the line along the top of it.
        Box(Modifier.offset(0f, Horizon).fillMaxWidth().height(PatrolHeight - Horizon).background(Colour.rgb(0x0E1220)))
        Box(Modifier.offset(0f, Horizon).fillMaxWidth().height(2f).background(Colour.rgb(0x38455F)))

        // The camp: a hut with a fire beside it.
        screenAt(CampBearing, heading)?.let { x ->
            Box(Modifier.offset(x - 22f, Horizon - 24f).size(44f, 24f).background(Colour.rgb(0x6B5B43), corner = 5f))
            Box(Modifier.offset(x - 26f, Horizon - 34f).size(52f, 14f).background(Colour.rgb(0x8A7452), corner = 7f))
            Box(Modifier.offset(x + 14f, Horizon - 22f).size(28f, 28f).alpha(0.3f).background(Colour.rgb(0xF2C94C), corner = 14f))
            Box(Modifier.offset(x + 22f, Horizon - 14f).size(12f, 12f).background(Colour.rgb(0xF2C94C), corner = 6f))
        }

        // The raiders: two of them, standing where the danger pin says they are.
        screenAt(RaidBearing, heading)?.let { x ->
            Box(Modifier.offset(x - 16f, Horizon - 38f).size(13f, 38f).background(Colour.rgb(0x1A1016), corner = 6f))
            Box(Modifier.offset(x + 4f, Horizon - 34f).size(13f, 34f).background(Colour.rgb(0x1A1016), corner = 6f))
            Box(Modifier.offset(x - 13f, Horizon - 34f).size(7f, 3f).background(Colour.rgb(0xE5484D), corner = 2f))
            Box(Modifier.offset(x + 7f, Horizon - 30f).size(7f, 3f).background(Colour.rgb(0xE5484D), corner = 2f))
        }
    }
}

/** One hill on the ridge, at the bearing it stands on. */
private class DocHill(val bearing: Float, val width: Float, val height: Float, val colour: Colour)

/** The ridge all the way round, so that turning always brings another one along. */
private val Hills = listOf(
    DocHill(350f, 250f, 92f, Colour.rgb(0x27314F)),
    DocHill(28f, 190f, 64f, Colour.rgb(0x1A2440)),
    DocHill(78f, 300f, 104f, Colour.rgb(0x27314F)),
    DocHill(140f, 230f, 78f, Colour.rgb(0x1A2440)),
    DocHill(214f, 270f, 96f, Colour.rgb(0x27314F)),
    DocHill(288f, 210f, 72f, Colour.rgb(0x1A2440)),
)

/** Where on the screen a bearing falls, or null when it is behind the player. */
private fun screenAt(bearing: Float, heading: Float): Float? {
    val away = turnWrapped(bearing - heading)
    if (abs(away) > PatrolField / 2f + 8f) return null
    return PatrolWidth / 2f + away / PatrolField * PatrolStripInner
}

/** A turn as the strip counts one: how far round, and which way, in -180 to 180. */
private fun turnWrapped(degrees: Float): Float {
    val wrapped = ((degrees % 360f) + 360f) % 360f
    return if (wrapped > 180f) wrapped - 360f else wrapped
}

/** One heading in the picture of clamping, with a line saying what the player has done. */
@Composable
private fun ClampStep(caption: String, heading: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(4f)) {
        Text(caption, style = "label.dim")
        CompassBar(
            heading = heading,
            fieldOfView = PatrolField,
            Modifier.width(540f),
            readout = { "${it.roundToInt()}°" },
            distanceText = { "${it.roundToInt()}m" },
            fadeRange = PickupFade,
            live = false,
        ) {
            pin(bearing = CampBearing, distance = CampRange)
            pin(bearing = RaidBearing, distance = RaidRange, style = "label.danger")
            pin(bearing = PickupBearing, distance = PickupRange, fadeWithDistance = true)
        }
    }
}

/** The same strip in one language, with an ordinary row under it that does mirror. */
@Composable
private fun LocalisedStrip(caption: String, locale: Locale, strings: Strings, direction: LayoutDirection) {
    ProvideLocale(locale, strings) {
        ProvideLayoutDirection(direction) {
            Column(Modifier.width(540f), verticalArrangement = Arrangement.spacedBy(6f)) {
                // Written in English either way: it is a caption on the picture rather than
                // anything the strip says, and the point is what the strip does underneath it.
                ProvideLayoutDirection(LayoutDirection.Ltr) { Text(caption, style = "label.dim") }
                CompassBar(
                    heading = CampBearing,
                    fieldOfView = PatrolField,
                    Modifier.fillMaxWidth(),
                    labels = rememberCompassLabels(points = 4),
                    readout = { "${it.roundToInt()}°" },
                    distanceText = { "${it.roundToInt()}m" },
                    fadeRange = PickupFade,
                    live = false,
                ) {
                    pin(bearing = CampBearing, distance = CampRange)
                    pin(bearing = RaidBearing, distance = RaidRange, style = "label.danger")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10f)) {
                    Text(stringOf("objective"), style = "label")
                    Text("180m", style = "label.dim")
                }
            }
        }
    }
}
