# Skins

A skin is a JSON file. It holds every colour, corner, border, padding and piece of
art in your interface, and nothing in your Kotlin names any of them.

Save the file while the game is running and the screen changes on the next frame.

---

## The shape of the file

```jsonc
{
  "defaults": {
    "textColour": "#E8ECF2",
    "text": { "font": "default", "size": 16 }
  },

  "styles": {
    "panel": {
      "background": { "fill": "#1A1F28", "corner": 6, "border": "#2C3545", "padding": 12 }
    },
    "label.title": { "text": { "size": 22 } }
  }
}
```

`defaults` is what every style starts from. `styles` is a flat map of name to look.
Comments are allowed — it is a file people edit by hand.

---

## Names, and how they fall back

A widget asks for a style by name. `Button` asks for `"button"`, `Text` for
`"label"`, `Panel` for `"panel"`.

```kotlin
Button("LAUNCH", onClick = { }, style = "button.primary")
Text("CHAPTER ONE", style = "label.title")
```

A name falls back one dot at a time:

```
button.primary.small  →  button.primary  →  button  →  nothing
```

So a skin that has not been written yet is not broken — a missing style is a plain
widget, not a crash. Anything that *is* written is used whole; there is no merging
of a style with its parent.

Two consequences worth knowing:

- **Invent your own names freely.** `"button.abandon"` needs no support from the
  toolkit. If nobody writes it, it looks like `"button"`.
- **Failures are loud at load time and quiet at draw time.** A typo in the file is
  reported with its line; a name nobody wrote is silently plain.

---

## States

Four of them, and a style names the ones it cares about:

```jsonc
"button": {
  "background": { "fill": "#232A35", "corner": 5, "border": "#2C3545", "padding": [14, 8] },
  "hovered":  { "background": { "fill": "#39445A", "corner": 5, "border": "#4A5670" } },
  "focused":  { "background": { "fill": "#2A3342", "corner": 5, "border": "#5B8DEF" } },
  "pressed":  { "background": { "fill": "#1A1F28", "corner": 5 }, "contentOffset": [0, 1] },
  "disabled": { "textColour": "#5E6B7E", "tint": "#A0FFFFFF" }
}
```

`contentOffset` moves what is inside without moving the frame — a pressed button's
label drops a pixel while its border stays put. It is the cheapest convincing thing
in a game interface, and it belongs in the file rather than in a widget.

---

## Art

A style can name a region of your atlas instead of a fill, and cut it into a
nine-patch here rather than in code:

```jsonc
"panel": {
  "background": { "patch": "panel", "slice": 16, "padding": [20, 18] }
},

"heading": {
  "background": {
    "patch": "ribbon",
    "slice": [8, 10, 8, 10],
    "padding": [14, 5, 14, 6],
    "edges": { "centreAcross": "tile" }    // keep the hatch's pitch instead of smearing it
  }
}
```

`slice` is how far in the stretchable middle starts — one number for all four
sides, or four. `padding` is the gap between the art's frame and its contents, so
changing the picture changes the gap and no layout code moves.

---

## Loading one

```kotlin
val skin = ReloadingSkin(
    source = FileSkinSource(Path.of("assets/ui/game.skin.json")),
    art = ArtAtlas.of(atlas.regions.associate { it.name to GdxTexture(it) }),
    fonts = fonts,
    onProblem = { console.log("skin: ${it.message}") },
)

host.setContent { ProvideSkin(skin.skin) { Hud(state) } }
```

Then once a frame:

```kotlin
skin.reloadIfChanged()
```

That is one look at the file's modified time, and nothing at all until the time
moves. `skin.skin` is Compose state, so anything reading it recomposes by itself
when the file changes.

**A bad save does not stop the game.** Half a file — which is what a file looks
like for the instant an editor takes to write it — and a file with a typo both
leave the last working skin on screen and report through `onProblem`. The *first*
load is different: a game whose skin was already wrong before it started should say
so and stop, so that one throws.

Ship the file inside the jar and read it from disk when it is there, which is how
the demo does it:

```kotlin
val source = if (Files.exists(onDisk)) FileSkinSource(onDisk) else Packaged
```

---

## Overriding part of one

```kotlin
SkinOverride(dangerSkin) { WarningPanel() }
```

Everything under it gets the overriding skin's styles and the outer skin's for
whatever it does not name. Useful for a corrupted-terminal screen, an enemy faction
whose interface is red, or a tutorial that dims everything but one panel.

---

## Reading a style yourself

Widgets do it like this, and so can yours:

```kotlin
@Composable
fun Chip(text: String, style: String = "chip") {
    val interaction = remember { InteractionState() }
    val resolved = rememberStyle(style, rememberStates(interaction))

    Box(Modifier.styled(resolved).interaction(interaction)) {
        Text(text, colour = resolved.textColour)
    }
}
```

`rememberStyle` resolves the name and the states into one `ResolvedStyle`;
`Modifier.styled` wears it. (There are two `styled` extensions — this one takes the
resolved style, and `dev.wildware.composegl.ui.skin.styled` takes the name and resolves it for
you. Import whichever suits.) A widget written this way contains no colour, no corner
radius and no texture name, which is the whole point.

---

## The default skin

There is one, it is dark and neutral, and it needs no atlas and no artist. It is an
ordinary skin file — [`default.json`](https://github.com/wildware-uk/composegl/blob/master/composegl-ui/src/commonMain/skins/default.json),
read by the same loader your file goes through. Nothing in the toolkit's code knows
what colour a button is: if the default needed a special case, the skin system would
not be finished.

Start from it and replace styles one at a time.

---

## What next

- **[[Widgets]]** — which style name each widget asks for
- **[[Modifiers]]** — `styled`, and what order does
- **[[Backends]]** — where the atlas and the fonts come from
