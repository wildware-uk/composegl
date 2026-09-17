package dev.wildware.composegl.kool.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.fabmax.kool.KoolContext
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.modules.ksl.KslUnlitShader
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.scene.addColorMesh
import de.fabmax.kool.util.Color
import dev.wildware.composegl.kool.ComposeGlScene
import dev.wildware.composegl.kool.KoolBackend
import dev.wildware.composegl.kool.composeGl
import dev.wildware.composegl.lwjgl3.StbFonts
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.border
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.SceneView
import dev.wildware.composegl.ui.widget.SceneViewState
import dev.wildware.composegl.ui.widget.Text

/** What the panel shows, kept outside the composition so a scripted run can read it. */
class DemoState {
    var clicks by mutableIntStateOf(0)
    var sceneColour by mutableStateOf(Palette.first())
    val scene = SceneViewState()

    fun click() {
        clicks++
        sceneColour = Palette[clicks % Palette.size]
        scene.invalidate()
    }

    companion object {
        val Palette = listOf(Colour.rgb(0x2E7D32), Colour.rgb(0xC62828), Colour.rgb(0x1565C0))
    }
}

/** The pieces of a running demo. */
class RunningDemo(val world: Scene, val ui: ComposeGlScene, val state: DemoState)

object KoolDemo {

    const val Width = 1280
    const val Height = 720

    /** Where the button is, in window pixels: the design is the window's size. */
    val ButtonAt = java.awt.Rectangle(64, 176, 272, 56)

    /** Builds the world and the panel on [ctx]. The world is drawn first, the panel over it. */
    fun start(ctx: KoolContext): RunningDemo {
        val world = world()
        ctx.addScene(world)
        val fonts = StbFonts().apply {
            register("default", bytes("/fonts/DejaVuSans.ttf"), listOf(16, 24, 32))
        }
        val state = DemoState()
        val ui = ctx.composeGl(KoolBackend(fonts), Size(Width.toFloat(), Height.toFloat())) { Panel(state) }
        return RunningDemo(world, ui, state)
    }

    private fun bytes(path: String) = requireNotNull(KoolDemo::class.java.getResourceAsStream(path)) { "missing $path" }.readBytes()

    /** Three still cubes, drawn by Kool with Kool's own shaders and depth. */
    private fun world() = Scene("world").apply {
        clearColor = de.fabmax.kool.pipeline.ClearColorFill(Color(0.05f, 0.06f, 0.09f, 1f))
        camera.setupCamera(position = Vec3f(0f, 2.5f, 9f), lookAt = Vec3f(1.5f, 0f, 0f))
        listOf(Vec3f(0f, 0f, 0f) to Color(0.95f, 0.55f, 0.1f), Vec3f(2.4f, 0f, -1.5f) to Color(0.3f, 0.75f, 0.95f), Vec3f(4.6f, 0f, -3f) to Color(0.85f, 0.3f, 0.75f))
            .forEach { (at, colour) ->
                addColorMesh {
                    generate {
                        translate(at)
                        cube { size.set(1.6f, 1.6f, 1.6f) }
                    }
                    shader = KslUnlitShader { color { constColor(colour) } }
                }
            }
    }

    private val PanelColour = Colour.argb(0xE01E2836)
    private val Accent = Colour.rgb(0x4CC2FF)
    private val Paper = Colour.rgb(0xE6EDF5)

    @Composable
    private fun Panel(state: DemoState) {
        Box(Modifier.size(Width.toFloat(), Height.toFloat())) {
            Box(Modifier.offset(40f, 40f).size(320f, 520f).background(PanelColour, corner = 12f).border(Accent, width = 2f, corner = 12f))
            Text("ComposeGL on Kool", Modifier.offset(64f, 64f), textStyle = TextStyle(family = "default", size = 32f), colour = Paper)
            Text("The cubes are Kool's. This panel is not.", Modifier.offset(64f, 116f), textStyle = TextStyle(family = "default", size = 16f), colour = Paper)
            Button(
                "Change the scene",
                onClick = state::click,
                modifier = Modifier.offset(ButtonAt.x.toFloat(), ButtonAt.y.toFloat()).size(ButtonAt.width.toFloat(), ButtonAt.height.toFloat()).testTag("button"),
            )
            Text(if (state.clicks == 1) "Clicked once" else "Clicked ${state.clicks} times", Modifier.offset(64f, 252f), textStyle = TextStyle(family = "default", size = 24f), colour = Paper)
            val colour = state.sceneColour
            SceneView(state.scene, Modifier.offset(64f, 300f).size(272f, 236f).testTag("scene")) { clear(colour) }
        }
    }
}
