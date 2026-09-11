# ComposeGL

A user-interface toolkit for games, built on Jetpack Compose's runtime and drawn with OpenGL.

**Being rebuilt.** Read [the design](docs/superpowers/specs/2026-09-09-runtime-ui-design.md) for
where it is going, and [spike S6](docs/superpowers/spikes/s6-runtime-ui.md) for the evidence it
works.

## What it is

Compose is the brain: the compiler plugin, the state system, and recomposition, which is the part
that makes an interface redraw only when something actually changed. Everything below the neck —
the widgets, the layout, the drawing, the input — is ours, drawn through LibGDX with a sprite batch
and a font.

The toolkit itself is a Kotlin Multiplatform module: every line of it is common code, and it is
compiled for a JVM *and* for Linux native on every build. That second target is not a product — it
is the thing that stops "this is portable" from being a claim nobody checks. A backend is what ties
it to a machine, and there are two of those.

It also means we are free to build the toolkit games actually need — skins from a texture atlas,
focus that works on a gamepad, animations on a clock the game can pause — instead of a
general-purpose one bent into shape.

![The example running](docs/images/demo.png)

*The example, in `composegl-demo`. Every panel, border, shadow, bar, blip and letter is drawn by our
own renderer from a tree the Compose runtime maintains. The STATUS and RANGE panels are drawn by the
shader — one quad each for corner, border and shadow together. The BRIEFING panel is nine-patch art
out of a texture atlas, and the gap between its frame and its text is a number in the atlas file
rather than a number in the source. The headings sit on a ribbon whose hatch repeats sideways and
stretches downwards. The reticle, the bars, the floating damage numbers, the minimap, the hotbar and
the key prompts under it are widgets the toolkit ships. The whole screen costs eleven draw calls,
and most of those are a scissor going on or off — the scrolling log and the minimap each clip, and a
clip has to flush what is queued. Every glyph at every size shares one page with the white texel
solid colour is drawn from, so the only texture changes left are the game's own art.*

## The thing it is for

A game interface should cost nothing when nothing is happening. In the spike, a still interface
redrew **4 times in 119 frames** — once at startup, once per button press. Switch an animation on
and it redraws every frame; switch it off and it stops. That behaviour is the whole reason to use
Compose, and it survives without Compose UI.

## Getting it

```kotlin
repositories { mavenCentral() }

dependencies {
    implementation("uk.wildware.composegl:composegl-ui:0.1.0")     // the toolkit
    implementation("uk.wildware.composegl:composegl-gdx:0.1.0")    // a backend — pick one
}
```

A backend is what actually draws, and a game needs exactly one: `composegl-gdx` for LibGDX,
`composegl-lwjgl3` for raw OpenGL. `composegl-android` and `composegl-robovm` go alongside a
backend on a phone, and answer the things no engine reports — what the keyboard is covering, and
what the platform's own text input is doing. `composegl-effects` is optional, and
`composegl-testing` is for testing your own widgets.

## Status

**0.1.0 is the first release, and it is a first release.** The interfaces will move. What works
today is the picture above: layout,
the modifier chain, the renderer, fonts, nine-patch art, and all three ways in — a mouse, a
keyboard and a gamepad, with hit testing, focus and key routing behind them. The chips and the
hotbar in the example light up under the pointer, respond to a click, draw a focus ring that Tab
and a d-pad move, and answer the number keys.

Every colour, corner, padding and slice in that picture comes from
[one JSON file](composegl-demo/src/main/resources/ui/demo.skin.json); not one of them is written in
the example's Kotlin. Save the file while the example is running and it changes on the next frame.
A skin that will not parse names the line and suggests the nearest region or key that would have
worked, and a broken save leaves the last skin that worked on screen. The widget set has started:
`Text`, `Image`, `Button`, `IconButton`, `Checkbox`, `RadioButton`, `Toggle`, `Slider`,
`ScrollArea`, `LazyColumn`, `LazyRow`, `Panel`, `Dialog`, `Tabs`, `TextField`, `Bar`, `Hotbar`,
`RadialCooldown`, `DamageNumberLayer`, `ParticleLayer`, `Reticle`, `Tooltip`, `Typewriter`, `PromptGlyph`, `Notifications` and `MinimapFrame` ship with
the toolkit, and the example is built from them. Typing works the whole way through: a caret that blinks
and stops blinking mid-word, selection by shift or by dragging, double-click for a word, word-wise
movement, the system clipboard through whichever backend is running, and a field that scrolls
sideways so a long name never types itself off the edge.

Animation is in too, and it names a clock. `animateFloatAsState`, `animateColourAsState` and an
`Animatable` a game drives itself, over tweens, springs and the usual easings — but an animation
belongs to `Clock.Ui` or `Clock.World`, and the game decides which clocks advance. Freezing the
world does not freeze the pause menu sitting on top of it, and a world animation resumes from where
it stopped rather than jumping to where it would have been. An animation that has arrived
unsubscribes, so a screen full of settled animations costs exactly as many redraws as a screen with
none: zero, and there is a test that drives a hundred frames to prove it.

The game widgets start with the health bar, which is where the clocks earn their keep. The fill
moves the instant the value does; a ghost bar behind it holds for a moment and then drains down to
meet it, so a player sees *how much* they just lost rather than only that they lost some. Two hits
in a row keep draining towards the newer value instead of the trail jumping back up and starting
again, healing has no trail at all, and the whole thing runs on `Clock.World`, so a paused game is
not still visibly bleeding. Thresholds name their own fill style — "red below a quarter" is one
line at the call site and a colour in the skin file — and a bar that low breathes.

Next to them is the ability cooldown: a dark wedge that covers what is left and sweeps away
clockwise, the seconds remaining on top of it, and a flash when it comes back. Triggering one that
is already running is ignored rather than starting it again, so the key a player is mashing cannot
push the end of it further away. The wedge covers a square icon's corners rather than a circle
inside it, and it is exact at any size: the edge of a rectangle is four straight lines, so the
sweep needs no arc and no smoothness setting. Drawing it added the one primitive this toolkit's
short drawing interface was missing — a triangle fan — which is also what a radial menu and a
compass needle are made of.

The bar those go in is a widget too. A hotbar answers the number keys, a click and a pad at once,
because a player uses all three; an empty slot looks different from a slot holding something that
cannot be used right now, because those mean different things to somebody deciding what to press;
and a press that would do nothing — empty, switched off, out of charges, still cooling down — is
not drawn as a press and never reaches the game. The number keys have to work wherever the player
is, and a key event only reaches a widget while focus is inside it, so the press is hoisted into a
`HotbarState` a game puts on its screen or calls from its own bindings.

Damage numbers are the first thing here that belongs to the world rather than the screen. A hit
puts a number over the thing that was hit, it floats, fades and goes; a critical is bigger and
lasts longer. Two hundred of them at once is an ordinary moment in a game, so the whole layer is
one node that measures each number once and draws the rest straight onto the canvas — no node per
number to make and throw away, and a test that watches the thread's own allocation counter says it
costs nothing per frame once they are up. Where a number goes is the game's to say: it hands over
an anchor that writes a world position and a projection that turns it into a screen one, so a
number over a walking target walks with it, and one behind the camera is simply not drawn.

Sparks are the same idea, one step further: the toolkit makes the pixels rather than moving text
about. `ParticleLayer` is a bounded pool — a burst takes slots that already exist, a particle that
dies gives its slot back, and the simulation allocates nothing at all — with gravity, drag, size and
colour over life, and either a plain quad or a picture. Two ways to feed it: a `burst` for a hit or
a win, or a source that runs at so many a second and can be moved, which is a trail behind something
flying. It is seeded, so the same burst comes out the same on every machine and on both backends —
which is what makes a shower of random sparks something a golden screenshot can actually check. An
emitter with nothing alive asks the runtime for no frames at all.

The crosshair is the other half of that. It draws at the centre of whatever box it is given, so it
is right at every window size without the game working out where the middle is; the spread animates
towards what the game says it is, kicking faster than it settles, because a crosshair that snaps
open reads as a glitch rather than as recoil. A landed shot flashes four ticks — one marker, so
firing faster does not stack markers into a bright blob, which is the bug every hand-rolled one
has. A hostile target changes its colour and nothing else.

Tooltips are a host round a screen rather than a modifier, because a tooltip has to be drawn over
the panel it belongs to and over whatever is next to that panel. The delay is the screen's, not each
widget's: a player who is already reading one tooltip and runs along a row of icons has decided to
look, so the next appears at once — every toolkit that stores the delay per widget makes a toolbar
feel like it is arguing. It flips above the thing it belongs to rather than hanging off the bottom
of the screen, slides back on at the sides, and appears on **focus** as well as hover, which is the
whole of the pad story: nothing is ever hovered on a pad. That needed one new primitive,
`Modifier.onFocusWithin`, for the difference between "this node has focus" and "focus is somewhere
in here".

Dialogue types itself. The whole line is measured before the first character is shown, so the box
is its final size from the start and nothing underneath jumps as the words arrive — the one thing a
typewriter has to get right. It rests at punctuation, which is what makes it read like somebody
talking rather than a machine printing, and a player who has read ahead presses the button and gets
the rest at once. An effect can take each character on its own — a shake as it lands, a fade up, a
colour — and it costs what it costs: with an effect the line is drawn a character at a time rather
than a line at a time, which is why there is a fast path without one.

An interface can also live *in* the world rather than on top of it — a screen on a wall, a
terminal, the display on a gun. It is the same tree, laid out the same way, drawn by the same
passes; the only difference is where the pixels land, and a test asserts the two are call for call
identical. The backend draws it into a texture the game maps onto whatever quad it likes, and what
comes out is premultiplied, so `GL_ONE, GL_ONE` gives a hologram for free rather than a grey haze
over every transparent pixel. Both backends do it, both are checked pixel for pixel against the
same drawing on the HUD, and resizing one fifty times hands every framebuffer back — asserted by
the names being handed out again rather than marching upwards. The point of doing it this way at
all is the last part: **a panel is drawn only when its tree changes**, so a terminal nobody is
looking at costs one comparison a frame.

Pointing at one of those panels is a raycast. The game is the only thing that knows where its quad
is, so it does the geometry and says where on the panel the ray landed, in the panel's own units;
everything after that is the code a mouse already goes through — the same router, the same hit
testing, the same capture, the same click. A panel moving under the ray takes care of itself,
because the game works out the new landing point each frame and that is what a drag is. The one
rule worth writing down: **a release is delivered because this pointer pressed on this panel**,
never because the toolkit said it did something with the press. Pressing the background of a panel
hits nothing and says so, and the release still has to arrive — it is what ends the gesture.
Gating it the other way is what leaves an in-world panel dead after one click.

The minimap is a frame with a hole in it. A game's map is its own — a texture it renders, a tile
grid, a mesh — so the toolkit draws the border, clips a rectangle and hands it over, and the game
either draws with our canvas or reaches the backend underneath with `raw { }`. What is left is the
chrome: an arrow at the edge for every objective that is off the map, and the cardinal letters. The
edge arrow is the part worth getting right — it is a ray meeting a **rectangle**, not a circle,
because a minimap is hardly ever square and the round version puts an objective due east in the
middle of nothing. A live map is redrawn every frame, because everything on it moves outside the
composition; one that is not live is not redrawn at all.

What the game has to say is a queue, not a list. A chest with twenty things in it or a quest that
finishes four steps at once is an ordinary moment, and a widget that shows everything it is handed
covers the screen at exactly the wrong time — so three are up, the rest wait, and the ones waiting
are counted rather than drawn. Each card slides in, holds and goes; clicking one takes it away
early and lets the next in, which is how a player gets through a burst. What runs out is a clock's,
so a queue on the world's clock holds behind a pause menu instead of emptying behind it. Past the
backlog the **oldest** waiting one is dropped, because a player who set off twenty pickups wants
the last few, not a queue still playing a minute later. An empty queue composes nothing, runs
nothing and asks for no frames.

Button prompts are the toolkit's answer to a question every game asks badly: `PromptGlyph(Action.Confirm)`
draws **E** on a keyboard, **A** on an Xbox pad and **✕** on a PlayStation one, and it changes on
the frame the player puts one down and picks the other up — nothing reloaded, no event sent to each
prompt, because what device is in use and what it is bound to are both Compose state. A rebinding
screen writes the new binding into `Prompts` and every prompt on screen follows. An action nobody
bound draws a dash rather than an empty box. The glyph sits in the middle of a sentence on the
sentence's own baseline, which is fiddlier than it sounds: a prompt is drawn a size smaller than
the words around it, so lining the boxes up puts the letter off the line, and it lines up the
baselines instead. A skin with real button art names `"prompt.pad.south"` and the plain box is the
fallback, so a skin that draws half the buttons is not half broken.

The gamepad half has never met a gamepad: there is no pad on the machine this is written on, so
the button layouts, the hot-plugging and the axis directions are written to what LibGDX's and
GLFW's own contracts say and have not been measured.

There is a whole small game in `composegl-demo-snake-core`, which is the same Snake the previous version
shipped with the same rules and the same tests, given its interface a second time. The board is
drawn straight into the frame by a renderer that has never heard of a composition; the menu, the
HUD, the pause screen and the game over screen are the toolkit's, over the top, in one canvas. The
two halves share one object of Compose state, which the game loop writes and the interface reads. A
frame of the running game is one draw call and the menu is three. What that port cost, and what it
gave up — there is no `AnimatedVisibility`, so screens snap rather than fade — is written down
honestly in [`docs/snake-port.md`](docs/snake-port.md).

![The showcase running](docs/images/showcase.png)

*The showcase, in `composegl-demo-showcase`: a 3D scene with the game-widget tier over it and a
panel standing inside it. The reticle, the hull and heat bars, the ability bar with its cooldown
sweeps, the radar, the tags stuck to the drones, the damage numbers, the sparks coming off the drone
that was just hit and the four shader tiles along the top are all toolkit widgets reading one object of game state — in the previous version every one of them was hand-written in
the demo. The DOCK TERMINAL on the pedestal is an ordinary composition drawn into a texture and
added to the frame as light; the mouse is pointing at its NEXT button, and the button is lit,
because anything the screen interface did not want becomes a ray into the scene and lands on the
panel through the same router a mouse goes through. The whole frame is three draw calls, and the
number in the corner says the terminal has been redrawn twice since it started — a panel nobody is
touching costs a comparison a frame.*

The showcase is the second demo of the pair and it runs on the LibGDX backend, where Snake runs on
raw OpenGL: between them they prove a game can take either one. Its one interesting file is
[`PanelPlane.kt`](composegl-demo-showcase/src/main/kotlin/uk/wildware/composegl/showcase/world/PanelPlane.kt) —
the ray-versus-quad arithmetic, pulled out on its own so it can be tested without a window, because
where a panel hangs in a scene is the game's business and nothing the toolkit should have an
opinion about. Everything after the hit is `WorldPointer`, and is the same code as a mouse.

When a frame goes over budget the first question is which part of it, and `FrameBudget` answers it:
the Compose runtime's own work, the layout pass and the draw pass, timed apart, with the worst frame
in the window and the ratio of frames that actually redrew. `FrameBudgetOverlay` puts them in a
corner. Press F3 in any of the three demos. Switched off it costs a boolean — the three wrappers are
inline, so a disabled budget compiles down to a comparison and the call that was there anyway, which
is what makes it a thing to leave in a shipped game rather than a thing to add and then remove.

It is also the first time the central claim has been measured in a running game rather than a spike.
Snake, mid-play, on software OpenGL: **the interface redrew 4 times in 294 frames**, and the whole
of it — runtime, layout and drawing together — averaged 0.13 ms a frame.

![Snake on Android](docs/images/android.png)

*The same Snake, on Android.* The game moved into `composegl-demo-snake-core`, which is the toolkit
and nothing else — no window library, no backend, no engine. A launcher opens a window, makes fonts
and a canvas, and calls four methods in order; the desktop one does it on raw OpenGL and
`composegl-demo-snake-android` does it on LibGDX, in about sixty lines. Nothing in the game and
nothing in the toolkit changed for the phone, which is a claim a diff can check.

Three things a phone needs that a desktop does not. A finger has no arrow keys, so a flick is a turn
([`SwipeSteering`](composegl-demo-snake-core/src/main/kotlin/uk/wildware/composegl/snake/SwipeSteering.kt), and
a press the interface already took never steers). And the hint under the score names whatever the
player is actually holding — *Swipe to steer* here, *Arrows or WASD* on a desktop, *D-pad steers*
on a pad — which is the toolkit's `InputSourceTracker` doing the deciding, not the game.

The third is the keyboard, and it is the interesting one. LibGDX can raise a keyboard and that is
all it can do: it cannot say how tall the keyboard is, and it cannot say when the player swiped it
away. Both answers are in the window's insets, which is an Android API rather than an engine one —
so `composegl-android` reads them and hands back two things. A dismissal clears the focus, so a
field stops blinking a caret at a keyboard that has gone. And the height goes into
`Viewport(…, safeArea = Padding(bottom = keyboard.heightPixels))`, which is the same knob a notch
or a rounded corner uses; no widget learns that a keyboard exists, the drawable area simply gets
shorter and the interface moves up on its own.

The honest part: this ran on an x86_64 emulator with no hardware acceleration, which is not a phone.
Touch is real — every widget in the picture was driven by `adb shell input`. The on-screen keyboard
is not verified. The inset listener fires and reports correctly, but it only ever reports *no
keyboard*, because on this emulator the keyboard never draws — and it never draws for the Settings
app's own search box either, so what that proves is that the emulator has no working keyboard, not
that the port has one. Keyboard, frame times and anything to do with a GPU need real hardware.

## Shaders

Anything can be drawn through a fragment shader, and the interesting part is who writes it.

```kotlin
Panel(Modifier.blur(8f)) { … }                        // one of the four we ship
Panel(Modifier.effect(ShaderEffect(myGlsl))) { … }    // one of yours, on the same road
```

A subtree with an effect on it is drawn into an offscreen picture instead of onto the screen, and
the shader decides what that picture comes out as. The shader is text — a fragment shader in the old
dialect, `varying` and `texture2D` and `gl_FragColor` — because text is the only thing that crosses
from common code, where there is no OpenGL, into a backend, where there is. It arrives with the
picture, its size in real pixels, the widget's size in design units, and the opacity in force;
whatever else it wants is a named [`Uniform`](composegl-ui/src/commonMain/kotlin/uk/wildware/composegl/ui/effect/ShaderEffect.kt).

The four we ship — blur, outline, colour grade, dissolve — live in `composegl-effects`, which is a
separate module that depends on `composegl-ui` and nothing else, and has a build check that fails if
that ever stops being true. That is the point of it. If a blur could not be written without reaching
inside the toolkit, the shader API would be a thing we have and you do not, and the build would say
so before a release did.

Two details worth knowing. An effect that spreads — a blur, an outline — asks for a `bleed`, so the
picture is bigger than the widget and the spread has somewhere to go instead of being cut off square.
And a backend with no offscreen drawing, or no shaders, draws the subtree plainly and says nothing:
an effect degrades to no effect rather than to a broken frame.

| | |
|---|---|
| `composegl-ui` | the toolkit. Multiplatform, and depends on the Compose runtime and coroutines |
| `composegl-effects` | blur, outline, colour grade, dissolve. Written against the public API, like yours |
| `composegl-gdx` | the LibGDX backend: renderer, fonts, input. The one to use |
| `composegl-lwjgl3` | a second backend, on raw OpenGL and stb_truetype. Exists to disagree |
| `composegl-testing` | the scenes both backends draw, and the golden comparison |
| `composegl-android` | the parts of a phone no engine reports: what the keyboard covers, and when it went |
| `composegl-robovm` | the same parts of an iPhone, through UIKit |
| `composegl-demo` | the example in the picture |
| `composegl-demo-snake-core` | Snake itself: rules, board, interface, input. Toolkit only, no backend |
| `composegl-demo-snake` | Snake on a desktop, on raw OpenGL |
| `composegl-demo-snake-android` | Snake on a phone, on the LibGDX Android backend |
| `composegl-demo-showcase` | the game-widget tier over a 3D scene, and an interface standing in it |
| Design | [`docs/superpowers/specs/2026-09-09-runtime-ui-design.md`](docs/superpowers/specs/2026-09-09-runtime-ui-design.md) |
| Spike, and its numbers | [`docs/superpowers/spikes/s6-runtime-ui.md`](docs/superpowers/spikes/s6-runtime-ui.md) |
| Everything else written down | [`docs/`](docs/README.md) |
| Work | the [issues](https://github.com/wildware-uk/composegl/issues), milestones M5 onwards |

## Testing

Almost nothing here needs a GPU. A widget's job is to decide *what* to draw and *where*, and a
decision can be asserted directly — so the toolkit is tested through a canvas that writes down what
it was asked to draw instead of drawing it.

What is left is the part only a GPU can answer: whether the pixels are right. That is a handful of
golden images, compared with a tolerance that survives two different software rasterisers
disagreeing about the last bit of an antialiased edge.

Both backends draw the same six scenes, out of `composegl-testing`, and each keeps its own goldens
beside its own tests. They are not shared on purpose: FreeType and stb_truetype will never agree on
a glyph pixel for pixel, and a tolerance loose enough to cover that would catch nothing. What the
two sets are for is the comparison a person makes by looking at them — shapes, positions and
clipping have to match, and where they do not, the toolkit has leaked something into one backend
that the other never heard about.

```bash
./gradlew build                                           # includes compiling the toolkit for Linux native
./gradlew check                                           # everything that needs no display
xvfb-run -a ./gradlew :composegl-gdx:test                 # the renderer, on software OpenGL
xvfb-run -a ./gradlew :composegl-lwjgl3:test              # and the same scenes with no LibGDX
COMPOSEGL_UPDATE_GOLDENS=1 xvfb-run -a ./gradlew :composegl-gdx:test   # after an intended change
```

A failed golden writes the actual, the expected and a difference map into `build/screenshots`, and
CI keeps them.

## The previous version

Until 2026-09-09 this repository was a different thing: Compose UI, rendered by Skia through skiko,
into the game's framebuffer. It worked, it was tested, and it is preserved at the tag
**`skia-final`** — `git checkout skia-final` for the code, the demos and its own README.

It was abandoned for one reason: skiko publishes no Android or iOS binary, and building one is not
work this project can do. Everything it taught us is in `docs/superpowers/spikes/s1`…`s6`.

## Running it

```bash
./gradlew :composegl-demo:run                             # the example in the picture
./gradlew :composegl-demo:runGl                           # the same example, with no LibGDX in it
./gradlew :composegl-demo-snake:run                       # Snake: menu, HUD, pause, game over
./gradlew :composegl-demo-showcase:run                    # the HUD over a 3D scene, and a panel in it
./gradlew :composegl-demo-snake-android:installDebug      # Snake on an attached phone or emulator
SPIKE_S6_HEADLESS=1 ./gradlew :spikes:s6-runtime-ui:run   # the redraw experiment, no window needed
```

Everything here has only ever run on Mesa's software rasteriser and, for the Android launcher, on an
emulator with no hardware acceleration. No real GPU, no macOS, no Windows, and no actual phone.
