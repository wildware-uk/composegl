# Roadmap

The Skia product is finished and abandoned; it is at the tag `skia-final`. What follows is the
runtime-only toolkit, designed in
[`specs/2026-09-09-runtime-ui-design.md`](superpowers/specs/2026-09-09-runtime-ui-design.md).

Each milestone is meant to be usable before the next one starts.

| Milestone | What exists at the end of it |
|---|---|
| **M5 — Toolkit core** | A node tree, modifiers, constraint layout, `Row`/`Column`/`Box`, a viewport with real units, and a canvas interface. No OpenGL, and therefore testable without one. |
| **M6 — LibGDX renderer** | It draws. Sprite batch, clipping, rounded corners and shadows, nine-slice, fonts, pointer input. |
| **M7 — Interaction** | Hit testing, pointer capture, and focus that works on a gamepad rather than a Tab key. |
| **M8 — Skin** | Appearance comes from a texture atlas and a file, so an artist can change it. |
| **M9 — Widgets** | The ordinary ones: buttons, toggles, sliders, scrolling, lists, dialogs. |
| **M10 — Text input** | The largest single piece. A pure editing model with exhaustive tests, then the keyboard, then the widget. |
| **M11 — Animation and clocks** | Animatable values on named clocks, so a pause menu animates over a frozen world. |
| **M12 — Game widgets** | The reason to build this: damage-trail bars, radial cooldowns, hotbars, damage numbers, typewriter dialogue, prompt glyphs. |
| **M13 — In-world interfaces** | The same tree on a quad in the 3D scene, clicked by raycast. |
| **M14 — Demos** | Snake and the showcase ported, and an honest frame-budget readout. |
| **M15 — Platforms** | Android, then iOS. The proof of the whole idea. |

## Standing caveat

Everything in this repository has only ever run on Mesa's llvmpipe software rasteriser on Linux.
No real GPU, no macOS, no Windows, and nothing on a phone.
