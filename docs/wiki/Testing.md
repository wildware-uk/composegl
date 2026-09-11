# Testing

The toolkit runs with no window, no GPU and no engine, which means your interface
can be tested the way the rest of your game is: fast, in CI, with no screenshots
and no flakiness.

---

## A screen with no screen

```kotlin
val host = UiHost()
host.setContent { ProvideFonts(MonospaceFontProvider()) { Hud(state) } }

host.frame(clock)                                        // recompose
MeasurePass().run(host.root, Constraints.atMost(1280f, 720f))   // lay out
DrawPass(canvas).draw(host.root)                         // draw
```

`MonospaceFontProvider` is not a real font, and that is the point: every character
is exactly 0.6 of the text size, so an expected width in a test can be worked out
on paper. A test asserting a button is 84 wide should be asserting the layout rule,
not the shape of a letter in a typeface.

---

## What was drawn

`RecordingCanvas` writes down the calls instead of making them:

```kotlin
val canvas = RecordingCanvas()
DrawPass(canvas).draw(host.root)

val bars = canvas.calls.filterIsInstance<DrawCall.Rectangle>()
assertEquals(Colour.rgb(0xE5484D), bars.first().colour)
```

The calls are a sealed hierarchy — `Rectangle`, `Border`, `Shadow`, `Text`, `Image`,
`Fan`, `Layer`, `Raw` — all data classes, so you can assert on exactly what you care
about.

Two things worth knowing:

```kotlin
canvas.assertBalanced()      // every clip and alpha pushed was popped
val frame = canvas.calls.toList()   // `calls` is a live view — copy it to compare two frames
```

An imbalance means a widget leaked state onto whatever was drawn after it. On a real
backend that is a scissor left switched on rather than an exception, so it is worth
asserting at the end of any test that draws a tree.

---

## Testing a widget's behaviour

Hand it events. There is no window, so nothing is faked:

```kotlin
val focus = FocusManager(host.root)
val pointer = PointerRouter(host.root, focus)

pointer.onPointer(PointerEvent.Press(PointerId(0), Offset(120f, 80f)))
pointer.onPointer(PointerEvent.Release(PointerId(0), Offset(120f, 80f)))

assertEquals(1, clicks)
```

Layout has to have run first — a click finds a node by where it is, and before the
first `MeasurePass` nothing is anywhere.

---

## Testing that nothing happens

The claim this toolkit is built on is that a screen which is not changing costs
nothing. That is a test, not a hope:

```kotlin
repeat(60) {
    wall += 16_000_000L
    assertFalse(host.frame(wall), "frame $it redrew a screen where nothing had changed")
}
```

`host.frame(...)` returns whether anything actually changed. A widget that asks for
a frame every frame — an animation that never settles, a state written during
composition — fails this immediately.

And on a JVM you can assert on the garbage:

```kotlin
val before = allocatedBytes()
repeat(20) {
    host.frame(wall)
    MeasurePass().run(host.root, constraints)
    DrawPass(silent).draw(host.root)
}
assertTrue((allocatedBytes() - before) / 20 < 5_120)
```

`FrameCostTest` in `composegl-ui` does exactly this over a twenty-widget HUD. It is
a ratchet: when the number goes down, the bar goes down with it. That test is what
caught text being laid out again on every pass.

---

## Golden images

The recording canvas answers nearly every question without a GPU, and it cannot
answer one: whether the pixels are right. A distance field a shade too soft, a
scissor a pixel short, a glyph half off its baseline — none of those change a draw
call, and all of them are obvious in a picture.

`composegl-testing` draws a set of scenes through a real backend and compares them
to a checked-in PNG per backend.

```bash
./gradlew :composegl-gdx:test
COMPOSEGL_UPDATE_GOLDENS=1 ./gradlew :composegl-gdx:test   # …then look at the diff before committing
```

The comparison is deliberately tolerant, because two software rasterisers disagree
about the last bit of an antialiased edge and a golden that fails on rounding is a
golden nobody reads:

| | |
|---|---|
| `ChannelTolerance` | 20 — a channel off by less is the rasterisers disagreeing |
| `MaxDifferingFraction` | 1% — how much of a picture may differ |
| `MaxMeanDifference` | 2.0 — catches a shift too small to trip either of the above |

On a failure the actual, the expected and a difference map are written to
`build/screenshots`, and CI keeps them — so "what does it look like now?" is
answered without anybody rerunning anything.

A missing golden is a failure, not a pass. A test that writes its own expectation
the first time it runs can never fail.

---

## Seeded randomness

Anything random takes a seed, so it can be a golden:

```kotlin
val sparks = ParticleEmitter(capacity = 120, seed = 4L)
sparks.burst(count = 40, x = 60f, y = 60f, style = HitSparks)
repeat(36) { sparks.update(1f / 60f) }
```

`kotlin.random.Random(seed)` is the same algorithm on every platform, so that burst
is identical on a JVM, on Kotlin/Native and on a phone. `update(delta)` is public
for exactly this reason: a test drives the clock rather than waiting for one.

---

## What the build checks for you

```bash
./gradlew build
```

Beyond the tests, two checks run as part of `check`:

- **`checkDependencyConfinement`** — `composegl-ui` resolves against an allow-list
  of modules. Add a dependency it does not name and the build fails, which is how
  "no engine in the core" stays true rather than being remembered.
- **`checkNoEngineTypes`** — reads the compiled bytecode for references to engine
  types. Same promise, checked from the other end.

---

## What next

- **[[Backends]]** — adding your backend to the golden scenes
- **[[Input]]** — the events to hand a widget
- **[[Widgets]]** — what each one draws, which is what you will be asserting on
