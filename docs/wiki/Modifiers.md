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
```

**How it looks**

```kotlin
Modifier.background(Colour.rgb(0x1A1F28), corner = 6f)
Modifier.border(Colour.rgb(0x2C3545), width = 1f, corner = 6f)
Modifier.shadow(Colour.argb(0x80000000), spread = 12f, corner = 6f)
Modifier.ninePatch(frame)          // skin art, stretched properly
Modifier.clip(corner = 6f)         // children cannot draw outside
Modifier.alpha(0.4f)               // the subtree fades as one thing
Modifier.effect(blur(radius = 8f)) // …through a shader
```

**Your own drawing**

```kotlin
Modifier.drawBehind { bounds -> rect(bounds, Colour.rgb(0xE5484D)) }
Modifier.drawInFront { bounds -> border(bounds, Colour.White, 1f) }
```

The receiver is a [`UiCanvas`](https://github.com/wildware-uk/composegl/blob/master/composegl-ui/src/commonMain/kotlin/dev/wildware/composegl/ui/graphics/UiCanvas.kt)
and the argument is the widget's rectangle in screen coordinates.

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
```

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
