package dev.wildware.composegl.ui.skin

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.ArtAtlas
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.EdgeMode
import dev.wildware.composegl.ui.graphics.NineRegions
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

            // A frame the host cut for itself, with both bands down to one texel so that no
            // coarser mip level can reach a neighbour on the atlas. See NineRegions.
            "frame/topLeft" to Region(6, 6),
            "frame/top" to Region(1, 6),
            "frame/topRight" to Region(6, 6),
            "frame/left" to Region(6, 1),
            "frame/centre" to Region(1, 1),
            "frame/right" to Region(6, 1),
            "frame/bottomLeft" to Region(6, 6),
            "frame/bottom" to Region(1, 6),
            "frame/bottomRight" to Region(6, 6),

            // And a three-piece bar: two caps and a middle, with no top or bottom row at all.
            "bar/cap" to Region(4, 12),
            "bar/fill" to Region(1, 12),
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
            "frame": {
              "background": {
                "patch": {
                  "topLeft": "frame/topLeft", "top": "frame/top", "topRight": "frame/topRight",
                  "left": "frame/left", "centre": "frame/centre", "right": "frame/right",
                  "bottomLeft": "frame/bottomLeft", "bottom": "frame/bottom",
                  "bottomRight": "frame/bottomRight"
                },
                "padding": 9
              }
            },
            "label": { "background": "none", "text": { "size": 20 } },
            "sky": { "background": { "gradient": { "vertical": ["#3A6EA5", "#1B2A41"] }, "corner": 6 } },
            "health": { "background": { "gradient": { "horizontal": ["#4CD964", "#FF3B30"] }, "border": "#000000" } },
            "sheen": { "background": { "gradient": { "linear": ["#FFFFFF", "#00FFFFFF"], "angle": 45 }, "padding": 4 } },
            "vignette": { "background": { "gradient": { "radial": ["#00000000", "#C0000000"] } } }
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
    fun `a gradient is read with the way it runs and its two colours`() {
        val skin = read(file)

        assertEquals(
            SkinDrawable.Gradient(Brush.vertical(Colour.rgb(0x3A6EA5), Colour.rgb(0x1B2A41)), corner = 6f),
            skin.styles.getValue("sky").base.background,
        )
        assertEquals(
            SkinDrawable.Gradient(
                Brush.horizontal(Colour.rgb(0x4CD964), Colour.rgb(0xFF3B30)),
                border = Colour.Black,
                borderWidth = 1f,
            ),
            skin.styles.getValue("health").base.background,
            "a border with no width is one wide, as it is on a fill",
        )
        assertEquals(
            SkinDrawable.Gradient(
                Brush.linear(Colour.White, Colour.argb(0x00FFFFFF), degrees = 45f),
                padding = Padding.all(4f),
            ),
            skin.styles.getValue("sheen").base.background,
        )
        assertEquals(
            SkinDrawable.Gradient(Brush.radial(Colour.Transparent, Colour.argb(0xC0000000))),
            skin.styles.getValue("vignette").base.background,
        )
    }

    @Test
    fun `a gradient is written back the way it would have been typed`() {
        val written = SkinFormat.write(read(file))

        assertTrue("\"vertical\": [\"#3A6EA5\", \"#1B2A41\"]" in written, written)
        assertTrue("\"horizontal\": [\"#4CD964\", \"#FF3B30\"]" in written, written)
        assertTrue("\"linear\": [\"#FFFFFF\", \"#00FFFFFF\"]" in written, written)
        assertTrue("\"angle\": 45" in written, written)
        assertTrue("\"radial\": [\"#00000000\", \"#C0000000\"]" in written, written)
    }

    @Test
    fun `a gradient takes a radius per corner as a fill does and writes it back`() {
        val skin = read(
            """{ "styles": { "tab": { "background": { "gradient": { "vertical": ["#FF0000", "#0000FF"] }, "corner": { "topLeft": 8, "topRight": 8 } } } } }""",
        )
        val expected = SkinDrawable.Gradient(Brush.vertical(Colour.rgb(0xFF0000), Colour.rgb(0x0000FF)), Corners.top(8f))

        assertEquals(expected, skin.styles.getValue("tab").base.background)
        assertEquals(expected, read(SkinFormat.write(skin)).styles.getValue("tab").base.background, "after a trip out and back")
    }

    @Test
    fun `a gradient needs exactly two colours`() {
        val problem = assertFailsWith<SkinFormatException> {
            read("""{ "styles": { "b": { "background": { "gradient": { "vertical": ["#FF0000", "#00FF00", "#0000FF"] } } } } }""")
        }

        assertTrue("two colours" in problem.message.orEmpty(), problem.message.orEmpty())
        assertTrue("3" in problem.message.orEmpty(), problem.message.orEmpty())
    }

    @Test
    fun `a gradient that says two directions is refused`() {
        val problem = assertFailsWith<SkinFormatException> {
            read(
                """{ "styles": { "b": { "background": { "gradient": """ +
                    """{ "vertical": ["#FF0000", "#00FF00"], "radial": ["#FF0000", "#00FF00"] } } } } }""",
            )
        }

        assertTrue("one way" in problem.message.orEmpty(), problem.message.orEmpty())
    }

    @Test
    fun `an angle on anything but a linear gradient is a mistake rather than a shrug`() {
        val problem = assertFailsWith<SkinFormatException> {
            read("""{ "styles": { "b": { "background": { "gradient": { "vertical": ["#FF0000", "#00FF00"], "angle": 30 } } } } }""")
        }

        assertTrue("only a \"linear\"" in problem.message.orEmpty(), problem.message.orEmpty())
    }

    @Test
    fun `a linear gradient without an angle says it needs one`() {
        val problem = assertFailsWith<SkinFormatException> {
            read("""{ "styles": { "b": { "background": { "gradient": { "linear": ["#FF0000", "#00FF00"] } } } } }""")
        }

        assertTrue("needs an \"angle\"" in problem.message.orEmpty(), problem.message.orEmpty())
    }

    @Test
    fun `a gradient direction spelled wrong suggests the right one`() {
        val problem = assertFailsWith<SkinFormatException> {
            read("""{ "styles": { "b": { "background": { "gradient": { "vertica": ["#FF0000", "#00FF00"] } } } } }""")
        }

        assertTrue("\"vertical\"" in problem.message.orEmpty(), problem.message.orEmpty())
    }

    @Test
    fun `a gradient and a fill on one background cannot both be the background`() {
        val problem = assertFailsWith<SkinFormatException> {
            read("""{ "styles": { "b": { "background": { "fill": "#FFFFFF", "gradient": { "radial": ["#FF0000", "#00FF00"] } } } } }""")
        }

        assertTrue("only be one" in problem.message.orEmpty(), problem.message.orEmpty())
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

    @Test
    fun `a patch can be nine pieces the host cut itself and then it is its own slice`() {
        val patch = (read(file).styles.getValue("frame").base.background as SkinDrawable.Patch).patch
        val pieces = patch.texture as NineRegions

        assertEquals(Padding.all(6f), patch.slice, "the corners are 6, so the border is 6")
        assertEquals(Padding.all(9f), patch.padding, "and the file still says its own padding")
        // Every piece against its own name: the mistake a reader of nine keys makes is putting
        // the right art in the wrong slot, and that draws a frame with its sides swapped rather
        // than failing.
        assertEquals(
            listOf(
                "frame/topLeft", "frame/top", "frame/topRight",
                "frame/left", "frame/centre", "frame/right",
                "frame/bottomLeft", "frame/bottom", "frame/bottomRight",
            ).map { atlas.region(it) },
            listOf(
                pieces.topLeft, pieces.top, pieces.topRight,
                pieces.left, pieces.centre, pieces.right,
                pieces.bottomLeft, pieces.bottom, pieces.bottomRight,
            ),
        )
    }

    @Test
    fun `pieces the file leaves out are rows and columns with no slice`() {
        val skin = read(
            """
            { "styles": { "bar": { "background": { "patch": {
                "left": "bar/cap", "centre": "bar/fill", "right": "bar/cap"
            } } } } }
            """.trimIndent(),
        )

        val patch = (skin.styles.getValue("bar").base.background as SkinDrawable.Patch).patch
        assertEquals(Padding(left = 4f, right = 4f), patch.slice, "no top row and no bottom row")
    }

    @Test
    fun `a patch cut into pieces cannot also be given a slice`() {
        val problem = assertFailsWith<SkinFormatException> {
            read("""{ "styles": { "b": { "background": { "patch": { "centre": "bar/fill" }, "slice": 6 } } } }""")
        }

        // Not just the word "slice", which a patch that is *missing* one also says.
        assertTrue("is its own slice" in problem.message.orEmpty(), problem.message.orEmpty())
    }

    @Test
    fun `a piece the format has never heard of is a mistake rather than a shrug`() {
        val problem = assertFailsWith<SkinFormatException> {
            read("""{ "styles": { "b": { "background": { "patch": { "middle": "bar/fill" } } } } }""")
        }

        assertTrue("middle" in problem.message.orEmpty(), problem.message.orEmpty())
    }

    @Test
    fun `art that does not add up says so on the line that named it`() {
        val problem = assertFailsWith<SkinFormatException> {
            read(
                """
                {
                  "styles": {
                    "b": { "background": { "patch": { "topLeft": "frame/topLeft", "left": "bar/cap" } } }
                  }
                }
                """.trimIndent(),
            )
        }

        val message = problem.message.orEmpty()
        assertTrue("line 3" in message, message)
        assertTrue("topLeft" in message, "and it names the pieces that disagree: $message")
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

        assertTrue("needs a \"slice\"" in problem.message.orEmpty(), problem.message.orEmpty())
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
