# Debugging

The tools for finding out why a screen looks or costs what it does: an overlay for the layout,
an inspector to point at one widget, and overlays for overdraw, draw calls, focus, redraws and
the lines inside text. Each one is drawn by the toolkit itself, so it looks the same on every
backend.

For one widget, `Modifier.debugBounds()` stays in `composegl-ui`; see
[Modifiers](Modifiers.md). For the tree as text, `dump`, see
[Testing](Testing.md#the-tree-as-text).

---

## Adding it

Everything on this page is in `composegl-debug`, a module of its own so a shipped game does not
carry it:

```kotlin
dependencies {
    implementation("dev.wildware.composegl:composegl-ui:0.5.0")
    // Only in a development build. Not in 0.5.0: until the next release it is on the snapshot.
    debugImplementation("dev.wildware.composegl:composegl-debug:0.6.0-SNAPSHOT")
}
```

`debugImplementation` is Android's name for a dependency only a debug build gets. On the desktop,
put it behind a Gradle property, or in a source set the release build leaves out. A game that
wants the overlays in every build, behind a key, uses `implementation`.

It is the same targets as `composegl-ui`: the JVM, Linux, iOS and the browser.

```kotlin
import dev.wildware.composegl.debug.FocusOverlay
import dev.wildware.composegl.debug.FrameBudgetOverlay
import dev.wildware.composegl.debug.Inspector
import dev.wildware.composegl.debug.LayoutOverlay
```

In 0.5.0 these were in `composegl-ui`, in `dev.wildware.composegl.ui.debug`. Moving to
`composegl-debug` changes the import and adds the dependency; nothing else about them changed.
What the renderer measures is still in `composegl-ui`, in `dev.wildware.composegl.ui.debug`:
`FrameBudget`, `DrawCallTrace`, `OverdrawMap` and `measureOverdraw`, so a test can hold a screen to
a budget with no debug module at all.

---

## The layout, on the screen

A dump says where everything is. `LayoutOverlay` shows it, over the running game:

```kotlin
Box(Modifier.fillMaxSize()) {
    Game()
    LayoutOverlay(enabled = debug)                                    // everything
    LayoutOverlay(enabled = debug, show = setOf(Show.Padding, Show.Gaps)) // or just some of it
}
```

| `Show` | Drawn as | What it is |
|---|---|---|
| `Bounds` | blue edge | every node's box, where layout put it (`layoutBoundsInRoot`) |
| `Drawn` | yellow edge | where a `scale` really draws it (`boundsInRoot`), left out where a blue edge already is |
| `Painted` | pink edge | where its ink really is (`paintedInRoot`) — text inside its line box, a background inside padding — left out where a blue or yellow edge already is |
| `Padding` | green wash | the padding inside each box |
| `Gaps` | orange wash | the space a `Row` or `Column` leaves between children |

"Why is there a gap here?" is the orange. "Why is this three pixels off?" is usually a
pink edge sitting inside a blue one.

Put it last, at the top of the screen. It has no size and takes no clicks, so it moves
nothing and a click goes straight through it. It reads the tree as it is drawn, so it
follows every change, and it never marks the tree changed itself: a still screen with it
on stays still. It has its own colours, the same over any skin. Take it off before
shipping.

![a panel with padding, a row of buttons with gaps between them and a label, under the layout overlay](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/layout-overlay.png)

Gaps are shown for rows and columns; a `FlowRow` or a grid does not shade its gaps yet.

---

## Pointing at one widget

The overlay shows everything. `Inspector` answers "why is *this* that size?" for one thing:

```kotlin
Inspector(enabled = debug) { Game() }
```

While it is on, the game stops taking the mouse and the inspector takes it:

| Do this | And you get |
|---|---|
| hover | the node under the pointer outlined in blue, its padding shaded green |
| read the panel | its name and tag, position, size, the room its parent `given` it, padding, its `policy` (`Row spaced 12`, `Box`, `Grid`…), and its modifier chain in the order it was written |
| click | the node pinned in orange, so the pointer can go; click again or Escape to let go |
| arrow keys or d-pad, while pinned | Up to the parent, Down to the first child, Left and Right to siblings; East lets go |
| the tree under the panel | every node on the screen, `-` and `+` to fold a branch, click a line to pin it; `tree` hides it and `<>` moves the panel to the other side |

The deepest node wins, so clicking the middle of a button pins its label; press Up for the
button. The panel reads the nodes again every frame while it is on, so a pinned node that
grows shows its new size. It only redraws when something it shows changed, and turning it
off rebuilds nothing: the screen keeps its state and focus.

![a settings panel with its APPLY button pinned, and the inspector's panel listing the button's size, padding and modifiers above a tree of the screen](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/inspector.png)

---

## How many times each pixel is painted

A panel on a panel on a background paints the same pixels three times, and a phone pays
for every one. Nothing looks different for it. `OverdrawOverlay` shows it:

```kotlin
Box(Modifier.fillMaxSize()) {
    Game()
    OverdrawOverlay(enabled = debug)            // one shaded square per 2 units
    OverdrawOverlay(enabled = debug, cell = 8f) // coarser, cheaper
}
```

| Painted | Shaded |
|---|---|
| once, or not at all | left alone |
| twice | blue |
| three times | green |
| four times | pink |
| five times or more | red |

Put it last, like `LayoutOverlay`. Each frame it draws the whole tree a second time into
a counter instead of the screen, then shades the counts. So it counts the calls the
frame really made — a background, a border, a shadow's whole spread, each run of text,
each picture — it follows every change, and a still screen with it on stays still. Its
own shading is not counted, nor `LayoutOverlay`'s, `Inspector`'s, `FocusOverlay`'s, `RedrawOverlay`'s or `TextMetricsOverlay`'s marks. Take it off
before shipping.

![a panel with a card, a button and a translucent scrim over half of it, shaded blue, green and pink where they stack](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/overdraw-overlay.png)

In a test, `ui.overdraw()` hands back the counts, so a screen can be held to a budget:

```kotlin
uiTest { PauseMenu() }.use { ui ->
    val map = ui.overdraw()
    assertTrue(map.deepest <= 3)          // no pixel painted more than three times
    assertEquals(2, map.at(640f, 360f))   // the middle of the screen, exactly twice
    println(map.average)                  // 1.4 is the fill rate of 1.4 screens
}
```

Outside a test, `measureOverdraw(host.root, canvas)` does the same.

Worth knowing:

- A subtree drawn into a picture — a `scale`, a `rotate`, an effect, a shaped clip —
  counts twice: once into the picture, once where the picture lands. That is what the
  GPU fills.
- A clipped-away or faded-out part counts nothing, as it paints nothing.
- Only the interface is counted. A 3D world drawn behind it, or anything drawn through
  `raw`, is not. A scrim over the world shows as painted once.
- A rounded corner counts as its square box, and text as its box rather than its letters.

---

## Where the draw calls go

`FrameBudgetOverlay` counts the draw calls. Under the count it lists the nodes that caused
the most of them, and why:

```kotlin
if (budget.isOn) FrameBudgetOverlay(budget, Modifier.align(Alignment.BottomEnd))
```

A batch is one trip to the GPU. It breaks — one more draw call — whenever the next thing
needs something the queue does not share:

| Reason | What cut the batch |
|---|---|
| `texture` | a picture from a different texture than the one before it |
| `blend` | `Modifier.blend`, going in and coming out |
| `clip` | `Modifier.clip`, going in and coming out |
| `layer` | an offscreen picture: a scale, a turn, a shaped clip, an effect |
| `shader` | a picture drawn through an effect's shader |
| `raw` | your own drawing inside `raw { }` |
| `full` | nothing changed; the queue was full |

The blame goes to the node that asked for the change. A glowing icon is blamed twice, for
the batch it cut going in and for its own glow coming out. A picture from its own texture
is blamed going in, and the next node that draws from the font atlas is blamed for going
back — so those two usually turn up as a pair. The frame's last call is nobody's fault and
is never listed, which is why the list adds up to one less than the count.

![a HUD with a glowing slot and a clipped panel, the frame budget overlay naming both as the nodes that cut the batch](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/frame-budget-culprits.png)

`UiRenderer` does all of this while the budget is on. A game writing its frame out itself
wires the two halves by hand: `drawPass.trace = budget.trace` and
`canvas.traceDrawCalls(budget.trace)`. In a test, read it off the reading:

```kotlin
val budget = FrameBudget(publishEveryMillis = 0)
uiTest(budget = budget) { Hud() }.use { ui ->
    ui.render()
    val worst = budget.reading.culprits.first()   // node, name, reason, calls
}
```

Every built-in canvas traces: LibGDX, raw OpenGL, WebGL and KorGE all draw through the same
shared renderer. The headless one does not batch, so it lists nothing.

---

## Focus and clicks, on the screen

Pad focus is worked out from geometry, so when Down goes to the wrong button there is
nothing to look at. `FocusOverlay` draws the answer before anybody presses anything:

```kotlin
Box(Modifier.fillMaxSize()) {
    Game()
    FocusOverlay(enabled = debug)                                   // everything
    FocusOverlay(enabled = debug, show = setOf(FocusShow.HitAreas)) // or just some of it
}
```

| `FocusShow` | Drawn as | What it is |
|---|---|---|
| `Arrows` | cyan arrow | where Up, Down, Left and Right take focus from the focused node, worked out from geometry |
| | orange arrow | the same, where a `focusOrder` names the answer |
| `Focusable` | green edge | every node focus can reach; the focused one gets a thick white edge |
| | grey edge | a focusable node a `focusTrap` shuts out |
| `Traps` | violet wash | each `focusTrap` |
| `HitAreas` | yellow wash | where a press lands: a click, a drag or a pointer handler, cut to the clips above it |
| | red wash | a hole a `hitShape`, or a shaped clip on it or above it, cuts in that rectangle |

It finds the `FocusManager` built over its tree by itself; pass `focus =` when a game has
two over one tree. A test can ask the same question without drawing anything:
`focus.targetOf(FocusDirection.Down)` is where Down would go, and nothing moves.

An arrow is where focus goes when the focused widget lets the press through. A slider keeps
Left and Right for itself until it reaches an end, and that is not drawn.

Like `LayoutOverlay` it has no size, takes no clicks and moves nothing. Focus moving is the
one change that can leave a screen looking the same, so while it is on a focus move marks
the frame changed. Take it off before shipping.

![a menu of four buttons with arrows from the focused one, and a round button tinted with red corners](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/focus-overlay.png)

Holes are found by asking the shape in 4-unit squares, so their edges are steps.

---

## What keeps redrawing

A still screen should cost almost nothing. When one does not, `RedrawOverlay` shows what
is changing:

```kotlin
Box(Modifier.fillMaxSize()) {
    Game()
    RedrawOverlay(enabled = debug)                    // flashes fade over half a second
    RedrawOverlay(enabled = debug, holdMillis = 2000) // or longer, for something that blinks
}
```

Every node that changed gets a red border on the frame it changed, fading out. "Changed"
means what makes a frame redraw: a new modifier chain, a new drawing lambda, a child added
(the child flashes) or removed (the parent flashes), or a size or place an animation moved.
A node recomposed with the same arguments as before does not flash, because it is not
redrawn either. A menu standing still shows nothing; a label handed a lambda written inline
flashes every time its parent recomposes.

For numbers rather than flashes, ask the frame budget for its busiest nodes:

```kotlin
val ui = UiRenderer(host, canvas, FrameBudget(busiest = 5))
// …or on a budget you already have
ui.budget.busiest = 5
```

`FrameBudgetOverlay` then lists the five nodes the most frames changed, most first, by test
tag (`#score`) or by name, with how many frames. The counts are also on every node as
`node.changes`, and `tree.countChanges = true` turns them on for a test that wants them
alone. `budget.reset()` and `tree.resetChangeCounts()` start them again.

Neither overlay marks anything changed itself, so turning them on does not make a still
screen redraw. The budget's own numbers refresh four times a second on purpose and are left
out of both. Counting is off until one of them asks, and costs one increment per change
while on.

![a still menu and a score that ticks, with the score's border flashing red](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/redraw-overlay.png)

A fade only moves while the game keeps drawing. A game that skips drawing unchanged frames
holds the last flash until something changes.

---

## The lines inside text, on the screen

`TextAnchor` places text by its line box, its capitals or its baseline, and none of
those can be seen. `TextMetricsOverlay` draws them through every piece of text:

```kotlin
Box(Modifier.fillMaxSize()) {
    Game()
    TextMetricsOverlay(enabled = debug)                                        // all five
    TextMetricsOverlay(enabled = debug, show = setOf(TextGuide.Baseline))      // or just one
}
```

| `TextGuide` | Drawn as | What it is |
|---|---|---|
| `LineBox` | cyan edge | each line's box, as layout counted it — what `TextAnchor.LineBox` places by |
| `Ascent` | red line | the top of the tallest glyph |
| `CapHeight` | orange line | the top of a capital — what `TextAnchor.CapTop` places by |
| `Baseline` | green line | the line the letters stand on — what `TextAnchor.Baseline` places by |
| `Descent` | blue line | the bottom of the lowest glyph |

Lining a label up with an icon is then a matter of looking: put the icon's edge on
the green line, not three pixels above it. Two sizes on one baseline share one green
line.

Every line of a wrapped paragraph gets its own set, each as wide as that line's
glyphs, so a centred label shows its lines under its letters rather than across its
box. A scaled label shows them where it is drawn. It is the same kind of node as
`LayoutOverlay` — no size, no clicks, a still screen stays still — and both can be on
at once.

![two sizes on one baseline and a line of body text, under the text metrics overlay](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/text-metrics-overlay.png)

It marks `Text` labels, text fields — a field's lines where its words have scrolled
to, or its hint's when it is empty — typewriters, tooltips and the letter on a
`PromptGlyph`. A typewriter shows every line as it will stand once typed, so the
guides do not crawl along with the letters. Damage numbers and a minimap's compass
letters are not marked yet.

---

## Your own overlay

The overlays use only `composegl-ui`'s public API, so a game can write one the same way. An
overlay is a node with no size and a drawing that walks the tree. Make the drawing a
`DebugOverlay`, and the toolkit treats it as debug tooling: an overdraw count leaves it out, the
frame budget does not list it, and the overlays on this page leave it out of what they mark.

```kotlin
class Centres : DebugOverlay {
    var node: UiNode? = null

    override fun invoke(canvas: UiCanvas, content: Rect) {
        val self = node ?: return
        // Where this canvas's origin is: the root's, unless something drew the tree elsewhere.
        val dx = content.left - self.contentBoundsInRoot.left
        val dy = content.top - self.contentBoundsInRoot.top
        self.tree?.root?.forEach { node ->
            if (!node.everMeasured || isDebugOverlay(node)) return@forEach
            val centre = node.boundsInRoot.centre
            canvas.rect(Rect.of(centre.x + dx - 1f, centre.y + dy - 1f, 2f, 2f), Colour.Red)
        }
    }
}

@Composable
fun CentresOverlay(enabled: Boolean) {
    if (!enabled) return
    val centres = remember { Centres() }
    ComposeNode<UiNode, UiApplier>(
        factory = { UiNode("centres") },
        update = {
            set(Modifier.zIndex(Float.MAX_VALUE)) { this.modifier = it }   // on top of its siblings
            set(MeasurePolicy.Empty) { this.measurePolicy = it }            // no size, no clicks
            set(centres) {
                it.node = this
                this.content = it
            }
        },
    )
}
```

Compose it last on the screen, like the overlays above. `DebugOverlay` and `isDebugOverlay` are in
`composegl-ui`, so a tool like this needs no `composegl-debug` at all.

What a tool can read off the tree, besides the rectangles every node has:

| Read | What it is |
|---|---|
| `node.contentBoundsInRoot` | the content box, less padding: the rectangle its drawing is handed |
| `node.everMeasured` | whether layout has reached it yet |
| `node.drawnScale`, `drawnMirrorX`, `drawnMirrorY`, `isResizing` | what the pointer search stops at |
| `node.changes`, `node.changedAtNanos`, `tree.clocks.frameNanos` | what changed, and when |
| `tree.watchChanges()`, `tree.stopWatchingChanges()` | change counting, while a tool needs it |
| `node.focusManager`, `focus.peek(direction)`, `focus.reachable()` | where focus goes, and why |
| `focus.addMovedListener`, `removeMovedListener` | told when focus moves |
| `policy.linearOrientation` | a `Row` or `Column`'s direction, or null |
| `policy as? TextGuideSource` | where a text node put its lines |
| `describe(element)`, `describePolicy`, `describeConstraints`, `describePadding` | what `dump` writes |

---

## What next

- [[Testing]] — the same questions asked in a test, with no window.
- [[Modifiers]] — `debugBounds`, for one widget.
