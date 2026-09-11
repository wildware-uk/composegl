package dev.wildware.composegl.testing

import dev.wildware.composegl.effects.Axis
import dev.wildware.composegl.effects.blur
import dev.wildware.composegl.effects.colourGrade
import dev.wildware.composegl.effects.dissolve
import dev.wildware.composegl.effects.outline
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.effect.Uniform
import dev.wildware.composegl.ui.game.ParticleEmitter
import dev.wildware.composegl.ui.game.ParticleStyle
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.EdgeMode
import dev.wildware.composegl.ui.graphics.NinePatch
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.text.FontProvider
import dev.wildware.composegl.ui.text.TextStyle

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

    Scene("layer") { art ->
        rect(Rect.of(0f, 0f, SceneSize.toFloat(), SceneSize.toFloat()), Ink)

        // Top: two overlapping boxes faded one at a time, so the overlap is two fades stacked and
        // the seam between them shows.
        pushAlpha(0.5f)
        rect(Rect.of(20f, 20f, 90f, 70f), Accent)
        rect(Rect.of(70f, 45f, 90f, 70f), Paper)
        popAlpha()

        // Bottom: the same pair through a layer, so they fade together as one object and the seam
        // is gone. The clip and the text are in here to prove that a layer is drawn in the same
        // coordinates as the screen — a backend that shifts either of them by the layer's origin
        // produces a visibly different picture.
        pushAlpha(0.5f)
        val bounds = Rect.of(20f, 130f, 140f, 95f)
        val picture = layer(bounds) {
            rect(Rect.of(20f, 130f, 90f, 70f), Accent)
            pushClip(Rect.of(70f, 155f, 60f, 50f))
            rect(Rect.of(70f, 155f, 90f, 70f), Paper)
            popClip()
            text(art.fonts.measure("Layer", Small), Offset(28f, 204f), Paper)
        }
        if (picture != null) drawLayer(picture, bounds) else rect(bounds, Accent)
        popAlpha()
    },

    Scene("effect") { art ->
        rect(Rect.of(0f, 0f, SceneSize.toFloat(), SceneSize.toFloat()), Ink)

        // Top: the panel as it is. Bottom: the same panel through somebody's GLSL — a plain box
        // blur, written against the public API and compiled by whichever backend is drawing. The
        // bleed is what lets the blur spread past the panel instead of being cut off square.
        panel(art, Rect.of(30f, 20f, 180f, 80f))

        val bounds = Rect.of(30f, 130f, 180f, 80f)
        val area = bounds.inset(-Blur.bleed)
        val picture = layer(area) { panel(art, bounds) }
        if (picture != null) drawLayer(picture, area, Blur) else panel(art, bounds)
    },

    Scene("effects") { art ->
        rect(Rect.of(0f, 0f, SceneSize.toFloat(), SceneSize.toFloat()), Ink)

        // The four effects the toolkit itself ships, drawn through the same public API a game's own
        // shader goes through. A backend that disagrees with the other one here disagrees about
        // what GLSL means, which is the one thing two backends are not allowed to do.
        val blur = listOf(blur(4f, Axis.Horizontal), blur(4f, Axis.Vertical))
        through(blur, Rect.of(16f, 16f, 96f, 88f)) { tile(art, Rect.of(16f, 16f, 96f, 88f), "Blur") }

        val ring = listOf(outline(Paper, width = 3f))
        through(ring, Rect.of(128f, 16f, 96f, 88f)) { tile(art, Rect.of(128f, 16f, 96f, 88f), "Line") }

        val grade = listOf(colourGrade(brightness = 0.7f, contrast = 1.2f, saturation = 0f))
        through(grade, Rect.of(16f, 136f, 96f, 88f)) { tile(art, Rect.of(16f, 136f, 96f, 88f), "Grade") }

        val gone = listOf(dissolve(progress = 0.4f, scale = 18f, edge = Accent))
        through(gone, Rect.of(128f, 136f, 96f, 88f)) { tile(art, Rect.of(128f, 136f, 96f, 88f), "Gone") }
    },

    Scene("particles") { _ ->
        rect(Rect.of(0f, 0f, SceneSize.toFloat(), SceneSize.toFloat()), Ink)

        // A seeded burst, wound on by a fixed step. Nothing here reads a clock, so the picture is
        // the same every time it is drawn — which is the only way a pool full of random numbers can
        // be checked by a golden at all. A burst that came out differently on another platform
        // would show up here as a different picture rather than as a shrug.
        val embers = ParticleEmitter(capacity = 120, seed = 4L)
        embers.burst(
            60,
            SceneSize / 2f,
            200f,
            ParticleStyle(
                life = 1.1f,
                speed = 150f,
                direction = -90f,
                spread = 55f,
                gravity = 180f,
                size = 7f,
                endSize = 0.2f,
                colour = Accent,
                endColour = Paper.withAlpha(0),
                corner = 3.5f,
            ),
        )
        // Six hundred milliseconds in, in the steps a game would have taken to get there.
        repeat(36) { embers.update(1f / 60f) }
        embers.drawInto(this, Rect.of(0f, 0f, SceneSize.toFloat(), SceneSize.toFloat()))
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

/** A picture as raw bytes: four to a pixel, red first, the top row first. */
class RawImage(val width: Int, val height: Int, val pixels: ByteArray)

/**
 * The nine-patch art the scenes draw, as arithmetic rather than as a file.
 *
 * A picture in the repository is a picture that can be edited by accident; twenty-four pixels of
 * arithmetic cannot be. It lives here rather than in either backend's tests because both of them
 * draw it, and art that differed between the two would make the two sets of goldens impossible to
 * compare by eye.
 *
 * Nothing in it is translucent. Every backend blends slightly differently in the last bit, and the
 * art is supposed to be the constant in this comparison.
 */
fun bevel(): RawImage {
    val size = 24
    val pixels = ByteArray(size * size * 4)

    fun set(x: Int, y: Int, red: Int, green: Int, blue: Int) {
        val at = (y * size + x) * 4
        pixels[at] = red.toByte()
        pixels[at + 1] = green.toByte()
        pixels[at + 2] = blue.toByte()
        pixels[at + 3] = 0xFF.toByte()
    }

    fun fill(left: Int, top: Int, width: Int, height: Int, red: Int, green: Int, blue: Int) {
        for (y in top until top + height) for (x in left until left + width) set(x, y, red, green, blue)
    }

    fill(0, 0, size, size, 31, 41, 56)
    // A cross through the middle eight pixels, so tiling and stretching look different.
    fill(8, 11, 8, 2, 42, 79, 106)
    fill(11, 8, 2, 8, 42, 79, 106)
    // The frame, and one corner marked, so a corner drawn from the wrong place is unmistakable.
    for (at in 0 until size) {
        set(at, 0, 77, 194, 255)
        set(at, size - 1, 77, 194, 255)
        set(0, at, 77, 194, 255)
        set(size - 1, at, 77, 194, 255)
    }
    fill(2, 2, 3, 3, 255, 217, 77)

    return RawImage(size, size, pixels)
}

/**
 * One effect on top of another, the way the draw pass does it.
 *
 * A copy of what [dev.wildware.composegl.ui.draw.DrawPass] does for a modifier chain, because a scene draws
 * straight onto a canvas rather than through nodes — and because a two-pass blur cannot be
 * demonstrated any other way. The first effect in the list ends up innermost, so it sees the
 * widget and the rest see each other's answers.
 */
private fun UiCanvas.through(effects: List<ShaderEffect>, bounds: Rect, body: () -> Unit) {
    val effect = effects.lastOrNull()
    if (effect == null) {
        body()
        return
    }
    val area = if (effect.bleed > 0f) bounds.inset(-effect.bleed) else bounds
    val picture = layer(area) { through(effects.dropLast(1), bounds, body) }
    if (picture == null) {
        through(effects.dropLast(1), bounds, body)
        return
    }
    drawLayer(picture, area, effect)
}

/** One of the four boxes in the effects scene: something with an edge, a fill and some text on it. */
private fun UiCanvas.tile(art: SceneArt, at: Rect, label: String) {
    rect(at, Panel, corner = 12f)
    border(at, Accent, width = 2f, corner = 12f)
    text(art.fonts.measure(label, Small), Offset(at.left + 14f, at.top + 14f), Paper)
    rect(Rect.of(at.left + 14f, at.top + 42f, at.width - 28f, 10f), Accent, corner = 5f)
    rect(Rect.of(at.left + 14f, at.top + 60f, (at.width - 28f) * 0.6f, 10f), Paper, corner = 5f)
}

/** The scene's panel, drawn twice: once plainly, once through a shader. */
private fun UiCanvas.panel(art: SceneArt, at: Rect) {
    rect(at, Panel, corner = 14f)
    border(at, Accent, width = 2f, corner = 14f)
    text(art.fonts.measure("Effect", Body), Offset(at.left + 18f, at.top + 18f), Paper)
}

/**
 * A box blur, as a user would write one.
 *
 * Twenty-five taps and no cleverness: this is a golden, and what it is watching is whether two
 * backends hand the same picture to the same GLSL, not how fast a blur can be.
 */
private val Blur = ShaderEffect(
    source = ShaderSource(
        name = "box-blur",
        fragment = """
            uniform float u_radius;

            void main() {
                vec2 step = u_radius / u_textureSize;
                vec4 total = vec4(0.0);
                for (int across = -2; across <= 2; across++) {
                    for (int down = -2; down <= 2; down++) {
                        total += texture2D(u_texture, v_texCoord + vec2(float(across), float(down)) * step);
                    }
                }
                gl_FragColor = total / 25.0 * u_alpha;
            }
        """.trimIndent(),
    ),
    uniforms = mapOf("u_radius" to Uniform.Number(2f)),
    bleed = 6f,
)
