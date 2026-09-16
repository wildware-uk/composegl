package dev.wildware.composegl.ui.skin

/**
 * Every style name a widget in this toolkit asks a skin for.
 *
 * The list exists because a missing style is silent: [Skin.style] falls back rather than failing, so
 * a widget whose style nobody wrote simply draws plain — no colour, no edge, no selection — and
 * nobody finds out until they look at it. A name added here that the shipped skins do not answer
 * fails a test instead, which is the point.
 *
 * Adding a widget that reads a new style means adding the name here and answering it in both
 * `src/commonMain/skins/default.json` and `high-contrast.json`. The name is whatever the widget
 * builds: `"tree"` plus `".row"` is `"tree.row"`.
 *
 * Names a *game* invents — `"chip.chosen"`, `"bar.shield.fill"` — do not belong here. This is the
 * toolkit's own vocabulary.
 */
val StyleNames = listOf(
    // surfaces
    "screen", "panel", "panel.raised", "panel.keyboard", "dialog", "scrim", "separator", "divider",
    // working, for an unknown length of time
    "spinner", "spinner.track", "indeterminatebar.track", "indeterminatebar.fill",
    // things that talk to the player
    "tooltip", "notification", "notification.detail", "notification.more",
    "minimap", "minimap.marker", "minimap.compass",
    "compass", "compass.tick", "compass.label", "compass.marker", "compass.pin", "compass.readout",
    "subtitle", "subtitle.speaker", "subtitle.caption",
    "dialogue", "dialogue.speaker", "dialogue.text", "dialogue.advance",
    "dialogue.choice", "dialogue.choice.reason",
    "dialogue.timer.track", "dialogue.timer.fill",
    "dialogue.control", "dialogue.control.on",
    "dialogue.history.speaker", "dialogue.history.line", "dialogue.history.answer",
    // text
    "label", "label.title", "label.heading", "label.dim", "label.danger", "label.good",
    // buttons
    "button", "button.listening", "button.primary", "button.danger", "button.quiet", "button.icon",
    "button.key", "button.key.on",
    // things that take a value
    "checkbox", "checkbox.tick", "radio", "radio.dot", "toggle", "toggle.on", "toggle.knob",
    "slider.track", "slider.fill", "slider.knob", "panzoom", "splitter",
    "colourpicker", "colourpicker.area", "colourpicker.marker", "colourpicker.checker", "colourswatch",
    "stepper", "stepper.arrow", "stepper.value",
    // a game's own furniture
    "bar.track", "bar.fill", "bar.fill.low", "bar.fill.critical", "bar.trail", "bar.segment",
    "damage", "damage.critical", "damage.direction", "vignette", "marker.arrow",
    "hotbar.slot", "hotbar.slot.selected", "hotbar.slot.empty", "hotbar.prompt", "hotbar.charges",
    "cooldown.sweep", "cooldown.flash", "cooldown.seconds",
    "wheel.backdrop", "wheel.slice", "wheel.slice.selected", "wheel.slice.highlighted",
    "wheel.ring", "wheel.ring.highlighted", "wheel.hub", "wheel.label",
    "inventory.cell", "inventory.item", "inventory.count",
    "inventory.footprint", "inventory.footprint.invalid", "inventory.split",
    "prompt", "reticle", "reticle.hostile", "reticle.hit", "reticle.kill",
    "hitmarker", "hitmarker.critical", "hitmarker.kill",
    "progress.track", "progress.fill", "progress.fill.danger", "progress.fill.good",
    // typing
    "field", "field.placeholder", "field.caret", "field.selection", "field.composition", "selection",
    // lists and menus
    "item", "item.selected", "dropdown", "dropdown.list",
    "collapsingheader", "collapsingheader.open", "collapsingheader.glyph", "collapsingheader.body",
    "table", "table.header", "table.header.cell", "table.header.cell.sorted", "table.divider",
    "table.row", "table.row.alt", "table.row.selected", "table.cell", "table.empty",
    "menubar", "menubar.title", "menubar.title.open",
    "menu", "menu.item", "menu.item.open", "menu.shortcut", "menu.separator", "menu.check", "menu.radio",
    "tree.row", "tree.row.selected", "tree.toggle", "tree.toggle.open", "tree.guide",
    "tab", "tab.selected", "scrollbar.track", "scrollbar.thumb", "focusRing",
    // the tools that sit over a game
    "debugwindow", "debugwindow.active", "debugwindow.title", "debugwindow.title.active",
    "debugwindow.button", "debugwindow.body", "debugwindow.label", "debugwindow.value", "debugwindow.grip",
    "plot", "plot.label", "plot.value", "plot.line", "plot.fill", "plot.guide", "plot.cursor", "plot.bar",
    "console", "console.title", "console.prompt",
    "console.line", "console.line.debug", "console.line.info", "console.line.warn",
    "console.line.error", "console.line.echo",
    "console.suggestion", "console.suggestion.selected",
    "console.field", "console.field.placeholder", "console.field.caret",
    "console.field.selection", "console.field.composition",
)
