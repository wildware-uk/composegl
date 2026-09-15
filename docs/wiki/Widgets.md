# Widgets

Everything that ships. Two sets: the ordinary interface controls, and a tier of
things only games need.

None of it is Material. There is no theme to fight, and every widget takes its
look from a [[Skins|skin]] file rather than from code.

---

## Text

```kotlin
Text("HULL INTEGRITY")
Text("148", colour = Colour.rgb(0xE5484D))
Text(story, softWrap = true, maxLines = 3, ellipsis = "…")
Text(name, Modifier.weight(1f), align = HorizontalAlignment.End)
```

What was measured is what is drawn — the layout object the font produced at
measure time is the object handed to the canvas, so text never wraps differently
from the space reserved for it.

`style` picks a named style from the skin (`"label"` by default):

```kotlin
Text("GAME OVER", style = "display")
```

![four text styles: a title, the default, a dim one and a wrapped paragraph](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-text.png)

### Where the text sits

A text node is placed by its **line box**, whose top is the tallest glyph's
ascent. That is right for laying out — a box stopping at the capitals would clip
the accent off `Á` — but it is not what most coordinates mean. If you are
porting a layout from an immediate-mode or batch renderer, every y you carry
across is a **cap top**, and handed straight to `Text` every figure draws
`ascent - capHeight` low:

```kotlin
Text("148", Modifier.offset(y = capTop), anchor = TextAnchor.CapTop)
Text("HP", Modifier.offset(y = baseline), anchor = TextAnchor.Baseline)
```

Worth doing rather than subtracting yourself, because the mistake is **silent** —
nothing clips and nothing overflows, the text is just low and looks deliberate —
and it is **proportional to the font size**, so a screen with three text sizes is
wrong by three different amounts and reads as three separate layout problems.

The anchor moves the node, not the glyphs inside it: same size, same wrapping,
and the background, border and clicks move with it. Your own `offset` still adds
on top. If you only want the number, `FontMetrics.capInset` is the line box top
to cap top, and `ascent` is the baseline.

`TextAnchor.Baseline` is also how you line a label up with an icon, or two
strings at different sizes against each other.

### Styled runs: an underlined term, a struck word, a value in colour

A `Text` draws one string in one style. When part of a sentence has to look
different — a term the reader can tap for an explanation, a word struck through
because it no longer applies, a number in another colour — say which characters:

```kotlin
val term = TextRange(4, 12)
Text(
    "The tincture wears off at dawn.",
    runs = listOf(
        TextRun(term, colour = Colour.Orange, decoration = TextDecoration.Underline, tag = "tincture"),
    ),
    onRunHover = { highlight(it?.tag) },
    onRunClick = { explain(it.tag as String) },
)
```

A `TextRun` is a range, a colour, a decoration (`Underline` or `Strike`) and a
`tag` of your own that comes back when the pointer is over it. Runs may overlap;
the later one wins for whichever of colour and decoration it names. A run cannot
change the family or the size, on purpose: a run that changes the size changes
the line height, and a paragraph whose lines are different heights is a much
bigger problem than an underlined term.

That is the whole of what you write. **The toolkit keeps ownership of measuring
and line breaking**, so the sentence stays one node — no splitting it into one
node per word, no hit region per word, no line-breaking rules of your own to keep
in step with everybody else's.

Two things change underneath, and both are worth knowing:

- **Lines are aligned as well as the block.** Ordinary `Text` hands the string to
  the backend and never sees where it wrapped, so `align` can only centre the
  block and leave the lines ragged inside it. A run-styled `Text` breaks the lines
  itself, so it centres them too.
- **It costs more.** Text is measured per line and per run boundary rather than
  once. Everything is cached on the text, the style and the width, so a paragraph
  standing still measures nothing — but a plain label should stay plain.

### Laying out text yourself

If you are doing your own inline layout — an icon in the middle of a sentence,
say — ask for the lines rather than approximating them:

```kotlin
val block = fonts.paragraph(story, style, maxWidth = 300f)

block.lines          // where each one breaks, and its baseline
block.words          // the stretches a line may break between
block.boxesOf(term)  // one box per line the term touches
block.indexAt(point) // which character is under the pointer
```

Greedy, like every interface text layout: a line takes as many words as fit. It
breaks after spaces, after hyphens, and between ideographic characters — so
Chinese and Japanese wrap without spaces, and a full stop or a closing bracket is
never pushed onto a line of its own. A word longer than the whole width overflows
rather than being chopped, and `TextLine.width` says that it did.

### Outlined text

A ring round the letters, so a readout stays legible over a moving, colourful
background:

```kotlin
Text("148", outline = TextOutline(Colour.Black, width = 2f))

// Or once, for a whole HUD.
ProvideTextOutline(Colour.Black, width = 2f) {
    Text("HULL")
    Text("$ammo")
}
```

`ProvideTextOutline` reaches `Text`, `Typewriter`, `Tooltip`,
`DamageNumberLayer` and `Minimap`'s compass letters. Not `TextField` or
`PromptGlyph`, which have backgrounds of their own; give those an explicit
outline if you ever want one.

Three things worth knowing before you use it:

- **It is stamped, not stroked.** The canvas draws the run eight times offset
  and once on top, out of the same bitmap glyphs. Honest up to about a sixth of
  the text size — two units on sixteen-unit text. Past that the eight copies
  start showing as eight copies.
- **Use an opaque outline colour.** The copies overlap, so a see-through ring
  reads darker where they stack. A fading label does fade whole — the ring is
  drawn at the face's alpha, so it goes out with the letters rather than leaving
  a silhouette — but not evenly: those stacked copies keep the ring reading a
  shade stronger than the letters all the way down.
- **It does not change layout.** The ring is painted outside the text's box and
  the box does not grow for it, so switching it on moves nothing and rewraps
  nothing. The price is that a tight clip trims it and a background sized to the
  text does not cover it — add `Modifier.padding` of the outline width where
  that matters.

It costs nine times the glyph quads and no extra nodes, draw calls or
measuring. For one hero label where the alpha has to be exactly right,
`Modifier.outline` from `composegl-effects` composites once instead of stacking;
it costs an offscreen picture and a draw call per node, which is why it is the
wrong tool for two hundred damage numbers.

---

## Buttons

```kotlin
Button("CONTINUE", onClick = { load(save) })
Button("QUIT", onClick = { exit() }, enabled = false)

// …or with whatever content you like
Button(onClick = { equip(sword) }) {
    Row(horizontalArrangement = Arrangement.spacedBy(6f)) {
        Image(icon)
        Text("EQUIP")
    }
}

IconButton(closeIcon, onClick = { dismiss() })
```

Every state comes from the skin — no Kotlin here names a colour:

| | |
|---|---|
| ![a button at rest](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-button.png) | resting |
| ![a button under the pointer](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-button-hover.png) | under the pointer |
| ![a button being pressed](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-button-pressed.png) | held down |
| ![a focused button, ringed in white](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-button-focused.png) | focused, which is where a pad and the arrow keys are |
| ![a greyed-out button](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-button-disabled.png) | disabled |

A disabled button still swallows the click, so it cannot fall through to whatever
is behind it.

Same widget, a different style name, and it is a chip:

![four chips, one chosen and one in the danger style](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-chips.png)

---

## Containers

```kotlin
Panel(Modifier.width(280f)) { … }          // a box with the skin's panel look
Panel(style = "panel.raised") { … }

Dialog(onDismiss = { open = false }) {      // a panel over a dimmed screen
    Text("Abandon the run?")
    Row { Button("YES", ::abandon); Button("NO") { open = false } }
}

Tabs(selected, onSelect = { selected = it }, titles = listOf("GEAR", "SKILLS")) { page ->
    when (page) { 0 -> Gear(); else -> Skills() }
}
```

`Panel` is one widget and two style names here — the art-backed one and a flat one:

![two panels side by side, one cut from art and one a flat fill](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-panel.png)

`Dialog` puts itself on the back stack, so Escape and the pad's B button close it
without you wiring anything up. `OnBack { }` is how anything else joins that stack.

---

## Dividers

```kotlin
Column {
    Text("AUDIO")
    Divider()                                   // across, 1 thick, skin style "divider"
    Text("VIDEO")
}

Row(Modifier.height(22f)) {
    Text("1920x1080")
    Divider(vertical = true, thickness = 2f)   // down, as tall as the row
    Text("144 Hz")
}

Divider(Modifier.width(80f))                    // a short rule instead of a full one
Divider(style = "divider.strong")               // falls back to "divider"
```

![a panel split by a horizontal divider, with vertical dividers between settings](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-divider.png)

A divider is as long as the room it is given, and `thickness` across. The colour is
the skin's `divider` style, so one line in the skin file recolours every divider.

A vertical divider takes all the height its row may have. Give the row a height, or
it grows to fill whatever holds it. Inside a `ScrollArea` there is no limit, so give
the divider a height there. The pad and the mouse pass straight over a divider.

---

## Fields and settings

```kotlin
var name by remember { mutableStateOf(TextFieldValue("")) }
TextField(name, onValueChange = { name = it }, placeholder = "CALLSIGN")

Checkbox(subtitles, onCheckedChange = { subtitles = it }, label = "Subtitles")
Toggle(vsync, onCheckedChange = { vsync = it }, label = "V-Sync")
RadioButton(quality == High, onSelect = { quality = High }, label = "High")
Slider(volume, onValueChange = { volume = it }, range = 0f..1f, step = 0.05f)
```

![three text fields: one with text, one showing a placeholder, one focused](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-field.png)

![two toggles and two checkboxes, on and off](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-toggle.png)

![two sliders at different values, the second focused](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-slider.png)

`TextField` handles selection, the clipboard, and the phone's keyboard, and
`onSubmit` fires on Enter. It works with a pad too: focus it and the on-screen
keyboard comes up on platforms that have one.

### Steppers: `< Medium >`

The console settings control. It needs nothing but left and right, so nothing
opens and a player on a stick never leaves the list.

```kotlin
Stepper(options = listOf("Low", "Medium", "High"), selected = quality, onSelect = { quality = it })
NumberStepper(value = volume, range = 0..10, onValueChange = { volume = it })
NumberStepper(fov, range = 60..110, step = 5, format = { "$it°" }, onValueChange = { fov = it })
```

![a quality stepper, a focused volume stepper at 7, and a difficulty stepper at its first option with its left arrow dimmed](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-stepper.png)

- **Left and right change it while it has focus.** Arrow keys and the pad both
  work. Up and down still move focus.
- **Holding repeats.** A held stick or d-pad steps once, pauses, then repeats at
  the pad's rate. A held key repeats at the keyboard's own rate. A mouse held on
  an arrow does the same on the frame clock, and waits while dragged off it.
- **At an end it lets go.** One more press to the right past the last option
  moves focus to the neighbour instead of doing nothing. That arrow is drawn
  disabled. Pass `wrap = true` to go round instead.
- **Enter, South, or a click on the value** moves to the next option, and goes
  round at the end.
- **The arrows stay put.** The value is as wide as the widest option. Give the
  stepper a width and the extra goes to the value.

Styles: `stepper` behind it, `stepper.arrow` for the two arrows (pressed while
held, disabled at an end), `stepper.value` for the words.

---

## Lists and scrolling

```kotlin
LazyColumn(count = saves.size, spacing = 6f) { index -> SaveRow(saves[index]) }
LazyRow(count = 9, spacing = 4f) { slot -> HotbarSlot(slot) }

ScrollArea(Modifier.fillMaxSize()) { LongPatchNotes() }
```

![a panel of save slots with a scrollbar down the side](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-scroll.png)

`LazyColumn` measures only what is on screen. `ScrollArea` is for content that is
one piece and simply too tall.

Both remember how far they were scrolled when their screen is left and come
back to, as long as a `SaveableStateHolder` is above them — see
[[Saving state]].

---

## Pictures

```kotlin
Image("portraits/sniper")                       // by name, out of the skin's atlas
Image(texture, Modifier.size(64f), tint = Colour.rgb(0x808080))
Image(icon, fit = ImageFit.Cover, alignment = Alignment.TopStart)
```

A name that is not in the atlas stops there and says so, rather than drawing
nothing — because nothing looks exactly like a widget somebody has not written yet.

---

## Tooltips and prompts

```kotlin
TooltipHost {                       // once, around the screen
    Tooltip("Reloads faster when crouched") {
        IconButton(reloadIcon, onClick = { })
    }
}

PromptGlyph(Action.Interact)        // draws E or Ⓐ, depending on what the player is using
```

![a tooltip under a button, explaining what it costs](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-tooltip.png)

`PromptGlyph` changes the moment somebody picks up a pad, without anything being
reloaded.

---

## Showing and hiding: AnimatedVisibility

`if (open) Menu()` takes the menu away the frame `open` goes false, so there is
nothing left to animate. `AnimatedVisibility` keeps it on screen until its exit
has played, then takes it away.

```kotlin
AnimatedVisibility(
    visible = open,
    enter = fadeIn() + scaleIn(from = 0.9f),
    exit = fadeOut() + slideOut(Offset(0f, 30f)),
) {
    Panel { … }
}
```

![a menu half way through fading and shrinking out](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-animated-visibility.png)

- **Parts:** `fadeIn`/`fadeOut`, `scaleIn`/`scaleOut`, `slideIn`/`slideOut`. Join
  them with `+`. Each takes its own spec, so a fade can be quick while a scale
  settles on a spring.
- **Changing your mind** turns round from where it is. Reopen a menu half way out
  and it comes back without ever leaving the tree.
- **Clocks:** `clock = Clock.World` freezes a leaving panel while the game is paused.
- **Starting hidden:** it opens at rest when `visible` is already true. Pass
  `initiallyVisible = false` for a toast that should animate in when it is added.
- **Cost:** open and still, or closed, it asks for no frames.
- A leaving panel can still be clicked until it is gone. Pass `enabled = open` to
  its buttons if that matters.
- Slides are in pixels, not a fraction of the panel's own size.

---

## Typewriter

```kotlin
val line = rememberTypewriter(dialogue[at])
Typewriter(line, charactersPerSecond = 40f, onFinished = { showChoices = true })
```

Text that arrives a letter at a time, skipping to the end on a press. It measures
the whole string up front, so the box does not grow as the words appear.

---

# Game widgets

These are the ones that made this toolkit worth building. They live in
`dev.wildware.composegl.ui.game`.

## Bars

```kotlin
Bar(hull, Modifier.fillMaxWidth())
Bar(
    health,
    segments = 5,                   // pips, not a smooth bar
    thresholds = listOf(BarThreshold(below = 0.25f, style = "bar.critical")),
    trail = true,                   // the white "you just lost this much" tail
    holdMillis = 300,
    drainMillis = 450,
)
```

![four bars: health, a segmented shield, stamina, and stamina under its threshold](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-bars.png)

The trail is the thing: a hit drops the bar instantly and leaves a pale tail that
catches up a moment later, which is how a player sees *how much* they just lost.

## Reticle

```kotlin
val reticle = rememberReticleState()
Reticle(reticle, Modifier.align(Alignment.Centre))

// …from the game
reticle.spread = movement * 1.4f
reticle.hit(kill = true)
```

![a crosshair standing still, and a wider red one over something hostile](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-reticle.png)

Spread, bloom, hit markers. It is driven from your game code, not from state the
interface owns.

## Damage numbers

```kotlin
val numbers = rememberDamageNumbers()
DamageNumberLayer(numbers, projection = projection)

// …when something is hit
numbers.show("148", WorldAnchor.at(enemy.x, enemy.y + 2f, enemy.z), critical = true)
```

They float, fade, and are placed in the world through the same `WorldProjection`
your camera already provides.

## Cooldowns, hotbars, minimaps, notifications

```kotlin
val dash = rememberCooldown(durationMillis = 4_000)
RadialCooldown(dash)                   // the sweeping wedge over an ability icon

Hotbar(slots, selected = selected, onSelect = { selected = it }, onUse = { use(it) })

MinimapFrame(Modifier.size(160f), markers = contacts, range = 120f) { bounds ->
    drawWorld(bounds)               // `this` is the UiCanvas
}

val alerts = rememberNotifications()
Notifications(alerts)
alerts.show("SHIELD DOWN")
```

| | |
|---|---|
| ![a dark wedge over an ability icon, with the seconds left on it](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-cooldown.png) | the wedge sweeps away as the ability comes back |
| ![five hotbar slots, one selected, two holding charges](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-hotbar.png) | slots, charges, and which one is selected |
| ![a minimap frame with a compass and three markers](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-minimap.png) | your own map in the middle, the chrome and the markers from the skin |

## Particles

```kotlin
val sparks = rememberParticles(capacity = 240, seed = 11L)
ParticleLayer(sparks, Modifier.fillMaxSize())

sparks.burst(count = 24, x = hit.x, y = hit.y, style = HitSparks)
```

Bursts and streams, simulated with no allocation per particle. Seeded, so the same
seed gives the same burst — which is what makes it testable against a golden image.

---

## What next

- **[[Skins]]** — how all of these get their look
- **[[Input]]** — focus, pads, and keyboard
- **[[Shaders]]** — blurring, outlining or dissolving any of the above
