package composegl.ui.host

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import composegl.ui.node.UiApplier
import composegl.ui.node.UiNode
import composegl.ui.node.UiTree
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Work the Compose runtime has queued, run when the game says so.
 *
 * A game already has a thread and a loop, and this is the toolkit fitting into it rather than
 * asking for one of its own: everything Compose wants to do lands here and happens inside
 * [UiHost.frame], on the caller's thread.
 *
 * Queueing is synchronised because coroutines may resume off-thread; running is not, because it
 * only ever happens on the frame thread.
 */
class FrameDispatcher : CoroutineDispatcher() {

    private val lock = Any()
    private val queue = ArrayDeque<Runnable>()

    override fun isDispatchNeeded(context: CoroutineContext) = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(lock) { queue.addLast(block) }
    }

    /** Runs everything queued, including anything queued while draining. */
    fun drain() {
        while (true) {
            val next = synchronized(lock) { queue.removeFirstOrNull() } ?: return
            next.run()
        }
    }

    val isIdle: Boolean get() = synchronized(lock) { queue.isEmpty() }
}

/**
 * The whole contract between a game and the Compose runtime.
 *
 * A dispatcher the runtime queues on, a clock it waits for frames on, a `Recomposer`, and a
 * `Composition` pointed at our applier. All of it is public, stable Compose API.
 *
 * ```kotlin
 * val host = UiHost()
 * host.setContent { MainMenu() }
 * // in the game loop:
 * if (host.frame(System.nanoTime())) { layout(); draw() }
 * ```
 *
 * The host owns no window, no thread and no OpenGL context. It does not know what a pixel is.
 */
class UiHost(val tree: UiTree = UiTree()) {

    val root: UiNode get() = tree.root

    private val dispatcher = FrameDispatcher()
    private val clock = BroadcastFrameClock()
    private val job = Job()
    private val scope = CoroutineScope(dispatcher + clock + job)
    private val recomposer = Recomposer(dispatcher + clock + job)
    private val composition = Composition(UiApplier(tree.root), recomposer)

    /** How many frames actually changed something. A cheap health check for a game to print. */
    var changedFrames = 0L
        private set

    var isDisposed = false
        private set

    init {
        // UNDISPATCHED so the recomposer is already running before the first frame, rather than
        // waiting in the queue for a drain that has not happened yet.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
    }

    fun setContent(content: @Composable () -> Unit) {
        check(!isDisposed) { "this host has been disposed" }
        composition.setContent(content)
    }

    /**
     * Advances the runtime by one frame.
     *
     * @return true when something changed and the tree needs laying out and drawing again. A
     *   composition that nobody has touched returns false forever, which is the entire reason for
     *   building on the Compose runtime.
     */
    fun frame(nanos: Long): Boolean {
        check(!isDisposed) { "this host has been disposed" }

        dispatcher.drain()
        // Nobody runs the global snapshot manager for us, so state writes are published here.
        Snapshot.sendApplyNotifications()
        // The recomposer wakes on that notification, and only *then* asks the clock for a frame.
        // Draining here is what lets it get to that ask before the frame is sent. Without it,
        // every state change lands one frame late — quietly, and only under animation.
        dispatcher.drain()
        clock.sendFrame(nanos)
        dispatcher.drain()

        val changed = tree.consumeChanges()
        if (changed) changedFrames++
        return changed
    }

    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        composition.dispose()
        recomposer.cancel()
        job.cancel()
    }
}
