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
| `ComposeGlView.kt` | A KorGE `View` that hosts a screen. It fits the design into its box, drives the frame, and wires every kind of input (see Input below). `Container.composeGl(...)` is the one-line entry. |

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

1. **Gradients.** Done. `rect(Rect, Brush, …)` draws both kinds; `drawsGradients` is true.
2. **Per-corner radii.** Done. The `Corners` overloads of `rect`, `border` and `shadow`;
   `roundsCornersSeparately` is true.
3. **Rotated images.** Done. The `image` call that takes `degrees`; `rotatesImages` is true.
4. **Blend modes.** Done. `pushBlend`/`popBlend`, and `supports` says yes to both modes. A layer or
   effect that draws outside the batch must follow `state.blend` itself.
5. **Tint.** Done. `faded()` multiplies in the tint; `pushTint`/`popTint`; `tints` is true.
   Tests: `KorgeCanvasExtrasTest`, `KorgeCanvasExtrasScreenTest`.
6. **Layers**, in a new `KorgeLayers.kt` (a pool of `AGFrameBuffer`s): `layer`, `drawLayer`, plus
   turn, cut (`featherOutline`), mirror, onto and tilt (the `w` in `a_uiPos` is already in place).
   Composite with `blend(…, premultiplied = true)`.
7. **ShaderEffect programs**, in a new `KorgeEffects.kt`. It turns the toolkit's `ShaderSource` GLSL
   into a KorGE program with `FragmentShaderRawGlSl` (check which GLSL version the context uses), and
   is used by `drawLayer(…, effect)`.
8. **Draw-call tracing.** Done: `KorgeDrawCallTraceTest`. When layers and effects land, add their
   `Layer` and `Shader` reasons to its second test, as gdx's has.
9. **Render target for in-world UI**, in a new `KorgeRenderTarget.kt`, modelled on
   `GdxRenderTarget.kt`. It draws a screen into a texture a game can put on a sprite.

### Fonts (`KorgeFonts.kt`, `KorgeFallback.kt`, `KorgeAtlas.kt`)

1. **Fallback fonts.** Done. `fallBackTo` for everyone and per family, `fallbacksOf`, tried one
   character at a time in `Chain`. A character nothing has is the main font's `?`. Note that DejaVu
   Sans has a plain 😀 of its own, and the main font always wins, so the tests use 🥳 for the picture.
2. **Picture glyphs (emoji).** Done. `registerPictures` (bitmaps) and `registerEncodedPictures`
   (PNG bytes), packed premultiplied into the same atlas, with the same size, gap and drop below the
   baseline as gdx and LWJGL.
3. **Outlines.** Done. `KorgeCanvas.textRing` draws the letters and leaves pictures out.
4. **Kerning.** Decided: not applied, like LWJGL. Widths stay the sum of advances, which
   `Paragraph`, `TextField`'s caret, bidi runs and `Typewriter` all rely on. KorGE reads no kerning
   from DejaVu Sans anyway; a test with a font that claims kerning pins the choice.

`families()`, `sizesOf` and `fontFor(style)` match `GdxFonts`. Text scale and `TextMetricsOverlay`
have GPU tests like gdx's. One difference: KorGE's cap height is the font's own fraction, where
FreeType rounds it to a whole pixel for gdx.

### Input — done

- ~~`KorgePointerInput.kt`~~ Done: mouse, touch with several fingers, drag, scroll, cancel on focus
  loss. HiDPI answer: a KorGE `MouseEvent` already counts framebuffer pixels (the AWT window scales
  it before dispatching), so nothing scales it again. Touches arrive in stage units and are brought
  back with `globalToWindowCoords`. KorGE's `emulated` copies (touch from mouse, mouse from touch)
  are ignored.
- ~~`KorgeKeyboardInput.kt`, `KorgeTextInput.kt`~~ Done: a full key table, repeat detection, and
  typed text with control characters dropped. KorGE 6 exposes no input-method preedit, so there is
  no underlined provisional text; committed text still arrives. `KorgeTextInput` is the fields'
  `TextInput` and tracks the open field.
- ~~`KorgeGamepadInput.kt`~~ Done: snapshots into button, stick and trigger events, lowest free
  `GamepadId`, dead zone, y flipped (KorGE's +1 is up).
- ~~`KorgeHaptics.kt`~~ Done: the window's haptic engine, then `NativeVibration`, chosen by
  `InputSourceTracker`. KorGE has no pad rumble.
- ~~Soft-keyboard height~~ Done as `KorgeSoftKeyboard.height`: KorGE cannot report it, so a game
  that can ask its platform passes it in (`KorgeBackend(keyboardHeight = …)`).
- `KorgeSystemCursor` has a shape for all ten icons; "not allowed" is a drawn custom cursor.
- `KorgeInput.kt` listens to a stage and feeds one sink; `ComposeGlView` uses it and wires key
  routing, key and pad navigation, the virtual cursor, the back stack, text input and the source
  tracker. Split-screen: views with `listens = false` behind an `InputRouter`.
  `KorgeInputScreenTest` drives all of it with real KorGE events.

### Everything else

- **Goldens for the shared scenes** — *done*. `KorgeScreenshotTest.kt` draws every scene in
  `Scenes.kt` and holds it to `src/jvmTest/resources/goldens`; the text-free scenes are also held to
  the LWJGL goldens. A scene that needs a capability the canvas does not claim yet is skipped by an
  assumption naming that flag (see `Needs` in the test). When you switch a flag on, the scene runs
  and fails for want of a golden: run with `COMPOSEGL_UPDATE_GOLDENS=1`, look at the picture, commit it.
  Waiting on layers: `layer`, `scale`, `tint` (`drawsLayers`); `skew` (`drawsLayersOnto`); `tilt`
  (`tiltsLayers`); `clip-shape` (`cutsLayers`). Waiting on layers and ShaderEffect programs: `effect`,
  `effects`.
- **A realistic screen driven by `uiTest`** — *done*. `KorgeUiTestScreenTest.kt`: a panel, a text
  field, buttons and a scrolling list, played with pointer and keys, checked in state and pixels.
- **Demo**: a new `composegl-demo-korge` module (not published). Set `duplicatesStrategy` on its
  distribution tasks: the Compose runtime brings two jars named `runtime-desktop-1.12.0.jar`.
- **Docs**: a KorGE page in `docs/wiki/`, and a KorGE line in `Your-first-screen.md`'s backends note.
- **CI** — *done*. The `gl` job runs `:composegl-korge:jvmTest` under Xvfb, and again with
  `KORGE_HEADLESS=true` and no display. The `build` job has neither, so the pixel tests skip there.
- **More targets**: turn on `wasmJs` (and later iOS/Android) in `composegl-korge/build.gradle.kts`.
  Needed first: `KorgeClipboard` uses AWT and `runBlocking`, and `KorgeFonts` uses
  `String.codePointAt`. Put those behind `expect`/`actual` in `commonMain`.
