# ComposeGL v2 — the Compose runtime, drawing itself

Date: 2026-09-09
Status: **proposed.** Nothing below is built. Supersedes `2026-09-08-composegl-design.md`.
Evidence: `docs/superpowers/spikes/s6-runtime-ui.md`.

## 1. What this is

A user-interface toolkit for games, written in Kotlin, that uses Jetpack Compose's *runtime* — the
compiler plugin, the state system, recomposition — and nothing else from Compose.

Plain version: Compose is the brain that decides what the interface should be. Everything from the
neck down is ours. Version 1 of this project borrowed Google's whole body (Compose UI) and Skia's
hands, which is why it only ran on desktop. This version keeps only the brain, and we build the
body out of what LibGDX already gives every platform: a sprite batch and a font.

S6 proved the part that could have killed it: recomposition still skips work. A still interface
redrew 4 times in 119 frames; an animation redrew 181 times in 181 frames and would stop dead when
switched off.

## 2. The one thing this buys

It runs everywhere LibGDX runs. Desktop, Android, iOS, with **no native library to build or
publish**. That was impossible in v1 and no amount of work would have made it possible, because
skiko has no published Android or iOS binary and building one is Google's job, not ours.

Everything else in this document is the price of that.

## 3. Decisions, and why

| Decision | Why |
|---|---|
| Only `androidx.compose.runtime`, forever | It is pure JVM bytecode, 1.9 MB, no native code, all public API. The moment anything else creeps in, the platform story dies. Enforced by a dependency check in CI. |
| Our own `Modifier`, deliberately imitating Compose's | Everyone who would use this already knows `Modifier.padding(8.dp).background(...)`. A chain of elements we interpret is about a hundred lines and makes the whole toolkit feel familiar instead of foreign. |
| Our own layout, copying Compose's model exactly | Constraints in, a size and a place-children function out. It is the right model, it is well documented by Google for free, and it makes `Row`, `Column`, `Box`, weights and alignment fall out rather than be special-cased. Roughly 250 lines. |
| Text is LibGDX `BitmapFont` + FreeType | Already solved, already on every platform, already handles wrapping and measurement. It is the single biggest thing we get for nothing. |
| Drawing goes through one `UiCanvas` interface | Engine-agnostic toolkit, engine-specific renderer. Same seam as v1's core/adapter split, which worked. |
| Animation is ours | Checked: both `androidx.compose.animation:animation-core` and the JetBrains equivalent pull in the whole of Compose UI. So the frame clock is ours and so is `Animatable`. It is small — easing curves and a spring are a few hundred lines. |
| No Skia, no AWT, no reflection | The clipboard becomes `Gdx.app.clipboard`, which is one line and works on Android. v1 needed a reflective AWT bridge for the same job. |

## 4. Non-goals

- **IME preediting** (Chinese, Japanese, Korean candidate windows). Committed text works; the
  floating candidate box does not. Called out because it is the honest limit of v1 text input, and
  because pretending otherwise would be worse than saying it.
- Accessibility and screen readers.
- Vector paths, arbitrary gradients, real blur. Rounded rectangles, borders and soft shadows are
  covered by one shader; anything beyond that is the game's own shader.
- Rich text, bidirectional text, right-to-left layout.
- Web (GWT/TeaVM).

## 5. Modules

```
composegl-ui     the toolkit. Pure Kotlin/JVM. compose.runtime + coroutines. Nothing else.
composegl-gdx    the LibGDX renderer and input adapter.
demo-snake       ported from v1.
demo-showcase    ported from v1.
```

Two modules, not four. `composegl-ui` knows nothing about OpenGL or LibGDX and so can be tested
headlessly and completely — which is the structural answer to "this needs way more tests". In v1,
anything touching a pixel needed a GPU. Here, only the renderer does.

## 6. The pieces

### 6.1 Node and applier

One node type, holding a `Modifier` chain, a measured size, a position, and children. `UiApplier`
extends `AbstractApplier` and does inserts, moves and removals. Both already exist in the spike and
both are small enough to be finished, not prototyped.

Change tracking: the applier marks structural change, the node marks modifier change. Same four
lines as the spike, which measured correctly.

### 6.2 Modifier

A linked chain of elements, interpreted by layout and drawing. Version 1 set:

`size` `width` `height` `fillMaxWidth` `fillMaxHeight` `fillMaxSize` `padding` `offset`
`background` `border` `clip` `alpha` `weight` `align` `clickable` `focusable` `onKeyEvent`
`drawBehind` `drawInFront` `pointerInput`

`drawBehind` / `drawInFront` hand out the raw `UiCanvas`, and `pointerInput` hands out raw pointer
events. Those two are the escape hatch: anything the toolkit does not do, a game can do itself
without waiting for us.

### 6.3 Layout

Compose's model, reimplemented:

```kotlin
fun interface MeasurePolicy {
    fun measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult
}
```

`Row`, `Column`, `Box` and `Spacer` are measure policies over that, and so is anything a game
writes with the public `Layout {}` composable. Alignment and weights are part of `Row`/`Column`,
not bolted on.

### 6.4 Text

The toolkit declares what it needs:

```kotlin
interface FontMetrics {
    fun measure(text: String, style: TextStyle, maxWidth: Float): TextLayout
}
```

The renderer supplies it from a `BitmapFont`. Fonts are named in a `FontFamily` the game registers
at startup, so the toolkit never touches a file. Wrapping, line breaking and alignment come from
`GlyphLayout`.

### 6.5 Drawing

```kotlin
interface UiCanvas {
    fun rect(x, y, w, h, colour, corner: Float = 0f)
    fun border(x, y, w, h, colour, width: Float, corner: Float = 0f)
    fun shadow(x, y, w, h, colour, spread: Float, corner: Float)
    fun text(layout: TextLayout, x, y, colour)
    fun image(handle: Any, x, y, w, h, tint)
    fun pushClip(x, y, w, h); fun popClip()
    fun pushAlpha(a: Float); fun popAlpha()
    fun raw(block: (Any) -> Unit)     // the engine's own batch, for anything we do not cover
}
```

The LibGDX implementation is one `SpriteBatch`, a 1×1 white texture, a scissor stack for clipping,
and one small shader that does rounded corners, borders and soft shadows in a single quad. That
shader is the only clever part of the renderer and it covers the overwhelming majority of what game
interfaces actually draw.

### 6.6 Input

Pointer events hit-test the tree, deepest first. A press captures the pointer so a drag that leaves
a node still belongs to it — the bug you found in the in-world panel was exactly this, and doing it
properly once here prevents the whole family.

Focus is a flat, ordered list of focusable nodes with Tab and Shift-Tab. Key events go to the
focused node and bubble to its ancestors.

### 6.7 Text input — the big one

This is the largest piece of work in the project and where the bugs will be, so it gets designed as
two layers with the hard part kept pure.

**The pure layer.** State plus commands, no engine, no toolkit:

```kotlin
data class TextFieldValue(val text: String, val selection: IntRange, val composition: IntRange?)

sealed interface EditCommand   // Insert, DeleteBackward, DeleteForward, MoveCursor, Select, ...
fun TextFieldValue.apply(command: EditCommand): TextFieldValue
```

Every rule about cursors, selection, word boundaries and deletion lives in pure functions over
that. It is exhaustively testable with no window, no GPU and no Compose, and that is the point: the
two bugs you found in v1 were both in the untested seam between a toolkit and a keyboard.

**The platform layer.** Turning LibGDX input into those commands. This is where v1's two bugs
actually lived and both lessons are already learned and written down:

- control characters arrive through `keyTyped` and must not be inserted as text;
- a held key repeats through `keyTyped` only — there is never a second `keyDown`.

That knowledge ports across as a test suite, not as code: `LibGdxEventSequenceTest` replays real
recorded toolkit sequences (tap, hold, repeat, shift, cancelled drag) and it comes with us.

**Clipboard** is `Gdx.app.clipboard`. **Android soft keyboard** is `Gdx.input.setOnscreenKeyboardVisible`,
with `Gdx.input.getTextInput` as the fallback if in-place editing on Android proves worse than a
native dialogue. **IME preedit** is out of scope, stated in the non-goals, and the `composition`
field above is there so adding it later is not a rewrite.

### 6.8 Animation

`Animatable`, `animateFloatAsState`, `animateColorAsState`, a handful of easings and a spring.
Driven by the same `BroadcastFrameClock` the recomposer uses, so an animation redraws every frame
while it runs and costs nothing the moment it settles. That behaviour is already measured.

## 7. Testing

The reason to be pleased about this design rather than merely resigned to it.

| Layer | How it is tested | Needs a GPU |
|---|---|---|
| State, commands, text editing | Plain unit tests, exhaustive | no |
| Layout | Measure a tree, assert geometry | no |
| Recomposition and redraw counts | Drive frames, count | no |
| Input routing, focus, hit testing | Replay recorded event sequences | no |
| Drawing | Screenshot comparison under Xvfb | yes |

In v1, everything above the line needed Skia and therefore a GL context. Here, only the last row
does. That is what makes "way more tests" achievable rather than aspirational.

## 8. Order of work

1. **Toolkit core.** Node, applier, modifier, constraints layout, `Row`/`Column`/`Box`/`Spacer`,
   `Text`, `Image`, `clickable`, focus. LibGDX renderer with the rounded-rect shader.
2. **Snake ported.** A real interface, end to end, proving the toolkit on something that already
   exists so the comparison is honest.
3. **Text input.** Pure layer first, with its tests, then the LibGDX layer, then the widget.
4. **Scrolling and lists, animation, showcase ported.**
5. **Android.** The proof of the whole idea.
6. **iOS.** Needs a Mac; not this machine.

Steps 1 and 2 are the ones that decide whether this is pleasant to write interfaces in. Nothing
after them is worth starting until that is answered by using it.

## 9. What happens to v1

`composegl-core`, `composegl-libgdx`, `composegl-lwjgl3` and both demos are the Skia product. The
owner's decision is that it is not the product any more. Recommendation: tag the current commit
`skia-final` so it stays recoverable, then delete those modules in one commit once the new toolkit
can draw a button. **Not done without an explicit yes.**

## 10. Honest risks

- **It is a lot of code.** v1 was 1,348 lines of core because Google wrote the interface toolkit.
  This is the interface toolkit. Expect several thousand lines before it is pleasant to use.
- **Text input is genuinely hard**, and the version that ships first will not handle every keyboard
  in the world.
- **Nothing here has run on a phone.** Every claim about Android and iOS is a claim about LibGDX's
  platform support, which is real and well proven, but this toolkit has not been on one.
- **Everything so far is Mesa llvmpipe.** No real GPU, no macOS, no Windows.
