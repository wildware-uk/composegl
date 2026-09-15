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

### Baselines

A measured child knows where its text stands: `placeable.firstBaseline` and
`placeable.lastBaseline`, down from its top, or `NaN` when it has no text. That is
how a layout of your own lines things up by their letters.

You rarely need to report one. A layout that only arranges children reports the
baselines of the text inside them without being asked. A layout that draws text
itself says where its lines are:

```kotlin
layout(width, height, firstBaseline = ascent, lastBaseline = ascent + (lines - 1) * lineHeight) {}
```

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

Or ask it first. Asking is not measuring, so it does not count:

```kotlin
val wanted = measurables[0].maxIntrinsicWidth(height = constraints.maxHeight)
val placeable = measurables[0].measure(Constraints.fixed(wanted, 40f))
```

---

## Intrinsic sizes

`Modifier.width(IntrinsicSize.Max)` asks a layout how big it would like to be before
measuring it (see [[Layout]]). Your policy already has an answer: by default the
toolkit runs your `measure` over stand-ins that report each child's own intrinsic size,
so a ring of icons reports the ring's size without you writing anything.

Override the four when that is wrong or wasteful:

```kotlin
override fun MeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) =
    measurables.maxOfOrNull { it.maxIntrinsicWidth(height) } ?: 0f
```

Three cases where it is worth it. A leaf whose measure cannot tell its narrowest from its
widest — `Text` answers `minIntrinsicWidth` with its longest word. A layout with
weights, where measuring with unlimited room hands every weighted child a share of
nothing — `Row` and `Column` write their own for that reason.

And one where it is required: a `measure` that writes anything down. The default runs
your `measure` with made-up room, so a scroll area that clamps its position there, or a
lazy list that records its window size, would be scrolled back or recomposed by the
question. `ScrollArea`, `LazyColumn`, `TextField` and `Typewriter` all answer directly
for that reason.

An answer asks children; it never measures them.

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

### Telling children apart

A layout with named parts — a list item with an icon, a label and a badge; a HUD
with a centre slot and four corners — should not find them by position. The
moment one of them is only there sometimes, "the first child" is a different
child. Give each one a name instead:

```kotlin
val ListItem = MeasurePolicy { measurables, constraints ->
    val placeables = measurables.map { it.measure(constraints.loosen()) }
    fun slot(id: String) =
        measurables.indexOfFirst { it.layoutId == id }.takeIf { it >= 0 }?.let { placeables[it] }

    val icon = slot("icon")
    val label = slot("label")
    val badge = slot("badge")
    val width = constraints.maxWidth

    layout(width, placeables.maxOfOrNull { it.height } ?: 0f) {
        icon?.at(0f, 0f)
        label?.at((icon?.width ?: 0f) + 10f, 0f)
        badge?.at(width - badge.width, 0f)
    }
}

Layout(measurePolicy = ListItem, content = {
    if (unread > 0) Badge(unread, Modifier.layoutId("badge"))   // only sometimes, and first
    Icon(mail, Modifier.layoutId("icon"))
    Text("Inbox", Modifier.layoutId("label"))
})
```

![two list items from the same layout, one with a badge written before its icon](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/layout-slots.png)

- A name is anything with an `equals`: a string, an enum, an object.
- Named twice, the later name wins.
- It changes on a later frame like anything else — rename a child and it moves
  slot.
- `Row`, `Column` and `Box` ignore it. It means something only to a layout that
  reads it.
- A layout sees only its own children's names. A name on something inside a
  child — an icon wrapped in a `Box` — is not seen; put it on the `Box`.
- Every child still has to be measured once, named or not, including ones your
  layout has no slot for.

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
val placements = placements(count)      // FloatArray, x, y, x, y…
val offers = offers(count)              // one ConstraintsCache per child
```

It is good until your `measure` returns. Keeping it past that is keeping somebody
else's paper.

### The placement block is the expensive bit

This looks free and is not:

```kotlin
layout(width, height) {
    for (index in 0 until count) placeables[index]?.at(xs[index], ys[index])
}
```

The block mentions `count` and `placeables`, so Kotlin makes a fresh object for it
**every time the layout runs** — once per node, every frame, forever. On a HUD of
twenty widgets that was most of what a still screen cost.

So write the corners down instead, and hand back the count:

```kotlin
val placements = placements(count)
for (index in 0 until count) {
    val placeable = placeables[index] ?: continue
    placements[index * 2] = …           // x
    placements[index * 2 + 1] = …       // y
}

return layout(width, height, count)
```

Nothing is mentioned, so nothing is made. `Ring` above rewritten this way:

```kotlin
val Ring = MeasurePolicy { measurables, constraints ->
    val radius = 90f
    val count = measurables.size
    val placeables = placeables(count)
    val placements = placements(count)

    var widest = 0f
    for (index in 0 until count) {
        val placeable = measurables[index].measure(constraints.loosen(offers(1)[0]))
        placeables[index] = placeable
        if (placeable.width > widest) widest = placeable.width
    }
    val side = radius * 2f + widest

    for (index in 0 until count) {
        val placeable = placeables[index] ?: continue
        val angle = index * 2f * PI.toFloat() / count
        placements[index * 2] = side / 2f + cos(angle) * radius - placeable.width / 2f
        placements[index * 2 + 1] = side / 2f + sin(angle) * radius - placeable.height / 2f
    }

    layout(constraints.constrainWidth(side), constraints.constrainHeight(side), count)
}
```

Keep the block form when the corners genuinely are not known until placing time, or
when arrays would make the code harder to read than it is worth.

### Constraints you work out yourself

If you hand a child something other than what you were given — a share of a row, a
fixed size — that is an object per child per frame too. `offers(count)` lends one
remembered answer per child:

```kotlin
val offers = offers(count)
val room = offers[index].of(0f, share, 0f, crossMax)
```

`ConstraintsCache.of(...)` hands back the same object when the four numbers have not
moved, which on a still screen is always. `constraints.loosen(cache)` and
`constraints.shrink(h, v, cache)` take one for the same reason.

One cache per child, never shared: two different sets of numbers through one cache
means neither is ever the one that was kept.

Ignore all of it and build your own lists if you would rather — nothing checks, and
for a layout that appears once on a settings screen it does not matter. For a HUD,
it does: see [[Testing]] for the allocation test that watches this.

---

## What next

- **[[Layout]]** — the three that ship, and what they do
- **[[Modifiers]]** — writing a modifier your layout reads
- **[[Testing]]** — proving a layout places things where you think
