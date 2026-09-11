package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Stable

/**
 * Everything you can say about a node other than what it is.
 *
 * Deliberately shaped like Compose's `Modifier`, because everybody who would use this toolkit
 * already knows `Modifier.padding(8f).background(...)` and there is nothing to gain by being
 * different. A chain, folded left to right, read by layout and by drawing.
 *
 * **Order matters, and it is not decoration.** `padding(8f).background(blue)` paints the blue
 * inside the padding; `background(blue).padding(8f)` paints it outside. Both are useful and the
 * difference is visible, so the chain is kept in order rather than collected into a bag of
 * properties.
 *
 * **Equality matters more.** A node's modifier is compared on every recomposition to decide
 * whether anything changed, and an unchanged interface must cost nothing. Every element is a data
 * class, and the chain compares element by element.
 *
 * The one thing that defeats that is a lambda: `drawBehind { }` written inline is a new object
 * every recomposition and so never compares equal. Wrap it in `remember` when it matters, exactly
 * as in Compose.
 */
@Stable
interface Modifier {

    /** Runs [operation] over the chain, outermost first. */
    fun <R> fold(initial: R, operation: (R, Element) -> R): R

    fun any(predicate: (Element) -> Boolean): Boolean

    fun all(predicate: (Element) -> Boolean): Boolean

    /** This chain, then [other]'s. */
    infix fun then(other: Modifier): Modifier =
        if (other === Modifier) this else CombinedModifier(this, other)

    /** One link. Elements are data classes so that chains compare by value. */
    interface Element : Modifier {
        override fun <R> fold(initial: R, operation: (R, Element) -> R): R = operation(initial, this)
        override fun any(predicate: (Element) -> Boolean): Boolean = predicate(this)
        override fun all(predicate: (Element) -> Boolean): Boolean = predicate(this)
    }

    /**
     * The empty chain, and the thing every chain starts from.
     *
     * `Modifier.padding(4f)` reads as an English sentence for this reason, and `Modifier` on its
     * own is a perfectly good way to say "nothing special about this node".
     */
    companion object : Modifier {
        override fun <R> fold(initial: R, operation: (R, Element) -> R): R = initial
        override fun any(predicate: (Element) -> Boolean): Boolean = false
        override fun all(predicate: (Element) -> Boolean): Boolean = true
        override infix fun then(other: Modifier): Modifier = other
        override fun toString(): String = "Modifier"
    }
}

/**
 * Two chains joined.
 *
 * Kept as a tree rather than flattened into a list because joining is far more common than reading
 * — a widget joins its own modifier onto the caller's on every recomposition — and a tree makes
 * that free. Equality still walks the whole structure, which is what matters.
 */
private class CombinedModifier(
    private val outer: Modifier,
    private val inner: Modifier,
) : Modifier {

    override fun <R> fold(initial: R, operation: (R, Modifier.Element) -> R): R =
        inner.fold(outer.fold(initial, operation), operation)

    override fun any(predicate: (Modifier.Element) -> Boolean): Boolean =
        outer.any(predicate) || inner.any(predicate)

    override fun all(predicate: (Modifier.Element) -> Boolean): Boolean =
        outer.all(predicate) && inner.all(predicate)

    override fun equals(other: Any?): Boolean =
        other is CombinedModifier && outer == other.outer && inner == other.inner

    override fun hashCode(): Int = outer.hashCode() + 31 * inner.hashCode()

    override fun toString(): String =
        fold("Modifier") { text, element -> if (text == "Modifier") "$element" else "$text -> $element" }
}

/** Every element in the chain, outermost first. Mostly for tests and for readable failures. */
fun Modifier.elements(): List<Modifier.Element> = fold(mutableListOf<Modifier.Element>()) { list, element ->
    list.apply { add(element) }
}
