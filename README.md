# ComposeGL

[![Maven Central](https://img.shields.io/maven-central/v/dev.wildware.composegl/composegl-ui?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.wildware.composegl/composegl-ui)
[![CI](https://github.com/wildware-uk/composegl/actions/workflows/ci.yml/badge.svg)](https://github.com/wildware-uk/composegl/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

**Write your game's interface in Compose. Draw it with OpenGL, inside your own frame.**

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
    implementation("dev.wildware.composegl:composegl-ui:0.1.0")
    implementation("dev.wildware.composegl:composegl-gdx:0.1.0")   // a backend — pick one
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

You need `composegl-ui` and exactly one backend.

| | |
|---|---|
| `composegl-ui` | the toolkit. Multiplatform: JVM, Linux native, iOS |
| `composegl-gdx` | the LibGDX backend. The one to use |
| `composegl-lwjgl3` | a second backend, raw OpenGL. Exists to disagree with the first |
| `composegl-android` | on a phone: what the keyboard covers, and the platform's own typing |
| `composegl-robovm` | the same, on an iPhone, through UIKit |
| `composegl-effects` | blur, outline, colour grade, dissolve. Optional |
| `composegl-testing` | the scenes both backends draw, for testing your own widgets |

## Try it

```bash
./gradlew :composegl-demo:run                # the picture above
./gradlew :composegl-demo-snake:run          # Snake: menu, HUD, pause, game over
./gradlew :composegl-demo-showcase:run       # a HUD over a 3D scene, and a panel standing in it
```

## Read more

| | |
|---|---|
| [Your first screen](docs/wiki/Your-first-screen.md) | a window with a button in it, start to finish |
| [Widgets](docs/wiki/Widgets.md) · [Layout](docs/wiki/Layout.md) · [Modifiers](docs/wiki/Modifiers.md) | what there is, and how it fits together |
| [Skins](docs/wiki/Skins.md) | every colour and corner comes out of a JSON file |
| [Input](docs/wiki/Input.md) · [Backends](docs/wiki/Backends.md) · [Shaders](docs/wiki/Shaders.md) | mouse, keyboard, gamepad; and writing your own |
| [How it works](docs/how-it-works.md) | the long version: what is built, and why |
| [Releasing](docs/releasing.md) | how a version gets to Maven Central |

## What this has actually run on

0.1.0 is a first release and the interfaces will move.

Everything here has only ever run on Mesa's software OpenGL. **No real GPU, no macOS, no Windows,
and no actual phone.** The Android launcher ran on an x86_64 emulator with no hardware
acceleration, where touch works and the on-screen keyboard never draws. The iOS module compiles
against real UIKit bindings and has never been linked or run. Frame times, drivers and gamepads
need hardware nobody here has.

## Licence

[Apache 2.0](LICENSE).
