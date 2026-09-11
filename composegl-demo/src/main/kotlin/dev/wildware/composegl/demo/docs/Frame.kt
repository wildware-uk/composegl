package dev.wildware.composegl.demo.docs

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.padding

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
