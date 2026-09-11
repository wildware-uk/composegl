# ComposeGL

A UI toolkit for games, built on `androidx.compose.runtime` and nothing else.

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
| **[[Layout]]** | `Column`, `Row`, `Box`, `weight`, and what differs from Compose |
| **[[Modifiers]]** | the full list, and the three rules that are ours |
| **[[Widgets]]** | everything that ships, including the game tier |

## Then

| | |
|---|---|
| **[[Skins]]** | one JSON file holds every colour in your game, and hot reloads |
| **[[Input]]** | mouse, keyboard, pad, focus, and giving the world what the interface did not want |
| **[[Shaders]]** | blur, outline, dissolve — and your own GLSL bound to any widget |
| **[[Backends]]** | LibGDX, raw OpenGL, Android, and writing your own |
| **[[Testing]]** | a whole interface tested with no window and no GPU |
| **[[Custom layouts]]** | writing your own `MeasurePolicy` when the three are not enough |

---

## What it is not

- **Not Material.** No theme to fight, no component library to override. Widgets
  take their look from a skin file.
- **Not `androidx.compose.ui`.** No `dp`, no `Modifier.Node`, no density. Sizes are
  floats against a design resolution; the viewport does the rest.
- **Not tied to an engine.** LibGDX is one optional module. The core names no
  engine anywhere, and the build fails if anybody adds one.

## What it is for

Games. A HUD that costs nothing while it is standing still, bars with a damage
trail, a crosshair with spread and hit markers, damage numbers anchored in the
world, pad navigation that works without being wired up, and prompts that change
the moment somebody picks up a controller.
