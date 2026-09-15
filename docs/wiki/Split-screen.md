# Split-screen

Local co-op: two to four players on one screen, each with their own HUD, their own
menus and their own focus. Player two's stick never moves player one's cursor.

![two players' pause menus side by side, player one on RESUME, player two moved down to LEAVE with their own pad](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/split-screen.png)

It takes three things, and you already know two of them:

1. **A `UiHost` per player.** Each host has its own tree, clocks and recomposer, so
   two of them are two completely separate interfaces.
2. **A `Viewport` per player,** from `Viewport.splitScreen`. It says which part of the
   window is theirs.
3. **One `InputRouter`,** which hands each pad, the keyboard and the mouse to the
   right player.

---

## The viewports

```kotlin
val viewports = Viewport.splitScreen(
    design = Size(960f, 1080f),      // one player's interface
    physical = window.framebuffer,   // the whole window, in real pixels
    players = 2,
)
```

| Players | Layout |
|---|---|
| 1 | the whole window |
| 2 | side by side, or one above the other with `stacked = true` |
| 3 | a quarter each; the bottom-right quarter is left for the game (a map, a scoreboard) |
| 4 | a quarter each, left to right, then down |

Every player is laid out at the same `design` size and fitted into their own area
with the usual [[Layout]] scale policy. So one HUD, written once, is right in every
quarter. Pick a design the same shape as one player's area and nothing is
letterboxed.

A viewport's `area` is where on the window it goes. Its `origin` is still measured from
the window's corner, so a canvas draws into the right place with nothing else told
about the split. The safe area is measured from the window's edges too: a notch on
the left only pushes in the player on the left.

## Drawing

One `UiRenderer` per player, all on the same canvas, one after another:

```kotlin
val hosts = List(2) { UiHost() }
val renderers = hosts.map { UiRenderer(it, canvas) }

// each frame, after the game has drawn every player's view of the world:
renderers.forEachIndexed { i, ui -> ui.render(viewports[i], System.nanoTime()) }
```

Each player's drawing is cut off at the edge of their own area, so nothing one player
draws lands in the other's half. That holds even with `ScalePolicy.Fill`, where a
design a different shape from its area is scaled past the area's edges.

## Input

A backend knows nothing about players. It pushes every pad, the keyboard and the
mouse into one sink. Make that sink an `InputRouter`:

```kotlin
// Each player's sink is what a one-player game already has: routers and
// navigators behind a SourceAware (see Input).
val router = InputRouter(unclaimed = lobby)     // pads nobody owns yet go here

router.assignGamepad(GamepadId(0), playerOne)
router.assignGamepad(GamepadId(1), playerTwo)
router.assignKeyboard(playerOne)
router.assignPointer(viewports[0], playerOne)
router.assignPointer(viewports[1], playerTwo)

GdxGamepadInput(router).start()
Gdx.input.inputProcessor = InputMultiplexer(
    GdxPointerInput(router, { Viewport.oneToOne(windowSize) }),   // the router wants window pixels
    GdxKeyboardInput(router),
)
```

The rules:

- **Pads go by number.** Every gamepad event says which pad it came from. When a pad
  changes hands, its old owner is told it was unplugged first, so a stick held while
  pads were being handed out does not keep scrolling the menu it left. A pad that is
  really unplugged keeps its owner, so plugging it back in gives it back.
- **The keyboard goes to one player,** because there is only one. Text goes with it.
- **The pointer goes by where it is.** A click in the right half reaches player two, in
  player two's own coordinates. A press keeps the pointer until release, so a slider
  dragged past the middle stays with whoever grabbed it. Moving from one half into the
  other ends the hover in the first.
- **Whatever belongs to nobody** (an unassigned pad, a click outside every area) goes to
  `unclaimed`, in window coordinates. Leave it null to ignore those.

Give each player their own `InputSourceTracker` and their prompts follow their own
device: the player on the keyboard reads **E**, the one on the pad reads **A**.

### "Press Start to join"

A pad nobody owns goes to `unclaimed`. Make that the lobby:

```kotlin
val lobby = object : InputSink {
    override fun onGamepad(event: GamepadEvent): Boolean {
        if (event !is GamepadEvent.ButtonDown || event.button != GamepadButton.Start) return false
        if (router.ownerOf(event.gamepadId) != null) return false
        router.assignGamepad(event.gamepadId, nextFreePlayer())
        return true
    }
    override fun onPointer(event: PointerEvent) = false
    override fun onKey(event: KeyEvent) = false
    override fun onText(event: TextEvent) = false
}
```

When a player leaves, `router.unassign(player)` gives back every device they held and
lets go of anything they had pressed.

When the window is resized, make new viewports and call `assignPointer` again for
each player. That replaces their old area, and cancels a press still held in it.

---

## Testing it

`uiTest` takes a viewport, and `UiTest.input` is the player's sink, so a test wires
two screens behind a router exactly as a game does:

```kotlin
val halves = Viewport.splitScreen(Size(400f, 300f), Size(800f, 300f), players = 2)
val one = uiTest(Size(400f, 300f), viewport = halves[0]) { Menu() }
val two = uiTest(Size(400f, 300f), viewport = halves[1]) { Menu() }
val router = InputRouter().apply {
    assignGamepad(GamepadId(0), one.input)
    assignGamepad(GamepadId(1), two.input)
}

router.onGamepad(GamepadEvent.ButtonDown(GamepadId(1), GamepadButton.DpadDown))
one.settle(); two.settle()

two.assertFocused("options")
one.assertFocused("play")
```

Events sent to a router do not settle anything, so call `settle()` on each player after.
`SplitScreenUiTest` in `composegl-ui` is the worked example, and `GdxSplitScreenTest` in
`composegl-gdx` checks both halves in real pixels.

## What next

- **[[Input]]** — the sink each player's router hands events to
- **[[Layout]]** — scale policies and the safe area
- **[[Testing]]** — `uiTest` and golden images
