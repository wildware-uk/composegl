package dev.wildware.composegl.ui.widget

import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.UiTest

/**
 * Reading menus off a screen a test composed: which are open, which row says what, where focus is.
 *
 * Menus take no test tags — a menu is written as data, not as composables a tag could be put on —
 * so rows and titles are found by the words drawn on them, the way a player finds them.
 */

/** Every run of text [node] and everything inside it draws, in order. */
internal fun UiTest.textsOf(node: UiNode): List<String> = drawingOf(node).texts()

/** What [node] and everything inside it draws, on its own, where it is on the screen. */
internal fun UiTest.drawingOf(node: UiNode): RecordingCanvas {
    val canvas = RecordingCanvas(Rect.of(0f, 0f, size.width, size.height))
    val bounds = node.layoutBoundsInRoot
    DrawPass(canvas).draw(node, bounds.left - node.x, bounds.top - node.y)
    return canvas
}

/**
 * The words on a row or a title, in one string. An underlined letter is drawn as a piece of its own,
 * so "File" with its F marked arrives as "F" and "ile".
 */
internal fun UiTest.wordsOf(node: UiNode): String = textsOf(node).joinToString("")

internal fun UiTest.named(name: String): List<UiNode> {
    val found = mutableListOf<UiNode>()
    root.forEach { if (it.name == name) found += it }
    return found
}

/** The open menu panels, outermost first. */
internal fun UiTest.openMenus(): List<UiNode> = named("menu")

/** The row labelled [label] in whichever open menu has one, the innermost if several do. */
internal fun UiTest.row(label: String): UiNode =
    named("menu.item").lastOrNull { wordsOf(it).startsWith(label) }
        ?: throw AssertionError("no open menu has a row \"$label\":\n" + dump())

internal fun UiTest.hasRow(label: String): Boolean =
    named("menu.item").any { wordsOf(it).startsWith(label) }

/** The menu bar title that says [label]. */
internal fun UiTest.title(label: String): UiNode =
    named("menubar.title").firstOrNull { wordsOf(it) == label }
        ?: throw AssertionError("no title \"$label\" on the bar:\n" + dump())

internal fun UiTest.clickRow(label: String) = click(row(label).boundsInRoot.centre)

internal fun UiTest.assertFocusedOn(node: UiNode, what: String) {
    if (focus.focused === node) return
    val actual = focus.focused?.let { wordsOf(it).ifEmpty { null } ?: it.testTag ?: it.name } ?: "nothing"
    throw AssertionError("expected focus on $what but it is on $actual:\n" + dump())
}

internal fun UiTest.assertRowFocused(label: String) = assertFocusedOn(row(label), "the $label row")

internal fun UiTest.assertTitleFocused(label: String) = assertFocusedOn(title(label), "the $label title")
