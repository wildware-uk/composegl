# Modifiers

`Modifier` is the same idea as Compose's: a chain of things said about a widget,
folded left to right, read by layout and by drawing.

```kotlin
Panel(Modifier.align(Alignment.BottomStart).padding(20f).width(280f)) { … }
```

If you know `Modifier.padding(8f).background(Color.Blue)`, you know this. What
follows is the list, and the three rules that are ours.

---

## The list

**Size**

```kotlin
Modifier.size(64f)                 // square
Modifier.size(width = 200f, height = 40f)
Modifier.width(280f)
Modifier.height(40f)
Modifier.fillMaxWidth()            // all of what the parent offers
Modifier.fillMaxHeight(0.5f)       // half of it
Modifier.fillMaxSize()
Modifier.fillMaxWidth().aspectRatio(3f / 4f)  // as wide as the slot, height follows at 3:4
```

**Space**

```kotlin
Modifier.padding(12f)
Modifier.padding(horizontal = 16f, vertical = 8f)
Modifier.padding(left = 28f, bottom = 28f)
Modifier.offset(x = 0f, y = 2f)    // move it, without moving anything else
```

**Where it goes**

```kotlin
Modifier.align(Alignment.TopEnd)   // inside a Box
Modifier.weight(1f)                // inside a Row or Column
Modifier.zIndex(1f)                // drawn over its siblings, and clicked first
Modifier.layoutId("icon")          // inside a layout of your own; see [[Custom layouts]]
```

**zIndex is for lifting one thing out of a pile.** Siblings paint in the order
they are written. A selected card, a dragged tile, a hovered item can come forward
without being moved in the code — which would also move it in focus order and
change what recomposition matches it by:

```kotlin
Card(Modifier.zIndex(if (selected) 1f else 0f))
```

Higher is drawn later, so on top. Equal values keep source order, so the default of
zero changes nothing, and a negative value sinks a node under its siblings. Clicks
and hover follow the picture: where two cards overlap, the one on top gets the
press. Layout and focus do not — a row still lays out left to right as written, and
Tab still walks in source order. It orders siblings only: a child with a huge
zIndex inside a low parent stays under that parent's higher siblings. Two on one
node add, like `offset`.

![three overlapping cards, the middle one lifted over both neighbours](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/modifier-zindex.png)

**How it looks**

```kotlin
Modifier.background(Colour.rgb(0x1A1F28), corner = 6f)
Modifier.border(Colour.rgb(0x2C3545), width = 1f, corner = 6f)
Modifier.shadow(Colour.argb(0x80000000), spread = 12f, corner = 6f)
Modifier.ninePatch(frame)          // skin art, stretched properly
Modifier.clip(corner = 6f)         // children cannot draw outside
Modifier.alpha(0.4f)               // the subtree fades as one thing
Modifier.scale(1.2f)               // …drawn bigger, without re-laying it out
Modifier.effect(blur(radius = 8f)) // …through a shader
```

**Scale is for arriving and for fitting.** The widget and everything under it are
drawn into an offscreen picture at the size layout gave them, and that picture is
put down bigger or smaller:

```kotlin
Panel(Modifier.scale(spring.value)) { … }                  // a panel springing in
Panel(Modifier.scale(min(1f, budget / measured))) { … }     // one squeezed to fit
Panel(Modifier.scale(1.4f, Alignment.TopStart)) { … }       // grown from a corner
```

Layout does not move, so a panel arriving does not shove its neighbours and a fit
correction does not re-flow what is inside it. Clicks and pad focus *do* move:
`boundsInRoot` reports where the widget is drawn, so a button drawn at twice the
size is clickable at twice the size. `layoutBoundsInRoot` is the rectangle before
any scaling, for the code that wants the slot rather than the pixels. And
`paintedInRoot` is a third question again — what the subtree actually *painted*,
scaling folded in, which for text is the glyphs rather than the line box and for a
bare `Box` used only for layout is nothing at all:

```kotlin
val ink = node.paintedInRoot          // null: it drew nothing, or has not been laid out
```

Reach for it when something has to frame composed content — a debug overlay, a
focus ring that should hug the letters, a screenshot cropper, a containment
assertion in a test. Using the node box for any of those over-reports by roughly
the leading plus the descent: small enough to look like a rounding bug, big enough
to fail a strict check.

A few things to know. It magnifies a picture, so past about 1.15 it is visibly
soft and text is soft sooner — a world that wants to be crisp at three times the
size wants to be *laid out* three times the size. The capture is the widget's own
rectangle, so anything a child draws outside it is cut off while the scale is on —
and clicks agree, so what you cannot see you cannot press. The other direction is
not true: the picture is put down filling the *scaled* rectangle, so `clip()` on the
same widget does not hold it in. A viewport that must not spill wants the `clip` on
the parent and the `scale` on the child inside it.

A canvas can refuse to make the picture at all, and the two backends here refuse one
bigger than 4096 screen pixels a side. Then the subtree is drawn plainly, at its
ordinary size, and hit testing goes back with it. Ask `canvas.drawsLayers` first if a
screen would rather pick a different animation.

Two scales on one widget multiply, so an arrival animation and a fit correction
compose. A scale of one takes no picture at all. Zero draws nothing and cannot be
clicked or focused, which is what lets a panel arrive from nothing. A negative factor
throws rather than mirroring, so hand an anticipate easing over as
`scale(t.coerceAtLeast(0f))`.

**Your own drawing**

```kotlin
Modifier.drawBehind { bounds -> rect(bounds, Colour.rgb(0xE5484D)) }
Modifier.drawInFront { bounds -> border(bounds, Colour.White, 1f) }
```

The receiver is a [`UiCanvas`](https://github.com/wildware-uk/composegl/blob/master/composegl-ui/src/commonMain/kotlin/dev/wildware/composegl/ui/graphics/UiCanvas.kt)
and the argument is the widget's rectangle in screen coordinates.

**Turning a picture, and making it glow.** Two things the canvas can do that a
plain rectangle cannot:

```kotlin
Modifier.drawBehind { bounds ->
    // A sunburst: one picture per ray, each turned about the hub on the left edge.
    pushBlend(BlendMode.Additive)        // light adds; paint covers
    repeat(14) { ray ->
        image(spark, bounds, degrees = ray * 360f / 14f, pivotX = 0f)
    }
    popBlend()
}
```

`degrees` turns clockwise, because y grows downwards here. `destination` is the box
*before* turning, so the pixels can land outside it. The rays batch together — no
draw call per ray — but a blend mode is a batch boundary, so push it round the whole
group rather than per call: a dozen quads between one push and one pop cost two
boundaries, not twenty-four.

Both degrade honestly on a backend that cannot do them: the picture is drawn upright
and the glow is drawn as ordinary paint. Ask `canvas.rotatesImages` and
`canvas.supports(BlendMode.Additive)` first if you would rather draw something else.

**A note on colours.** `Colour.rgb(0x…)` and `Colour.argb(0x…)` are how you write
one. There are also about a dozen named ones — `Colour.Red`, `Colour.Grey`,
`Colour.Orange` — for a debug box, an example, or a prototype nobody has skinned
yet. They are deliberately plain and deliberately few: your game's actual colours
belong in its **[[Skins|skin]]**, where one edit changes every panel at once.

**Input**

```kotlin
Modifier.clickable { fire() }
Modifier.clickable(enabled = false) { }   // still swallows the click
Modifier.interaction(state)               // hover and press, for drawing
Modifier.onPointer(handler)               // raw pointer events
Modifier.onKeyEvent(handler)
Modifier.onTextEvent(handler)
Modifier.hitShape { it.x >= 20f }         // which points inside the box are really yours
```

**A note on `hitShape`.** Hit testing is rectangles, because nearly everything is a
rectangle and rectangles are cheap. A round button, a diamond or a honeycomb cell is
not, and its rectangle overlaps its neighbours' — so the empty corner of one sits over
the middle of another and the click goes to whichever was drawn later. `hitShape` is
how a widget says those corners are not its own; saying no lets the click carry on to
whatever is underneath.

The question is asked in the widget's own coordinates, the ones `onPointer` delivers,
with any `scale` already divided back out — so the shape is written once against the
widget's own width and height and keeps working while it grows. Unlike `clip` it gates
that one widget and says nothing about its children: it does not change what is drawn,
so it must not change what is reachable. A child that wants the same shape asks for it
itself. Two on one widget is a choice rather than a quantity, so the later one wins.

**Focus**

```kotlin
Modifier.focusable()
Modifier.focusRequester(requester)        // send focus straight here
Modifier.focusOrder(down = inventoryTab)  // override the geometry
Modifier.focusTrap()                      // a dialogue keeps focus inside itself
Modifier.onFocusWithin { inside -> … }
Modifier.onReveal { child -> scrollTo(child) }
```

Focus has its own page: [[Input]].

**Testing**

```kotlin
Modifier.testTag("play")                  // host.root.find("play") from a test
```

Changes nothing about how the node looks or behaves. See [[Testing]].

---

The decoration ones, on the same box:

![four boxes showing background, border, shadow and alpha](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/modifier-decoration.png)

And what `padding` does to what is inside it:

![the same blue box inside a dark one, without and with padding](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/modifier-padding.png)

---

## Rule 1: order matters, and it is visible

```kotlin
Modifier.padding(8f).background(blue)   // blue is painted inside the padding
Modifier.background(blue).padding(8f)   // blue is painted across the whole widget
```

Both are useful and the difference is on the screen, which is why the chain stays
in order rather than being collected into a bag of properties.

The same applies to two backgrounds, a background and a border, or a nine-patch
and padding — each one paints where it sits in the chain.

---

## Rule 2: equality is what makes a still screen free

A node's modifier is compared on every recomposition to decide whether anything
changed. Every element is a data class, so the chain compares by value, and a
screen that has not changed costs nothing.

The one thing that defeats that is a lambda written inline:

```kotlin
Modifier.drawBehind { … }        // ❌ a new object every recomposition
```

Same trap as Compose, same fix:

```kotlin
val glow = remember { drawGlow() }
Modifier.drawBehind(glow)        // ✅ compares equal
```

It only matters on a widget that recomposes often. `clickable { }` on a menu
button is fine.

---

## Rule 3: `styled` is how a widget gets its look

```kotlin
Box(Modifier.styled(style)) { … }
```

One call, because the three parts have to happen in order: the background paints
across the widget, the padding keeps its contents off the edge, and the content
offset moves those contents without moving the background — which is how a pressed
button's label shifts down a pixel while its frame stays put.

A widget that writes `styled(style)` and then draws its text in `style.textColour`
contains no colour, no corner radius and no texture name of its own. That is what
makes one skin file change the look of a whole game. See [[Skins]].

---

## Writing your own

A modifier is an element plus an extension function:

```kotlin
data class ShakeElement(val amount: Float) : Modifier.Element

fun Modifier.shake(amount: Float) = then(ShakeElement(amount))
```

A data class, so the chain still compares by value. Then read it wherever it is
meant to act — a measure policy, a draw pass, your own widget. The built-in ones
are read by `ResolvedModifier`, which folds the chain once per node per frame.

---

## What next

- **[[Layout]]** — size, weight, arrangement, alignment
- **[[Skins]]** — where `styled` gets its style from
- **[[Input]]** — clickable, focus, and what the handlers see
- **[[Shaders]]** — what `effect` can be given
