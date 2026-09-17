# Kool

Put a ComposeGL screen in a [Kool](https://github.com/fabmax/kool) game. It is a Kool
scene like any other: Kool draws it in the order you added it, over the scenes before
it, and the mouse reaches it through Kool's own input.

![a Kool world of three cubes with a ComposeGL panel on top: a button, a click count and a scene view that turned red after the click](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/kool-demo-clicked.png)

**Desktop JVM only, pointer only, for now.** Built on Kool 0.19.0 and run on desktop
Linux. See [the limits](#limits) before you start.

---

## Add it

`composegl-kool` is not in a release yet. Until it is, take the snapshot:

```kotlin
repositories {
    mavenCentral()
    google()   // the Compose runtime reaches for androidx, which lives here
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}

dependencies {
    implementation("dev.wildware.composegl:composegl-kool:0.7.0-SNAPSHOT")
}
```

It brings `composegl-ui`, `composegl-render`, `composegl-lwjgl3` and Kool 0.19.0 with it.
On the desktop Kool's OpenGL is LWJGL, so the GL calls and the fonts are the raw OpenGL
backend's: `StbFonts` and stb_truetype. Kool asks for LWJGL 3.3.6; this module moves every
LWJGL module up to the one ComposeGL is built on, which Kool draws correctly on.

---

## A first screen

```kotlin
import de.fabmax.kool.KoolApplication
import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.pipeline.backend.gl.RenderBackendGl
import dev.wildware.composegl.kool.KoolBackend
import dev.wildware.composegl.kool.composeGl
import dev.wildware.composegl.lwjgl3.StbFonts
import dev.wildware.composegl.ui.geometry.Size

fun main() = KoolApplication(KoolConfigJvm(renderBackend = RenderBackendGl)) {
    ctx.addScene(myWorld)

    val fonts = StbFonts().apply { register("default", File("DejaVuSans.ttf").readBytes(), listOf(16, 24)) }
    ctx.composeGl(KoolBackend(fonts), Size(1280f, 720f)) {
        var clicks by remember { mutableStateOf(0) }
        Button("Clicked $clicks", onClick = { clicks++ })
    }
}
```

**OpenGL, not Vulkan.** Kool picks Vulkan by itself where it can. ComposeGL draws with
OpenGL, so start Kool with `renderBackend = RenderBackendGl`; `composeGl` refuses a Kool
that renders with anything else, and says so.

The design size is fitted into the scene's view by a `ScalePolicy`, exactly as a
`Viewport` fits one onto a window on the other backends.

`composeGl` returns the `ComposeGlScene`: its Kool `scene` (to remove it later), and
`player`, the input sink a game hands its own keys or pads to. `close()` stops listening to
Kool's pointer and lets go of the composition; the backend is yours to close.

The demo is all of this in one file: `./gradlew :composegl-demo-kool:run`.

---

## Pictures

Wrap a Kool texture in `KoolTexture` and draw it like any picture:

```kotlin
val hero = Assets.loadTexture2d("hero.png").getOrThrow()
Image(KoolTexture(hero), Modifier.size(64f, 64f))
```

It is drawn with the sampler settings you gave the texture, and a texture Kool has not
uploaded yet is uploaded through Kool the first time it is drawn.

---

## Where it draws, and Kool's memory

The screen draws while Kool renders its scene, on Kool's render thread, into the
framebuffer Kool has bound. Kool remembers the GL state it last set — the program, depth
testing and writing, the depth comparison, culling — and skips setting what it thinks is
already set. So the canvas uses `HostState.Restore`: it saves what Kool left, and puts all
of it back when the frame ends and around `raw`. Kool's next scene draws as it would with
no interface at all; the tests check that pixel for pixel with a Kool mesh drawn after a
careless scene.

Kool updates the game on a thread of its own while it renders. The mouse is read there and
handed to the toolkit at the start of the next render, so the toolkit is only ever touched
on the render thread.

---

## Scene view

A `SceneView` works: its `raw` block is handed a `KoolFrame` — Kool's `ctx`, the
projection and the viewport — with the scene's picture bound.

```kotlin
SceneView(state, Modifier.size(320f, 240f)) {
    clear(Colour.rgb(0x101820))
    raw { frame ->
        frame as KoolFrame
        // Your own OpenGL, on Kool's context, into the picture.
    }
}
```

Kool draws only through its own passes, so whatever the block draws with is OpenGL behind
Kool's back, and Kool's memory cannot be cleared from outside. So unlike KorGE, what the
block leaves is **not** kept: when the block returns, Kool's own state goes back, and Kool
is never left believing something that is not true.

The block starts in Kool's state, not the toolkit's. On a context with clip control that
means reversed depth — the depth comparison in force is Kool's reversed one, not `LESS`. A block that tests depth
sets the comparison it wants; the picture's depth is cleared to 1.

---

## Limits

Honest ones:

- **JVM desktop only.** Kool is multiplatform; the browser and Android are the next step
  and are not built or tested yet.
- **Pointer only.** The mouse and touches are translated. Kool's keys, typed text and pads
  are not yet; a game that translates them hands them to `ComposeGlScene.player`.
- **No clipboard, soft keyboard, cursor shapes or haptics.** They are the toolkit's
  do-nothing ones.
- **OpenGL only.** Kool's Vulkan backend is refused.
- **Kool 0.19.0 on Linux asks GLFW for Wayland** and does not fall back to X11 when there is
  no Wayland display. The demo starts GLFW on X11 first when `WAYLAND_DISPLAY` is not set;
  a game on Linux does the same (see `initGlfwOnX11` in the demo's `Main.kt`).

---

## What next

- **[[Backends]]** — the other backends, and how each is a thin wrapper round the same renderer
- **[[Scene view]]** — the scene view in full
