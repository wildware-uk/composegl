# Backends

A backend is the bit that turns "draw a rounded box here" into actual OpenGL, and
"how wide is this word?" into a number.

Two ship. Neither is required: the toolkit names no engine anywhere.

| module | what it is |
|---|---|
| `composegl-gdx` | LibGDX. Desktop, Android, iOS. The one to ship a game on. |
| `composegl-lwjgl3` | Raw OpenGL and stb_truetype. Desktop only. |
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
that came out wrong or as code that will not compile. Both backends' golden images
are checked against each other in CI.

---

## Setting one up

LibGDX:

```kotlin
val fonts = GdxFonts()
fonts.registerTrueType("default", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(13, 16, 20))

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

Three backends implement it, so the swap is real:

| | |
|---|---|
| `GdxBackend(fonts, spriteBatch)` | LibGDX |
| `Lwjgl3Backend(window, fonts)` | raw OpenGL |
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
- `drawCalls` — how many times you handed work to the GPU this frame. Return -1 and
  the frame budget shows nothing for it.
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
