package dev.wildware.composegl.lwjgl3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.modifier.fillMaxSize
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

    interface OrbitCamera {
        fun orbit(by: Offset)
        fun zoom(by: Float)
        fun pick(x: Float, y: Float, width: Int, height: Int)
        fun frameSelection()
        fun fly(axis: GamepadAxis, value: Float)
    }

    class Grab {
        var at = Offset.Zero
    }

    @Composable
    fun EditorViewport(renderer: MyRenderer, camera: OrbitCamera) {
        val scene = rememberSceneViewState()
        val grab = remember { Grab() }

        SceneView(
            scene,
            Modifier.fillMaxSize(),
            onPointer = { e ->
                when (e) {
                    is PointerEvent.Press -> {
                        grab.at = e.position
                        camera.pick(e.position.x, e.position.y, scene.width, scene.height)
                        true
                    }
                    is PointerEvent.Move -> if (e.pressed.isEmpty()) false else {
                        camera.orbit(e.position - grab.at)
                        grab.at = e.position
                        scene.invalidate()
                        true
                    }
                    is PointerEvent.Scroll -> {
                        camera.zoom(e.delta.y)
                        scene.invalidate()
                        true
                    }
                    else -> false
                }
            },
            onKey = { e ->
                if (e.key == Key.F && e.type == KeyEventType.Down) {
                    camera.frameSelection()
                    scene.invalidate()
                    true
                } else {
                    false
                }
            },
            onPad = { e ->
                if (e is GamepadEvent.Axis) {
                    camera.fly(e.axis, e.value)
                    scene.invalidate()
                    true
                } else {
                    false
                }
            },
        ) {
            clear(Colour.Black)
            raw { frame -> renderer.draw(frame as GlFrame, width, height) }
        }
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
