package uk.wildware.composegl.demo.docs

import androidx.compose.runtime.Composable
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.layout.Alignment
import uk.wildware.composegl.ui.layout.Box
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.background
import uk.wildware.composegl.ui.modifier.fillMaxSize
import uk.wildware.composegl.ui.modifier.padding

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
