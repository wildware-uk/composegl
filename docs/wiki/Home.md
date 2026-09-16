# ComposeGL

These docs are for **ComposeGL 0.5.0**. New since 0.4.0: **[[What's new in 0.5.0|Whats-new-0.5.0]]**.

A UI toolkit for games, built on `androidx.compose.runtime` and nothing else.

**Try it in your browser: [the showcase](https://wildware-uk.github.io/composegl/)** — click, type or
pick up a pad and go round every widget, layout, animation and effect, live.

You write `Text("Score: $score")`. It is drawn with OpenGL, through your engine,
inside your game loop — no separate window, no web view, no second thread, no
Android dependency.

```kotlin
Box(Modifier.fillMaxSize()) {
    Panel(Modifier.align(Alignment.BottomStart).padding(28f).width(280f)) {
        Column(verticalArrangement = Arrangement.spacedBy(10f)) {
            Text("HULL")
            Bar(hull, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("AMMO")
                Text("$ammo")
            }
        }
    }
    Reticle(reticle, Modifier.align(Alignment.Centre))
}
```

![a heads-up display: a hull bar and an ammo count in the corner, a crosshair in the middle](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/home-hud.png)

Every picture on this wiki is a photograph of the real toolkit, taken by
`./gradlew :composegl-demo:docShots` — never a drawing of it.

---

## Start here

| | |
|---|---|
| **[[Your first screen]]** | a window, a panel and a working button, in about eighty lines |
| **[[Layout]]** | `Column`, `Row`, `Box`, `FlowRow`, `Grid`, lazy lists and grids, `weight`, and what differs from Compose |
| **[[Modifiers]]** | the full list, and the three rules that are ours |
| **[[Widgets]]** | everything that ships in the toolkit: text, buttons, fields, collapsing headers, splitters, spinners, colour pickers, lists, tables, tooltips |
| **[[Game widgets]]** | the game tier, in `composegl-game`: bars, reticle, hit markers, damage direction arcs, a low-health vignette, damage numbers, world markers, cooldowns, hotbar, weapon wheel, inventory grid, minimap, compass bar, dialogue, subtitles, skill trees, item cards, particles |
| **[[Animation]]** | clocks, springs, panels that animate in and out, screens that crossfade or slide, and pausing it all to look |

## Then

| | |
|---|---|
| **[[Skins]]** | one JSON file holds every colour in your game, and hot reloads |
| **[[Input]]** | mouse, keyboard, pad, focus, and giving the world what the interface did not want |
| **[[Saving state]]** | the tab, the scroll position and the half-typed name, still there when a screen comes back |
| **[[Split-screen]]** | local co-op: a HUD per player, and each pad routed to its own player |
| **[[Localisation]]** | strings by language, screens that mirror for Arabic and Hebrew, and Hebrew and English on one line |
| **[[Shaders]]** | blur, outline, dissolve — and your own GLSL bound to any widget |
| **[[Backends]]** | LibGDX, raw OpenGL, a browser tab, Android, and writing your own |
| **[[KorGE]]** | a screen on a KorGE stage: fonts, skins, input, split-screen, in-world panels, testing, and the limits |
| **[[Testing]]** | a whole interface tested with no window and no GPU, and `@Preview` composables drawn to PNGs |
| **[[Debugging]]** | floating windows that tune your own values while the game runs, overlays for layout, focus, overdraw, draw calls, redraws and text, an inspector, a browsable tree of the whole screen, live plots, and a drop-down console for typing commands, from `composegl-debug` |
| **[[Custom layouts]]** | writing your own `MeasurePolicy` when the three are not enough |

---

## What it is not

- **Not Material.** No theme to fight, no component library to override. Widgets
  take their look from a skin file.
- **Not `androidx.compose.ui`.** No `dp`, no `Modifier.Node`, no density. Sizes are
  floats against a design resolution; the viewport does the rest.
- **Not tied to an engine.** LibGDX and KorGE are optional modules. The core names no
  engine anywhere, and the build fails if anybody adds one.

## What it is for

Games. A HUD that costs nothing while it is standing still, bars with a damage
trail, a crosshair with spread and hit markers, damage numbers and nameplates
anchored in the world, pad navigation that works without being wired up, prompts
that change the moment somebody picks up a controller, and a text size setting
that makes the words bigger without making the whole interface bigger.
