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
Modifier.widthIn(min = 200f, max = 400f)   // as wide as the contents, inside that range
Modifier.heightIn(min = 40f)
Modifier.sizeIn(minWidth = 64f, minHeight = 64f)
Modifier.defaultMinSize(minWidth = 48f)    // only when nothing else says a width
Modifier.width(IntrinsicSize.Max)  // as wide as the contents would like, asked first
Modifier.height(IntrinsicSize.Min) // as short as they can be squeezed to
Modifier.animateContentSize()      // grows to new contents rather than jumping
```

A range is a rule rather than a wish, so it holds wherever it sits in the chain:
`width` and `fillMaxWidth` are measured inside it. See [[Layout]] for the details.

**Growing with its contents.** When what is inside a panel changes size — a quest
entry opening, a chat bubble filling up as the text types in, a tooltip whose text
changes — the panel normally snaps to the new size. `animateContentSize` makes it
travel there instead:

```kotlin
Panel(Modifier.animateContentSize()) {
    Text(quest.title)
    if (expanded) Text(quest.description)
}

Modifier.animateContentSize(Tween(250))                        // a fixed time, not a spring
Modifier.animateContentSize(alignment = Alignment.BottomStart) // a chat log growing upwards
Modifier.animateContentSize(clock = Clock.World)               // stops when the game pauses
```

![a quest entry closed, part-way open with its last line cut off, and open](images/modifier-animate-content-size.png)

The contents are measured at their new size straight away; the panel is laid out at
a size moving from the old one to the new one, and its neighbours move with it.
Whatever does not fit yet is cut off at the panel's edge, and cannot be clicked
there, until it arrives. Once it has, nothing is cut.

It animates the widget's whole size — padding, background and border too — wherever
it sits in the chain. A widget appearing for the first time takes its size at once,
and a size the chain or the parent fixes has nothing to animate. The default is a
spring, so contents that change again part-way turn it round smoothly. A spec
written inline is fine: specs compare by value, so recomposing does not cost a
redraw. It costs nothing once it has arrived, and a test's `settle` waits for it.

**Space**

```kotlin
Modifier.padding(12f)
Modifier.padding(horizontal = 16f, vertical = 8f)
Modifier.padding(left = 28f, bottom = 28f)
Modifier.paddingFrom(Baseline.First, before = 24f)  // measured to the text's line, not its box
Modifier.paddingFromBaseline(top = 28f, bottom = 12f)
Modifier.offset(x = 0f, y = 2f)    // move it, without moving anything else
Modifier.parallax(pointer, factor = -0.02f) // …by how far the pointer is from the middle
```

**Parallax is cheap depth.** Each layer is offset by a *factor* times how far a
*source* has moved from rest — the far layer at a small factor, the near one at a
bigger one:

```kotlin
val pointer = remember { PointerParallax(centre = Offset(640f, 360f)) }
val stick = remember { StickParallax(reach = Offset(640f, 360f)) }
val sink = ParallaxAware(toolkitSink, pointer = pointer, stick = stick)  // hand this to the backend

Image(sky, Modifier.parallax(pointer, factor = -0.02f).parallax(stick, factor = -0.02f))
Image(hills, Modifier.parallax(pointer, factor = -0.06f).parallax(stick, factor = -0.06f))
```

![the same three layers with the pointer in the middle and at the right edge](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/modifier-parallax.png)

Three sources come with it:

- `PointerParallax(centre)` — how far the pointer is from `centre`. Back to rest when
  the mouse leaves the window or the finger it is following lifts; lifting a second
  finger leaves it with the first.
- `StickParallax(reach)` — the right stick by default, since the left one is moving
  focus. Full tilt is worth `reach`, so the same factor means the same thing for a
  stick as for a mouse. Back to rest when the pad that pushed it is unplugged.
- `ScrollParallax(scrollState)` — how far a `ScrollArea`'s rows have moved. Factor
  one travels with the rows; a half travels at half their speed.

`ParallaxAware` wraps the sink a backend pushes into so the sources see every event,
including the ones a button uses; the answers are the sink's, unchanged. Anything
else that moves can be a source too: implement `ParallaxSource` with a
state-backed `position`.

It is an `offset` and nothing more, so layout does not move — neighbours keep their
places — while clicks move with the picture. A positive factor follows the source;
a negative one leans away from it. Two on one widget add up. The source is read while
composing, so each move recomposes the composable that wrote the modifier: keep the
layers in a small composable of their own. A still source costs nothing. Give a layer
that fills the screen a margin, or `clip` its parent, or its edge shows as it drifts.

**Where it goes**

```kotlin
Modifier.align(Alignment.TopEnd)   // inside a Box
Modifier.weight(1f)                // inside a Row or Column
Modifier.zIndex(1f)                // drawn over its siblings, and clicked first
Modifier.layoutId("icon")          // inside a layout of your own; see [[Custom layouts]]
Modifier.wrapContentSize()         // its own size, centred in a slot bigger than it
Modifier.wrapContentWidth(HorizontalAlignment.End)
Modifier.animatePlacement()        // slides to a new slot instead of jumping there
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

**animatePlacement is for lists that change.** Sort an inventory, reshuffle a
leaderboard, take a notification out of the middle of a stack, and every row that
moved slides from where it was to where it now belongs instead of jumping:

```kotlin
LazyColumn(count = scores.size, key = { scores[it].id }) { index ->
    ScoreRow(scores[index], Modifier.animatePlacement())
}
```

The key matters. Without one, a sorted list keeps each row in its place and changes
what it says, so nothing moved. Whatever moves the node counts — a sort, something
inserted above it, a neighbour growing — and the row is put back where it was on the
very frame it moved, so there is no flicker. A new row is not a move and appears where
it lands. A second move mid-slide carries on from where the row got to, at the speed
it was going, which is why the default is a spring; pass any `AnimationSpec`, and a
`clock` of `Clock.World` if the slide should freeze with the game.

The row is really there as it slides: a click, hover and focus find it where it is
drawn. "Where it was" is measured against whatever carries the row about, so only a
real move slides. Scrolling a `LazyColumn`, `LazyRow`, lazy grid or `ScrollArea` is not a move. A
cell that slides inside a row that slides goes along with its row rather than sliding
twice. Anything else that moves as a whole — a window the player drags, a panel flying
in — should say `Modifier.placementFrame()`, or its rows will trail behind it.

![a leaderboard sorted a moment ago: on the left the rows have jumped, on the right they are part way through sliding](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/modifier-animate-placement.png)

**How it looks**

```kotlin
Modifier.background(Colour.rgb(0x1A1F28), corner = 6f)
Modifier.background(Brush.vertical(top, bottom), corner = 6f)   // a gradient
Modifier.border(Colour.rgb(0x2C3545), width = 1f, corner = 6f)
Modifier.border(accent, width = 2f, style = BorderStyle.Dashed(on = 6f, off = 4f))
Modifier.border(accent, width = 2f, corner = 8f, style = BorderStyle.Dotted)
Modifier.border(bottom = BorderSide(1f, divider))   // one edge: a divider, a tab's underline
Modifier.shadow(Colour.argb(0x80000000), spread = 12f, corner = 6f)
Modifier.ninePatch(frame)          // skin art, stretched properly
Modifier.background(Accent, Corners.top(8f))  // …with a radius per corner
Modifier.clip()                    // children cannot draw outside
Modifier.clip(corner = 6f)         // …with rounded corners
Modifier.clipShape(Shapes.Circle)  // …or any convex shape: a round portrait
Modifier.alpha(0.4f)               // the subtree fades as one thing
Modifier.tint(Colour.Red)          // …every colour in it multiplied by red
Modifier.scale(1.2f)               // …drawn bigger, without re-laying it out
Modifier.mirror()                  // …flipped to face the other way
Modifier.rotate(8f)                // …turned clockwise
Modifier.skew(x = -12f)            // …slanted, top leaning forward
Modifier.rotate3d(y = 180f)        // …turned over in depth, like a card
Modifier.effect(blur(radius = 8f)) // …through a shader
```

**Clipping to a shape.** `clip()` is a rectangle, and free. `clipShape` cuts to any
convex shape instead — a round portrait, a diamond minimap, a hexagon tile — from
square art, at draw time:

![Four square pictures cut to a circle, a diamond, a hexagon and a rounded rectangle](images/modifier-clip-shape.png)

```kotlin
Image(portrait, Modifier.size(64f).clipShape(Shapes.Circle))
Box(Modifier.size(96f).clipShape(Shapes.Diamond)) { Minimap() }
Box(Modifier.clipShape(Shapes.polygon(0.5f, 0f, 1f, 1f, 0f, 1f))) { … }  // fractions of the box
```

The shapes are `Shapes.Rectangle`, `Circle`, `Ellipse`, `Diamond`, `Hexagon`,
`roundedRect(corner)` and `polygon(…)`. A polygon's points are fractions of the
widget's box, so one shape fits every size; a concave one throws, because only a
convex outline can be drawn in one piece.

Chain order decides what is cut. What the chain paints *after* `clipShape` is cut
with the content and children; what it paints *before* stays whole:

```kotlin
Modifier.border(ring, 2f).clipShape(Shapes.Circle).background(grey)  // square ring, round disc
```

What the shape cuts away cannot be clicked either — the click goes to whatever is
drawn there instead. The same shape goes to `hitShape` to make the widget itself
round to the pointer without cutting anything:

```kotlin
Modifier.hitShape(Shapes.Circle)
```

A shaped clip draws the widget into an offscreen picture and puts it back through the
shape, with an edge softened over one screen pixel. That is one picture per clipped
widget per frame: fine for portraits and a row of tiles, not for a thousand. A canvas
that cannot make or cut pictures clips to the rectangle instead.

**Skew is for leaning.** A banner with speed in it, an italic-style title card, the
slanted bars of a fighting-game HUD:

```kotlin
Banner(Modifier.skew(x = -12f))                                   // top leans forward
HealthBar(Modifier.skew(x = -20f, origin = Alignment.BottomStart)) // bottom edge stays put
Ribbon(Modifier.skew(y = 8f))                                     // sides upright, sliding down
```

![A tile upright, leaning forward, and slid down](images/modifier-skew.png)

The angles are degrees, with the sign CSS uses: negative `x` puts the top to the
right. It is built like `scale` and `rotate` — the widget is drawn upright into a
picture and the picture is put down on four corners — so text inside is not asked
for an oblique font and layout does not move. A skew and a rotate on the same widget
share one picture. Clicks do not follow it: a slanted widget is hit inside its
upright box, exactly as a turned one is. Two skews on one axis add their slopes, and
an angle of ±90 or beyond throws. On a canvas that cannot put a picture on four
corners (`canvas.drawsLayersOnto`) the widget is drawn without the slant.

**Tint multiplies a whole subtree by a colour.** A damage flash, a locked item
dimmed, one piece of art in every team's colour — with no shader and no offscreen
picture, just the multiply a tinted image already gets:

```kotlin
Hotbar(slots, Modifier.tint(Colour.Red.scaleAlpha(flash)))   // flash runs 1 → 0
Image("sword", Modifier.tint(Colour.Grey))                   // locked
Image("banner", Modifier.tint(team.colour))                  // one banner, four teams
```

The colour's alpha is **how strong the tint is**, not an opacity: at 0 nothing changes,
at 1 the colour applies in full. So a flash is one number animating back to zero. Fade
with `alpha`, not with this.

Two tints multiply, on one widget or nested, so a team colour on a locked slot is both.
White changes nothing and nothing gets brighter, so a flash *towards* white wants an
additive overlay instead; turning something truly grey wants `colourGrade`. What a
`raw` block draws is not tinted. A canvas says whether it can with `canvas.tints`; the
two backends here both can.

![a panel plain, flashed red, locked grey, and a team colour](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/modifier-tint.png)

**Rotate3d is for depth.** A card flipping over, a panel swinging in on its hinge, a
menu tipping towards the pointer:

```kotlin
Card(Modifier.rotate3d(y = flip * 180f))                                   // turns over
Panel(Modifier.rotate3d(y = -70f * (1f - arrival), origin = Alignment.CentreStart)) // swings in
Menu(Modifier.rotate3d(x = -tilt.y * 8f, y = tilt.x * 8f))                 // leans at the pointer
```

![A tile flat, swung about y, and tipped about x](images/modifier-rotate3d.png)

Angles are degrees with CSS's signs: positive `y` sends the right edge away, positive
`x` sends the top edge away, positive `z` turns clockwise like `rotate`. They are
applied `z`, then `y`, then `x`, about `origin`, and a camera straight in front of that
point looks at the result. `cameraDistance` is in 72-pixel inches, as on Android, so
the default 8 puts it 576 pixels away; smaller is more dramatic.

It is built like `rotate` and `skew`: the widget is drawn flat into a picture, and the
picture is put down through the camera. The GPU divides by depth for every pixel, so
the picture does not bend along the diagonal. A `skew` and a `rotate` on the same
widget share the picture. Past 90 degrees you see the back of the picture, mirrored —
a two-sided card swaps what it composes at the halfway point:

```kotlin
val angle = flip * 180f
Card(Modifier.rotate3d(y = angle)) {
    if (angle < 90f) Front() else Back(Modifier.mirror())  // mirrored again, so it reads
}
```

Clicks do not follow it: a tilted widget is hit inside its flat box, like a turned one
(`hitShape` is the escape hatch). Two `rotate3d`s add angle by angle. On a canvas that
cannot tilt a picture (`canvas.tiltsLayers`) the widget is drawn without the depth.

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

To see all three on a running screen, turn on `LayoutOverlay`: blue for the laid-out
box, yellow for where a scale draws it, pink for the ink. See
[Testing](Testing.md#the-layout-on-the-screen).

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
throws rather than mirroring — flipping is `mirror()`, below — so hand an anticipate
easing over as `scale(t.coerceAtLeast(0f))`.

**Mirror is for one piece of art facing either way.** The same offscreen picture,
put down read from the other side, flipped in place about the widget's middle:

```kotlin
Portrait(Modifier.mirror(horizontal = speaker.isOnRight))   // a speaker on either side
Arrow(Modifier.mirror(vertical = pointsDown))               // one arrow, up or down
```

![one portrait and arrow, then the same art with mirror() on it](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/modifier-mirror.png)

**Text inside flips too**, and reads backwards — nothing inside knows it is being
mirrored. Mirror the art, and put the name label beside it rather than inside it.

Layout does not move. What is inside does, and clicks, pad focus and `boundsInRoot`
move with it: in a mirrored row the first child is drawn on the right, clicked on the
right, and is where focus goes when the player presses right. A pointer handler
inside gets its own coordinates mirrored too, so a drag to the right on screen is a
drag to the left to it — which is what keeps a mirrored slider under the finger. The
same goes for the arrows and the pad a focused widget claims: pressing right on a
mirrored slider moves its knob right on screen, which is towards its own minimum.

Two mirrors cancel, so a flipped portrait inside a flipped panel faces the way it was
drawn. `mirror(horizontal = false)` takes no picture, so the flag can come straight
from game state. It shares a picture with a `scale` on the same widget, and is
flipped first and then turned under a `rotate`. The rest is scale's bargain: the
capture is a clip, and a canvas that cannot flip a picture (`canvas.mirrorsLayers`)
draws the widget the right way round and is clicked the right way round.

**Shake**

```kotlin
val shake = rememberShake()
Panel(Modifier.shake(shake)) { … }
shake.trigger()                     // wrong password: it jolts and settles on its own
shake.trigger(intensity = 0.3f)     // a lighter knock
```

![five panels knocked at once, from full strength to not at all, each moved off its outlined slot by a little less](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/modifier-shake.png)

A shake is **trauma**, the way game cameras do it. Each knock adds to `trauma`, which
stops at one and drains by itself (`decayPerSecond`, 1.5 by default, so a full knock is
still in about two thirds of a second). The widget is moved by trauma *squared* times a
smooth noise, up to `maxOffset` each way — so half a knock is a quarter of the movement,
and a big shake eases out instead of stopping dead. Mashing a locked door cannot shake
the panel off the screen, because full is full.

It is an `offset`, so layout does not move: the neighbours stay put and the parent does
not grow. Clicks move with it, as they do with any offset. Put `shake` before
`background` to move the whole widget, or after it to rattle the contents inside a frame
that stays still.

It runs on a clock. The default is `Clock.Ui`, which keeps going under a pause menu; a hit
on the player belongs to `Clock.World` and freezes with the game:

```kotlin
val hurt = rememberShake(clock = Clock.World, maxOffset = 6f)
```

The wobble comes from the time and a `seed`, not from randomness, so a shake is the same
every run and a test can say exactly where the widget was. Give two widgets shaking side
by side different seeds, or they move in step. A still shake costs no frames; `stop()`
puts it straight back.

**Marquee**

```kotlin
Text(trackName, Modifier.width(160f).marquee(speed = 30f, delayMillis = 1500))
Text(itemName, Modifier.width(120f).marquee(iterations = 2))       // two trips, then rest
Text(bossName, Modifier.width(200f).marquee(clock = Clock.World))  // stops with the game
```

![four name slots at the same moment: a name that fits sitting still, one resting before it starts, one part way across, and one coming round with its copy behind](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/modifier-marquee.png)

A song title, a player name, an item name in a narrow hotbar tooltip: text too long
for its slot scrolls round inside it instead of wrapping or losing its end to an
ellipsis.

The contents are measured with no limit on their width, so a label stays on one line,
and the widget keeps the width it was given. **It only moves when the contents
overflow.** A name that fits sits still, is not cut, stays centred if it was centred,
and costs nothing. One that does not rests for `delayMillis`, slides left at `speed`
units a second, and comes round with a copy `spacing` behind it (32 by default), so the
loop has no seam. It rests again at the start of every trip. Everything is cut to the
widget's box inside its padding.

It runs on a clock — `Clock.Ui` by default, so it keeps going under a pause menu — and
only the drawing moves: nothing recomposes, layout does not change, and a frame is
asked for only while the contents are actually moving. The rest at the start of each
trip is free. New contents, a new width or a different marquee start again from rest; a
new colour — a title lighting up under the pointer — carries on from where it was.

Where it sits in the chain does not matter. It is for labels: a button inside a marquee
is clicked where layout put it, not where it has scrolled to. `uiTest` does not wait for
a marquee to stop, since it never does; `advanceBy` moves it along.

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

**Seeing where a widget went**

```kotlin
Panel(Modifier.debugBounds())                           // a magenta box round it
Panel(Modifier.debugBounds(Colour.Red, label = true))   // …with its size, 120x40, in the corner
```

What to write instead of a temporary `border` you then have to remember to take
off. It draws an outline and a faint wash over the widget and its children, and
changes nothing else: not the size, not the padding, not the clicks — a click goes
straight through it. It is a data class, so a screen that recomposes with the same
box on it stays still.

It paints where it sits in the chain, like a border. First, which is where a
widget's own `modifier` puts it, boxes the whole widget; after a `padding` it boxes
what the padding left. A widget laid out with no width shows as a line, which is
often the answer. The label's digits are drawn with rectangles, so they need no
font and look the same on every backend.

For every widget on the screen at once — boxes, padding and the gaps between
children — use `LayoutOverlay` instead ([Testing](Testing.md#the-layout-on-the-screen)).

![a panel, its padding, a button and a zero-width box, each outlined with its size](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/modifier-debug-bounds.png)

**A note on colours.** `Colour.rgb(0x…)` and `Colour.argb(0x…)` are how you write
one. There are also about a dozen named ones — `Colour.Red`, `Colour.Grey`,
`Colour.Orange` — for a debug box, an example, or a prototype nobody has skinned
yet. They are deliberately plain and deliberately few: your game's actual colours
belong in its **[[Skins|skin]]**, where one edit changes every panel at once.

**Input**

```kotlin
Modifier.clickable { fire() }
Modifier.clickable(enabled = false) { }   // still swallows the click
Modifier.clickable(onDoubleClick = { equip() }, onLongPress = { actions() }) { select() }
Modifier.repeatingClickable { count++ }   // the + on a quantity picker
Modifier.interaction(state)               // hover and press, for drawing
Modifier.draggable { delta -> at += delta } // slop, capture and cancel done for you
Modifier.dragSource(payload = item) { ItemIcon(item) } // inside a DragAndDropHost
Modifier.dropTarget<Item>(accepts = { it.fits(slot) }, onDrop = { move(it, slot) })
Modifier.onActivate { pickUp(); true }    // South or Enter, before the click
Modifier.onPointer(handler)               // raw pointer events
Modifier.onKeyEvent(handler)
Modifier.onTextEvent(handler)
Modifier.hitShape { it.x >= 20f }         // which points inside the box are really yours
Modifier.pointerHoverIcon(PointerIcon.Hand) // the mouse cursor's shape over it
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

**Being told where it ended up**

```kotlin
Modifier.onPlaced { node -> anchor = node.boundsInRoot }   // it moved on screen
Modifier.onSizeChanged { size -> emitter.resize(size) }    // it got bigger or smaller
```

A popup under a button, a particle emitter the size of a panel, a tutorial arrow
pointing at something: all of them need to know where a widget is, and none of
them should ask every frame.

Both are called after layout has finished, so every rectangle in the tree is this
frame's, parents first. Both are called only when the answer changed — the first
layout counts — so a still screen calls nothing. `onPlaced` is about where the
widget is *drawn*: a parent moving it or scaling it counts, even though the widget
itself did not change. `onSizeChanged` is only its size, so moving is not resizing.

`onPlaced` hands you the node rather than a rectangle, because you usually want one
of three: `boundsInRoot` for the pixels, `layoutBoundsInRoot` for the slot, or
`paintedInRoot` for the ink. Read what you need during the call; do not keep the node.

State written in either lands on the next frame, the same as in Compose:

![a menu hanging under the Options button, placed by the button's onPlaced](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/modifier-on-placed.png)

Rule 2 below applies: `remember` the handler on a widget that recomposes often.

---

The decoration ones, on the same box:

![four boxes showing background, border, shadow and alpha](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/modifier-decoration.png)

**One radius, or one per corner.** `background` (a colour or a gradient), `border` and `shadow` take a single
`corner`, or a `Corners` with a radius for each — clockwise from the top-left, like
CSS. That is how a tab rounds only along its top, a speech bubble keeps one sharp
corner, and a panel docked to the edge of the screen stays square against it:

```kotlin
val speech = Corners(topLeft = 14f, topRight = 14f, bottomRight = 14f, bottomLeft = 0f)

Modifier.background(Accent, Corners.top(8f))                       // a tab
Modifier.shadow(Glow, spread = 10f, corners = speech)
    .background(Steel, speech)
    .border(Accent, width = 2f, corners = speech)                  // a bubble
Modifier.background(Steel, Corners.left(16f))                      // docked to the right edge
```

Give the border and the shadow the same `Corners` as the background, or they show
at the corner that differs. `Corners.top`, `bottom`, `left` and `right` round two;
`Corners.all(r)` is exactly `corner = r`. A backend that cannot round corners one by
one draws every corner at the smallest of the four and says so through
`canvas.roundsCornersSeparately`; both backends here can. `clip(corners)` rounds
a clip the same way, through `clipShape` (see *Clipping to a shape* above).

![a tab rounded along its top, a speech bubble with one square corner, and a panel square on its right](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/modifier-corners.png)

Gradients: a `Brush` in place of a colour.

```kotlin
Brush.vertical(top, bottom)
Brush.horizontal(left, right)
Brush.linear(start, end, degrees = 45f)      // clockwise from pointing right
Brush.radial(centre, edge)                   // an ellipse that fits the box
```

Two colours each. The gradient runs edge to edge across the node, whatever its size, and
a corner cuts it like any other fill — `Modifier.background(brush, Corners.top(8f))` gives
each corner its own radius. Fading to `Colour.Transparent` keeps the colour
rather than darkening on the way. A backend without gradients draws the first colour
flat; `canvas.drawsGradients` says which you have.

![a sky panel, a green-to-red health bar, a vignette and a fade](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/modifier-gradients.png)

**Borders can be one-sided, dashed or dotted.** `BorderSide(width, colour, style)` is
one edge, and `Modifier.border(left =, top =, right =, bottom =)` takes any of the four;
a side left out is not drawn. The edges meet in square corners, top and bottom running
the full width. A rounded outline is the all-sides `border`, where one line follows the
curve.

`BorderStyle.Dashed(on, off)` stretches its lengths a touch so a whole number of dashes
fits, which is why an edge always starts and ends on a dash. `BorderStyle.Dotted` is
square dots as long as the line is thick, the same distance apart. Both are drawn as
plain rectangles and lines, so every backend gets them without doing anything.

![a header divider, tabs with an underline, dashed and dotted boxes](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/modifier-borders.png)

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
data class GlowElement(val amount: Float) : Modifier.Element

fun Modifier.glow(amount: Float) = then(GlowElement(amount))
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
