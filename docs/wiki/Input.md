# Input

Four calls in, one answer out.

```kotlin
interface InputSink {
    fun onPointer(event: PointerEvent): Boolean
    fun onKey(event: KeyEvent): Boolean
    fun onText(event: TextEvent): Boolean
    fun onGamepad(event: GamepadEvent): Boolean
}
```

That is the entire contract between your engine and the toolkit. Your backend
translates whatever it has into these; the toolkit answers **did I use that?** so
your game can take whatever is left.

There is no dependency on LibGDX, GLFW, Android or SDL anywhere in it. That is
also why the toolkit tests without a window: a test hands it the same events a
keyboard would.

---

## The interface gets first refusal

```kotlin
override fun onKey(event: KeyEvent): Boolean {
    if (ui.onKey(event)) return true    // a text field ate it
    return world.onKey(event)           // otherwise it is the game's
}
```

Write it that way and a player typing a save-game name does not also strafe.

---

## Wiring it up

Three pieces, each doing one job:

```kotlin
val focus = FocusManager(host.root)
val pointer = PointerRouter(host.root, focus)   // what is under the pointer, and what is a click
val keys = KeyRouter(focus, host.root)          // keys to the focused node, then outwards
val pad = GamepadNavigator(focus, onBack = { back() })
```

Behind one sink:

```kotlin
class GameInput(root: UiNode) : InputSink {
    private val focus = FocusManager(root)
    private val pointer = PointerRouter(root, focus)
    private val keys = KeyRouter(focus, root)
    private val navigate = KeyNavigator(focus, onBack = { back() })
    private val pad = GamepadNavigator(focus, onBack = { back() })

    override fun onPointer(event: PointerEvent) = pointer.onPointer(event)

    // The router first, always: a field that wanted the key has consumed it by the
    // time the navigator is asked. That is the whole of "a field takes the keys it needs".
    override fun onKey(event: KeyEvent) = keys.onKey(event) || navigate.onKey(event)

    override fun onText(event: TextEvent) = keys.onText(event)
    override fun onGamepad(event: GamepadEvent) = pad.onGamepad(event)

    fun frame(timeMillis: Long) {
        focus.refresh()       // focus may have been on a node that no longer exists
        pad.frame(timeMillis) // a held direction repeats
    }
}
```

Then point your engine's translator at it. With LibGDX:

```kotlin
Gdx.input.inputProcessor = InputMultiplexer(
    GdxPointerInput(input, { viewport }),
    GdxKeyboardInput(input),
)
GdxGamepadInput(input).start()
```

Call `input.frame(...)` once a frame, after layout — focus is worked out from where
things actually are.

---

## Focus

Focus is the cursor for anybody without a cursor. It moves by geometry: press down
and the nearest focusable thing below takes it.

```kotlin
Button("LAUNCH", onClick = { }, initialFocus = true)

Modifier.focusable()
Modifier.focusRequester(callsign)     // …and focus.focusOn(callsign) to send it there
Modifier.focusOrder(down = mapTab)    // override the geometry where it guesses wrong
Modifier.focusTrap()                  // a dialogue keeps focus inside itself
Modifier.onReveal { child -> scrollTo(child) }
```

`focus.refresh()` once a frame is what stops a menu ending up open with nothing
selected: when the node that had focus disappears, something real takes it.

---

## Which device is the player using?

```kotlin
val source = InputSourceTracker()
val sink = SourceAware(source, toolkit)   // wraps; the answers underneath are unchanged
```

Then:

```kotlin
source.current          // Mouse, Touch, Keyboard or Gamepad
source.isPointing       // there is a cursor to draw
source.showsFocusRing   // there is not, so draw the ring instead
```

A focus ring drawn while somebody is using a mouse is a second highlight fighting
the hover, which is why interfaces that draw it unconditionally look wrong on a
desktop. This is the answer to that, and it changes the moment somebody picks up a
pad.

It is also what `PromptGlyph` reads:

```kotlin
PromptGlyph(Action.Interact)       // E … or Ⓐ … or ✕
```

`Action` is a value class over a string, so your own actions cost nothing:

```kotlin
val Vent = Action("vent")
prompts.bind(Vent, PromptStyle.Xbox, Prompt(key = "pad.west", label = "X"))
```

A skin with a button atlas names its regions after those keys and gets real
buttons; a skin with none gets the label in a box, which is readable everywhere
and wrong nowhere.

---

## Back

Escape, the pad's B button and Android's back gesture are the same question: *what
is open?*

```kotlin
ProvideBackStack(backs) {
    Screen()
}

// anywhere inside
OnBack(enabled = inventoryOpen) { inventoryOpen = false }
```

The innermost one wins: the stack asks the most recently added handler first, and
the most recently added is whatever opened last. `Dialog` puts itself on the stack,
so Escape and B close it without you wiring anything.

Ask the stack first and let the screen handle what nothing wanted:

```kotlin
GamepadNavigator(focus, onBack = { if (!backs.back()) state.back() })
```

---

## Raw events

When a widget needs more than a click:

```kotlin
Modifier.onPointer { event ->
    when (event) {
        is PointerEvent.Press -> { drag.start(event.position); true }
        is PointerEvent.Move -> { drag.to(event.position); true }
        is PointerEvent.Release -> { drag.finish(); true }
        is PointerEvent.Cancel -> { drag.abandon(); true }
        else -> false
    }
}
```

`Cancel` is the one people forget. The window lost focus, the platform started a
system gesture, a finger lifted outside the screen — whatever had the pointer must
let go **without** firing a click. Handle it and a dragged slider does not stick.

---

## Text

```kotlin
Modifier.onTextEvent { event -> buffer.insert(event.text); true }
```

Text is separate from keys on purpose: a key is a physical button, and text is what
the platform decided those buttons meant — which depends on layout, modifiers and
the input method. `TextField` already does all of this.

---

## What next

- **[[Widgets]]** — `TextField`, `Slider`, `Hotbar` and what they do with input
- **[[Backends]]** — the translators that ship, and writing your own
- **[[Testing]]** — handing the toolkit events with no window anywhere
