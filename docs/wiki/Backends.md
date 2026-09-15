# Backends

A backend is the bit that turns "draw a rounded box here" into actual OpenGL, and
"how wide is this word?" into a number.

Four ship. None is required: the toolkit names no engine anywhere.

| module | what it is |
|---|---|
| `composegl-gdx` | LibGDX. Desktop, Android, iOS. The one to ship a game on. |
| `composegl-lwjgl3` | Raw OpenGL and stb_truetype. Desktop only. |
| `composegl-webgl` | WebGL in a browser tab, from WebAssembly. The page's own fonts and input. |
| `composegl-korge` | KorGE 6. A screen is a view on the stage (`Container.composeGl`), with KorGE's input. Desktop JVM for now; try `./gradlew :composegl-demo-korge:run`. |
| `composegl-android` | Not a backend — the things about an Android phone LibGDX cannot answer. |
| `composegl-robovm` | The same, for an iPhone, through UIKit. |

---

## Which one

**LibGDX**, unless you have a reason. It has audio, assets, asset loading, a soft
keyboard, Android and iOS. A game needs all of those and the toolkit provides none
of them.

The LWJGL3 backend is the reference thin wrapper. It draws with the library's own
renderer, `composegl-render`, and adds only what raw OpenGL needs: a `Gl` binding, a
glyph rasteriser and a window. The other backends move onto the same renderer one at a
time (see `docs/superpowers/specs/2026-09-15-shared-gl-renderer.md`), and until they do
they carry renderers of their own.

Each backend has its own golden images, and CI checks each backend against its own.
The LibGDX and LWJGL3 sets are never compared with each other by a test — FreeType
and stb_truetype never agree on a glyph pixel for pixel — so that comparison is one a
person makes by looking at the two sets. The WebGL and KorGE backends go one step
further: besides their own goldens, their scenes with no text in them must also match
the LWJGL3 goldens.

---

## Setting one up

LibGDX:

```kotlin
val fonts = GdxFonts()
fonts.registerTrueType("default", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(13, 16, 20))
// Optional: where characters DejaVu lacks come from. See Widgets → Characters your font does not have.
fonts.registerTrueType("cjk", Gdx.files.internal("fonts/NotoSansCJK.ttf"), listOf(13, 16, 20), onDemand = true)
fonts.fallBackTo(listOf("cjk"))

val sprites = SpriteBatch()
val backend = GdxBackend(fonts, sprites)          // canvas, fonts, clipboard, keyboard, cursor
val canvas = backend.canvas

val host = UiHost()
host.setContent { ProvideFonts(fonts) { ProvideSkin(skin.skin) { Hud(state) } } }
```

`GdxCanvas(sprites, fonts.atlas)` on its own is still there for a game that wants
only the canvas. Neither builds anything on the GPU until the first thing is drawn,
so both can be constructed before the interface is ready; `canvas.warmUp()` — on
`UiCanvas`, and a no-op on a canvas with nothing to build — pays for the mesh and the
shader on a loading screen, where the player will not see a stutter.

LWJGL3:

```kotlin
val backend = Lwjgl3Backend(window, StbFonts().apply { registerTrueType(…) })
val canvas = backend.canvas
```

Then the same loop, whichever you chose — one renderer, made once:

```kotlin
val ui = UiRenderer(host, canvas)
// …once a frame:
ui.render(viewport, System.nanoTime())
```

See [Your first screen](Your-first-screen) for the four calls it is made of, and
when you would write them out instead.

---

## In a browser

`composegl-webgl` runs the toolkit in a web page: Kotlin compiled to WebAssembly,
drawn with WebGL (version 2 where the browser has it, 1 where it does not). Every
widget and every screen you already wrote runs unchanged — `composegl-ui` and
`composegl-effects` build for `wasmJs` alongside the JVM, Linux and iOS.

```kotlin
fun main() {
    MainScope().launch {
        val fonts = WebFonts()
        fonts.load("default", "fonts/DejaVuSans.ttf", listOf(13, 16, 20, 28))

        val canvas = document.getElementById("game") as HTMLCanvasElement
        val backend = WebGlBackend(canvas, fonts)
        BrowserUi(backend, Size(960f, 600f)) { MainMenu() }.start()
    }
}
```

`BrowserUi` is the one thing a web game needs beyond its screens. It is the loop
(`requestAnimationFrame`) and the wiring every desktop launcher writes by hand: the
pointer, key and pad routers, focus, the back stack, and the backend's fonts,
clipboard, keyboard and input method provided to the content. `frame(nanos)` is one
turn of it, for a page with its own loop.

What the page gives you, translated:

| | |
|---|---|
| **pointer** | mouse, touch and pen, each finger its own pointer; captured on a press, so a drag that leaves the canvas still ends |
| **keys** | named by where the key is (`code`), so WASD is WASD on any layout; text comes from what the key typed |
| **text** | an invisible text box takes focus with a field, so Japanese, Chinese and Korean input methods compose into it, and a phone raises its keyboard |
| **clipboard** | Ctrl+V waits for the browser's `paste` event, so a field pastes what was really copied; copies go to the system clipboard |
| **pads** | the Gamepad API's standard layout, polled once a frame; a browser only shows a page a pad after a button is pressed |
| **cursor** | the canvas's CSS `cursor` |
| **haptics** | `navigator.vibrate` on a phone, the pad's rumble where it has one |
| **size** | the canvas follows its size on the page times the device pixel ratio, and the viewport letterboxes the design into it |

**Fonts** are drawn by the browser. Each glyph is drawn once with the 2D canvas onto
an atlas page and is a quad from then on, so a screen of panels and labels is still
one draw call. A character outside ASCII and Latin-1 is drawn on demand the first
time a label needs it — including from the system's fallback fonts — rather than
coming out as `?`.

**Try it: [the showcase](https://wildware-uk.github.io/composegl/)**, a tour of every
widget, layout, animation, effect and debug tool, published to GitHub Pages from
`composegl-demo-web` on every push to master. It works with a mouse, a keyboard, a pad and
a phone's touch screen. To run it locally:
`./gradlew :composegl-demo-web:wasmJsBrowserDevelopmentRun`.

![the showcase in Chromium: the section list down the side, and the landing page with a live HUD and a tile for every section](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/web-demo.png)

The showcase's screens are common code, so their logic is tested on the JVM with
`uiTest`; `:composegl-demo-web:wasmJsBrowserTest` loads the whole thing in headless
Chromium and clicks, taps and pages through every section.

What differs in a browser, today:

- **Emoji** come from the visitor's system fonts, drawn once into the glyph atlas. A
  machine with no emoji font shows boxes, and colour emoji are tinted by the label's
  colour like any glyph.
- **Joined scripts** such as Arabic are drawn a character at a time, so letters do not
  join. Hebrew and other scripts without joining forms are fine.
- **Pads** appear only after a button is pressed while the page has focus — a browser
  rule, not the toolkit's.
- **Frame times** are coarse, because browsers blunt their clocks on purpose.

The backend is held to the same scenes as the other two. Its own goldens are drawn
in headless Chromium with WebGL in software, and the scenes with no text in them are
also compared with the raw OpenGL backend's goldens by the same rule — shapes, clips,
layers and effects have no reason to differ between OpenGL and WebGL. The tests are
`./gradlew :composegl-webgl:wasmJsBrowserTest`; set `CHROME_BIN`, or have Playwright's
Chromium installed.

---

## Hold the interface, not the backend

Whatever class of yours owns the interface — the one with the host, the renderer, the
focus manager and the canvas in it — should name `UiCanvas`, or `UiBackend` for the
whole lot. Never `GdxCanvas` or `GlCanvas`.

```kotlin
class Hud(backend: UiBackend)     // yes
class Hud(canvas: GdxCanvas)      // no
```

The difference is not tidiness. A class that names a backend can only ever be built
with that backend running, so every test of your focus, your input routing and your
lifecycle needs a window and a GPU — for code that has nothing to do with either.
Name the interface and the same class takes `HeadlessBackend` in a test: a recording
canvas, a monospace font, an in-memory clipboard, no machine. See [[Testing]] for the
worked example.

Four backends implement it, so the swap is real:

| | |
|---|---|
| `GdxBackend(fonts, spriteBatch)` | LibGDX |
| `Lwjgl3Backend(window, fonts)` | raw OpenGL |
| `WebGlBackend(canvas, fonts)` | a browser tab |
| `HeadlessBackend()` | no window at all, in `composegl-ui` |

`SnakeApp` in `composegl-demo-snake-core` is the shape to copy: it takes fonts and a
`UiCanvas` per frame, and its desktop, LibGDX and Android launchers are the only
things that have ever heard of an engine.

One thing that is *not* in `UiBackend`, on any of them: input. Translating a key, a
pointer or a pad into the toolkit's events is per-platform and stays with the
launcher — see [[Input]]. The cursor's shape *is* in it, because that goes the other
way: the toolkit asks for an I-beam, and the backend shows one.

---

## The viewport

You design against one fixed size and the viewport scales it:

```kotlin
Viewport(
    design = Size(1280f, 720f),
    physical = Size(backBufferWidth.toFloat(), backBufferHeight.toFloat()),
    policy = ScalePolicy.Fit,
    safeArea = Padding(bottom = keyboard.heightPixels),
)
```

| policy | |
|---|---|
| `Fit` | scale until it just fits, keeping the shape. Bars on two sides. The safe default. |
| `Fill` | scale until it covers. Nothing is barred; edges are lost. |
| `Stretch` | each axis on its own. Nothing lost, nothing barred, circles go oval. |

`safeArea` is what you take off for a notch, a rounded corner, a gesture bar, or a
keyboard covering the bottom third of the screen.

Hand the same viewport to your pointer translator, or clicks land in the wrong
place on a letterboxed screen.

---

## Android

LibGDX runs the game. Two things it cannot answer, because they are window
questions rather than engine ones: **how tall is the keyboard**, and **did the
player dismiss it themselves**. Both matter — a field near the bottom of the screen
ends up behind the keyboard without the first, and a swiped-away keyboard leaves a
caret blinking with nothing to type into without the second.

```kotlin
val keyboard = AndroidSoftKeyboard(activity.window, view) { focus.clearFocus() }

// …then every frame
Viewport(design, physical, Fit, safeArea = Padding(bottom = keyboard.heightPixels))
```

And the phone's own feel for a tap, which needs no permission and follows the
player's touch-feedback setting:

```kotlin
ProvideHaptics(AndroidHaptics(view)) { Hud() }
```

That is the whole of `composegl-android`. `composegl-robovm` has the same three for
an iPhone: `UiKitSoftKeyboard`, `UiKitTextInput` and `UiKitHaptics`.

---

## Writing your own

**The library draws; a backend is a thin wrapper.** `composegl-render` holds the one
renderer: the canvas, the batch, the shape shader, layers, render targets, shader
effects, the glyph atlas, fallback fonts, emoji and draw-call tracing. On OpenGL a
backend supplies four small things and nothing that draws:

| piece | what it is | lwjgl3's |
|---|---|---|
| a `Gl` binding | about seventy calls, each a one-liner onto your GL (`glDrawElements`, `glUniform4f`, …) | `LwjglGl.kt` |
| a `GlyphRasteriser` | one font at one size, one glyph at a time: metrics, advance, a coverage or colour bitmap | `StbRasteriser` in `StbFonts.kt` |
| a `TextureResolver` | your texture type as a GL name and texture coordinates | `GlTexture.Resolver` |
| the engine handoff | what `raw { }` hands a game, and `HostState.Leave` or `Restore` | `GlCanvas` |

```kotlin
class MyCanvas(fonts: MyFonts) : RenderCanvas(GlDevice(MyGl, HostState.Leave), fonts, MyTextures) {
    override fun handOver(projection: FloatArray, viewport: Viewport): Any = MyFrame(projection, viewport)
}
class MyFonts : AtlasFonts(MyRasteriser, MyImageDecoder)
```

Pick `HostState.Leave` when your engine sets the GL state it needs before it draws;
`Restore` when it caches GL state and believes the cache (KorGE, three.js). If your
context can be lost (Android, the browser), call `canvas.contextLost()` when it is.

Then implement `UiBackend` round them — `canvas`, `fonts`, `clipboard`,
`softKeyboard`, `textures`, and optionally `cursor` and `haptics` — and translate your
platform's input into the four [[Input|`InputSink`]] calls.

`RendererConfinementCheck` keeps it thin. One line in your build file,
`confineRenderer("MyGl.kt")`, fails the build if shader text appears anywhere in your
module, if a draw, shader, blend or framebuffer call appears outside the binding file,
or if the binding grows past 400 lines.

A graphics API that is not OpenGL implements `GpuDevice` instead of `Gl` — about
sixteen members — and ships its own port of the shape shader. Nothing above the device
knows OpenGL exists.

### If you really must draw yourself

`UiCanvas` is the interface `RenderCanvas` implements, and it is deliberately short: `rect`,
`border`, `shadow`, `text`, `image`, `fan`, a clip stack, an alpha stack, a blend stack
and `raw`. The richer calls — `layer`, `cutLayer`, `textRing`, turned and tilted
layers, `drawCalls`, `traceDrawCalls` — each have a capability flag and a default that
degrades rather than fails. **The rule for `raw` and for `layer`: leave your own state
as you found it.**

### The escape hatch, in detail

`raw` is the one optional thing with no sensible degrade: the toolkit cannot
approximate a block it knows nothing about. So it is a question instead.

- `handsOverRaw` — whether there is a backend object to hand over at all. Answer
  honestly and a widget can pick a composed fallback at construction, instead of
  finding out at the first frame. This is the one capability that can vary *within* a
  backend: `GdxCanvas` answers yes or no depending on whether a `SpriteBatch` was
  passed to its constructor.
- `rawX(x)` / `rawY(y)` — a coordinate this interface would take, as the one your
  drawing object wants. Once the object is handed over the block is writing your
  coordinates, so only you can convert them. Both default to the identity; override
  `rawY` if you measure y upwards, as both backends here do.
- `raw(destination) { }` — the same hatch with the origin moved to the node, so a
  block that draws at `0, 0, width, height` fills it. Say so in `movesRawOrigin`.
  Override both or neither: an unmoved origin is not a lesser picture, it is the same
  drawing in the wrong place.

`composegl-lwjgl3` is about 1,800 lines all in, and most of that is the window and its
input rather than drawing.

---

## Testing a backend

`composegl-testing` draws a set of scenes and compares them to per-backend golden
PNGs. Add yours and the same scenes prove your backend agrees with the other two:

```
COMPOSEGL_UPDATE_GOLDENS=1 ./gradlew :your-backend:test
```

The comparison has tolerance in it — a channel or two, on under 1% of pixels —
because two glyph rasterisers will never agree exactly, and nothing else about a
frame should differ at all.

---

## What next

- **[[Your first screen]]** — the whole of a LibGDX integration in one file
- **[[Input]]** — the four calls your translator has to make
- **[[Testing]]** — including how to test without a backend at all
