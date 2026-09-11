package uk.wildware.composegl.ui.internal

import kotlin.concurrent.AtomicInt

/**
 * A spin lock, because Kotlin/Native's standard library has an atomic integer and no lock.
 *
 * Spinning is the wrong answer for anything held longer than a few instructions — a waiting thread
 * burns a core instead of sleeping. It is the right answer here: the critical section is one
 * `ArrayDeque` push or pop, so a thread that finds the lock taken is microseconds from getting it.
 */
internal actual class Guard actual constructor() {

    private val taken = AtomicInt(0)

    actual fun <T> hold(block: () -> T): T {
        while (!taken.compareAndSet(0, 1)) {
            // Nothing useful to do but ask again.
        }
        try {
            return block()
        } finally {
            taken.value = 0
        }
    }
}
