# ComposeGL

These docs are for **ComposeGL 0.5.0**.

[![Maven Central](https://img.shields.io/maven-central/v/dev.wildware.composegl/composegl-ui?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.wildware.composegl/composegl-ui)
[![CI](https://github.com/wildware-uk/composegl/actions/workflows/ci.yml/badge.svg)](https://github.com/wildware-uk/composegl/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

**Write your game's interface in Compose. Draw it with OpenGL, inside your own frame.**

**Try it in your browser: https://wildware-uk.github.io/composegl/** — every widget, layout, animation
and effect, running live in one WebGL canvas.

The Compose runtime you already know — `@Composable`, `remember`, recomposition — with everything
above it replaced. Our own layout, widgets, renderer and input, built for games rather than apps.
No Android, no Compose UI, no second window.

![The example running](docs/images/demo.png)

## Install

```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}

repositories {
    mavenCentral()
    google()   // the Compose runtime reaches for androidx, which lives here
}

dependencies {
    implementation("dev.wildware.composegl:composegl-ui:0.5.0")
    implementation("dev.wildware.composegl:composegl-gdx:0.5.0")   // a backend — pick one
}
```

## Write a screen

```kotlin
@Composable
fun Hello() {
    var clicks by remember { mutableStateOf(0) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
        Panel(Modifier.width(280f)) {
            Column(verticalArrangement = Arrangement.spacedBy(12f)) {
                Text("HELLO")
                Text("Clicked $clicks times")
                Button("CLICK ME", onClick = { clicks++ })
            }
        }
    }
}
```

## Draw it

Make one renderer when the game starts, call it once a frame after your world is drawn:

```kotlin
val ui = UiRenderer(host, canvas)

ui.render(viewport, System.nanoTime())
```

It returns `false` when nothing changed. A menu sitting still costs almost nothing — in the spike, a
still interface redrew **4 times in 119 frames**.

The window, the fonts and the input hook are about sixty lines, and
**[Your first screen](docs/wiki/Your-first-screen.md)** writes all of them out.

## The modules

You need `composegl-ui` and exactly one backend. The library draws the interface itself, in
`composegl-render`; each backend is a thin wrapper that connects it to one engine's OpenGL.

| | |
|---|---|
| `composegl-ui` | the toolkit. Multiplatform: JVM, Linux native, iOS, and the browser as WebAssembly |
| `composegl-render` | the renderer every backend draws with. Comes with the backend; you do not add it yourself |
| `composegl-gdx` | the LibGDX backend. The one to use |
| `composegl-lwjgl3` | raw OpenGL on a GLFW window, desktop only. The reference thin backend |
| `composegl-webgl` | the browser: WebGL, the page's fonts, mouse, touch, keys, pads and input methods |
| `composegl-korge` | the KorGE backend: a screen as a view on a KorGE stage, with KorGE's input. JVM for now |
| `composegl-android` | on a phone: what the keyboard covers, the platform's own typing, and haptics |
| `composegl-robovm` | the same, on an iPhone, through UIKit |
| `composegl-effects` | blur, outline, colour grade, dissolve. Optional |
| `composegl-game` | the game widgets: bars, reticle, damage numbers, cooldowns, hotbar, minimap, notifications, particles. Optional |
| `composegl-debug` | the inspector and the layout, focus, overdraw, redraw, text and frame budget overlays. For development builds |
| `composegl-testing` | the scenes every backend draws, for testing your own widgets |

## Try it

```bash
./gradlew :composegl-demo:run                # the picture above
./gradlew :composegl-demo-snake:run          # Snake: menu, HUD, pause, game over
./gradlew :composegl-demo-korge:run          # a KorGE game: menu, HUD, settings, a panel on a sprite
./gradlew :composegl-demo-showcase:run       # a HUD over a 3D scene, and a panel standing in it
./gradlew :composegl-demo:renderPreviews     # every @Preview in the example, as PNGs in build/previews
./gradlew :composegl-demo-web:wasmJsBrowserDevelopmentRun   # the toolkit in a browser tab
```

## Read more

| | |
|---|---|
| [Your first screen](docs/wiki/Your-first-screen.md) | a window with a button in it, start to finish |
| [Widgets](docs/wiki/Widgets.md) · [Layout](docs/wiki/Layout.md) · [Modifiers](docs/wiki/Modifiers.md) | what there is — buttons, fields, sliders, dropdowns, steppers, dialogs, collapsing headers that fold a section away, splitters with a divider the player drags, menu bars with shortcuts and context menus, tables with sortable and resizable columns, trees that open and close — and how it fits together, down to lists and grids that build only what is on screen, lists in sections whose headers stay at the top, a panel that shakes on a wrong password, parallax layers that follow the mouse, stick or scroll, panels that animate out before they go, panels that grow to new contents instead of jumping, a long name that scrolls round inside its slot, screens that crossfade into each other, pages that slide or scale into the next with a transition picked per change, a small thing kept small in a big slot, and borders that are one-sided, dashed or dotted, text lined up by its baseline, Chinese, Japanese, Korean and colour emoji in text through font fallbacks, sprite-sheet animation, labels players can select and copy, an on-screen keyboard for players with only a pad, a key rebinding button for the controls screen, rows that slide when a list is sorted, cards that flip over in 3D and a row of them tilting under one shared camera, several values moving as one off a single state, `Modifier.debugBounds()` to see where a widget landed |
| [Game widgets](docs/wiki/Game-widgets.md) | health bars with a damage trail, a crosshair with spread and hit markers, damage numbers anchored in the world, cooldowns, a hotbar, a minimap frame, notifications and particles, in the optional `composegl-game` module |
| [Skins](docs/wiki/Skins.md) | every colour, gradient and corner — one radius, or one per corner — comes out of a JSON file; a high-contrast skin ships too, and the player can switch skins live from an options screen |
| [Animation](docs/wiki/Animation.md) | clocks, springs and tweens, panels that animate in and out, screens that crossfade or slide, sizes and places that move, shakes, marquees, parallax and sprite strips, and pausing or stepping it all a frame at a time |
| [Input](docs/wiki/Input.md) · [Backends](docs/wiki/Backends.md) · [KorGE](docs/wiki/KorGE.md) · [Shaders](docs/wiki/Shaders.md) | mouse, keyboard, gamepad, the cursor's shape; a pad-driven cursor for maps and inventories; UI sounds; long press, double click, hold to repeat; drag and drop between slots, with a pad too; and writing your own |
| [Split-screen](docs/wiki/Split-screen.md) | local co-op: a viewport and a HUD per player, and each pad, the keyboard and the mouse routed to the right one |
| [Saving state](docs/wiki/Saving-state.md) | `rememberSaveable`: the tab, the scroll and the half-typed name survive leaving a screen |
| [Localisation](docs/wiki/Localisation.md) | strings looked up by the player's language with fallbacks and plurals; rows, alignment and padding that mirror for Arabic and Hebrew; Hebrew and English on one line drawn in reading order, with a caret that moves the way the arrows point |
| [Debugging](docs/wiki/Debugging.md) | `composegl-debug`, for development builds: `LayoutOverlay` to see boxes, padding and gaps across the whole screen, `Inspector` to point at one widget and read its size, constraints and modifiers, `OverdrawOverlay` to see which pixels are painted over and over, a frame budget overlay that names the nodes costing extra draw calls and why, `FocusOverlay` to see where the pad will move focus and where clicks really land, `RedrawOverlay` to see which nodes keep redrawing, and `TextMetricsOverlay` to see every label's baseline and cap height |
| [Testing](docs/wiki/Testing.md) | screens tested with no window: tag a widget, then click, type and press pad buttons on it with `uiTest`; and `@Preview` composables drawn to PNGs with `renderPreviews`; print the tree with `dump`; animations paused and stepped a frame at a time |
| [What's new in 0.5.0](docs/wiki/Whats-new-0.5.0.md) | everything since 0.4.0, and the breaking changes |
| [How it works](docs/how-it-works.md) | the long version: what is built, and why |
| [Releasing](docs/releasing.md) | how a version gets to Maven Central |

## What this has actually run on

0.5.0 is still early, and the interfaces will move. [What's new in 0.5.0](docs/wiki/Whats-new-0.5.0.md) lists what changed since 0.4.0, including what was removed.

Everything here has only ever run on Mesa's software OpenGL. **No real GPU, no macOS, no Windows,
and no actual phone.** The Android launcher ran on an x86_64 emulator with no hardware
acceleration, where touch works and the on-screen keyboard never draws. The iOS module compiles
against real UIKit bindings and has never been linked or run. Frame times, drivers and gamepads
need hardware nobody here has.

## Licence

[Apache 2.0](LICENSE).
