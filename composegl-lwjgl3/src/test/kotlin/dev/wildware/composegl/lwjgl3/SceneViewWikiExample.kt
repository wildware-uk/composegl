package dev.wildware.composegl.lwjgl3

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.SceneView
import dev.wildware.composegl.ui.widget.rememberSceneViewState
import org.lwjgl.opengl.GL11

/**
 * The examples on the wiki's Scene view page, kept here so they are compiled against the real API.
 * Never run: nothing here has a context.
 */
@Suppress("unused")
private object SceneViewWikiExample {

    interface MyRenderer {
        fun draw(frame: GlFrame, width: Int, height: Int)
        fun turn(degrees: Float)
    }

    interface Shadows {
        fun render()
    }

    @Composable
    fun WeaponPreview(renderer: MyRenderer) {
        val scene = rememberSceneViewState()

        SceneView(scene, Modifier.size(480f, 270f).clip(8f)) {
            clear(Colour.Black)
            raw { frame ->
                GL11.glEnable(GL11.GL_DEPTH_TEST)
                renderer.draw(frame as GlFrame, width, height)
            }
        }

        Button("SPIN", onClick = {
            renderer.turn(15f)
            scene.invalidate()
        })
    }

    @Composable
    fun HalfSize() {
        val scene = rememberSceneViewState(resolutionScale = 0.5f)
        SceneView(scene, Modifier.size(64f)) { clear(Colour.Black) }
    }

    fun drivingThePrepass(host: UiHost, canvas: UiCanvas, shadows: Shadows, viewport: Viewport, nanos: Long) {
        val ui = UiRenderer(host, canvas)
        ui.renderScenes = false
        ui.onLaidOut = { _ ->
            shadows.render()
            ui.scenes.render(viewport, nanos)
        }
    }
}
