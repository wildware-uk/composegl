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

A disabled button still swallows the click, so it cannot fall through to whatever
is behind it.

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

`Dialog` puts itself on the back stack, so Escape and the pad's B button close it
without you wiring anything up. `OnBack { }` is how anything else joins that stack.

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

`TextField` handles selection, the clipboard, and the phone's keyboard, and
`onSubmit` fires on Enter. It works with a pad too: focus it and the on-screen
keyboard comes up on platforms that have one.

---

## Lists and scrolling

```kotlin
LazyColumn(count = saves.size, spacing = 6f) { index -> SaveRow(saves[index]) }
LazyRow(count = 9, spacing = 4f) { slot -> HotbarSlot(slot) }

ScrollArea(Modifier.fillMaxSize()) { LongPatchNotes() }
```

`LazyColumn` measures only what is on screen. `ScrollArea` is for content that is
one piece and simply too tall.

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

`PromptGlyph` changes the moment somebody picks up a pad, without anything being
reloaded.

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
`composegl.ui.game`.

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
