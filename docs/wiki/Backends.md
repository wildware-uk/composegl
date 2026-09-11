# Backends

A backend is the bit that turns "draw a rounded box here" into actual OpenGL, and
"how wide is this word?" into a number.

Two ship. Neither is required: the toolkit names no engine anywhere.

| module | what it is |
|---|---|
| `composegl-gdx` | LibGDX. Desktop, Android, iOS. The one to ship a game on. |
| `composegl-lwjgl3` | Raw OpenGL and stb_truetype. Desktop only. |
| `composegl-android` | Not a backend — the two things about a phone LibGDX cannot answer. |

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
val canvas = GdxCanvas(sprites, fonts.atlas)

val host = UiHost()
host.setContent { ProvideFonts(fonts) { ProvideSkin(skin.skin) { Hud(state) } } }
```

LWJGL3:

```kotlin
val backend = Lwjgl3Backend(window, StbFonts().apply { registerTrueType(…) })
val canvas = backend.canvas
```

Then the same four lines each frame, whichever you chose:

```kotlin
host.frame(System.nanoTime())
MeasurePass().run(host.root, viewport)
canvas.begin(viewport)
DrawPass(canvas).draw(host.root)
canvas.end()
```

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

That is the whole of `composegl-android`.

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
}
```

The real work is `UiCanvas`, and it is deliberately short — about ten calls:
`rect`, `border`, `shadow`, `text`, `image`, `fan`, a clip stack, an alpha stack,
and `raw`.

A long drawing interface is a long list of things every future backend has to
reimplement, and most of what an interface draws is a rounded rectangle with some
text on it. Anything richer goes through `raw { }`, which hands your own drawing
object back to whoever asked for it.

Three optional extras, each of which degrades rather than fails:

- `layer(bounds) { }` and `drawLayer(...)` — offscreen drawing, which is what
  [[Shaders|effects]] are built on. Return null and effects simply do not happen.
- `drawCalls` — how many times you handed work to the GPU this frame. Return -1 and
  the frame budget shows nothing for it.
- Everything in `raw { }` — your business entirely.

**The rule for `raw` and for `layer`: leave your own state as you found it.**

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
