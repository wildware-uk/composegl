package dev.wildware.composegl.render

import java.lang.management.ManagementFactory

/**
 * What this thread allocates, in bytes: HotSpot's own count, exact to the byte.
 *
 * Read with escape analysis off (`jvmAllocationTest`), so it counts every object the code asks for,
 * as Android does, rather than only those the desktop JVM could not prove short-lived.
 */
internal object Allocation {

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** The bytes [block] allocated on this thread. */
    fun of(block: () -> Unit): Long {
        val before = threads.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }

    /**
     * The fewest bytes [block] allocated in any of [rounds] runs.
     *
     * The JVM now and then allocates on this thread for itself: a few dozen bytes the first time the
     * count is read, and a few hundred while it compiles code that has just become hot (with the
     * compiler off, those are gone). That lands in one round, not every round. What the code under
     * test makes, it makes every round, so it is in the fewest too.
     */
    fun leastOf(rounds: Int = 5, block: () -> Unit): Long {
        var least = Long.MAX_VALUE
        repeat(rounds) { least = minOf(least, of(block)) }
        return least
    }
}
