# Widgets

Everything that ships in `composegl-ui`: the ordinary interface controls. The tier
of things only games need — bars, a reticle, a hotbar — is in `composegl-game`, on
**[[Game widgets]]**.

None of it is Material. There is no theme to fight, and every widget takes its
look from a [[skin|Skins]] file rather than from code.

---

## Text

```kotlin
Text("HULL INTEGRITY")
Text("148", colour = Colour.rgb(0xE5484D))
Text(story, softWrap = true, maxLines = 3, ellipsis = "…")
Text(name, Modifier.weight(1f), align = HorizontalAlignment.End)
Text(trackName, Modifier.width(160f).marquee())   // too long? it scrolls round
```

A name too long for a fixed slot can scroll instead of wrapping or ellipsising: see
[Marquee](Animation.md#marquee) in [[Animation]].

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

To see where those lines are, turn on `TextMetricsOverlay`: line box, ascent, cap
height, baseline and descent, in five colours, through every label, text field,
typewriter, tooltip and prompt glyph. See
[Debugging](Debugging.md#the-lines-inside-text-on-the-screen).

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

### Text size, separate from the interface scale

The `Viewport` scales the whole interface. A player who cannot read the words
does not want bigger panels, bigger icons and bigger gaps too — that spends the
screen on what was already big enough. So text has its own setting:

```kotlin
ProvideTextScale(settings.textScale) { Game() }
```

Everything inside draws its text that many times the size its style says, and
everything sized by its contents grows to fit: a button round its label, a
tooltip, a row, a field's height. Anything you gave a fixed size keeps it, and
text in it wraps sooner. Icons, bars and padding do not move.

![text at 100%, 125% and 150%: the labels and the button grow, the panel and the icon do not](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-text-scale.png)

- **Every text widget follows it**: `Text`, `Typewriter`, `TextField`,
  `Tooltip`, `PromptGlyph`, and from [[Game widgets]] `DamageNumberLayer` and `MinimapFrame`'s compass, and so
  everything built from them, like `Button` and `Stepper`. A `textStyle` you pass by hand is scaled too.
- **Nested, they multiply.** A dense panel that asks for `0.85f` inside a
  player's `1.5f` draws at 1.275, so it still honours the setting.
- **Changing it re-measures.** It is meant to move when a slider in a settings
  menu does, not every frame.
- **Sizes are rounded to whole numbers**, so 16 at 110% is 18, not 17.6. That is
  what keeps it sharp: the text is measured and baked at the bigger size rather
  than measured small and stretched.

The one thing to do in return is register the sizes. A backend bakes fonts at
startup and refuses a size it has never seen, so give it every size at every
scale your setting offers:

```kotlin
val sizes = scaledTextSizes(listOf(13, 16, 20), listOf(1f, 1.25f, 1.5f))
fonts.registerTrueType("body", Gdx.files.internal("fonts/body.ttf"), sizes)
```

Forget one and the error names the size it wanted and the sizes it has.

### Characters your font does not have

A display font rarely has Chinese, Japanese, Korean or emoji, and player names
and chat have all of them. Name the fonts to borrow from, in order:

```kotlin
fonts.registerTrueType("body", Gdx.files.internal("fonts/body.ttf"), sizes)
fonts.registerTrueType("cjk", Gdx.files.internal("fonts/NotoSansCJK.ttf"), sizes, onDemand = true)
fonts.registerPictures("emoji", mapOf("😀" to smiley, "👍" to thumbsUp), sizes)

fonts.fallBackTo(listOf("cjk", "emoji"))
```

![a chat panel mixing English, Chinese, Japanese, Korean and emoji in one skin](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-text-fallback.png)

- **One character at a time.** `"Ace 玩家 😀"` takes its letters from `body`,
  its Chinese from `cjk` and the smiley from `emoji`. Your font always wins for a
  character it has; only a character nothing has comes out as a box.
- **It sits on your font's line.** Borrowed glyphs are moved onto your font's
  baseline, and the line height is still yours, so a label does not jump when a
  name with 한글 in it arrives.
- **`onDemand = true`** makes a character the first time text uses it, instead
  of baking the whole font at startup — a CJK font has tens of thousands. The
  atlas takes another page when one fills.
- **Emoji are pictures.** Register one per character, from any emoji set. They
  are drawn in their own colours whatever colour the text is, and an outline
  rings the letters but not the pictures.
- **Every size, again.** A fallback must be registered at every size it is asked
  for, text scale included, or the error names the size it wanted.
- `fallBackTo("display", listOf(...))` gives one family its own list.

On the raw OpenGL backend, `StbFonts` has the same `fallBackTo` and
`registerPictures` (from PNG bytes), but bakes everything up front: register a
fallback with `codepoints = StbFonts.codepointsOf(textYouExpect)` rather than
the whole font.

Not yet: emoji made of several characters joined together (families, flags,
skin tones), right-to-left scripts, and scripts that need shaping, like Arabic
or Devanagari.

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
and from [[Game widgets]] `DamageNumberLayer` and `MinimapFrame`'s compass letters. Not `TextField` or
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

### Selectable text

A label is scenery: the pointer passes straight through it. For the things a
player wants to copy out of a game — a seed, a server address, a lobby code, an
error message for a bug report — wrap them:

```kotlin
SelectionContainer { Text("Seed: 8F3A-22C1") }
```

![A seed code with its code dragged over and highlighted](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-selection.png)

Inside, every `Text` takes the same gestures a `TextField` does, from the same
code: press and drag to select, double-click a word, triple-click a line,
shift-click to extend. **Ctrl+C** copies (Command+C on a Mac), **Ctrl+A** selects
the whole label, and shift with the arrows, Home and End moves the far end.

- **One selection per container.** Pressing a second label moves it there, as on
  a web page. Pass a `SelectionState` to read `selectedText` or `clear()` it.
- **Pad navigation is unchanged.** A click brings the keyboard to the label, so
  Ctrl+C talks to it rather than to the last button, but a label is never a Tab
  stop or somewhere the d-pad lands. `Modifier.focusableByPointer()` is that rule
  on its own, for widgets of your own.
- **Controls stay controls.** The labels on `Button`, `Checkbox`, `Toggle`,
  `RadioButton`, `Stepper`, and from [[Game widgets]] a `Hotbar` slot and a click-to-dismiss notification
  are not selectable, so pressing them still presses. Anything else
  you press — a `clickable` save slot or list row — needs its text wrapped in
  `DisableSelection { }`, or the label takes the press first.
- **Focus leaving clears it**, and so does the label's text changing.
- The highlight is the skin's `selection` style (its background), falling back to
  `field.selection`.

One thing changes underneath: a label inside breaks its own lines, as a
run-styled `Text` does, because a selection has to know where each character is.
Very occasionally that moves a line break by one word.

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

A click also asks for a light tap on a phone or a pad, if the game provided one —
see [[haptics|Input#haptics]].

Same widget, a different style name, and it is a chip:

![four chips, one chosen and one in the danger style](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-chips.png)

---

## Containers

```kotlin
Panel(Modifier.width(280f)) { … }          // a box with the skin's panel look
Panel(style = "panel.raised") { … }

Dialog(onDismiss = { open = false }) {      // a panel over a dimmed screen
    Text("Abandon the run?")
    Row { Button("YES", onClick = ::abandon); Button("NO", onClick = { open = false }) }
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
it grows to fill whatever holds it. `Row(Modifier.height(IntrinsicSize.Min))` makes
the row, and so the line, as tall as the tallest thing in it. Inside a `ScrollArea` there is no limit, so give
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
`onSubmit` fires on Enter.

### Typing with only a pad

A console or a Steam Deck has no keyboard at all. Wrap the screen and every field
in it gets one made of buttons:

```kotlin
ProvideGamepadKeyboard {
    NameYourSave()
}
```

![a keyboard of buttons along the bottom of the screen, under a name field](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-gamepad-keyboard.png)

- It opens when a **pad** moves focus onto a field. A mouse click or Tab never
  opens it, so desktop players never see it.
- The d-pad walks the keys, South presses one. The keys type through the field's
  own editor, so `maxLength` and the caret work the same as with a real keyboard.
- Three pages — letters, symbols, a number pad — and the page key cycles them.
  Shift gives one capital, then lets go.
- Done, B or Escape close it, and focus goes back to the field. South on the
  field opens it again.
- Picking up the mouse, or typing on a real keyboard, closes it. The typed
  letters still reach the field.

`GamepadKeyboard(openOnFocus = false)` waits for South instead, for a long form a
player walks down. The pad's shortcut buttons — X deletes, Y is a space, the
bumpers move the caret, the left stick is Shift, Start is done — need one line in
your input sink, because the pad navigator does not use those buttons:

```kotlin
override fun onGamepad(event: GamepadEvent) = keyboard.onGamepad(event) || pad.onGamepad(event)
```

The keys draw from `"button.key"` (a lit Shift is `"button.key.on"`), Done from
`"button.primary"` and the panel from `"panel.keyboard"`. A skin without them
falls back to plain buttons and a panel.

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
- **Each step ticks.** On a phone or a pad every change asks for a
  [[haptic|Input#haptics]] `Tick`. A press at an end that only moves focus
  gives nothing.

Styles: `stepper` behind it, `stepper.arrow` for the two arrows (pressed while
held, disabled at an end), `stepper.value` for the words.

### Dropdowns

One choice out of a list that opens and closes — resolution, language,
difficulty, a quality preset:

```kotlin
PopupHost {                           // once, around the screen
    Dropdown(
        options = resolutions,
        selected = current,
        onSelect = { current = it },
        modifier = Modifier.width(200f),
        label = { Text(it.toString()) },
    )
}
```

![a resolution dropdown open over the settings under it, with the chosen option lit](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-dropdown.png)

A click, Enter or the pad's South opens the list under the field, **over
everything else on the screen**, with focus on the option that is chosen now.
The arrows or the d-pad move, and the same press chooses. While it is open:

- **Focus cannot leave the list.** A pad pressing down past the last option
  stays on it, rather than wandering into the screen behind.
- **Escape, East and Back close it** without choosing, before they reach any
  `OnBack` behind it. Focus goes back to the field either way.
- **A press outside closes it and does nothing else.** It is not also a click
  on whatever was under the pointer.

The list is as wide as the field, so give the field a width that fits the
longest option. It opens upwards when there is no room below, and scrolls when
it is taller than `maxListHeight` or than the room it has.

`PopupHost` is what draws it on top. Draw order is tree order, so the only
place a list can be drawn over its neighbours — and escape a `ScrollArea` that
would clip it — is the end of the screen. The host composes the list there, but
**as if it were where the dropdown is**: it still gets the skin, the fonts and
anything else you provided around the dropdown. Forget the host and the screen
fails as it is built, saying so.

The field is the `"dropdown"` style, the list's panel `"dropdown.list"`, and
the options are `"item"` and `"item.selected"`.

---

## Menus

File, Edit, View along the top of an editor, and a menu that opens on an
inventory slot. Both are written in one scope — `Item`, `CheckItem`,
`RadioItem`, `Submenu` and `Separator` — so a list of items written once works
in either. Both drop through a `PopupHost`, so one has to be round the screen.

### Menu bars

```kotlin
PopupHost {
    Column(Modifier.fillMaxSize()) {
        MenuBar(padButton = GamepadButton.Back) {
            Menu("&File") {
                Item("&New", shortcut = Modifiers.Primary + Key.N) { newLevel() }
                Item("&Save", shortcut = Modifiers.Primary + Key.S, enabled = dirty) { save() }
                Submenu("&Recent") {
                    recent.forEach { level -> Item(level.name) { open(level) } }
                }
                Separator()
                Item("&Quit") { quit() }
            }
            Menu("&View") {
                CheckItem("&Grid", checked = showGrid) { showGrid = it }
                Separator()
                RadioItem("&Wireframe", selected = mode == Wire) { mode = Wire }
                RadioItem("S&haded", selected = mode == Shaded) { mode = Shaded }
            }
        }
        LevelEditor()
    }
}
```

The bar is as wide as what holds it: the top of the screen, or the top of a
window. The menu scope is plain Kotlin, not composables. It runs every time the
bar is composed, so `enabled = dirty` greys Save out the moment nothing needs
saving, and a shortcut works while its menu is closed.

- **Mouse.** Click a title to open it; click it again to close it. While one is
  open, move onto another title to switch. Rest on a submenu's row to open it.
  Moving diagonally from that row towards the submenu does not close it, even
  across the rows in between.
- **Keyboard.** Alt on its own, or F10, puts focus on the bar. The arrows move,
  and Down, Enter or a title's letter opens a menu. Alt with a letter — Alt+F —
  opens that menu from anywhere. Alt held for anything else — Alt+Left in a
  field, Alt+click — does not. A click below the bar while it has focus gives
  focus to what was clicked. In a menu, Right opens a submenu and Left
  closes it; Left and Right with nowhere to go move to the next menu along.
  Escape closes one level at a time, and the last one gives focus back to
  wherever it was.
- **Shortcuts.** `Modifiers.Primary + Key.S` is Ctrl+S, or Command+S on a Mac,
  and the menu writes it that way beside the item. It fires from anywhere on
  the screen while every menu is closed. It does not fire through an open
  dialogue, or when the focused widget used the key itself — a text field keeps
  its Ctrl+A.
- **Pad.** `padButton` puts focus on the bar and takes it away. The d-pad moves,
  South opens and chooses, the shoulders switch menus, and East closes one
  level through `OnBack`. Leave `padButton` null and a pad cannot reach the bar.
- **Letters.** `&` marks the letter: `"&File"` underlines the F while the
  keyboard or pad is driving the bar. `&&` is an ampersand. A translated label
  marks its own letter — `"&Fichier"`.
- **Right to left**, the bar reads from the right, menus hang from a title's
  right edge, submenus open to the left, and Left opens a submenu.

Items can be disabled (`enabled = false`), which greys them out, skips them on
the arrows and stops their shortcut. `icon = { Image("icons/save", Modifier.size(16f)) }`
puts a picture in the column before the label. A submenu with no room on its
side opens on the other side.

Styles: `"menubar"` behind the titles, `"menubar.title"` for a title and
`"menubar.title.open"` for the one whose menu is open. A menu is `"menu"`, a row
`"menu.item"` (`"menu.item.open"` while its submenu is open), then
`"menu.shortcut"`, `"menu.separator"`, and `"menu.check"` and `"menu.radio"` for
the tick and the dot.

### Context menus

```kotlin
Box(Modifier.size(64f).contextMenu {
    Item("&Use") { use(item) }
    Item("S&plit stack", enabled = item.count > 1) { split(item) }
    Separator()
    Item("&Drop") { drop(item) }
})
```

It opens four ways:

- **Right-click** — at the pointer. It wins over a button inside that only
  clicks. A widget that takes the right button itself with `onPointer` keeps it,
  and so does a button that is not inside the menu's widget, like a HUD button
  drawn over a map.
- **Long press** — at the finger, after the same hold `onLongPress` uses. Like a
  right-click it reaches past a button inside that only clicks, so on a touch
  screen a slot built as a button still opens its menu. The release is not a
  click. A widget's own `onLongPress` wins. Only a pointer or a finger holds it
  open: a held Enter or South is still a click. A press that moves — a slider's
  thumb, a list scrolling — is not a hold. A quick tap still reaches a
  clickable around the menu's widget.
- **Shift+F10** — the menu of the focused widget, or of the nearest one around
  it with a menu, opens under the focused widget itself.
- **`padButton`** (the pad's North unless you say) — the same, from a pad.

None of these reach past a dialog: a `Dialog` open over the menu's widget keeps
Shift+F10, the pad button and right-clicks on its own buttons to itself.

It hangs down and towards the end of where it opened, and flips back when it
would go off the screen. While it is open, focus is trapped in it. Escape, Back,
East and a click outside close it, and so does the widget it opened on leaving
the screen. While it is open it shows the items as the widget last composed
them. Its items' shortcuts are shown but do not fire; put the same item on a
`MenuBar` for that.

To share items, write them as an extension and call it in both:

```kotlin
fun MenuScope.editItems() {
    Item("Cu&t", shortcut = Modifiers.Primary + Key.X) { cut() }
    Item("&Copy", shortcut = Modifiers.Primary + Key.C) { copy() }
}

MenuBar { Menu("&Edit") { editItems() } }
TextField(notes, { notes = it }, Modifier.contextMenu { editItems() })
```

---

## Lists and scrolling

```kotlin
LazyColumn(count = saves.size, spacing = 6f) { index -> SaveRow(saves[index]) }
LazyRow(count = 9, spacing = 4f) { slot -> HotbarSlot(slot) }
LazyVerticalGrid(count = shop.size, columns = GridCells.Adaptive(64f)) { index -> ShopTile(shop[index]) }

ScrollArea(Modifier.fillMaxSize()) { LongPatchNotes() }
```

![a panel of save slots with a scrollbar down the side](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-scroll.png)

`LazyColumn` and `LazyVerticalGrid` measure only what is on screen; [[Layout]]
covers grids, and lists in sections whose headers stay at the top
(`LazyColumn { stickyHeader { … }; items(…) { … } }`). `ScrollArea` is for content that is
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

### Sprite-sheet animation

Moved to [Animation](Animation.md#sprite-sheet-animation).

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

Moved to [Animation](Animation.md#entering-and-leaving-animatedvisibility).

---

## Rebinding controls

```kotlin
KeyBindButton(
    binding = binds[Jump],                    // a key, a mouse button or a pad button; null draws a dash
    onBind = { input ->
        binds[Jump] = input
        prompts.bind(Jump, input)             // every PromptGlyph(Jump) follows
    },
    cancelKey = Key.Escape,                   // and GamepadButton.Back on a pad
)
```

![a controls list with one binding waiting for a press](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/widget-keybind.png)

Click it, press Enter on it or press South on it, and it says **PRESS A KEY**. The
next press of anything is the answer, and nothing else hears it: an arrow or the
d-pad is bound instead of moving focus, East is bound instead of going back. The
release of that press is swallowed too, so binding Enter or South does not start it
listening again. Escape or the pad's Back gives up, and so does focus leaving it.

A mouse button counts when it is pressed on the button, which is where the cursor
already is. Sticks and triggers are not bindings. `accepts = { it !is InputBinding.Mouse }`
refuses a kind of press and keeps listening.

It never decides what a clash means. `onBind` gets the press either way, and
`clashesWith` says who else has it:

```kotlin
onBind = { input ->
    val clash = binds.clashesWith(input, ignoring = Jump)
    if (clash.isEmpty()) binds[Jump] = input else warning = "Already used by ${clash.first()}"
}
```

Pass a `KeyBindState` to read `isListening` from outside — a "press Esc to cancel"
line under the list — or to call `listen()` yourself.

---

## Switching screens: Crossfade

Moved to [Animation](Animation.md#crossfade).

---

## Sliding between pages: AnimatedContent

Moved to [Animation](Animation.md#animatedcontent).

---

## Several values off one state: updateTransition

Moved to [Animation](Animation.md#several-values-off-one-state-updatetransition).

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

Bars, the reticle, damage numbers, cooldowns, the hotbar, the minimap frame,
notifications and particles live in their own module, `composegl-game`. They
have **[[their own page|Game-widgets]]**.

---

## What next

- **[[Game widgets]]** — bars, reticle, damage numbers, cooldowns, hotbar, minimap, particles
- **[[Skins]]** — how all of these get their look
- **[[Input]]** — focus, pads, and keyboard
- **[[Shaders]]** — blurring, outlining or dissolving any of the above
- **[[Animation]]** — clocks, animated values, and panels that come and go
