package dev.wildware.composegl.demo.docs

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.Relief
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.borderOutside
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.relief
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.Text

/** The three edges a lit box can have, and the same edge with the light moved round it. */
@Composable
internal fun ReliefShapes() {
    val face = Brush.evenly(listOf(Colour.rgb(0x74CB3C), Colour.rgb(0x4FAF28), Colour.rgb(0x41A122)))
    val ink = Colour.rgb(0x2B1D12)
    Box(Modifier.fillMaxSize().background(Colour.rgb(0xF4F1EA)).padding(18f), contentAlignment = Alignment.Centre) {
        Column(verticalArrangement = Arrangement.spacedBy(14f), horizontalAlignment = HorizontalAlignment.Centre) {
            Row(horizontalArrangement = Arrangement.spacedBy(16f), verticalAlignment = VerticalAlignment.Centre) {
                listOf("chamfer" to Relief.Chamfer, "fillet" to Relief.Fillet, "dome" to Relief.Dome).forEach { (name, shape) ->
                    Box(
                        Modifier
                            .size(150f, 60f)
                            .background(face, corner = 30f)
                            .relief(corner = 30f, shape = shape, depth = 0.3f, gloss = 0.35f)
                            .borderOutside(ink, width = 4f, corner = 30f),
                        contentAlignment = Alignment.Centre,
                    ) {
                        Text(name, style = "label")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16f), verticalAlignment = VerticalAlignment.Centre) {
                listOf("wet" to 0.05f, "satin" to 0.35f, "shiny" to 0.6f, "glass" to 0.95f).forEach { (name, polish) ->
                    Box(
                        Modifier
                            .size(110f, 60f)
                            .background(face, corner = 16f)
                            .relief(corner = 16f, shape = Relief.Fillet, depth = 0.35f, gloss = 0.9f, polish = polish)
                            .borderOutside(ink, width = 4f, corner = 16f),
                        contentAlignment = Alignment.Centre,
                    ) {
                        Text(name, style = "label")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16f), verticalAlignment = VerticalAlignment.Centre) {
                listOf(90f, 45f, 0f, 315f).forEach { angle ->
                    Box(
                        Modifier
                            .size(110f, 60f)
                            .background(face, corner = 16f)
                            .relief(corner = 16f, shape = Relief.Fillet, depth = 0.35f, light = angle, gloss = 0.4f)
                            .borderOutside(ink, width = 4f, corner = 16f),
                        contentAlignment = Alignment.Centre,
                    ) {
                        Text("${angle.toInt()}°", style = "label")
                    }
                }
            }
        }
    }
}
