package spike.rt

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Work queued by Compose, drained once per game frame. Same idea as composegl-core's. */
class GameLoopDispatcher : CoroutineDispatcher() {
    private val lock = Any()
    private val queue = ArrayDeque<Runnable>()

    override fun isDispatchNeeded(context: CoroutineContext) = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(lock) { queue.addLast(block) }
    }

    fun drain() {
        while (true) {
            val next = synchronized(lock) { queue.removeFirstOrNull() } ?: return
            next.run()
        }
    }
}

/**
 * The whole host, in forty lines.
 *
 * This is the entire contract between a game and the Compose runtime: a dispatcher it can queue
 * on, a clock it can wait for frames on, a `Recomposer`, and a `Composition` pointed at our
 * `Applier`. Everything here is public, stable Compose API — no `@InternalComposeUiApi` anywhere,
 * which is the single biggest difference from the Skia path.
 */
class GlCompose {

    val root = GlNode().apply { style = GlStyle(direction = Direction.Stack) }

    private val dispatcher = GameLoopDispatcher()
    private val clock = BroadcastFrameClock()
    private val job = Job()
    private val scope = CoroutineScope(dispatcher + clock + job)
    private val recomposer = Recomposer(dispatcher + clock + job)
    private val composition = Composition(GlNodeApplier(root), recomposer)

    var recompositions = 0L
        private set

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
    }

    fun setContent(content: @Composable () -> Unit) {
        composition.setContent(content)
    }

    /**
     * Advances the runtime by one frame.
     *
     * @return true when something actually changed and the tree needs laying out and drawing.
     */
    fun frame(nanos: Long): Boolean {
        dispatcher.drain()
        // Nobody runs the global snapshot manager for us here, so state writes are published now.
        Snapshot.sendApplyNotifications()
        // The recomposer wakes on that notification, and only *then* asks the clock for a frame.
        // Draining here is what lets it get to that ask before the frame is sent — without this,
        // every state change is applied one frame late.
        dispatcher.drain()
        clock.sendFrame(nanos)
        dispatcher.drain()

        val changed = Tree.consume()
        if (changed) recompositions++
        return changed
    }

    fun dispose() {
        composition.dispose()
        recomposer.cancel()
        job.cancel()
    }
}
