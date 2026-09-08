# ComposeGL

Draw your game's UI with Jetpack Compose, inside your game's own OpenGL frame.

First engine: LibGDX (desktop JVM). Compose renders to an offscreen framebuffer only when the UI actually changes; the engine blits it as one quad every frame.

Design: [docs/superpowers/specs/2026-09-08-composegl-design.md](docs/superpowers/specs/2026-09-08-composegl-design.md)

Status: design complete, implementation not started. Work is tracked in the issues and milestones.
