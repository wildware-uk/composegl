package dev.wildware.composegl.demo.docs

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.moulded
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.Text

/** One button, one modifier, and the light moved round it: from above, the left, the right, below. */
@Composable
internal fun MouldedLights() {
    Box(Modifier.fillMaxSize().background(Colour.rgb(0xF4F1EA)).padding(16f), contentAlignment = Alignment.Centre) {
        Row(horizontalArrangement = Arrangement.spacedBy(16f), verticalAlignment = VerticalAlignment.Centre) {
            listOf("above" to 90f, "left" to 0f, "right" to 180f, "below" to 270f).forEach { (name, light) ->
                Box(
                    Modifier
                        .size(120f, 52f)
                        .background(Brush.evenly(listOf(Colour.rgb(0x74CB3C), Colour.rgb(0x4FAF28), Colour.rgb(0x41A122))), corner = 26f)
                        .moulded(corner = 26f, light = light, shadow = 0.12f, outline = Colour.rgb(0x2B1D12)),
                    contentAlignment = Alignment.Centre,
                ) {
                    Text(name, style = "label")
                }
            }
        }
    }
}
