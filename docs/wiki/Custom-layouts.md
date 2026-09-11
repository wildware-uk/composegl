# Custom layouts

When `Column`, `Row` and `Box` are not enough — a radial menu, a hex grid, a
ring of icons round a crosshair — you write a `MeasurePolicy`.

There is no private hook the built-in layouts use and you cannot. `Row` is written
against exactly this interface.

---

## The shape of one

```kotlin
val Ring = MeasurePolicy { measurables, constraints ->
    val radius = 90f
    val placeables = measurables.map { it.measure(constraints.loosen()) }
    val side = radius * 2f + (placeables.maxOfOrNull { it.width } ?: 0f)

    layout(constraints.constrainWidth(side), constraints.constrainHeight(side)) {
        placeables.forEachIndexed { index, placeable ->
            val angle = index * 2f * PI.toFloat() / placeables.size
            placeable.at(
                side / 2f + cos(angle) * radius - placeable.width / 2f,
                side / 2f + sin(angle) * radius - placeable.height / 2f,
            )
        }
    }
}

@Composable
fun RadialMenu(content: @Composable () -> Unit) {
    Layout(measurePolicy = Ring, content = content)
}
```

`Layout`'s `content` is not its last parameter — the policy is — so pass it by name
rather than as a trailing lambda.

Then use it like anything else:

```kotlin
RadialMenu {
    IconButton(heal, onClick = { })
    IconButton(grenade, onClick = { })
    IconButton(scan, onClick = { })
}
```

---

## The two halves

**Measure**, then **place**. They are separate because a parent has to know how big
*all* its children are before it can decide where *any* of them go — a row cannot
centre its children without having measured them first.

```kotlin
val placeable = measurable.measure(constraints)   // how big it wants to be
…
placeable.at(x, y)                                // inside the layout { } block
```

`layout(width, height) { … }` says what size you chose. The block runs after every
layout in the tree has a size, which is why you can place a child you measured
earlier without measuring it again.

---

## Measure each child exactly once

Ask a child its size twice in one pass and you get an exception naming the widget:

```
Panel was measured twice in one pass.
```

It is an error rather than a warning because measuring twice doubles the cost of
everything underneath, and two of them nested squares it.

If you want a child's size before deciding the constraints for it — the classic
reason people measure twice — measure it loose once, keep the `Placeable`, and work
from that.

---

## What a child can tell you

`Measurable.layoutData` carries the parts of a child's modifier that only its parent
can act on:

```kotlin
val weight = measurables[index].layoutData.weight
val alignment = measurables[index].layoutData.alignment ?: contentAlignment
```

That is how `Modifier.weight(1f)` and `Modifier.align(...)` reach the layout that
has to honour them. Nothing else about a child is visible, which is what stops
layouts reaching into each other.

---

## Leaves

A widget that draws but has no children is a `LeafLayout` — its policy decides its
size and a draw function paints it:

```kotlin
private class DialPainter(private val value: Float) : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints) =
        layout(constraints.constrainWidth(64f), constraints.constrainHeight(64f)) {}

    val draw: UiCanvas.(Rect) -> Unit = { bounds ->
        fan(wedge(bounds, value), Colour.rgb(0x5B8DEF))
    }
}

@Composable
fun Dial(value: Float, modifier: Modifier = Modifier) {
    val painter = remember(value) { DialPainter(value) }
    LeafLayout(modifier = modifier, name = "dial", measurePolicy = painter, draw = painter.draw)
}
```

One object measuring and drawing is the pattern the built-in widgets use, and it is
deliberate: what was measured is what is drawn, and nothing can re-measure at draw
time.

Note `remember(value)` — a new object when the value changes, the same object when
it has not, so an unchanged widget does not redraw the frame.

---

## Making it cost nothing per frame

A layout runs every frame in a game, because the frame it draws into was just
cleared. The obvious implementation — `map` to a list, `maxOfOrNull`, `forEachIndexed`
— makes three or four objects per node per frame.

For a layout that is on screen all the time, the scope will lend you room that
belongs to the node and is reused next frame:

```kotlin
val count = measurables.size
val placeables = placeables(count)      // Array<Placeable?>, at least `count` long
val sizes = sizes(count)                // FloatArray
val positions = positions(count)        // FloatArray

for (index in 0 until count) {
    val placeable = measurables[index].measure(offered)
    placeables[index] = placeable
    sizes[index] = placeable.width
}
```

It is good until your `measure` returns. Keeping it past that is keeping somebody
else's paper.

Ignore all three and build your own lists if you would rather — nothing checks, and
for a layout that appears once on a settings screen it does not matter. For a HUD,
it does: see [[Testing]] for the allocation test that watches this.

---

## What next

- **[[Layout]]** — the three that ship, and what they do
- **[[Modifiers]]** — writing a modifier your layout reads
- **[[Testing]]** — proving a layout places things where you think
