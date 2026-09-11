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
downwards. The whole screen costs five draw calls: every glyph at every size, and the white texel
solid colour is drawn from, share one page — so the only texture changes left are the game's own
art.*

## The thing it is for

A game interface should cost nothing when nothing is happening. In the spike, a still interface
redrew **4 times in 119 frames** — once at startup, once per button press. Switch an animation on
and it redraws every frame; switch it off and it stops. That behaviour is the whole reason to use
Compose, and it survives without Compose UI.

## Status

Nothing is shippable yet, and there is no release. What works today is the picture above: layout,
the modifier chain, the renderer, fonts, nine-patch art and pointer input. There are no widgets
yet, nothing is clickable, and there is no skin file — those are the next milestones.

| | |
|---|---|
| `composegl-ui` | the toolkit. Depends on the Compose runtime and coroutines, and nothing else |
| `composegl-gdx` | the LibGDX backend: renderer, fonts, input |
| `composegl-demo` | the example in the picture |
| Design | [`docs/superpowers/specs/2026-09-09-runtime-ui-design.md`](docs/superpowers/specs/2026-09-09-runtime-ui-design.md) |
| Spike, and its numbers | [`docs/superpowers/spikes/s6-runtime-ui.md`](docs/superpowers/spikes/s6-runtime-ui.md) |
| Everything else written down | [`docs/`](docs/README.md) |
| Work | the [issues](https://github.com/wildware-uk/composegl/issues), milestones M5 onwards |

## Testing

Almost nothing here needs a GPU. A widget's job is to decide *what* to draw and *where*, and a
decision can be asserted directly — so the toolkit is tested through a canvas that writes down what
it was asked to draw instead of drawing it.

What is left is the part only a GPU can answer: whether the pixels are right. That is a handful of
golden images under `composegl-gdx/src/test/resources/goldens`, compared with a tolerance that
survives two different software rasterisers disagreeing about the last bit of an antialiased edge.

```bash
./gradlew check                                          # everything that needs no display
xvfb-run -a ./gradlew :composegl-gdx:test                # the renderer, on software OpenGL
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
SPIKE_S6_HEADLESS=1 ./gradlew :spikes:s6-runtime-ui:run   # the redraw experiment, no window needed
```

Everything here has only ever run on Mesa's software rasteriser. No real GPU, no macOS, no Windows,
and nothing on a phone.
