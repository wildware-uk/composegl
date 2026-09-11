package dev.wildware.composegl.ui.skin

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.ArtAtlas
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.EdgeMode
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.text.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A skin as a file an artist edits.
 *
 * Two things are being checked. A skin survives a trip out to text and back, which is the cheapest
 * proof there is that the reader and the format agree about every field; and a file that is wrong
 * says so at load, on a line, in words somebody who does not write Kotlin can act on.
 */
class SkinFormatTest {

    private class Region(override val width: Int = 32, override val height: Int = 32) : TextureHandle

    private val atlas = object : ArtAtlas {
        private val regions = mapOf(
            "ui/button" to Region(),
            "ui/button_hover" to Region(),
            "icons/heart" to Region(16, 16),
        )

        override fun region(name: String): TextureHandle? = regions[name]
        override val names: Set<String> get() = regions.keys
    }

    private fun read(text: String) = SkinFormat.read(text, atlas)

    private val file = """
        {
          // What every style falls back to.
          "defaults": { "textColour": "#E6E6E6", "text": { "font": "body", "size": 16 } },

          "styles": {
            "button": {
              "background": { "patch": "ui/button", "slice": 6, "padding": [12, 6] },
              "textColour": "#CCCCCC",
              "hovered": { "background": { "patch": "ui/button_hover", "slice": 6 } },
              "pressed": { "contentOffset": [0, 1] },
              "disabled": { "tint": "#80FFFFFF" },
            },
            "button.danger": { "textColour": "#FF5C5C" },
            "panel": { "background": { "fill": "#20242C", "corner": 4 }, "padding": 8 },
            "icon": { "background": { "image": "icons/heart" } },
            "label": { "background": "none", "text": { "size": 20 } }
          }
        }
    """.trimIndent()

    // --- reading ---

    @Test
    fun `a style is read whole and so are its states`() {
        val skin = read(file)
        val style = skin.styles.getValue("button")

        val patch = (style.base.background as SkinDrawable.Patch).patch
        assertEquals(Colour.rgb(0xCCCCCC), style.base.textColour)
        assertEquals(Padding(12f, 6f, 12f, 6f), patch.padding)
        assertEquals(Padding.all(6f), patch.slice)
        assertEquals(Offset(0f, 1f), style.pressed?.contentOffset)
        assertEquals(Colour.argb(0x80FFFFFF), style.disabled?.tint)
    }

    @Test
    fun `the defaults are what a style leaves unsaid`() {
        val style = read(file).resolve("panel")

        assertEquals(Colour.rgb(0xE6E6E6), style.textColour, "the file named this once")
        assertEquals(TextStyle(family = "body", size = 16f), style.textStyle)
    }

    @Test
    fun `a text block changes what it names and keeps the family`() {
        val style = read(file).resolve("label")

        assertEquals("body", style.textStyle.family, "the size was all this style said")
        assertEquals(20f, style.textStyle.size)
    }

    @Test
    fun `the three kinds of background and also none`() {
        val skin = read(file)

        assertTrue(skin.styles.getValue("button").base.background is SkinDrawable.Patch)
        assertEquals(
            SkinDrawable.Fill(Colour.rgb(0x20242C), corner = 4f),
            skin.styles.getValue("panel").base.background,
        )
        assertEquals(
            SkinDrawable.Image(atlas.region("icons/heart")!!),
            skin.styles.getValue("icon").base.background,
        )
        assertEquals(SkinDrawable.Blank, skin.styles.getValue("label").base.background)
    }

    @Test
    fun `a variant the file never mentions falls back to the style it is a variant of`() {
        val danger = read(file).resolve("button.danger")
        assertEquals(Colour.rgb(0xFF5C5C), danger.textColour, "a variant the file does write")

        val quiet = read(file).resolve("button.quiet")
        assertEquals(Colour.rgb(0xCCCCCC), quiet.textColour, "and one it does not is the button")
        assertEquals(
            Padding(12f, 6f, 12f, 6f),
            quiet.padding,
            "with the padding its art carries, which is where that number belongs",
        )
    }

    @Test
    fun `an edge can tile instead of stretching`() {
        val skin = read(
            """
            { "styles": { "bar": { "background": {
                "patch": "ui/button", "slice": 4, "edges": { "centreAcross": "tile" }
            } } } }
            """.trimIndent(),
        )

        val patch = (skin.styles.getValue("bar").base.background as SkinDrawable.Patch).patch
        assertEquals(EdgeMode.Tile, patch.centreAcross)
        assertEquals(EdgeMode.Stretch, patch.centreDown, "an edge nobody mentioned stretches")
    }

    // --- round trip ---

    @Test
    fun `a skin written out and read back is the same skin`() {
        val skin = read(file)

        val again = read(SkinFormat.write(skin))

        assertEquals(skin.styles, again.styles)
        assertEquals(skin.defaults, again.defaults)
    }

    @Test
    fun `what is written is a file rather than a dump`() {
        val written = SkinFormat.write(read(file))

        assertTrue("\"patch\": \"ui/button\"" in written, written)
        assertTrue("\"textColour\": \"#CCCCCC\"" in written, written)
        assertTrue("\"tint\": \"#80FFFFFF\"" in written, "alpha is kept: $written")
        assertTrue("\"contentOffset\": [0, 1]" in written, written)
    }

    // --- failing loudly ---

    @Test
    fun `a region the atlas has never heard of stops the load and suggests the right one`() {
        val problem = assertFailsWith<SkinFormatException> {
            read(
                """
                {
                  "styles": {
                    "button": { "background": { "patch": "ui/buton", "slice": 6 } }
                  }
                }
                """.trimIndent(),
            )
        }

        val message = problem.message.orEmpty()
        assertTrue("line 3" in message, message)
        assertTrue("ui/buton" in message, message)
        assertTrue("ui/button" in message, "it knows what was meant: $message")
    }

    @Test
    fun `a key spelled the American way is a mistake rather than a shrug`() {
        val problem = assertFailsWith<SkinFormatException> {
            read("""{ "styles": { "button": { "textColor": "#FFFFFF" } } }""")
        }

        assertTrue("textColour" in problem.message.orEmpty(), problem.message.orEmpty())
    }

    @Test
    fun `a colour that is not a colour says what one looks like`() {
        val problem = assertFailsWith<SkinFormatException> {
            read("""{ "styles": { "button": { "textColour": "red" } } }""")
        }

        assertTrue("#RRGGBB" in problem.message.orEmpty(), problem.message.orEmpty())
    }

    @Test
    fun `a nine-patch without a slice is not a nine-patch`() {
        val problem = assertFailsWith<SkinFormatException> {
            read("""{ "styles": { "b": { "background": { "patch": "ui/button" } } } }""")
        }

        assertTrue("slice" in problem.message.orEmpty(), problem.message.orEmpty())
    }

    @Test
    fun `a background cannot be two things at once`() {
        val problem = assertFailsWith<SkinFormatException> {
            read("""{ "styles": { "b": { "background": { "patch": "ui/button", "fill": "#FFFFFF" } } } }""")
        }

        assertTrue("only be one" in problem.message.orEmpty(), problem.message.orEmpty())
    }

    @Test
    fun `naming a region with no atlas loaded is a mistake rather than an empty picture`() {
        val problem = assertFailsWith<SkinFormatException> {
            SkinFormat.read("""{ "styles": { "b": { "background": { "image": "icons/heart" } } } }""")
        }

        assertTrue("no atlas" in problem.message.orEmpty(), problem.message.orEmpty())
    }

    @Test
    fun `an empty file is a skin with nothing in it rather than an error`() {
        val skin = read("{}")

        assertEquals(emptyMap(), skin.styles)
        assertEquals(SkinDrawable.Blank, skin.resolve("button").background, "and it still draws")
    }
}
