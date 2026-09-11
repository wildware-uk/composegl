package composegl.demo.docs

import androidx.compose.runtime.Composable
import composegl.ui.graphics.Colour
import composegl.ui.layout.Alignment
import composegl.ui.layout.Box
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.background
import composegl.ui.modifier.fillMaxSize
import composegl.ui.modifier.padding

/**
 * The page every documentation picture is taken on: one dark ground, a little air, centred.
 *
 * Shared so that twenty pictures sit together on a wiki page without one of them having a
 * different background or a different margin from the rest.
 */
@Composable
internal fun Frame(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Page).padding(14f),
        contentAlignment = Alignment.Centre,
        content = content,
    )
}

private val Page = Colour.rgb(0x0B0E13)
