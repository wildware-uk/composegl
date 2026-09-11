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

That means it runs anywhere LibGDX runs, with no native library to build or publish: desktop,
Android and iOS. It also means we are free to build the toolkit games actually need — skins from a
texture atlas, focus that works on a gamepad, animations on a clock the game can pause — instead of
a general-purpose one bent into shape.

![The example running](docs/images/demo.png)

*The example, in `composegl-demo`. Every panel, border, shadow, bar and letter is drawn by our own
renderer from a tree the Compose runtime maintains. The STATUS panel is drawn by the shader — one
quad for its corner, border and shadow together. The BRIEFING panel is nine-patch art out of a
texture atlas, and the gap between its frame and its text is a number in the atlas file rather than
a number in the source. The headings sit on a ribbon whose hatch repeats sideways and stretches
downwards.*

## The thing it is for

A game interface should cost nothing when nothing is happening. In the spike, a still interface
redrew **4 times in 119 frames** — once at startup, once per button press. Switch an animation on
and it redraws every frame; switch it off and it stops. That behaviour is the whole reason to use
Compose, and it survives without Compose UI.

## Status

Nothing is shippable yet. The repository currently holds the design, the spike that justifies it,
and nothing else.

| | |
|---|---|
| Design | [`docs/superpowers/specs/2026-09-09-runtime-ui-design.md`](docs/superpowers/specs/2026-09-09-runtime-ui-design.md) |
| Spike, and its numbers | [`docs/superpowers/spikes/s6-runtime-ui.md`](docs/superpowers/spikes/s6-runtime-ui.md) |
| Everything else written down | [`docs/`](docs/README.md) |
| Work | the [issues](https://github.com/wildware-uk/composegl/issues), milestones M5 onwards |

## The previous version

Until 2026-09-09 this repository was a different thing: Compose UI, rendered by Skia through skiko,
into the game's framebuffer. It worked, it was tested, and it is preserved at the tag
**`skia-final`** — `git checkout skia-final` for the code, the demos and its own README.

It was abandoned for one reason: skiko publishes no Android or iOS binary, and building one is not
work this project can do. Everything it taught us is in `docs/superpowers/spikes/s1`…`s6`.

## Running the spike

```bash
SPIKE_S6_HEADLESS=1 ./gradlew :spikes:s6-runtime-ui:run   # the experiment, no window needed
./gradlew :spikes:s6-runtime-ui:run                       # the window in the screenshot
```

Everything here has only ever run on Mesa's software rasteriser. No real GPU, no macOS, no Windows,
and nothing on a phone.
