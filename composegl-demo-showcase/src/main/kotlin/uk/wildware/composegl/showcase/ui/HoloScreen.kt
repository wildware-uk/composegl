package uk.wildware.composegl.showcase.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import uk.wildware.composegl.showcase.ShowcaseState
import uk.wildware.composegl.ui.layout.Alignment
import uk.wildware.composegl.ui.layout.Arrangement
import uk.wildware.composegl.ui.layout.Box
import uk.wildware.composegl.ui.layout.Column
import uk.wildware.composegl.ui.layout.Row
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.fillMaxSize
import uk.wildware.composegl.ui.modifier.fillMaxWidth
import uk.wildware.composegl.ui.skin.ProvideSkin
import uk.wildware.composegl.ui.skin.Skin
import uk.wildware.composegl.ui.skin.styled
import uk.wildware.composegl.ui.text.FontProvider
import uk.wildware.composegl.ui.widget.Button
import uk.wildware.composegl.ui.widget.LocalFonts
import uk.wildware.composegl.ui.widget.Text

/**
 * What is on the panel standing in the scene.
 *
 * An ordinary interface: a heading, a page of readouts, and two buttons that change the page. It
 * does not know it is a texture on a quad, and the buttons do not know the press came from a ray
 * rather than a mouse — which is the whole claim being made by putting it in the world.
 */
@Composable
fun HoloScreen(state: ShowcaseState, fonts: FontProvider, skin: Skin) {
    CompositionLocalProvider(LocalFonts provides fonts) {
        ProvideSkin(skin) {
            Box(Modifier.fillMaxSize().styled("holo"), contentAlignment = Alignment.TopStart) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12f)) {
                    Text("DOCK TERMINAL", style = "label.title")

                    when (state.holoPage) {
                        0 -> Page("BAY STATUS", listOf("Clamps" to "RELEASED", "Fuel" to "94%", "Drones" to "3 AIRBORNE"))
                        1 -> Page("MANIFEST", listOf("Ore" to "1 240 t", "Coolant" to "60 t", "Spares" to "12"))
                        else -> Page("CREW", listOf("Aboard" to "4", "Suited" to "1", "Asleep" to "2"))
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10f)) {
                        Button("BACK", { state.holoPage = (state.holoPage + 2) % 3 }, style = "holo.button")
                        Button("NEXT", { state.holoPage = (state.holoPage + 1) % 3 }, style = "holo.button")
                    }

                    Text("aim at it and click", style = "holo.hint")
                }
            }
        }
    }
}

@Composable
private fun Page(title: String, rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6f)) {
        Text(title, style = "label.dim")
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = "label")
                Text(value, style = "label.holo")
            }
        }
    }
}
