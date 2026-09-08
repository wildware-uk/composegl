package composegl

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The performance claim in one file: a HUD that is not changing costs nothing, and a HUD that is
 * animating costs exactly one render per frame and no more.
 */
class SurfaceStatsTest {

    private val context = ComposeGlContext.createRaster()
    private val surface = ComposeSurface(context, object : HostServices { override val density = 1f })
    private var nanos = 0L

    @AfterEach
    fun tearDown() = context.dispose()

    private fun show(content: @Composable () -> Unit) {
        surface.setContent(content)
        surface.setRenderTarget(RenderTarget.Raster(64, 64))
    }

    private fun frames(count: Int) = repeat(count) {
        nanos += 16_666_667
        surface.update(nanos)
        if (surface.needsRedraw) surface.render(nanos)
    }

    @Test
    fun `a static HUD renders once and then never again`() {
        show { Box(Modifier.fillMaxSize().background(Color.Red)) { Text("score: 12") } }

        frames(100)

        assertEquals(1L, surface.stats.composeRenders, "100 frames, one render")
        assertEquals(100L, surface.stats.frames)
        assertTrue(surface.stats.lastRenderNanos > 0, "and the one render was timed")
    }

    @Test
    fun `an animating HUD renders every frame and no more`() {
        show {
            val transition = rememberInfiniteTransition()
            val alpha by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
            )
            Box(Modifier.fillMaxSize().alpha(alpha).background(Color.Red))
        }

        frames(2) // start the animation
        val start = surface.stats.composeRenders
        frames(20)
        val drawn = surface.stats.composeRenders - start

        assertEquals(20L, drawn, "an animation costs exactly one render per frame")
    }

    @Test
    fun `stats start at zero`() {
        assertEquals(SurfaceStats(composeRenders = 0, frames = 0, lastRenderNanos = 0), surface.stats)
    }
}
