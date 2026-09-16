package dev.wildware.composegl.korge

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.SceneView
import dev.wildware.composegl.ui.widget.rememberSceneViewState
import korlibs.korge.render.RenderContext
import korlibs.korge.view.Container

/**
 * The KorGE example on the wiki's Scene view page, kept here so it is compiled against the real
 * API. Never run: nothing here has a context.
 */
@Suppress("unused")
private object SceneViewWikiExample {

    @Composable
    fun MiniMap(world: Container) {
        val scene = rememberSceneViewState()

        SceneView(scene, Modifier.size(200f, 200f).clip(8f)) {
            clear(Colour.Black)
            raw { ctx ->
                // The scene view's picture is on top of the context's framebuffer stack,
                // so anything KorGE draws through the context lands in it.
                world.render(ctx as RenderContext)
            }
        }
    }
}
