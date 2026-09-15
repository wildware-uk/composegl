package dev.wildware.composegl.demo.docs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.AnimatedVisibility
import dev.wildware.composegl.ui.animation.Crossfade
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
import dev.wildware.composegl.ui.game.Bar
import dev.wildware.composegl.ui.game.BarThreshold
import dev.wildware.composegl.ui.game.Hotbar
import dev.wildware.composegl.ui.game.HotbarSlot
import dev.wildware.composegl.ui.game.MinimapFrame
import dev.wildware.composegl.ui.game.MinimapMarker
import dev.wildware.composegl.ui.game.RadialCooldown
import dev.wildware.composegl.ui.game.Reticle
import dev.wildware.composegl.ui.game.rememberCooldown
import dev.wildware.composegl.ui.game.rememberReticleState
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Shape
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.BorderSide
import dev.wildware.composegl.ui.graphics.BorderStyle
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.GamepadButton
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
import dev.wildware.composegl.ui.debug.LayoutOverlay
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
import dev.wildware.composegl.ui.widget.Divider
import dev.wildware.composegl.ui.widget.Dropdown
import dev.wildware.composegl.ui.widget.PopupHost
import dev.wildware.composegl.ui.widget.DragAndDropHost
import dev.wildware.composegl.ui.widget.DropTargetState
import dev.wildware.composegl.ui.widget.Image
import dev.wildware.composegl.ui.widget.NumberStepper
import dev.wildware.composegl.ui.widget.LazyGridState
import dev.wildware.composegl.ui.widget.LazyVerticalGrid
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.ProvideTextScale
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.SelectionContainer
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Stepper
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import dev.wildware.composegl.ui.widget.Toggle
import dev.wildware.composegl.ui.widget.Tooltip
import dev.wildware.composegl.ui.widget.TooltipHost
import dev.wildware.composegl.ui.widget.VirtualCursor
import dev.wildware.composegl.ui.layout.Baseline
import dev.wildware.composegl.ui.modifier.paddingFrom
import dev.wildware.composegl.ui.text.TextStyle
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
}

// ---------------------------------------------------------------- whole screens

private fun MutableList<DocShot>.scenes() {
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
