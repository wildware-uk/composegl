package composegl.testing

import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.graphics.Colour
import composegl.ui.graphics.EdgeMode
import composegl.ui.graphics.NinePatch
import composegl.ui.graphics.UiCanvas
import composegl.ui.text.FontProvider
import composegl.ui.text.TextStyle

/**
 * What the golden screenshots draw.
 *
 * Written against [UiCanvas] and nothing else, so the same list can be pointed at a second backend
 * and produce the same pictures. That is the point of having a second backend at all: a scene that
 * comes out differently is a LibGDX assumption that leaked somewhere it should not have.
 *
 * Every scene is deterministic. No clock, no randomness, no window size that depends on the
 * machine — a golden that moves is a golden nobody trusts, and a golden nobody trusts gets
 * regenerated instead of read.
 */
class SceneArt(val fonts: FontProvider, val panel: NinePatch)

class Scene(val name: String, val draw: UiCanvas.(SceneArt) -> Unit)

/** How big every golden is. Small on purpose: they live in the repository. */
const val SceneSize = 240

private val Ink = Colour.rgb(0x12161D)
private val Panel = Colour.rgb(0x1E2836)
private val Accent = Colour.rgb(0x4CC2FF)
private val Paper = Colour.rgb(0xE6EDF5)

private val Body = TextStyle(family = "body", size = 16f)
private val Small = TextStyle(family = "body", size = 12f)

fun scenes(): List<Scene> = listOf(
    Scene("panel") { art ->
        rect(Rect.of(0f, 0f, SceneSize.toFloat(), SceneSize.toFloat()), Ink)
        shadow(Rect.of(30f, 30f, 180f, 120f), Colour.argb(0xAA000000), spread = 16f, corner = 14f)
        rect(Rect.of(30f, 30f, 180f, 120f), Panel, corner = 14f)
        border(Rect.of(30f, 30f, 180f, 120f), Accent, width = 2f, corner = 14f)
        text(art.fonts.measure("Panel", Body), Offset(48f, 48f), Paper)
    },

    Scene("shadow") { _ ->
        rect(Rect.of(0f, 0f, SceneSize.toFloat(), SceneSize.toFloat()), Ink)
        // Nothing on top of it, so the falloff itself is what the golden is watching.
        shadow(Rect.of(70f, 70f, 100f, 100f), Colour.argb(0xFF000000), spread = 30f, corner = 20f)
    },

    Scene("corners") { _ ->
        rect(Rect.of(0f, 0f, SceneSize.toFloat(), SceneSize.toFloat()), Ink)
        // Square, gently rounded, and rounded until it is a circle: the three ends of the range
        // the distance field has to get right.
        rect(Rect.of(20f, 20f, 60f, 60f), Accent, corner = 0f)
        rect(Rect.of(90f, 20f, 60f, 60f), Accent, corner = 12f)
        rect(Rect.of(160f, 20f, 60f, 60f), Accent, corner = 30f)
        border(Rect.of(20f, 100f, 60f, 60f), Paper, width = 1f, corner = 0f)
        border(Rect.of(90f, 100f, 60f, 60f), Paper, width = 4f, corner = 12f)
        border(Rect.of(160f, 100f, 60f, 60f), Paper, width = 10f, corner = 30f)
    },

    Scene("text") { art ->
        rect(Rect.of(0f, 0f, SceneSize.toFloat(), SceneSize.toFloat()), Ink)
        text(art.fonts.measure("Handgloves", Body), Offset(16f, 20f), Paper)
        text(art.fonts.measure("Handgloves", Small), Offset(16f, 50f), Accent)
        // Wrapped, so the golden covers line breaking as well as glyph placement.
        text(
            art.fonts.measure("The relay went quiet six hours ago.", Small, maxWidth = 120f),
            Offset(16f, 80f),
            Paper,
        )
    },

    Scene("nine-patch") { art ->
        rect(Rect.of(0f, 0f, SceneSize.toFloat(), SceneSize.toFloat()), Ink)
        // Wide and short, then narrow and tall, from the same art: the corners must be identical
        // in both, which is the whole claim a nine-patch makes.
        art.panel.drawInto(this, Rect.of(16f, 16f, 200f, 60f))
        art.panel.drawInto(this, Rect.of(16f, 92f, 60f, 130f))
        art.panel.copy(centreAcross = EdgeMode.Tile, centreDown = EdgeMode.Tile)
            .drawInto(this, Rect.of(96f, 92f, 120f, 130f))
    },

    Scene("clip") { art ->
        rect(Rect.of(0f, 0f, SceneSize.toFloat(), SceneSize.toFloat()), Ink)
        rect(Rect.of(30f, 30f, 180f, 180f), Panel, corner = 8f)

        // A scroll area: a window with rows longer than it, one of them half cut off.
        pushClip(Rect.of(30f, 30f, 180f, 120f))
        repeat(6) { row ->
            val y = 40f + row * 34f
            rect(Rect.of(44f, y, 152f, 26f), Accent, corner = 6f)
            text(art.fonts.measure("Row ${row + 1}", Small), Offset(54f, y + 5f), Ink)
        }
        // Nested, and narrower: the inner clip is the overlap, never the inner rectangle alone.
        pushClip(Rect.of(30f, 30f, 90f, 400f))
        rect(Rect.of(30f, 118f, 180f, 18f), Paper)
        popClip()
        popClip()

        // Outside both clips again, to prove the scissor was actually lifted.
        rect(Rect.of(44f, 186f, 152f, 10f), Paper, corner = 5f)
    },
)
