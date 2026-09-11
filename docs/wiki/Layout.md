# Layout

How things end up where they end up.

If you have used Compose's layouts, you already know this one: same two-pass model,
same `Column`/`Row`/`Box`, same `weight` and `Arrangement`. The
[Compose layout basics](https://developer.android.com/develop/ui/compose/layouts/basics)
guide applies almost word for word.

This page is the differences and the things that are ours.

---

## The three layouts

`Column`, `Row`, `Box`. There is no `FlowRow`, no `ConstraintLayout`, no grid —
these three plus `weight` have covered every HUD, menu and inventory screen we
have built.

```kotlin
Column { Text("one"); Text("two") }   // downwards
Row    { Text("one"); Text("two") }   // across
Box    { Text("one"); Text("two") }   // on top of each other
```

---

## Sizes are floats, not `dp`

The one change you have to make coming from Compose UI.

```kotlin
Modifier.width(280f)        // not 280.dp
Modifier.size(64f)
Modifier.fillMaxWidth(0.5f)
```

There is no density, no `Dp`, no `LocalDensity`. You design against one fixed
screen — say 1280×720 — and `Viewport` scales the whole interface to whatever it
actually lands on:

```kotlin
Viewport(
    design = Size(1280f, 720f),
    physical = Size(Gdx.graphics.backBufferWidth.toFloat(), …),
    policy = ScalePolicy.Fit,
)
```

So `280f` is the same fraction of the screen on a phone, a laptop and a 4K
television, and there is nothing per-device to think about. That is the trade: you
lose "physically the same size everywhere" and gain "looks like the mock-up
everywhere", which is what a game wants.

Constraints work as you would expect — a parent offers a range, a child answers
with a size inside it — and `Constraints` has `minWidth`, `maxWidth`, `minHeight`,
`maxHeight` as floats.

---

## Sharing out the leftovers: `weight`

Inside a `Row` or a `Column`, `weight` says "give me a share of whatever is left
after everybody else has taken what they need".

```kotlin
Row(Modifier.fillMaxWidth()) {
    Text("HP")                          // as wide as the word "HP"
    Bar(hull, Modifier.weight(1f))      // …and the bar takes the rest
}
```

Two weights split it between them, in proportion:

```kotlin
Row(Modifier.fillMaxWidth()) {
    Panel(Modifier.weight(2f)) { Text("map") }    // two thirds
    Panel(Modifier.weight(1f)) { Text("log") }    // one third
}
```

As in Compose, `weight` only means anything inside a `Row` or a `Column`.

---

## Spacing them out: `Arrangement`

`Arrangement` is what a `Row` or `Column` does with space it has left over.

```kotlin
Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text("AMMO")
    Text("148")          // pushed to the far end
}
```

The full set:

| | what it does |
|---|---|
| `Start` / `Top` | everything at the beginning, leftover space at the end |
| `End` / `Bottom` | everything at the end |
| `Centre` | everything in the middle |
| `SpaceBetween` | first at the start, last at the end, gaps equal |
| `SpaceAround` | equal gaps, half-size gaps at the two ends |
| `SpaceEvenly` | equal gaps, including at the two ends |
| `spacedBy(12f)` | a fixed 12 between each, the rest left over |

`spacedBy` is the one you will use most — it is how you get a menu with breathing
room without putting spacers between things:

```kotlin
Column(verticalArrangement = Arrangement.spacedBy(10f)) {
    Button("CONTINUE", onClick = { })
    Button("OPTIONS", onClick = { })
    Button("QUIT", onClick = { })
}
```

---

## Lining them up: alignment

Same idea as Compose, different type names — and British spelling throughout:
`Centre`, not `Center`.

```kotlin
Row(verticalAlignment = VerticalAlignment.Centre) { … }      // centred top-to-bottom
Column(horizontalAlignment = HorizontalAlignment.Centre) { … } // centred left-to-right
```

A `Box` uses both at once, so it takes a combined `Alignment`:

```kotlin
Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
    Text("PAUSED")
}
```

And any single child can overrule it:

```kotlin
Box(Modifier.fillMaxSize()) {
    Text("HP 100", Modifier.align(Alignment.TopStart))
    Text("12:04",  Modifier.align(Alignment.TopEnd))
    Reticle(reticle, Modifier.align(Alignment.Centre))
}
```

The nine you would expect: `TopStart`, `TopCentre`, `TopEnd`, `CentreStart`,
`Centre`, `CentreEnd`, `BottomStart`, `BottomCentre`, `BottomEnd`.

That last example is a whole HUD's worth of positioning, and it is three lines.

---

## Padding

```kotlin
Modifier.padding(12f)                        // all four sides
Modifier.padding(horizontal = 16f, vertical = 8f)
Modifier.padding(left = 28f, bottom = 28f)   // name the ones you want
```

There is no margin, and no `Spacer` in a typical chain. Space *between* things is
the parent's job: `Arrangement.spacedBy`.

---

## Long lists

`LazyColumn` and `LazyRow`, with a simpler signature than Compose's: a `count` and
an item composable, rather than a `LazyListScope` DSL.

```kotlin
LazyColumn(count = saves.size, spacing = 6f) { index ->
    Row(Modifier.fillMaxWidth()) {
        Text(saves[index].name)
        Text(saves[index].date, Modifier.weight(1f), align = HorizontalAlignment.End)
    }
}
```

It scrolls, draws its own scrollbars, and only measures what is on screen plus a
couple either side. `key` and `spacing` do what you expect.

---

## When it does not fit

A line hands out the room it has, top to bottom, and whoever asks after it has
run out gets none. That child reports no height — and draws itself anyway,
because nothing here cuts a widget down to the size it agreed to.

So a column with more in it than it has room for does not get neatly cut off at
the bottom. It gets **printed on top of itself**, which looks like a bug
somewhere else entirely. It is worth knowing the shape of it, because the usual
way to meet it is a phone keyboard taking two thirds of the screen away from a
menu that used to fit.

The answer is a `ScrollArea`. It offers its contents as much room as they like,
takes only the room it was given, and clips once around the whole thing:

```kotlin
ScrollArea {
    Column(verticalArrangement = Arrangement.spacedBy(16f)) {
        // …as long as you like
    }
}
```

It is the same size as before when the contents fit, so wrapping a menu in one
costs nothing on a screen where it was never a problem. Moving focus to a child
that is off-screen scrolls it into view, which is what makes a text field usable
with a keyboard over it.

---

## The one rule that catches people out

**A widget is never measured twice.** Compose UI tolerates it; we do not. Ask a
child its size twice in one pass and you get an exception naming the widget:

```
Panel was measured twice in one pass.
```

It is an error rather than a warning because measuring twice doubles the cost of
everything underneath, and two of them nested squares it. At 60 frames a second
that is the difference between a HUD costing nothing and a HUD costing the frame.

Measure once, keep the `Placeable`, place it.

---

## What next

- **[[Modifiers]]** — the full list, and what the chain order does
- **[[Widgets]]** — what you put inside these layouts
- **[[Custom layouts]]** — writing your own `MeasurePolicy` when the three are not enough
