package dev.wildware.composegl.ui.host

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import dev.wildware.composegl.ui.animation.Clocks
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.internal.Guard
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.layout.run
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
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

    private val lock = Guard()
    private val queue = ArrayDeque<Runnable>()

    override fun isDispatchNeeded(context: CoroutineContext) = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        lock.hold { queue.addLast(block) }
    }

    /** Runs everything queued, including anything queued while draining. */
    fun drain() {
        while (true) {
            val next = lock.hold { queue.removeFirstOrNull() } ?: return
            next.run()
        }
    }

    val isIdle: Boolean get() = lock.hold { queue.isEmpty() }
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
class UiHost(val tree: UiTree = UiTree(), val clocks: Clocks = Clocks()) {

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
        // A long press on this tree is measured in the same time as every animation in it.
        tree.clocks = clocks

        // UNDISPATCHED so the recomposer is already running before the first frame, rather than
        // waiting in the queue for a drain that has not happened yet.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
    }

    fun setContent(content: @Composable () -> Unit) {
        check(!isDisposed) { "this host has been disposed" }
        // Every animation under this host runs on this host's clocks, without a game having to
        // remember to say so. A screen that wants its own — a replay running at half speed — still
        // provides them over the top for its own subtree.
        composition.setContent { CompositionLocalProvider(LocalClocks provides clocks, content = content) }
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

        // Before anything else: an animation waking up this frame must see this frame's time.
        clocks.advance(nanos)
        // Then the held presses, which fire callbacks that write state — before the drain below, so
        // a long press or a repeat step is drawn this frame rather than the next.
        tree.runWaiters()

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

/**
 * A tree that is safe to read: recomposed, laid out, and with focus pointing at something real.
 *
 * Three calls have to happen in one order before anything can be asked a question about the tree,
 * and until now the only place that order was written down was inside
 * [dev.wildware.composegl.ui.host.UiRenderer.render], which is no use to a test that never draws.
 * Leaving the layout out gives right contents and last frame's rectangles — so a click lands where
 * the button used to be. Leaving the focus refresh out leaves focus on a node that the recompose
 * has just removed, so the next direction press has nowhere to move from.
 *
 * ```kotlin
 * host.settle(viewport, focus, nanos = clock)
 * assertEquals("Continue", focus.focused?.name)
 * ```
 *
 * @param focus the manager to refresh, or null for a screen that has none. It is an argument
 *   rather than something the host holds because a game usually has more than one — a heads-up
 *   display and a panel in the world are two trees with two managers — and a host cannot know
 *   which of them this frame belongs to.
 * @param nanos the frame's time, from whatever clock the caller already reads. Required, because
 *   the host has no clock of its own and guessing one is how animation tests quietly stop testing
 *   anything.
 * @param budget where the timings go, for a caller that wants the split: the recompose and the
 *   layout, which are two of the three numbers the overlay shows. The focus refresh is not timed.
 *   Null costs nothing.
 * @return whether anything actually changed. **One settle is not always enough.** It publishes
 *   state that was written during the *previous* frame; state written by a coroutine that resumes
 *   *during* this one is not published until the `Snapshot.sendApplyNotifications` at the top of
 *   the next frame. So a harness that wants a finished tree loops until nothing more changes:
 *
 * ```kotlin
 * while (host.settle(viewport, focus, nanos = clock)) { clock += 16_666_667L }
 * ```
 *
 * Advance the clock in that loop, as above. A loop on a fixed time settles state, but an animation
 * asks for a frame at a time that never arrives and the loop never ends. In a test, put a count on
 * it as well and fail when it runs out: a bug that made this always report a change would otherwise
 * hang the build rather than fail it, and a hung job prints nothing.
 */
fun UiHost.settle(
    viewport: Viewport,
    focus: FocusManager? = null,
    nanos: Long,
    budget: FrameBudget? = null,
): Boolean = settleWith(focus, nanos, budget) { pass -> pass.run(root, viewport) }

/**
 * The same thing against plain [Constraints], for a test that has no screen to describe.
 *
 * A [Viewport] exists to fit a design resolution onto a real framebuffer and lays the root out at
 * exactly one size; a test usually just wants "at most 1280 by 720" and to see what the tree makes
 * of it. Read [settle] above for what this does and for the loop that finishes the job.
 */
fun UiHost.settle(
    constraints: Constraints,
    focus: FocusManager? = null,
    nanos: Long,
    budget: FrameBudget? = null,
): Boolean = settleWith(focus, nanos, budget) { pass -> pass.run(root, constraints) }

/**
 * The order itself, written once: recompose, lay out, refresh focus.
 *
 * The two overloads above differ only in what they hand the layout pass — a viewport places the
 * root at the safe area's corner, plain constraints leave it at the origin — so that one line is
 * the argument and the other three are here. Inline, because the alternative is a fresh lambda
 * every frame for a frame that otherwise allocates almost nothing.
 *
 * The refresh is last on purpose: taking focus tells every ancestor to reveal the newly focused
 * node, and the rectangle a scrolling list is handed there is whatever the layout wrote — run it
 * first and the list scrolls to where that node was a frame ago. It is outside the budget's
 * wrappers because the budget splits a frame into the three passes and this is none of them; the
 * testing wiki says the same about the allocation it costs.
 */
private inline fun UiHost.settleWith(
    focus: FocusManager?,
    nanos: Long,
    budget: FrameBudget?,
    measure: (MeasurePass) -> Unit,
): Boolean {
    val changed = if (budget == null) frame(nanos) else budget.recompose { frame(nanos) }
    if (budget == null) measure(MeasurePass()) else budget.layout { measure(MeasurePass()) }
    focus?.refresh()
    return changed
}
