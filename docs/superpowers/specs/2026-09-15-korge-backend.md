# KorGE backend

The owner asked for first-class KorGE support, the same as LibGDX. This note says what the first step
built and splits the rest into pieces that separate people can pick up without editing the same files.

## What exists

`composegl-korge` is a new published module. It uses KorGE 6.0.0 as a plain library, with no KorGE
Gradle plugin. It is a multiplatform module with only the JVM target turned on for now.

| File (`composegl-korge/src/jvmMain/.../korge/`) | What it does |
|---|---|
| `KorgeBackend.kt` | The `UiBackend`: canvas, fonts, clipboard, soft keyboard, cursor. |
| `KorgeCanvas.kt` | Every required `UiCanvas` call: rounded boxes, borders, shadows, text, images (tint and source rectangle), fans, clips, opacity, `raw`, `begin`/`end`/`warmUp`, `drawCalls`. |
| `KorgeShapeBatch.kt` | One vertex buffer and one KorGE `Program`. It uses the same distance maths as gdx's `UiShapeBatch`, so shapes look the same. It draws straight through `AG.draw`. |
| `KorgeAtlas.kt` | Premultiplied `Bitmap32` pages, packed in shelves, with the white block on page zero. A new page starts when one fills. |
| `KorgeFonts.kt` | The `FontProvider`. It registers TTF fonts by family and size and makes glyphs the first time they are needed. It wraps lines with the toolkit's own `paragraph()`. It needs no OpenGL. |
| `KorgeTexture.kt` | A KorGE `Bitmap` as a `TextureHandle`. |
| `KorgeClipboard.kt`, `KorgeSoftKeyboard.kt`, `KorgeSystemCursor.kt` | Small wrappers round KorGE's `GameWindow`. |
| `ComposeGlView.kt` | A KorGE `View` that hosts a screen. It fits the design into its box, drives the frame, and sends mouse events to the `PointerRouter`. `Container.composeGl(...)` is the one-line entry. |

Tests live in `src/jvmTest`. `KorgeGl.kt` boots one shared KorGE game and runs work inside its frames.
They run under `xvfb-run -a` and also with `KORGE_HEADLESS=true` and no display.

### Things worth knowing before you touch it

- **KorGE counts y down**, like the toolkit. No backend code flips y. The only sign change is in
  `KorgeCanvas.begin`: the window's top is +1 in clip space, but a KorGE render texture is the other
  way up. Getting this wrong draws everything off-screen and throws no error.
- **The transform happens on the CPU**, per vertex, in `KorgeShapeBatch.vertex`. The program uses no
  uniform blocks, so it can't clash with KorGE's (KorGE uses slots 0 to 6).
- **Textures should be premultiplied.** KorGE logs an error for straight-alpha uploads. Atlas pages are
  premultiplied. The shader turns any premultiplied picture back into straight alpha before blending,
  using the fourth `a_uiShape` value.
- **Flush KorGE's own batch** (`ctx.flush()`) before drawing and after `raw`. `AG.draw` sets all of
  its state on every call, so nothing else needs putting back.
- `KorgeClipboard.read()` blocks for at most 500 ms. It must not block on AWT's event thread, where
  it falls back to the last text it wrote.

## What is left, and where each piece goes

Each item names the files it should touch. Items that name different files can go in parallel.
`KorgeCanvas.kt` is the shared hotspot: keep each change there to its own section and override.

### Canvas extras (`KorgeCanvas.kt`, `KorgeShapeBatch.kt`)

1. **Gradients.** The shader and `KorgeShapeBatch.gradient` already handle them. Override
   `rect(Rect, Brush, …)` and set `drawsGradients` to true. Port gdx's gradient pixel tests.
2. **Per-corner radii.** The shader already has four radii per vertex. Add the `Corners` overloads of
   `rect`, `border` and `shadow`, and set `roundsCornersSeparately`.
3. **Rotated images.** `KorgeShapeBatch.textured(…, degrees)` exists. Override the `image` call that
   takes `degrees` and set `rotatesImages`.
4. **Blend modes.** `KorgeShapeBatch.blend` exists. Add `pushBlend`/`popBlend` and `supports`.
5. **Tint.** Multiply `CanvasState.tint` into `faded()`, then add `pushTint`/`popTint` and `tints`.
6. **Layers**, in a new `KorgeLayers.kt` (a pool of `AGFrameBuffer`s): `layer`, `drawLayer`, plus
   turn, cut (`featherOutline`), mirror, onto and tilt (the `w` in `a_uiPos` is already in place).
   Composite with `blend(…, premultiplied = true)`.
7. **ShaderEffect programs**, in a new `KorgeEffects.kt`. It turns the toolkit's `ShaderSource` GLSL
   into a KorGE program with `FragmentShaderRawGlSl` (check which GLSL version the context uses), and
   is used by `drawLayer(…, effect)`.
8. **Draw-call tracing** already records a reason per flush. Add a test like gdx's
   `DrawCallTraceGlTest`.
9. **Render target for in-world UI**, in a new `KorgeRenderTarget.kt`, modelled on
   `GdxRenderTarget.kt`. It draws a screen into a texture a game can put on a sprite.

### Fonts (`KorgeFonts.kt`, `KorgeAtlas.kt`)

1. **Fallback fonts.** Add `fallBackTo` (for everyone, and per family), trying each fallback one
   character at a time. At the moment a missing character becomes `?` in `Face.make`. Model it on
   `GdxFonts`/`StbFonts`.
2. **Picture glyphs (emoji).** Add `registerPictures`, packed into the same atlas. The canvas's
   `textRing` then has to skip pictures.
3. **Outlines.** Override `textRing` once pictures exist. Until then the interface's default draws
   outlines correctly.
4. **Kerning.** KorGE's `getKerning` is not applied yet, which keeps it in line with the toolkit's
   `Paragraph` assumptions. Decide deliberately before adding it.

### Input (new files only)

- `KorgePointerInput.kt`: take the translation out of `ComposeGlView.onMouse` and add touch, scroll,
  cancel on focus loss, and a HiDPI check (does `MouseEvent.x` count window or framebuffer pixels?).
- `KorgeKeyboardInput.kt`, `KorgeTextInput.kt`: KorGE `KeyEvent` into toolkit `KeyEvent`/`TextEvent`.
- `KorgeGamepadInput.kt`: KorGE's `GamepadInfo` into `GamepadEvent`.
- `KorgeHaptics.kt`: `GameWindow.hapticFeedbackGenerate`.
- Soft-keyboard height for the safe area: `KorgeSoftKeyboard.kt`.

### Everything else

- **Goldens for the 21 shared scenes**: add `src/jvmTest/kotlin/.../KorgeScreenshotTest.kt` against
  `composegl-testing`'s `Scenes.kt`, reusing the LWJGL goldens as gdx and WebGL do.
- **Demo**: a new `composegl-demo-korge` module (not published). Set `duplicatesStrategy` on its
  distribution tasks: the Compose runtime brings two jars named `runtime-desktop-1.12.0.jar`.
- **Docs**: a KorGE page in `docs/wiki/`, and a KorGE line in `Your-first-screen.md`'s backends note.
- **CI**: add `:composegl-korge:test` to the `gl` job in `.github/workflows/ci.yml`, under Xvfb as for
  gdx. Consider a second step with `KORGE_HEADLESS=true`.
- **More targets**: turn on `wasmJs` (and later iOS/Android) in `composegl-korge/build.gradle.kts`.
  Needed first: `KorgeClipboard` uses AWT and `runBlocking`, and `KorgeFonts` uses
  `String.codePointAt`. Put those behind `expect`/`actual` in `commonMain`.
