package dev.wildware.composegl.ui.input

/**
 * Told of every key and every pointer press on a tree, before either is routed anywhere.
 *
 * It watches and never takes: whatever it hears still goes on to focus or to what is under the
 * pointer exactly as it would have. For the rare thing that has to know input happened somewhere it
 * was not sent — a menu bar that must forget a lone Alt once Alt+Left moved the caret in a field,
 * or let go of focus when the player clicks the screen below it.
 *
 * Put on a tree with [dev.wildware.composegl.ui.node.UiTree.watchInput]. A `KeyRouter` and a
 * `PointerRouter` do the telling.
 */
internal interface InputWatcher {

    /** A key, going down or coming up, on its way to focus. */
    fun onKey(event: KeyEvent) {}

    /** A pointer went down, at [event]'s position in the root's coordinates. */
    fun onPress(event: PointerEvent.Press) {}
}
