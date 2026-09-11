# Snake, written twice

The same small game, with the same rules and the same tests, given an interface twice: once with
Material 3 on Compose for Skia, and once with this toolkit. This is the honest comparison the
rewrite is supposed to earn, so it records what got worse as well as what got better.

Both versions are in the repository's history — the old one at the `skia-final` tag, the new one in
`composegl-demo-snake`.

## What did not change at all

`SnakeGame` (158 lines) and its 14 tests are byte for byte the same file. `SnakeSession` changed one
field: the player's name went from `TextFieldValue` to a `String`, because our `TextField` has a
plain-string overload. Its 15 tests are unchanged apart from that one type.

That is the part worth saying first. The rules of a game have nothing to do with a UI toolkit, and
neither version made them think about one.

## What got easier

**One interface file instead of six.** The old version had `SnakeUi`, `MenuScreen`, `HudOverlay`,
`PauseScreen`, `GameOverScreen`, `WallScoreboard` and a `Theme` — 607 lines. The new one is a single
`SnakeUi.kt` of 263 lines, and the reason is not density: it is that none of it declares a colour, a
corner radius or a text size. All of that moved into `ui/snake.skin.json`, which is watched while the
game runs, so re-skinning the game is editing a file and looking at the window rather than an edit,
a rebuild and a restart.

**The theme object is gone.** `Theme.kt` existed to translate a game's palette into Material's
`ColorScheme` and `Typography`, which is work you only do because the widgets demand that shape. A
style here is a name a widget asks the skin for, and a name nobody wrote falls back to a shorter one.

**The glue shrank.** `SnakeApp` was 303 lines of LibGDX lifecycle, Compose overlay setup, texture
sizing and a self-check. The new `Main.kt` is 205, and most of that is the high-score file and the
screenshot writer; the frame itself is fifteen lines — draw the board, run the composition, draw the
interface, present.

**The dependency list.** The old demo pulled in LibGDX, Skia through Skiko, Material 3, and an AWT
window underneath the lot. The new one depends on `composegl-lwjgl3` and the Compose *runtime* — no
Skia, no AWT, no engine.

**It costs almost nothing to draw.** A frame of the running game is **one** draw call, and the menu
with two panels, a text field, a slider, a toggle and four chips on it is **three**. The old version
composited a full-window Skia surface every frame the HUD changed.

## What got worse

**There is no transition.** Material gave us `AnimatedVisibility` for free, so the old menu faded
and scaled in. Ours changes screens with an `if`, and it snaps. We have `Animatable` and
`animateFloatAsState`, so a fade is a few lines, but a fade in *and out* needs the leaving screen to
stay mounted while it goes — which is exactly what `AnimatedVisibility` did and we do not have yet.

**Widgets we had to draw ourselves.** `Card`, `Badge`, `FilterChip`, `AssistChip` and
`HorizontalDivider` have no equivalent. A chip is a `Button` with a different style name and a badge
is a `Box` with one, which is ten lines of skin rather than ten lines of Kotlin — but somebody has to
write them, and Material's come with states already thought about.

**`animateIntAsState` does not exist**, so the rolling score is a float rounded on the way out.

**Input routing is the game's problem.** While the snake is moving, the arrow keys must steer it and
must *not* walk focus around the HUD's buttons. That is `SnakeInput`, 119 lines, and the toolkit gave
us the pieces (`KeyRouter`, `KeyNavigator`, `PointerRouter`, `GamepadNavigator`) rather than an
answer. The old version had the same problem and solved it by clearing focus with Material's
`LocalFocusManager` while playing — one line. Ours is more honest about who gets a keystroke and
more work.

**Text sizes are not arbitrary.** Fonts are rasterised at the sizes the game registers, so a skin
asking for 15px when 13, 16, 20 and 34 were registered is an error rather than a slightly different
label. That is the price of no runtime text engine, and it wants saying out loud in the skin docs.

## The verdict

For this game, the toolkit won on everything that is about a game — what it costs to draw, what it
drags in, how a designer changes it — and lost on everything that is about a widget library being
old. The gaps are a list of tickets, not a rethink: screen transitions, a handful of widgets, and an
`animateIntAsState`.

*Everything here was run on Mesa llvmpipe (software rendering) on Linux. No real GPU, no macOS, no
Windows, no phone, and no gamepad hardware exists on this machine — pad behaviour in either version
is unverified.*
