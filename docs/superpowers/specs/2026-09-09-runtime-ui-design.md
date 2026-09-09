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

## 3. What "suits games better" means

The platform story is the reason this became necessary. This section is the reason it is worth
doing. We are no longer reimplementing Material 3 badly — we are building the interface toolkit
games have always needed and never had, because Compose UI, and every other general-purpose
toolkit, is built for documents and forms.

Concretely, these are first-class here and absent or painful everywhere else:

**Skins, not styles.** A game's interface is art. Panels are nine-slice sprites from a texture
atlas, buttons have pressed and hover frames, fonts are bitmap fonts an artist made. The toolkit
takes a `Skin` — an atlas plus a description of which region draws what — so changing how the
interface looks is a change to an atlas, not a change to Kotlin. Compose UI has no concept of this
at all; scene2d.ui had it and it is one of the few things people genuinely miss about scene2d.

**Gamepad navigation as the primary focus model.** Not Tab order with a controller bolted on.
Directional navigation between focusables, an explicit "what is up from here" override when
geometry gets it wrong, an initial focus, and focus that survives a screen change. This is the
single biggest thing Compose UI is bad at for games and the reason console interfaces get written
by hand.

**Virtual resolution, not `dp`.** A game interface is designed at one size and scaled to the
window. Units are virtual pixels against a design resolution, with a scaling policy the game picks
(fit, fill, stretch, integer). Safe-area insets for notches and TV overscan are part of the same
mechanism.

**Several clocks.** A pause menu must animate while the game is frozen. Animations are attached to
a named clock, the game decides which clocks advance, and pausing the world does not pause the
menu. Every general toolkit assumes one wall clock.

**Game primitives in the box.** Health and stamina bars with damage trails, radial cooldowns,
ammo counters, damage numbers that float and fade, reticles, tooltips, hotbars, minimap frames,
dialogue boxes with typewriter reveal. The showcase already proved every one of these is worth
having; in v1 each was assembled by hand from Material parts that resisted.

**Every widget is a texture and a shader you can replace.** `drawBehind`, `drawInFront` and a
`raw {}` hatch onto the engine's own batch, on any node. A glow, a scanline, a dissolve, a
distortion — the toolkit gets out of the way rather than becoming the ceiling.

**In-world interfaces are not a special case.** A panel rendered onto a quad in the 3D scene is the
same tree, laid out the same way, with pointer events fed from a ray hit. v1 could do this and it
was the best thing about it; here it is designed in rather than discovered.

**Frame budget is visible.** Layout and draw report their cost per frame, because a game that
misses 16 ms because of its inventory screen needs to know which part.

Each of these is small on its own. Together they are the difference between "Compose, in a game"
and "a toolkit for games that happens to be written in Compose".

## 4. Decisions, and why

| Decision | Why |
|---|---|
| Only `androidx.compose.runtime`, forever | It is pure JVM bytecode, 1.9 MB, no native code, all public API. The moment anything else creeps in, the platform story dies. Enforced by a dependency check in CI. |
| Our own `Modifier`, deliberately imitating Compose's | Everyone who would use this already knows `Modifier.padding(8.dp).background(...)`. A chain of elements we interpret is about a hundred lines and makes the whole toolkit feel familiar instead of foreign. |
| Our own layout, copying Compose's model exactly | Constraints in, a size and a place-children function out. It is the right model, it is well documented by Google for free, and it makes `Row`, `Column`, `Box`, weights and alignment fall out rather than be special-cased. Roughly 250 lines. |
| Text is LibGDX `BitmapFont` + FreeType | Already solved, already on every platform, already handles wrapping and measurement. It is the single biggest thing we get for nothing. |
| Drawing goes through one `UiCanvas` interface | Engine-agnostic toolkit, engine-specific renderer. Same seam as v1's core/adapter split, which worked. |
| Animation is ours | Checked: both `androidx.compose.animation:animation-core` and the JetBrains equivalent pull in the whole of Compose UI. So the frame clock is ours and so is `Animatable`. It is small — easing curves and a spring are a few hundred lines. |
| Skin-driven appearance from a texture atlas | A game's interface is art an artist iterates on. If changing a panel means editing Kotlin, the artist cannot do their job. |
| Gamepad direction navigation is the focus model, keyboard Tab is a case of it | Doing it the other way round is how every toolkit ends up with unusable console interfaces. |
| Virtual design resolution, not `dp` | Games are designed at a resolution and scaled. `dp` answers a question games do not ask. |
| Animations belong to a named clock the game advances | A pause menu has to animate while the world is stopped. |
| No Skia, no AWT, no reflection | The clipboard becomes `Gdx.app.clipboard`, which is one line and works on Android. v1 needed a reflective AWT bridge for the same job. |

## 5. Non-goals

- **IME preediting** (Chinese, Japanese, Korean candidate windows). Committed text works; the
  floating candidate box does not. Called out because it is the honest limit of v1 text input, and
  because pretending otherwise would be worse than saying it.
- Accessibility and screen readers.
- Vector paths, arbitrary gradients, real blur. Rounded rectangles, borders and soft shadows are
  covered by one shader; anything beyond that is the game's own shader.
- Rich text, bidirectional text, right-to-left layout.
- Web (GWT/TeaVM).

## 6. Modules

```
composegl-ui       the toolkit. Pure Kotlin/JVM. compose.runtime + coroutines. Nothing else, ever.
composegl-gdx      the LibGDX backend: renderer, input translation, fonts, clipboard, keyboard.
composegl-lwjgl3   a second backend on raw GLFW and OpenGL. Desktop only. Its job is to keep the
                   seam honest, not to be used.
demo-snake         ported from v1.
demo-showcase      ported from v1.
```

`composegl-ui` does not depend on LibGDX, or on OpenGL, or on any engine. It defines what it needs
from the outside world and a **backend** supplies it:

```kotlin
interface UiBackend {
    val canvas: UiCanvas
    val fonts: FontProvider          // measurement and glyphs
    val clipboard: Clipboard
    val softKeyboard: SoftKeyboard   // show, hide; a no-op on desktop
    fun texture(id: String): TextureHandle
}
```

Input goes the same way. The toolkit defines `PointerEvent`, `KeyEvent`, `TextEvent`,
`GamepadEvent`, its own key codes and its own modifiers; a backend translates whatever its platform
gives it and pushes events in. No LibGDX type, no GLFW constant and no AWT type appears anywhere in
`composegl-ui`, and CI fails the build if one does.

The whole toolkit therefore compiles and tests with LibGDX absent from the classpath — a full
interaction, press through drag through typing through pad navigation, driven by hand-written
events with no engine present. That is the structural answer to "this needs way more tests": in v1
anything touching a pixel needed Skia and a GPU; here only a backend does.

**Why LibGDX is still the reference backend.** Being able to leave is not a reason to. LibGDX
already ships the window, the GL context, input, audio, asset loading, the soft keyboard and the
FreeType natives — for desktop, Android and iOS, today, built and published by somebody else.
Replacing it means writing and maintaining that per platform, which is precisely the trap that
killed the Skia version. The second backend exists to prove we *could*, and to catch assumptions
leaking into the toolkit, not because we should.

## 7. The pieces

### 7.1 Node and applier

One node type, holding a `Modifier` chain, a measured size, a position, and children. `UiApplier`
extends `AbstractApplier` and does inserts, moves and removals. Both already exist in the spike and
both are small enough to be finished, not prototyped.

Change tracking: the applier marks structural change, the node marks modifier change. Same four
lines as the spike, which measured correctly.

### 7.2 Modifier

A linked chain of elements, interpreted by layout and drawing. Version 1 set:

`size` `width` `height` `fillMaxWidth` `fillMaxHeight` `fillMaxSize` `padding` `offset`
`background` `border` `clip` `alpha` `rotate` `scale` `weight` `align` `clickable` `hoverable`
`focusable` `focusOrder` `onKeyEvent` `pointerInput` `drawBehind` `drawInFront` `shader`
`ninePatch` `tooltip`

`ninePatch` draws a skin region rather than a flat colour, `shader` swaps the program used for that
node's own drawing, and `focusOrder` is the manual override for when directional navigation guesses
wrong.

`drawBehind` / `drawInFront` hand out the raw `UiCanvas`, and `pointerInput` hands out raw pointer
events. Those two are the escape hatch: anything the toolkit does not do, a game can do itself
without waiting for us.

### 7.3 Layout

Compose's model, reimplemented:

```kotlin
fun interface MeasurePolicy {
    fun measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult
}
```

`Row`, `Column`, `Box` and `Spacer` are measure policies over that, and so is anything a game
writes with the public `Layout {}` composable. Alignment and weights are part of `Row`/`Column`,
not bolted on.

### 7.4 Text

The toolkit declares what it needs:

```kotlin
interface FontMetrics {
    fun measure(text: String, style: TextStyle, maxWidth: Float): TextLayout
}
```

The renderer supplies it from a `BitmapFont`. Fonts are named in a `FontFamily` the game registers
at startup, so the toolkit never touches a file. Wrapping, line breaking and alignment come from
`GlyphLayout`.

### 7.5 Drawing

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

### 7.6 Input

Pointer events hit-test the tree, deepest first. A press captures the pointer so a drag that leaves
a node still belongs to it — the bug you found in the in-world panel was exactly this, and doing it
properly once here prevents the whole family.

**Focus is built for a controller first.** Focusable nodes know where they are on screen, so
pressing a direction moves focus to the nearest focusable in that direction — scored by angle and
distance, the way console interfaces actually work. `Modifier.focusOrder(up = ..., left = ...)`
overrides it when geometry lies, which it does around wrapped grids and gaps. Tab and Shift-Tab
are then just "next" and "previous" over the same set, not a separate mechanism.

Focus also survives a screen change, and a screen declares its initial focus, because a menu that
opens with nothing selected is unusable on a pad.

**Every input source at once.** Mouse, touch, keyboard and gamepad are all live; the toolkit tracks
which one was used last so the interface can show or hide a cursor, swap button prompts between
keyboard glyphs and pad glyphs, and stop drawing a focus ring while somebody is using a mouse.

### 7.7 Text input — the big one

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

### 7.8 Animation, and clocks

`Animatable`, `animateFloatAsState`, `animateColorAsState`, a handful of easings and a spring.

The part that is not a copy of Compose: **animations name a clock.** The game advances clocks
itself, so freezing the world does not freeze the pause menu that is sitting on top of it. Two
clocks are there by default — `Clock.Ui`, which always runs, and `Clock.World`, which the game
stops — and a game can add its own.

An animation that is settled subscribes to no clock and therefore costs nothing, which is the
measured property from S6 and must stay true.

### 7.9 Skin

Appearance lives in data, not in Kotlin:

```kotlin
class Skin(atlas: TextureAtlas, fonts: Map<String, BitmapFont>, styles: Map<String, Style>)
```

A `Style` names the regions a widget draws in each state — normal, hovered, pressed, disabled,
focused — plus its nine-slice insets, padding, font and colours. Widgets read their style from the
skin by name through a composition local, so `Button("PLAY")` looks like the game without the game
saying so.

This is the piece that makes the toolkit usable by somebody who is not a programmer, and it is the
main thing scene2d.ui had that Compose UI does not.

### 7.10 Units and the viewport

A game interface is designed at a resolution and scaled to whatever window it lands in. There is no
`dp`. The game declares a design size and a policy — fit, fill, stretch, or integer multiples for
pixel art — and everything in the tree is in those virtual pixels. Safe-area insets, for phone
notches and television overscan, are subtracted from the root before anything is laid out.

### 7.11 In-world interfaces

The same tree, laid out the same way, drawn to a framebuffer the game maps onto a quad in its 3D
scene. Pointer events come from the game's own ray hit rather than the mouse position, through the
same entry point. v1 could do this and it was its best trick; the difference is that here it is one
of the designed cases rather than something discovered afterwards — including the release-tracking
rule that the in-world panel bug taught us.

### 7.12 Widgets

Two tiers, and the second is the reason to build this at all.

**Ordinary:** `Text` `Image` `Button` `IconButton` `Toggle` `Checkbox` `Slider` `Row` `Column`
`Box` `Stack` `Spacer` `ScrollArea` `LazyColumn` `Panel` `Dialog` `Tabs` `TextField`.

**Game:** `Bar` (health, stamina, with a damage trail that drains behind the real value),
`RadialCooldown`, `Hotbar`, `DamageNumber`, `Reticle`, `Tooltip`, `PromptGlyph` (draws the right
button icon for whatever the player is currently holding), `Typewriter` (dialogue revealed a
character at a time), `MinimapFrame`, `Notification` (a queue that pops, holds and slides away).

Every one of the second tier came out of the showcase, where each was assembled by hand from
Material parts that fought back.

### 7.13 Frame budget

Layout and draw record their own cost per frame and expose it, because when a game misses 16 ms
because of its inventory screen, the first question is which half.

## 8. Testing

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

## 9. Order of work

1. **Toolkit core.** Node, applier, modifier, constraints layout, `Row`/`Column`/`Box`/`Spacer`,
   `Text`, `Image`, `clickable`, directional focus, the viewport and units, and the skin. LibGDX
   renderer with the rounded-rect shader and nine-slice.
2. **Snake ported.** A real interface, end to end, proving the toolkit on something that already
   exists so the comparison is honest.
3. **Text input.** Pure layer first, with its tests, then the LibGDX layer, then the widget.
4. **Scrolling and lists, animation and clocks, the game widget tier, showcase ported.**
5. **Android.** The proof of the whole idea.
6. **iOS.** Needs a Mac; not this machine.

Steps 1 and 2 are the ones that decide whether this is pleasant to write interfaces in. Nothing
after them is worth starting until that is answered by using it.

## 10. What happens to v1

`composegl-core`, `composegl-libgdx`, `composegl-lwjgl3` and both demos are the Skia product. The
owner's decision is that it is not the product any more. Recommendation: tag the current commit
`skia-final` so it stays recoverable, then delete those modules in one commit once the new toolkit
can draw a button. **Not done without an explicit yes.**

## 11. Honest risks

- **It is a lot of code.** v1 was 1,348 lines of core because Google wrote the interface toolkit.
  This is the interface toolkit. Expect several thousand lines before it is pleasant to use, and
  section 3 is a list of ambitions, not a list of things that arrive in week one.
- **Text input is genuinely hard**, and the version that ships first will not handle every keyboard
  in the world.
- **Nothing here has run on a phone.** Every claim about Android and iOS is a claim about LibGDX's
  platform support, which is real and well proven, but this toolkit has not been on one.
- **Everything so far is Mesa llvmpipe.** No real GPU, no macOS, no Windows.
