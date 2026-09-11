# Layout

How things end up where they end up.

There are three layouts and a handful of modifiers. That is the whole system —
no grid, no constraint solver, no flexbox. It is enough for every HUD, menu and
inventory screen we have built with it.

---

## The three layouts

```kotlin
Column { Text("one"); Text("two") }   // downwards
Row    { Text("one"); Text("two") }   // across
Box    { Text("one"); Text("two") }   // on top of each other
```

That is genuinely it. A menu is a `Column`. A toolbar is a `Row`. A HUD is a
`Box` with things pinned to its corners.

They nest, and nesting is how you get anything complicated:

```kotlin
Column {
    Text("INVENTORY")
    Row {
        Text("Sword")
        Text("x1")
    }
    Row {
        Text("Potion")
        Text("x3")
    }
}
```

---

## How big is a widget?

Every widget gets asked one question by its parent: **"you may be up to this big —
how big do you want to be?"** The widget answers, and the parent then decides where
to put it.

That is the entire model. Two steps, no arguing, no second guesses.

By default a widget asks for the smallest size that fits what is in it. A `Text`
asks for the width of its text; a `Panel` asks for whatever is inside it plus its
own padding.

Three modifiers change the answer:

```kotlin
Modifier.width(280f)     // "I want to be 280 wide"
Modifier.height(40f)     // "…and 40 tall"
Modifier.size(64f)       // both, square
```

And two more ask for a share of what is on offer:

```kotlin
Modifier.fillMaxWidth()       // all the width the parent will give
Modifier.fillMaxWidth(0.5f)   // half of it
Modifier.fillMaxSize()        // everything, both ways
```

> **Why `280f` and not `280.dp`?** Because of the viewport. You design against one
> fixed size — say 1280×720 — and the viewport scales the whole interface to
> whatever screen it lands on. So `280f` always means the same fraction of the
> screen, on every device. See [[Your first screen]] for the two lines that set
> that up.

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

`weight` only means anything inside a `Row` or a `Column` — a `Box` piles its
children up rather than sharing anything out, so there is nothing to share.

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

Arrangement handles the direction a `Row` or `Column` runs in. Alignment handles
the other one.

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

Padding is room *inside* a widget, between its edge and what is in it.

```kotlin
Modifier.padding(12f)                        // all four sides
Modifier.padding(horizontal = 16f, vertical = 8f)
Modifier.padding(left = 28f, bottom = 28f)   // name the ones you want
```

There is no margin. Space *between* things is the parent's job — use
`Arrangement.spacedBy`.

---

## Long lists

A list of fifty save games should not measure fifty rows to show eight of them.
`LazyColumn` builds only what is on screen:

```kotlin
LazyColumn(count = saves.size, spacing = 6f) { index ->
    Row(Modifier.fillMaxWidth()) {
        Text(saves[index].name)
        Text(saves[index].date, Modifier.weight(1f), align = HorizontalAlignment.End)
    }
}
```

It scrolls, it has scrollbars, and it recycles. `LazyRow` is the same thing lying
down — a hotbar, a filmstrip of cards.

---

## The one rule that catches people out

**A widget is never measured twice.** If a layout asks a child how big it is and
then asks again, that is an error, and the toolkit says so by name:

```
Panel was measured twice in one pass.
```

Why it is an error rather than merely slow: measuring twice doubles the cost of
everything underneath, and two of them nested squares it. A tree ten deep with a
double-measure at each level is a thousand times the work.

So: measure once, keep what you got back, and place it.

---

## What next

- **[[Modifiers]]** — the full list, and what the chain order does
- **[[Widgets]]** — what you put inside these layouts
- **[[Custom layouts]]** — writing your own `MeasurePolicy` when the three are not enough
