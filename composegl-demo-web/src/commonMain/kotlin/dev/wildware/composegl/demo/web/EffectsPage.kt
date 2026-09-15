package dev.wildware.composegl.demo.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.effects.blur
import dev.wildware.composegl.effects.colourGrade
import dev.wildware.composegl.effects.dissolve
import dev.wildware.composegl.effects.outline
import dev.wildware.composegl.ui.animation.Spring
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.animateFloatAsState
import dev.wildware.composegl.ui.game.Bar
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Shape
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.FlowRow
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.blend
import dev.wildware.composegl.ui.modifier.border
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clipShape
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.mirror
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.perspective
import dev.wildware.composegl.ui.modifier.rotate3d
import dev.wildware.composegl.ui.modifier.shadow
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.skew
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.tint
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Stepper
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Toggle

@Composable
fun EffectsPage() {
    Page(Section.Effects, "Drawn by the backend's own shaders, in the same batch as everything else wherever it can be.") {
        Gradients()
        CornersCard()
        ClipShapes()
        Blend()
        Tint()
        CardFlip()
        Perspective()
        SkewMirror()
        Shaders()
    }
}

@Composable
private fun Gradients() = Card("Gradients") {
    FlowRow(horizontalSpacing = 12f, verticalSpacing = 12f) {
        Swatch("vertical") { Modifier.background(Brush.vertical(Accent, Deep), corner = 6f) }
        Swatch("horizontal") { Modifier.background(Brush.horizontal(Colour.rgb(0x4CD964), Colour.rgb(0xFF3B30)), corner = 6f) }
        Swatch("radial") { Modifier.background(Paper, corner = 6f).background(Brush.radial(Colour.Transparent, Colour.argb(0xE0000000)), corner = 6f) }
        Swatch("45°") { Modifier.background(Steel, corner = 6f).background(Brush.linear(Warm, Warm.withAlpha(0), degrees = 45f), corner = 6f) }
    }
}

@Composable
private fun CornersCard() = Card("Per-corner radii", "A tab, a speech bubble and a docked panel.") {
    Row(horizontalArrangement = Arrangement.spacedBy(20f), verticalAlignment = VerticalAlignment.Bottom) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(4f)) {
                Box(Modifier.size(46f, 22f).background(Accent, Corners.top(8f))) {}
                Box(Modifier.size(46f, 22f).background(Steel, Corners.top(8f))) {}
            }
            Box(Modifier.size(112f, 44f).background(Accent, Corners(topRight = 8f, bottomRight = 8f, bottomLeft = 8f))) {}
        }
        val speech = Corners(topLeft = 14f, topRight = 14f, bottomRight = 14f, bottomLeft = 0f)
        Box(Modifier.size(100f, 56f).shadow(Accent.scaleAlpha(0.5f), spread = 10f, corners = speech).background(Steel, speech).border(Accent, width = 2f, corners = speech), contentAlignment = Alignment.Centre) {
            Text("Hi!")
        }
        Box(Modifier.size(64f, 66f).background(Steel, Corners.left(16f)).border(Accent, width = 2f, corners = Corners.left(16f))) {}
    }
}

@Composable
private fun ClipShapes() = Card("Clip shapes", "The same square art, cut four ways when it is drawn.") {
    FlowRow(horizontalSpacing = 14f, verticalSpacing = 10f) {
        Labelled("Circle") { Portrait(Shapes.Circle) }
        Labelled("Diamond") { Portrait(Shapes.Diamond) }
        Labelled("Hexagon") { Portrait(Shapes.Hexagon) }
        Labelled("Rounded") { Portrait(Shapes.roundedRect(18f)) }
    }
}

@Composable
private fun Portrait(shape: Shape) {
    Box(Modifier.size(66f).clipShape(shape)) {
        Column {
            Box(Modifier.size(66f, 22f).background(Accent)) {}
            Box(Modifier.size(66f, 22f).background(Paper)) {}
            Box(Modifier.size(66f, 22f).background(Deep)) {}
        }
    }
}

@Composable
private fun Blend() = Card("Blend modes", "Additive light brightens where it overlaps; ordinary paint covers.") {
    Row(horizontalArrangement = Arrangement.spacedBy(24f)) {
        listOf("SourceOver" to BlendMode.SourceOver, "Additive" to BlendMode.Additive).forEach { (name, mode) ->
            Labelled(name) {
                Box(Modifier.size(120f, 90f).background(Colour.rgb(0x151A22), corner = 6f)) {
                    listOf(Colour.rgb(0xC0302A) to (10f to 10f), Colour.rgb(0x2A9A40) to (44f to 10f), Colour.rgb(0x2A50C0) to (27f to 36f)).forEach { (colour, at) ->
                        Box(Modifier.offset(at.first, at.second).size(50f, 50f).blend(mode).background(colour, corner = 25f)) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun Tint() = Card("Tint", "A damage flash, a locked item and a team colour, from one piece of art.") {
    var flash by remember { mutableStateOf(false) }
    val amount by animateFloatAsState(if (flash) 0.7f else 0f, Tween(if (flash) 60 else 400), onFinished = { flash = false })
    FlowRow(horizontalSpacing = 12f, verticalSpacing = 12f) {
        Swatch("plain") { Modifier.background(Accent, corner = 6f).border(Paper, width = 2f, corner = 6f) }
        Swatch("flash") { Modifier.tint(Colour.Red.scaleAlpha(amount)).background(Accent, corner = 6f).border(Paper, width = 2f, corner = 6f) }
        Swatch("locked") { Modifier.tint(Colour.Grey).background(Accent, corner = 6f).border(Paper, width = 2f, corner = 6f) }
        Swatch("team") { Modifier.tint(Colour.Orange).background(Paper, corner = 6f).border(Paper, width = 2f, corner = 6f) }
    }
    Button("Flash", { flash = true }, style = "button.quiet")
}

@Composable
private fun CardFlip() = Card("3D card flip", "Click the card. It turns on a spring, with a real perspective camera.") {
    var flipped by remember { mutableStateOf(false) }
    val angle by animateFloatAsState(if (flipped) 180f else 0f, Spring(damping = Spring.Gentle, stiffness = Spring.Low))
    Box(Modifier.fillMaxWidth().height(170f), contentAlignment = Alignment.Centre) {
        Box(
            Modifier.size(130f, 160f).rotate3d(y = angle, cameraDistance = 4f).focusable().clickable { flipped = !flipped }.testTag("flip-card"),
        ) {
            if (angle < 90f) {
                Box(Modifier.size(130f, 160f).background(Brush.vertical(Accent, Deep), corner = 10f).border(Paper, width = 2f, corner = 10f).padding(12f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                        Text("FIRE BOLT")
                        Text("Deals 12", style = "label.dim")
                    }
                }
            } else {
                Box(Modifier.mirror().size(130f, 160f).background(Brush.radial(Warm, Colour.rgb(0x5A2A10)), corner = 10f).border(Paper, width = 2f, corner = 10f), contentAlignment = Alignment.Centre) {
                    Text("?", colour = Paper)
                }
            }
        }
    }
}

@Composable
private fun Perspective() = Card("Perspective", "One camera for the whole row, so the cards recede to one point.") {
    var turn by remember { mutableFloatStateOf(40f) }
    Slider(turn, { turn = it }, range = -70f..70f, length = 200f)
    Row(Modifier.perspective(320f).fillMaxWidth().height(90f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = VerticalAlignment.Centre) {
        repeat(3) { index ->
            Box(Modifier.rotate3d(y = turn).size(76f, 70f).background(Deep, corner = 6f).border(Accent, width = 2f, corner = 6f), contentAlignment = Alignment.Centre) {
                Text("${index + 1}")
            }
        }
    }
}

@Composable
private fun SkewMirror() = Card("Skew and mirror", "A slanted banner, text and all; and art flipped without a second picture.") {
    var skew by remember { mutableFloatStateOf(-12f) }
    var mirrored by remember { mutableStateOf(false) }
    Slider(skew, { skew = it }, range = -30f..30f, length = 200f)
    Row(horizontalArrangement = Arrangement.spacedBy(24f), verticalAlignment = VerticalAlignment.Centre) {
        Box(Modifier.skew(x = skew).size(150f, 50f).background(Deep, corner = 4f).padding(10f)) {
            Column(verticalArrangement = Arrangement.spacedBy(6f)) {
                Text("RANK 1")
                Box(Modifier.size(100f, 6f).background(Accent, corner = 3f)) {}
            }
        }
        Row(Modifier.mirror(horizontal = mirrored), verticalAlignment = VerticalAlignment.Centre) {
            Box(Modifier.size(34f, 12f).background(Warm)) {}
            Box(Modifier.size(20f, 32f).background(Warm, corner = 3f)) {}
        }
    }
    Toggle(mirrored, { mirrored = it }, label = "mirror()")
}

private enum class Effect(val title: String) { Blur("Blur"), Outline("Outline"), Grade("Colour grade"), Dissolve("Dissolve") }

@Composable
private fun Shaders() = Card("Shader effects", "Blur, outline, colour grade and dissolve, bound to a widget with one modifier.") {
    var effect by remember { mutableStateOf(Effect.Blur) }
    var amount by remember { mutableFloatStateOf(0.5f) }
    Stepper(Effect.entries, effect, { effect = it }, label = { it.title }, modifier = Modifier.testTag("effect"))
    Slider(amount, { amount = it }, length = 200f)
    val applied = when (effect) {
        Effect.Blur -> Modifier.blur(amount * 8f)
        Effect.Outline -> Modifier.outline(Warm, width = 1f + amount * 3f)
        Effect.Grade -> Modifier.colourGrade(saturation = 1f - amount, contrast = 1f + amount * 0.3f)
        Effect.Dissolve -> Modifier.dissolve(progress = amount, edge = Warm)
    }
    Box(Modifier.fillMaxWidth().height(120f), contentAlignment = Alignment.Centre) {
        Box(applied.width(230f).padding(10f)) {
            Column(Modifier.fillMaxWidth().background(Colour.rgb(0x1D4F70), corner = 8f).padding(12f), verticalArrangement = Arrangement.spacedBy(8f), horizontalAlignment = HorizontalAlignment.Start) {
                Text("WARDEN OF THE DEEP")
                Bar(0.7f, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Swatch(name: String, modifier: @Composable () -> Modifier) {
    Column(horizontalAlignment = HorizontalAlignment.Centre, verticalArrangement = Arrangement.spacedBy(6f)) {
        Box(modifier().size(76f, 44f)) {}
        Text(name, style = "label.dim")
    }
}
