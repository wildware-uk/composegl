package dev.wildware.composegl.lwjgl3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.draw.ScenePass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.LazyColumn
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

    class Item(val id: String)

    interface Models {
        fun draw(item: Item, frame: GlFrame, width: Int, height: Int)
    }

    interface Log {
        fun warn(message: String)
    }

    @Composable
    fun Shelf(items: List<Item>, models: Models) {
        LazyColumn(count = items.size, key = { items[it].id }) { index ->
            val preview = rememberSceneViewState()
            SceneView(preview, Modifier.fillMaxWidth().height(96f)) {
                clear(Colour.Black)
                raw { frame -> models.draw(items[index], frame as GlFrame, width, height) }
            }
        }
    }

    fun warnings(ui: UiRenderer, log: Log) {
        ui.scenes.warn = { message -> log.warn(message) }
    }

    fun counting(host: UiHost, canvas: UiCanvas, viewport: Viewport, nanos: Long): Pair<Int, Float> {
        val budget = FrameBudget(publishEveryMillis = 0)
        val ui = UiRenderer(host, canvas, budget)
        ui.render(viewport, nanos)
        return budget.reading.scenes to budget.reading.sceneMillis
    }

    fun ownPass(host: UiHost, canvas: UiCanvas, budget: FrameBudget): ScenePass =
        ScenePass(host.tree, canvas, budget)

    fun drivingThePrepass(host: UiHost, canvas: UiCanvas, shadows: Shadows, viewport: Viewport, nanos: Long) {
        val ui = UiRenderer(host, canvas)
        ui.renderScenes = false
        ui.onLaidOut = { _ ->
            shadows.render()
            ui.scenes.render(viewport, nanos)
        }
    }
}
