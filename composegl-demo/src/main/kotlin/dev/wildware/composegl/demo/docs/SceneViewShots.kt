package dev.wildware.composegl.demo.docs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.demo.scene.ModelViewer
import dev.wildware.composegl.demo.scene.rememberCube
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.Splitter
import dev.wildware.composegl.ui.widget.Text

/**
 * The pictures on the Scene view page: a real cube, drawn by raw OpenGL into a `SceneView`, taken
 * through the example's own [ModelViewer] so the page's worked example is what is photographed.
 */
internal fun MutableList<DocShot>.sceneViews() {
    // A panel with the scene in it, rounded, beside ordinary controls. Dragged once across the
    // picture, so the angle the cube is at is one a real drag turned it to.
    add(
        DocShot(
            "scene-view-panel",
            420,
            300,
            pointer = Offset(150f, 150f),
            dragTo = Offset(190f, 165f),
        ) {
            val cube = rememberCube()
            Box(Modifier.fillMaxSize().background(Colour.rgb(0x0B0E13)).padding(16f)) {
                Panel(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10f)) {
                        Text("MODEL VIEWER", style = "label.dim")
                        ModelViewer(cube, Modifier.fillMaxWidth().weight(1f).clip(8f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                            Button("RESET", onClick = {})
                            Button("EXPORT", onClick = {})
                        }
                    }
                }
            }
        },
    )

    // The same view in a splitter pane, caught while the bar is still being dragged: the picture it
    // had is stretched over the wider pane, soft, and is only remade once the bar stops.
    add(
        DocShot(
            "scene-view-resize",
            520,
            260,
            drags = listOf(Dragging.Drag(ResizeFrom, ResizeFrom + Offset(8f, 0f), hold = true)) +
                (1..ResizeSteps).map { step -> Dragging.Carry(ResizeFrom + Offset(8f + step * 12f, 0f)) },
            still = 0,
        ) {
            val cube = rememberCube()
            var split by remember { mutableStateOf(ResizeFraction) }
            Box(Modifier.fillMaxSize().background(Colour.rgb(0x0B0E13)).padding(16f)) {
                Splitter(
                    fraction = split,
                    onFractionChange = { split = it },
                    modifier = Modifier.fillMaxSize(),
                    minFirst = 80f,
                    minSecond = 80f,
                    first = { ModelViewer(cube, Modifier.fillMaxSize()) },
                    second = {
                        Column(Modifier.fillMaxSize().padding(left = 12f), verticalArrangement = Arrangement.spacedBy(6f)) {
                            Text("PROPERTIES", style = "label.dim")
                            Text("Mesh  crate.glb")
                            Text("Faces  12")
                        }
                    },
                )
            }
        },
    )

    // Four previews in a list, each its own SceneView at 56 units square. One is picked; the rest
    // were drawn once and cost nothing since.
    add(DocShot("scene-view-previews", 340, 300) {
        val cube = rememberCube()
        Box(Modifier.fillMaxSize().background(Colour.rgb(0x0B0E13)).padding(16f)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6f)) {
                PreviewItems.forEachIndexed { index, (name, tint) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (index == 1) Colour.argb(0x404CC2FF) else Colour.argb(0x20FFFFFF), corner = 6f)
                            .padding(6f),
                        horizontalArrangement = Arrangement.spacedBy(12f),
                        verticalAlignment = VerticalAlignment.Centre,
                    ) {
                        ModelViewer(cube, Modifier.size(52f).clip(6f), tint = tint)
                        Column(Modifier.height(40f), verticalArrangement = Arrangement.spacedBy(2f)) {
                            Text(name)
                            Text(if (index == 1) "equipped" else "in the hold", style = "label.dim")
                        }
                    }
                }
            }
        }
    })
}

/** Where the splitter's bar is grabbed in the resize picture: on the bar, halfway down. */
private val ResizeFrom = Offset(16f + (520f - 32f) * 0.35f, 130f)

/** Where the bar starts, as a fraction of the room. */
private const val ResizeFraction = 0.35f

/** How many frames the bar is still being carried for, and so how far it has gone. */
private const val ResizeSteps = 12

/** The previews in the list picture: a name and what the model is painted. */
private val PreviewItems = listOf(
    "Supply crate" to Colour.rgb(0xC8A060),
    "Shield cell" to Colour.rgb(0x6FD3FF),
    "Med kit" to Colour.rgb(0xE06060),
    "Fuel block" to Colour.rgb(0x7EE08A),
)
