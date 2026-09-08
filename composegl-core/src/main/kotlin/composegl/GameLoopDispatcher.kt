package composegl

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * The dispatcher every Compose coroutine in a [ComposeGlContext] runs on.
 *
 * Work is never run inline. [dispatch] appends to a queue and [drain] empties it, so everything
 * Compose does — effects, coroutine resumptions, recomposition — happens at one known point in the
 * game's frame instead of on whatever thread happened to complete a `delay`.
 *
 * It deliberately does not implement `Delay`. A `delay()` inside a `LaunchedEffect` therefore uses
 * kotlinx's default timer and is dispatched back here when it expires, which is what we want: the
 * wait is off-thread, the resumption is on the game thread.
 *
 * Enqueueing is thread-safe. Draining is not: call [drain] only from the game thread.
 */
internal class GameLoopDispatcher : CoroutineDispatcher() {

    private val lock = Any()
    private var queue = ArrayDeque<Runnable>()
    private var draining = false

    /** Always true: nothing runs inline, not even when already on the game thread. */
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(lock) { queue.addLast(block) }
    }

    /** True when [drain] has something to do. */
    val hasPendingWork: Boolean
        get() = synchronized(lock) { queue.isNotEmpty() }

    /**
     * Runs everything queued, including tasks queued *by* those tasks, until the queue is empty.
     *
     * A task that throws does not swallow the rest of the queue: the remaining tasks still run and
     * the first failure is rethrown afterwards, with any later failures attached as suppressed.
     *
     * Re-entrant calls (a task that calls `drain()` again) return immediately; the outer drain
     * picks up whatever that task queued.
     */
    fun drain() {
        if (draining) return
        draining = true
        var failure: Throwable? = null
        try {
            while (true) {
                val next = synchronized(lock) { queue.removeFirstOrNull() } ?: break
                try {
                    next.run()
                } catch (t: Throwable) {
                    if (failure == null) failure = t else failure.addSuppressed(t)
                }
            }
        } finally {
            draining = false
        }
        failure?.let { throw it }
    }
}
