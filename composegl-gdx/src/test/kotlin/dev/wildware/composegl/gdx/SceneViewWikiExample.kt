package dev.wildware.composegl.gdx

import androidx.compose.runtime.Composable
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.SceneView
import dev.wildware.composegl.ui.widget.rememberSceneViewState

/**
 * The LibGDX example on the wiki's Scene view page, kept here so it is compiled against the real
 * API. Never run: nothing here has a context.
 */
@Suppress("unused")
private object SceneViewWikiExample {

    @Composable
    fun ShipPreview(models: ModelBatch, camera: PerspectiveCamera, ship: ModelInstance, environment: Environment) {
        val scene = rememberSceneViewState()

        SceneView(scene, Modifier.size(320f, 240f)) {
            clear(Colour.Black)
            raw { batch ->
                // `batch` is the SpriteBatch you gave the canvas, open on the picture.
                // ModelBatch sets up its own depth test, so it draws straight in.
                camera.viewportWidth = width.toFloat()
                camera.viewportHeight = height.toFloat()
                camera.update()
                models.begin(camera)
                models.render(ship, environment)
                models.end()
                (batch as SpriteBatch).flush()
            }
        }
    }
}
