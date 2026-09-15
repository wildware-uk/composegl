# What's new in 0.5.0

Everything that landed between 0.4.0 and 0.5.0, in plain words. Each line points at
the page that explains it. If you are upgrading a LibGDX or raw OpenGL game, read
[Breaking changes](#breaking-changes) first.

---

## Widgets and layout

- **New widgets:** `Dropdown`, `Stepper` and `NumberStepper`, `Divider`,
  `KeyBindButton` for a controls screen, an on-screen keyboard for players with only a
  pad (`ProvideGamepadKeyboard`), and `SelectionContainer` so plain text can be
  selected and copied. See [[Widgets]].
- **New layouts:** `Grid`, `FlowRow`, and lazy grids (`LazyVerticalGrid`,
  `LazyHorizontalGrid`) that build only what is on screen. Lazy lists can have
  `stickyHeader`s. See [[Layout]].
- **Sizing:** `aspectRatio`, `sizeIn` ("at least 200, at most 400"), `wrapContentSize`
  so a small thing keeps its size in a big slot, and `IntrinsicSize` so a column can be
  as wide as its widest child.
- **Placing:** `zIndex` lifts one sibling over the others, and clicks follow it.
  `layoutId` lets a custom layout find children by name. `onPlaced` and
  `onSizeChanged` tell a node when it moves or changes size. A row can line text up by
  its baseline, and `paddingFromBaseline` spaces a label from it. See
  [[Custom layouts]].
- **Drawing:** a radius per corner (`Corners`), gradient backgrounds, borders that are
  one-sided, dashed or dotted (`BorderStyle`), clipping to a circle, a diamond or any
  convex shape (`clipShape`), `rotate`, `skew`, `mirror`, `tint` for a whole subtree,
  `blend` modes, and `rotate3d` with a shared `perspective` so a row of cards can flip
  and tilt. See [[Modifiers]].
- **Styled text:** `TextRun`s colour, underline or strike part of one label, and text
  can be placed by its capitals or its baseline (`TextAnchor`).
- **Skins:** a high-contrast skin ships beside the default (`Skin.HighContrast`), and a
  player can switch skins live from an options screen. See [[Skins]].

## Animation

All of it now lives on one page: [[Animation]].

- Panels animate out before they go (`AnimatedVisibility`), screens fade into each
  other (`Crossfade`), and pages slide or scale into the next with a transition picked
  per change (`AnimatedContent`).
- Several values move as one off a single state (`updateTransition`).
- A panel grows to its new contents (`animateContentSize`), and rows slide to their new
  place when a list is sorted (`animatePlacement`).
- A panel shakes on a wrong password (`rememberShake`, `Modifier.shake`), a long name
  scrolls round inside its slot (`marquee`), and layers drift against the pointer, a
  stick or a scroll (`parallax`).
- A strip of pictures plays as an animation (`AnimatedImage`).
- Clocks can be paused, stepped a frame at a time and slowed down
  (`clocks.debug`, `ClockDebugKeys`).

## Input

See [[Input]].

- `clickable` can long press, double click, and repeat while held
  (`repeatingClickable`).
- A node can be dragged (`draggable`), with slop, capture and cancel done once, and an
  item can be dragged from one slot and dropped on another (`dragSource`,
  `dropTarget`), with a pad too.
- The mouse cursor changes shape over what it points at (`pointerHoverIcon`).
- A pad can drive a free cursor on maps and inventories (`VirtualCursor`).
- Menus can tick, click and whoosh with no sound code in any widget
  (`ProvideUiSounds`), and controls give a small bump back on a phone or a pad
  (`ProvideHaptics`).
- **Split-screen:** a viewport and a HUD per player, and each pad routed to its own
  player (`Viewport.splitScreen`, `InputRouter`). See [[Split-screen]].
- A screen keeps its tab, its scroll and its half-typed name when the player comes
  back (`rememberSaveable`). See [[Saving state]].

## Text

- Characters your font lacks come from fallback fonts, Chinese, Japanese, Korean and
  colour emoji included (`fallBackTo`, picture glyphs).
- Text can be made bigger without making the whole interface bigger
  (`ProvideTextScale`).
- Screens in Hebrew and Arabic mirror, mixed-direction text reads in the right order,
  and strings come by language with plurals (`Strings`, `ProvideLocale`). See
  [[Localisation]].
- **Text stays sharp when the interface is scaled up**, on a Retina display or a 4K
  monitor: glyphs are made again at the screen's scale.

## Debug tools

- `Modifier.debugBounds()` shows where one widget landed.
- `LayoutOverlay` draws boxes, padding and gaps across the whole screen.
- `Inspector`: point at a widget and read its size, constraints and modifiers.
- `OverdrawOverlay` shows which pixels are painted over and over.
- The frame budget (`FrameBudgetOverlay`) names the nodes that cost extra draw calls,
  and why.
- `FocusOverlay` shows where the pad will move focus and where clicks really land.
- `RedrawOverlay` shows which nodes keep redrawing.
- `TextMetricsOverlay` draws every label's baseline and cap height.

## Testing

See [[Testing]].

- Tag a widget with `testTag`, then click, type and press pad buttons on a composed
  screen with `uiTest`.
- `dump` prints the node tree as text.
- A composable marked `@Preview` is drawn to a PNG by a `renderPreviews` Gradle task.

## Backends

See [[Backends]].

- **KorGE.** A new backend, `composegl-korge`: a screen as a view on a KorGE stage,
  with KorGE's input, fonts, render targets for in-world UI, and shader effects. JVM
  for now. See [[KorGE]].
- **The browser.** A new backend, `composegl-webgl`: the toolkit in a web page, drawn
  with WebGL 2 or WebGL 1.
- **One shared renderer.** `composegl-render` now holds all the drawing: batching,
  layers, effects, render targets and the glyph atlas. Every backend (LibGDX, raw
  OpenGL, WebGL, KorGE) is a thin wrapper that tells it how to call OpenGL, how to make
  a glyph, and how to find a texture. It comes with your backend; you do not add it
  yourself.
- **GL 3 and OpenGL ES 3.** The interface draws on a GL 3.2 core context and on ES 3,
  where the LibGDX backend used to draw nothing. The whole suite also runs on real
  OpenGL ES 2 and ES 3 contexts, and on WebGL 1.
- **The browser showcase.** Every widget, layout, animation and effect, live in one
  page: <https://wildware-uk.github.io/composegl/>.

---

## Breaking changes

The removals are in the LibGDX and raw OpenGL backends, whose drawing classes moved
into `composegl-render`. The toolkit's own API (`composegl-ui`) mostly grew; see
[the last section](#what-changed-shape-but-still-compiles) for what moved there.

### LibGDX (`composegl-gdx`)

| Gone or changed | Use instead |
|---|---|
| `UiShapeBatch` | nothing: `GdxCanvas` draws everything through `composegl-render` |
| `GdxAtlas` | `GdxFonts.atlas`, which is now a `GlyphAtlas` |
| `GdxFonts(atlas, ownsAtlas)` | `GdxFonts(pageSize, maxPages)` |
| `GdxFonts.register(family, size, BitmapFont)` | `GdxFonts.registerTrueType(family, file, sizes)` |
| `GdxFonts.fontFor(style)` | none; `families()` and `sizesOf(family)` say what is registered |
| `GdxCanvas(spriteBatch, atlas: GdxAtlas)` | `GdxCanvas(spriteBatch, fonts)`, or `GdxCanvas(spriteBatch, fonts.atlas)` |
| `GdxTextLayout` with `glyphs` and `font` | `GdxTextLayout` is a typealias for `AtlasTextLayout` |

### Raw OpenGL (`composegl-lwjgl3`)

| Gone or changed | Use instead |
|---|---|
| `GlShapeBatch` | nothing: `GlCanvas` draws everything through `composegl-render` |
| `StbTextLayout` as its own class | a typealias for `AtlasTextLayout` |

### What changed shape but still compiles

- `GdxCanvas` and `GlCanvas` are now `RenderCanvas` subclasses. They gain
  `begin(viewport, FrameTarget, clear)`, `contextLost()`, `device` and a public
  `warmedUp`. `begin(viewport, framebuffer)` still works.
- `GdxFonts`, `StbFonts`, `WebFonts` and `KorgeFonts` are `AtlasFonts`.
- `GlRenderTarget` and `WebGlRenderTarget` gain `readPixels()`.
- A single `corner` on a background, border or skin fill is deprecated in favour of
  `corners`. The old getter returns the smallest of the four.

### Looks different

- **Text no longer kerns** on LibGDX. A width is the sum of the advances, as on every
  backend, so text measured in pieces adds up to text measured whole. Golden images
  with text in them may need making again once.
- In the browser, text edges come out slightly fuller, and colour emoji keep their own
  colours instead of taking the label's.
