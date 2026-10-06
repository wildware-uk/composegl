package dev.wildware.composegl.ui.host

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.tooling.setObserver
import dev.wildware.composegl.ui.animation.Clocks
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.internal.Guard
import androidx.compose.runtime.mutableStateOf
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.LocalScreen
import dev.wildware.composegl.ui.layout.LocalWindowClass
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.Screen
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
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
 * When a host's frames ran, for a loop that wakes on one and has to know how long it has been.
 *
 * The host's own, rather than [Clocks], because clocks are a game's to replace or share: a subtree
 * on a replay's clocks, or two hosts on one set, would hand such a loop the wrong gap. Nothing but
 * [UiHost.frame] writes these.
 */
internal class FrameTimes {

    private var current = NoFrame
    private var previous = NoFrame

    fun begin(nanos: Long) {
        previous = current
        current = nanos
    }

    /**
     * How long it has been since the frame before the one running now, in nanoseconds: the gap
     * something that started between the two — a fling let go of — has already been moving for.
     * Zero before there have been two frames.
     */
    fun sincePrevious(): Long = if (previous == NoFrame) 0L else (current - previous).coerceAtLeast(0L)

    private companion object {
        const val NoFrame = Long.MIN_VALUE
    }
}

/** The frames of the host this composition runs under, or null outside one. */
internal val LocalFrameTimes = staticCompositionLocalOf<FrameTimes?> { null }

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
    private val frames = FrameTimes()

    /**
     * How much room the interface has, for the layouts that change shape rather than scale.
     *
     * Written by [settle] from the viewport it is laid out with, and read through [LocalScreen].
     * A game driving the host itself sets it, once, from whatever it lays out with.
     */
    var screen by mutableStateOf(Screen(Size.Zero))

    /** How many frames actually changed something. A cheap health check for a game to print. */
    var changedFrames = 0L
        private set

    var isDisposed = false
        private set

    /**
     * What stopped this screen, or null while it runs.
     *
     * When a composable throws while recomposing, or an effect under the screen throws, Compose
     * stops the screen for good: nothing on it changes again. The error itself goes wherever the
     * platform sends an uncaught coroutine exception, which is printed on the desktop and a crash on
     * Android, and nothing else tells the game. This is that error, for whoever is driving the
     * screen: a test harness that has to fail rather than click on a frozen screen, or a game that
     * would rather say so than show one. A failure the screen survives, inside a `supervisorScope`
     * say, is not one, and neither is [dispose].
     *
     * Set from whichever thread the failure happened on, which is not always the frame's.
     */
    @Volatile
    var failure: Throwable? = null
        private set

    init {
        // A child of the host's job hears the moment that job is cancelled. Its own cancellation
        // says why: the cause is what cancelled the parent, which is what the screen threw.
        Job(job).invokeOnCompletion { stopped ->
            if (!isDisposed && failure == null) failure = stopped?.cause ?: stopped
        }

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
        composition.setContent {
            // The clocks, and how much room there is: the two things every screen under this host
            // shares and neither of which a game should have to thread through its own tree. The
            // window class is provided beside the screen rather than derived at each reader, so a
            // layout that only cares about phone-or-desktop is not recomposed by every pixel of a
            // window being dragged wider.
            CompositionLocalProvider(
                LocalClocks provides clocks,
                LocalFrameTimes provides frames,
                LocalScreen provides screen,
                LocalWindowClass provides screen.windowClass,
                content = content,
            )
        }
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
        frames.begin(nanos)
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

        // A resize stepped by layout moves only if this frame is laid out, so a frame with one under
        // way on a moving clock is a changed frame — including the first, which has not moved yet.
        if (clocks.isResizing) tree.invalidateFrame()

        val changed = tree.consumeChanges()
        if (changed) changedFrames++
        lastFrameChanged = changed
        onlyRedrawn = changed && tree.onlyRedrawn
        return changed
    }

    /**
     * Whether the layout that just ran changed anything by itself, counted as a changed frame if
     * [frame] had not already counted it. Clears the flag, so the next frame starts clean.
     *
     * [moved] is whether the pass moved, resized or first placed any node. That is a different
     * picture whether or not anything said so: the pass that follows one that moved something,
     * putting right a layout that read where another node was, is what it catches.
     */
    internal fun layoutChanged(moved: Boolean): Boolean {
        if (!tree.consumeChanges() && !moved) return false
        // Still only a redraw if the frame before layout was nothing or only a redraw as well, and
        // the layout moved no box.
        onlyRedrawn = !moved && (!lastFrameChanged || onlyRedrawn) && tree.onlyRedrawn
        if (!lastFrameChanged) changedFrames++
        lastFrameChanged = true
        return true
    }

    /**
     * Whether anything under this host is waiting for a frame or has work queued for one: an
     * animation, a fling, a recomposition. False on a still screen, which is how a test pins that a
     * still screen really is asleep rather than waking the recomposer every frame to do nothing.
     */
    internal val hasPendingWork: Boolean get() = recomposer.hasPendingWork

    /** Starts counting every recompose scope this screen runs. See [RecomposeCounter]. */
    @OptIn(ExperimentalComposeRuntimeApi::class)
    internal fun countRecompositions(): RecomposeCounter =
        RecomposeCounter().also { it.handle = composition.setObserver(it) }

    /** Whether the last [frame] said it changed, so a layout change in the same frame counts once. */
    private var lastFrameChanged = false

    /**
     * Whether everything this frame changed, before and after layout, was only a redraw — a
     * marquee sliding along. What lets a test harness stop waiting on one.
     */
    internal var onlyRedrawn = false
        private set

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
 * A frame where nothing has changed the tree since it was last laid out, at the same size, skips
 * the layout: every rectangle is already the answer. A frame where something did change measures
 * only the nodes that changed and the path from each up to the root; a node beside them, offered
 * the same room as last time, keeps its size. A layout policy that reads something the tree is
 * never told about — a game's own field, read while measuring — is therefore laid out again only
 * when its own node changes. Read it while composing instead, or call
 * [UiNode.invalidate][dev.wildware.composegl.ui.node.UiNode.invalidate] on the node when it moves.
 * Where other nodes are is the exception: a policy that reads that is noticed doing so and is
 * measured on every pass.
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
): Boolean {
    // Before the recompose, so a screen that has just changed shape is composed at the new one
    // rather than a frame behind it.
    screen = Screen.of(viewport)
    return settleWith(focus, nanos, budget, viewport.rootConstraints, viewport.contentOrigin.x, viewport.contentOrigin.y)
}

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
): Boolean {
    // No viewport, so the room is whatever the constraints allow: what a test means by "at most
    // 1280 by 720" is a 1280 by 720 screen.
    screen = Screen(Size(constraints.maxWidth.orZero(), constraints.maxHeight.orZero()))
    return settleWith(focus, nanos, budget, constraints, 0f, 0f)
}

/** An unbounded constraint describes no screen; zero is the honest answer for one. */
private fun Float.orZero() = if (isFinite()) coerceAtLeast(0f) else 0f

/**
 * One pass over the tree, or two the first time it is ever laid out. Returns whether the last pass
 * moved anything.
 *
 * Every node is placed for the first time on the first pass, so a layout that asks where another
 * node is, measured before that node is placed, is told nowhere. Any later pass that moves something
 * is followed by another on the next frame, which puts that right. On the very first one nothing has
 * been drawn yet, so it is put right now instead, and the frame after it has nothing left to do.
 */
private fun UiHost.layOut(constraints: Constraints, x: Float, y: Float): Boolean {
    val first = !tree.laidOutOnce
    val moved = MeasurePass().run(root, constraints, x, y)
    return if (first && moved) MeasurePass().run(root, constraints, x, y) else moved
}

/**
 * The order itself, written once: recompose, lay out, refresh focus.
 *
 * The two overloads above differ only in what they hand the layout pass — a viewport places the
 * root at the safe area's corner, plain constraints leave it at the origin — so the room and the
 * corner are the arguments and the other three steps are here.
 *
 * The layout is skipped when nothing has reached the tree since it was last laid out, in the same
 * room and at the same corner: a frame with no state change, no input and no animation stepping a
 * size would write every rectangle back exactly as it found it. Anything that does change the tree
 * — a recompose, a node added or moved, a resize under way — marks it to be laid out again, and so
 * does laying the tree out anywhere else in between. A redraw alone does not: it moves the picture,
 * not the boxes. A pass that moved or resized anything is followed by one more on the next frame,
 * for the layouts that read where other nodes are; see [UiTree.laidOut].
 *
 * The refresh is last on purpose: taking focus tells every ancestor to reveal the newly focused
 * node, and the rectangle a scrolling list is handed there is whatever the layout wrote — run it
 * first and the list scrolls to where that node was a frame ago. It is outside the budget's
 * wrappers because the budget splits a frame into the three passes and this is none of them; the
 * testing wiki says the same about the allocation it costs.
 */
private fun UiHost.settleWith(
    focus: FocusManager?,
    nanos: Long,
    budget: FrameBudget?,
    constraints: Constraints,
    x: Float,
    y: Float,
): Boolean {
    val changed = if (budget == null) frame(nanos) else budget.recompose { frame(nanos) }
    val moved = when {
        tree.isLaidOut(constraints, x, y) -> false
        budget == null -> layOut(constraints, x, y)
        else -> budget.layout { layOut(constraints, x, y) }
    }
    focus?.refresh()
    // Layout can change the picture by itself: a node part-way through `animateContentSize` moves
    // every frame with nothing recomposed, and a pass that follows one that moved something can
    // move more. Reported now, on the frame it was laid out at the new size, rather than on the
    // next one — a game that skips drawing an unchanged frame would otherwise show every step of a
    // resize a frame late and miss the last one.
    return layoutChanged(moved) || changed
}
