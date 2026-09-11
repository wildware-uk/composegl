package uk.wildware.composegl.ui.internal

/**
 * The one piece of the toolkit that cannot be written once: a mutual exclusion lock.
 *
 * Only [uk.wildware.composegl.ui.host.FrameDispatcher] needs one, and only around a queue push and pop,
 * because coroutines may resume on a thread that is not the frame thread. Kotlin has no common
 * lock — a JVM has `synchronized`, and Kotlin/Native has nothing in its standard library at all —
 * so this is where the difference lives, in nine lines, instead of leaking into the toolkit.
 *
 * Held for a handful of instructions and never across a suspension. Anything that needs a lock for
 * longer than that does not belong on the frame thread in the first place.
 */
internal expect class Guard() {
    fun <T> hold(block: () -> T): T
}
