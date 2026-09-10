# Documentation

## Current — the runtime-only toolkit

| | |
|---|---|
| [Design](superpowers/specs/2026-09-09-runtime-ui-design.md) | What is being built, and every decision with its reason. |
| [Roadmap](roadmap.md) | The milestones, M5 to M15, and what exists at the end of each. |
| [Spike S6](superpowers/spikes/s6-runtime-ui.md) | The evidence it works: recomposition still skips work with no Compose UI. Real numbers, real OpenGL. |

Work lives in the [GitHub issues](https://github.com/wildware-uk/composegl/issues), grouped by
milestone.

## History — the Skia product

Until 2026-09-09 this was Compose UI rendered by Skia into the game's framebuffer. It worked and it
was tested. It was abandoned because skiko publishes no Android or iOS binary and building one is
not work this project can do.

The code is at the tag `skia-final`. The reasoning is here, and most of it still applies:

| | |
|---|---|
| [Design](superpowers/specs/2026-09-08-composegl-design.md) | The original spec. Superseded, kept for its reasons. |
| [S1](superpowers/spikes/s1-desktop.md) | Can LibGDX and Skiko share one GL context? Yes. |
| [S2](superpowers/spikes/s2-awt-scan.md) | What does Compose UI actually reference from AWT? |
| [S3](superpowers/spikes/s3-editor-mode.md) | Can a Compose node show a live GL texture? |
| [S4](superpowers/spikes/s4-android-ios-artifacts.md) | What skiko publishes for Android and iOS. This is the one that killed it. |
| [S5](superpowers/spikes/s5-no-awt-runtime.md) | The whole core suite on a JVM with no AWT. |
