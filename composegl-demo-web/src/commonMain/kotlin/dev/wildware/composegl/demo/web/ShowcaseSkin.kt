package dev.wildware.composegl.demo.web

import dev.wildware.composegl.ui.graphics.ArtAtlas
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinFormat

/**
 * The three skins the tour can wear, each with the art laid in.
 *
 * Every one starts from a skin the toolkit ships, so every widget has a style. The styles every
 * skin needs for the tour's own pages — the extra bars, the cards — go on all three, and the
 * showcase's own look goes on the first only. Switching is swapping one object: nothing is rebuilt.
 */
class ShowcaseSkins(art: ArtAtlas?) {

    private val extras = SkinFormat.read(Extras)
    private val artOnly = Skin(art = art)

    val showcase: Skin = Skin.Default.overriddenWith(extras).overriddenWith(SkinFormat.read(Look)).overriddenWith(artOnly)
    val standard: Skin = Skin.Default.overriddenWith(extras).overriddenWith(artOnly)
    val highContrast: Skin = Skin.HighContrast.overriddenWith(SkinFormat.read(HighContrastExtras)).overriddenWith(artOnly)

    operator fun get(choice: SkinChoice): Skin = when (choice) {
        SkinChoice.Showcase -> showcase
        SkinChoice.Standard -> standard
        SkinChoice.HighContrast -> highContrast
    }
}

/** Styles the tour's pages ask for that the stock skins do not have. */
private const val Extras = """
{
  "styles": {
    "card": { "background": { "fill": "#1A1F28", "corner": 10, "border": "#2C3545", "padding": 16 } },
    "bar.shield.track": { "background": { "fill": "#40000000", "corner": 5 } },
    "bar.shield.fill": { "background": { "fill": "#4CC2FF", "corner": 5 } },
    "bar.shield.trail": { "background": { "fill": "#704CC2FF", "corner": 5 } },
    "bar.shield.segment": { "background": { "fill": "#0A1018" } },
    "bar.stamina.track": { "background": { "fill": "#40000000", "corner": 5 } },
    "bar.stamina.fill": { "background": { "fill": "#7BE08A", "corner": 5 } },
    "bar.stamina.trail": { "background": { "fill": "#707BE08A", "corner": 5 } },
    "bar.stamina.segment": { "background": { "fill": "#0A1018" } },
    "minimap.objective": { "background": { "fill": "#F2C94C" } },
    "code": { "textColour": "#8ADBFF", "text": { "size": 13 } }
  }
}
"""

/** The same, loud, for the high-contrast skin: no greys, heavy edges. */
private const val HighContrastExtras = """
{
  "styles": {
    "card": { "background": { "fill": "#000000", "corner": 4, "border": "#FFFFFF", "borderWidth": 2, "padding": 16 } },
    "bar.shield.track": { "background": { "fill": "#000000", "border": "#FFFFFF" } },
    "bar.shield.fill": { "background": { "fill": "#00E5FF" } },
    "bar.shield.trail": { "background": { "fill": "#FFFFFF" } },
    "bar.shield.segment": { "background": { "fill": "#000000" } },
    "bar.stamina.track": { "background": { "fill": "#000000", "border": "#FFFFFF" } },
    "bar.stamina.fill": { "background": { "fill": "#00FF66" } },
    "bar.stamina.trail": { "background": { "fill": "#FFFFFF" } },
    "bar.stamina.segment": { "background": { "fill": "#000000" } },
    "minimap.objective": { "background": { "fill": "#FFFF00" } },
    "code": { "textColour": "#FFFF00", "text": { "size": 13 } }
  }
}
"""

/** The showcase's own look: the example's blue, laid over the standard skin a style at a time. */
private const val Look = """
{
  "name": "Showcase",
  "defaults": { "textColour": "#E6EAF2", "text": { "font": "default", "size": 16 } },
  "styles": {
    "screen": { "background": { "fill": "#0B0E13" } },
    "card": { "background": { "fill": "#141A22", "corner": 12, "border": "#223040", "padding": 18 } },
    "panel": { "background": { "fill": "#1B2230", "corner": 10, "border": "#2A3A4E", "padding": 16 } },
    "label.title": { "textColour": "#4CC2FF", "text": { "font": "default", "size": 26 } },
    "label.heading": { "textColour": "#E6F4FF", "text": { "font": "default", "size": 18 } },
    "label.dim": { "textColour": "#9AA4B2", "text": { "size": 13 } },
    "button": {
      "background": { "fill": "#404CC2FF", "corner": 8, "border": "#4CC2FF", "padding": [16, 9] },
      "textColour": "#E6F4FF",
      "hovered": { "background": { "fill": "#804CC2FF", "corner": 8, "border": "#8ADBFF", "borderWidth": 2 } },
      "pressed": { "background": { "fill": "#FF4CC2FF", "corner": 8, "border": "#FFFFFF", "borderWidth": 2 }, "textColour": "#0B1119" },
      "focused": { "background": { "fill": "#404CC2FF", "corner": 8, "border": "#FFFFFF", "borderWidth": 2 } },
      "disabled": { "background": { "fill": "#141A22", "corner": 8, "border": "#2A3440" }, "textColour": "#5A6675" }
    },
    "button.quiet": {
      "background": { "fill": "#18FFFFFF", "corner": 8, "border": "#30FFFFFF", "padding": [16, 9] },
      "textColour": "#B8C2D0",
      "hovered": { "background": { "fill": "#284CC2FF", "corner": 8, "border": "#4CC2FF" } },
      "pressed": { "background": { "fill": "#484CC2FF", "corner": 8, "border": "#4CC2FF" } },
      "focused": { "background": { "fill": "#18FFFFFF", "corner": 8, "border": "#FFFFFF", "borderWidth": 2 } }
    },
    "item": {
      "background": { "fill": "#00000000", "corner": 8, "padding": [12, 8] },
      "textColour": "#9AA4B2",
      "hovered": { "background": { "fill": "#184CC2FF", "corner": 8 }, "textColour": "#E6F4FF" },
      "focused": { "background": { "fill": "#184CC2FF", "corner": 8, "border": "#FFFFFF", "borderWidth": 2 }, "textColour": "#E6F4FF" },
      "pressed": { "background": { "fill": "#404CC2FF", "corner": 8 } }
    },
    "item.selected": {
      "background": { "fill": "#304CC2FF", "corner": 8, "border": "#4CC2FF", "padding": [12, 8] },
      "textColour": "#4CC2FF",
      "hovered": { "background": { "fill": "#404CC2FF", "corner": 8, "border": "#4CC2FF" } },
      "focused": { "background": { "fill": "#404CC2FF", "corner": 8, "border": "#FFFFFF", "borderWidth": 2 } }
    },
    "toggle.on": {
      "background": { "fill": "#604CC2FF", "corner": 11, "border": "#8ADBFF", "padding": 3 },
      "focused": { "background": { "fill": "#604CC2FF", "corner": 11, "border": "#FFFFFF", "borderWidth": 2 } }
    },
    "slider.fill": { "background": { "fill": "#4CC2FF", "corner": 3 } },
    "checkbox.tick": { "background": { "fill": "#4CC2FF", "corner": 2 } },
    "field": {
      "background": { "fill": "#600E1116", "corner": 6, "border": "#304CC2FF", "padding": [10, 7] },
      "hovered": { "background": { "fill": "#600E1116", "corner": 6, "border": "#604CC2FF" } },
      "focused": { "background": { "fill": "#600E1116", "corner": 6, "border": "#8ADBFF", "borderWidth": 2 } }
    },
    "dialog": { "background": { "fill": "#F21B1F2A", "corner": 12, "border": "#4CC2FF", "padding": 22 } },
    "tooltip": {
      "background": { "fill": "#F0151B26", "corner": 4, "border": "#4CC2FF", "padding": [8, 5] },
      "textColour": "#E8ECF2", "text": { "size": 13 }
    }
  }
}
"""
