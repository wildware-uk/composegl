# Localisation, right-to-left screens and mixed-direction text

Date: 2026-09-15
Status: **built.** Issue #176.

## 1. What is asked for

Three things a game shipping in Arabic or Hebrew cannot do without, and that a game shipping in
French already wants the first of:

1. **Strings by locale.** A key goes in, the player's language comes out, with a fallback when a
   translation is missing.
2. **Mirrored layouts.** In a right-to-left language the whole screen is the mirror image: the
   first thing in a row is on the right, a `Start`-aligned label hugs the right of its panel.
3. **Bidirectional text.** One line holding Hebrew and English, or Arabic and a number, is drawn in
   the order a reader reads it, and a caret moves across it the way the arrow keys point.

## 2. How things work today

- **Layout.** `Row`, `Column`, `Box`, `FlowRow` and `Grid` are `MeasurePolicy`s that write each
  child's corner into a `placements` float array lent by the node's `NodeMeasureScope`. Horizontal
  positions come from `Arrangement.arrange` along a row and from `Alignment.xIn` across a column or
  inside a box. `Start` means left everywhere. `Modifier.padding` names left and right outright.
- **Text.** A plain `Text` hands the whole string to the backend (`FontProvider.measure`) and draws
  what comes back. A styled `Text` (with `runs`) and every label inside a `SelectionContainer` break
  lines themselves through `FontProvider.paragraph`, which measures prefixes to find where every
  character is. Both assume a line is drawn in the order its characters are stored.
- **Editing.** `TextField` keeps its own `FieldMetrics` (prefix widths per line, no wrapping), and
  `KeyboardEditor` turns keys into `TextFieldValue.move(Movement)`. Left and right are documented as
  "along the string", with a note that this is wrong for Arabic and Hebrew.
- **Backends** (LibGDX, stb_truetype, the browser canvas) draw a string left to right, glyph by
  glyph. None of them shapes or reorders.

## 3. Design

### 3.1 Layout direction

`enum class LayoutDirection { Ltr, Rtl }` and a static `LocalLayoutDirection`, left to right by
default. `Layout` and `LeafLayout` copy it onto the `UiNode` at composition, and the node copies it
into its `NodeMeasureScope`, so `MeasureScope.layoutDirection` is a field read for a policy.

The built-in policies keep computing left-to-right positions exactly as today, then mirror once at
the end: `x = width - x - childWidth` for each placed child. One helper, five call sites, and every
arrangement and alignment mirrors correctly for free — `Arrangement.Start` packs to the right,
`SpaceBetween` stays symmetric, a column's `Start` children hug the right, a grid's first column is
on the right, a flow row fills from the right. A custom `Layout` is left alone; it reads
`layoutDirection` if it cares. The size-animation and wrap-content slot alignments mirror too.

`Modifier.paddingRelative(start, top, end, bottom)` is resolved into a left and a right with the
node's direction (`Modifier.resolve(direction)`), which is why changing direction drops the node's
cached resolution. `padding(left = …)` and `offset` keep naming a side; pictures do not flip.

### 3.2 Bidirectional text

A common-code implementation of the parts of the Unicode Bidirectional Algorithm (UAX #9) that
interface text needs: character classes for the scripts that read right to left (Hebrew, Arabic,
Syriac, Thaana, NKo and the presentation forms), paragraph direction from the first strong
character with the layout direction as fallback, the weak-type rules W1–W7, neutrals N1–N2,
implicit levels I1–I2, trailing whitespace L1, and reordering L2 by level runs. Explicit embeddings
and isolates are treated as invisible.

`BidiText` resolves levels once per string; `runs(from, to)` gives one line's `BidiRun`s in the
order they are drawn. Text with nothing right-to-left in it, in a left-to-right paragraph, is
answered by a single run without any per-character work.

Backends are not changed. A right-to-left run is handed to them already in visual order: its
graphemes reversed (so an accent stays on its letter and an emoji stays whole) and its brackets
mirrored (rule L4). Widths do not change under that reversal — the same no-kerning promise
`Paragraph` already relies on — so where a character is can still be worked out by measuring
logical prefixes: in a right-to-left run, character *i* is at `runLeft + runWidth - prefix(i)`.

- `Paragraph` gains `runsOf(line)`, `isRightToLeft(line)` and bidi-aware `xOf`, `offsetOf`,
  `indexAt` and `boxesOf` (one box per run a range touches). `FontProvider.paragraph` gains an
  overload taking the direction, which also decides which side `Start` lines sit on.
- A styled `Text` draws run by run. A plain `Text` whose string would not read left to right as
  stored switches itself onto the paragraph path, so every label is right; one that would keeps the
  single backend call it has today. `Start` and `End` alignment follow the layout direction.
- `TextField` draws and places its caret and selection off the same runs, and in a right-to-left
  screen its text and placeholder sit against the right.

### 3.3 Caret movement

`TextFieldValue.move(movement, extend, direction)`. Left and Right move across the screen: among the
caret positions on the caret's line, the nearest one in the arrow's direction by drawn position.
Positions are ordered with every grapheme one unit wide, which gives the same order as real widths,
so movement needs no fonts. At the visual edge of a line the caret carries on into the next or
previous line, whichever is that way for the paragraph's direction. Ctrl with an arrow moves by word
in the direction the paragraph reads. Home and End stay logical. `KeyboardEditor` and
`SelectionContainer` pass the layout direction through.

### 3.4 Strings

`Locale` (language, optional region, its reading direction) and `Strings`: tables of key to text per
locale tag, looked up `pt-BR` → `pt` → the fallback locale → the key itself, so a missing string is
visible rather than blank. `{0}` and `{name}` placeholders, a small `key.zero/one/two/other` plural
pick, and a `key = value` text format to load a table from a file. `ProvideLocale(locale, strings)`
provides the locale, the strings and the layout direction together; `stringOf(key, …)` reads them.

## 4. Not in this design

- **Arabic shaping.** Arabic letters change shape by position in a word; no backend here shapes, so
  Arabic is drawn in the right order with every letter in its isolated form. Hebrew is unaffected.
- Explicit embedding and isolate controls (U+202A–U+202E, U+2066–U+2069) are ignored.
- `LazyRow`, `ScrollArea` scrolling sideways and `Slider` do not mirror yet.
- A caret between two runs has one drawn position, not two, so one of the two visual stops at a
  run boundary is skipped by the arrow keys.
- Plural rules beyond zero/one/two/other (Arabic's few and many, Polish) are not modelled.
