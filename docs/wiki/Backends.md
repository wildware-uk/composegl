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

The LWJGL3 backend exists to keep the others honest. It is written against the same
interface, shares **no code** with the LibGDX one, and draws the same test scenes —
so anything the toolkit quietly assumes about LibGDX shows up here as a picture
that came out wrong or as code that will not compile.

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

Implement `UiBackend`:

```kotlin
interface UiBackend {
    val canvas: UiCanvas
    val fonts: FontProvider
    val clipboard: Clipboard
    val softKeyboard: SoftKeyboard
    val textures: TextureSource
    val cursor: SystemCursor          // optional: defaults to one that does nothing
    val haptics: Haptics              // optional: defaults to Haptics.None
}
```

The real work is `UiCanvas`, and it is deliberately short — about ten calls:
`rect`, `border`, `shadow`, `text`, `image`, `fan`, a clip stack, an alpha stack,
a blend stack, and `raw`.

A long drawing interface is a long list of things every future backend has to
reimplement, and most of what an interface draws is a rounded rectangle with some
text on it. Anything richer goes through `raw { }`, which hands your own drawing
object back to whoever asked for it.

The optional extras, each of which degrades rather than fails:

- `cursor` — `set(PointerIcon)`, the mouse cursor's shape. Leave it and the shape
  never changes; a phone has nothing to change anyway. Show the arrow for a shape you
  have no picture for.
- `layer(bounds) { }` and `drawLayer(...)` — offscreen drawing, which is what
  [[Shaders|effects]] are built on. Return null and effects simply do not happen.
- `cutLayer(layer, destination, outline)` — a layer put down through a convex outline,
  which is what `Modifier.clipShape` is built on. `featherOutline` cuts the outline
  into quads with a one-pixel soft edge for any batch that draws quads. Leave it and
  shaped clips fall back to the node's rectangle; say so in `cutsLayers`.
- `textRing(layout, x, y, colour)` — one copy stamped for a text outline's ring.
  Leave it and it is plain `text`, which is right for letters. If you draw some
  glyphs as pictures in their own colours (emoji), override it to leave those out,
  or the ring is eight emoji smeared round the real one.
- `drawCalls` — how many times you handed work to the GPU this frame. Return -1 and
  the frame budget shows nothing for it.
- `traceDrawCalls(trace)` — keep the trace, and each time you hand work to the GPU call
  `trace.record(BatchBreak.Texture)` (or `Blend`, `Clip`, `Layer`, `Shader`, `Raw`,
  `Full`, and `End` for the frame's last). Only when something was queued: an empty flush
  is not a draw call. The trace already knows which node is drawing. Leave it and the
  overlay lists no culprits; say so in `tracesDrawCalls`.
- `image(texture, destination, degrees, …)` — a turned picture. Leave it and the
  default draws it upright; say so in `rotatesImages`.
- `drawLayer(layer, destination, mirrorX, mirrorY)` — a flipped picture, for
  `Modifier.mirror`. Leave it and the default draws it the right way round; say so
  in `mirrorsLayers`, and the toolkit skips the picture and keeps clicks unflipped.
- `drawLayer(layer, destination, degrees, …)` and `drawLayerOnto(layer, destination,
  corners)` — a layer turned, or put down on four corners, which is what
  `Modifier.rotate` and `Modifier.skew` are composited with. Leave them and the
  default puts the picture down upright in `destination`; say so in `turnsLayers`
  and `drawsLayersOnto`.
- `drawLayer(layer, destination, transform)` — a picture through a `Matrix4`, for
  `Modifier.rotate3d`. Project each corner of `destination` with
  `transform.project(x, y, into, at)` and hand the GPU the undivided x, y and w, so it
  divides per pixel and the picture does not bend. Leave it and the default draws the
  picture flat in `destination`; say so in `tiltsLayers`.
- `pushBlend(mode)` / `popBlend()` — additive blending, for light. Leave them and
  everything paints the ordinary way; say so in `supports(mode)`.
- Everything in `raw { }` — your business entirely.

**The rule for `raw` and for `layer`: leave your own state as you found it.**

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

Then translate your platform's input into the four [[Input|`InputSink`]] calls, and
you are done. `composegl-lwjgl3` is about 2,700 lines all in, and it is a fair
guide to the size of the job.

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
