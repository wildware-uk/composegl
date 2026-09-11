package dev.wildware.composegl.demo.docs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.border
import dev.wildware.composegl.ui.modifier.fillMaxHeight
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.shadow
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Checkbox
import dev.wildware.composegl.ui.widget.Image
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import dev.wildware.composegl.ui.widget.Toggle
import dev.wildware.composegl.ui.widget.Tooltip
import dev.wildware.composegl.ui.widget.TooltipHost

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

    add(DocShot("widget-field", 320, 150) {
        Frame {
            Column(verticalArrangement = Arrangement.spacedBy(14f)) {
                TextField("Commander", onValueChange = {}, modifier = Modifier.width(240f))
                TextField("", onValueChange = {}, modifier = Modifier.width(240f), placeholder = "Call sign")
                TextField("Focused", onValueChange = {}, modifier = Modifier.width(240f), initialFocus = true)
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
}

// ---------------------------------------------------------------- the small pieces

private val Ink = Colour.rgb(0x12161D)
private val Steel = Colour.rgb(0x2C3545)
private val Accent = Colour.rgb(0x4CC2FF)

/** The accent, dark enough that [Slab]'s dim label is still readable on top of it. */
private val Deep = Colour.rgb(0x1D4F70)

/** A box with a word in it, for showing where a layout put things rather than what a widget is. */
@Composable
private fun Swatch(label: String) {
    Box(Modifier.size(90f, 48f).background(Steel, corner = 6f), contentAlignment = Alignment.Centre) {
        Text(label, style = "label.dim")
    }
}

/** The same, but sized by its modifier, for weights and alignments. */
@Composable
private fun Slab(label: String, modifier: Modifier, colour: Colour) {
    Box(modifier.height(36f).background(colour, corner = 6f), contentAlignment = Alignment.Centre) {
        Text(label, style = "label.dim")
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

@Composable
private fun Labelled(name: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4f)) {
        Text(name, style = "label.dim")
        content()
    }
}

@Composable
private fun Tile(name: String, modifier: @Composable () -> Modifier) {
    Column(horizontalAlignment = HorizontalAlignment.Centre, verticalArrangement = Arrangement.spacedBy(6f)) {
        Box(modifier().size(80f, 44f)) {}
        Text(name, style = "label.dim")
    }
}
