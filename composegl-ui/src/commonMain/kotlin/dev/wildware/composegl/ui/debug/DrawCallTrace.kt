package dev.wildware.composegl.ui.debug

import dev.wildware.composegl.ui.node.UiNode

/**
 * Why a batch was cut: what a backend had to change before it could draw the next thing.
 *
 * A batched backend draws everything it can in one call, and has to hand what it has queued to the
 * GPU the moment the next quad needs something the queue does not share. Each of those is one more
 * draw call, and each has one of these reasons.
 */
enum class BatchBreak {

    /** A picture from a different texture than the one the queue was drawing from. */
    Texture,

    /** A different blend mode, pushed or popped — `Modifier.blend`, `pushBlend`. */
    Blend,

    /** A clip, pushed or popped: the scissor changed. */
    Clip,

    /** An offscreen picture, opened, closed or put down — a scale, a turn, an effect, a shaped clip. */
    Layer,

    /** A picture drawn through somebody's shader. */
    Shader,

    /** The game's own drawing, through `raw { }`. */
    Raw,

    /** Nothing changed; the queue was simply full. */
    Full,

    /**
     * The frame closing. Every frame that drew anything has exactly one, and it is nobody's fault,
     * so it is counted — every draw call is accounted for — but never shown as an offender.
     */
    End,
}

/**
 * One node's share of a frame's draw calls, for one reason.
 *
 * @param node what was being drawn when the batch was cut, or null for drawing that happened outside
 *   the tree — a game's own world, drawn behind the interface in the same batch.
 * @param name the node as a person reads it: its name, and its test tag after a `#` when it has one.
 * @param reason what the backend had to change.
 * @param calls how many draw calls that cost this frame.
 */
data class DrawCallCulprit(
    val node: UiNode?,
    val name: String,
    val reason: BatchBreak,
    val calls: Int,
)

/**
 * Which node cut the batch, and why, for one frame.
 *
 * [dev.wildware.composegl.ui.graphics.UiCanvas.drawCalls] says how many times a frame went to the
 * GPU. This says who sent it. Two halves fill it in: a [dev.wildware.composegl.ui.draw.DrawPass]
 * writes [node] as it walks the tree, and a backend that batches calls [record] each time it
 * actually hands work over — only then, so a blend pushed with nothing queued before it is not
 * blamed for a draw call it did not cost.
 *
 * The blame goes to whichever node asked for the change. A node pushing an additive blend is
 * blamed twice: once going in, for the queue it cut off, and once coming out, for its own glow. A
 * picture from its own texture in the middle of text is blamed going in, and the label after it is
 * blamed for going back to the atlas — which is the truth about the cost, if not about the fault,
 * and why the two lines usually turn up together.
 *
 * A [FrameBudget] keeps one; see [FrameBudget.trace]. Nothing is allocated per call while the same
 * nodes cut the batch for the same reasons frame after frame, which is what a still screen does.
 */
class DrawCallTrace {

    /** The node being drawn right now, or null outside the tree. Written by the draw pass. */
    var node: UiNode? = null

    private val entries = ArrayList<Entry>()
    private var used = 0

    /** Every draw call recorded since the last [clear], End included. */
    var total: Int = 0
        private set

    /** One draw call, cut for [reason] while [node] was being drawn. What a backend calls. */
    fun record(reason: BatchBreak) {
        total++
        val node = node
        // Linear: a frame has a handful of these, and a map would allocate on every new key.
        for (index in 0 until used) {
            val entry = entries[index]
            if (entry.node === node && entry.reason == reason) {
                entry.calls++
                return
            }
        }
        if (used == entries.size) entries += Entry()
        entries[used++].also {
            it.node = node
            it.reason = reason
            it.calls = 1
        }
    }

    /**
     * What this frame's draw calls went on, most first, ties in the order they first happened.
     *
     * [BatchBreak.End] is left out unless [withEnd], since every frame has one.
     */
    fun culprits(withEnd: Boolean = false): List<DrawCallCulprit> {
        val found = ArrayList<DrawCallCulprit>(used)
        for (index in 0 until used) {
            val entry = entries[index]
            if (entry.reason == BatchBreak.End && !withEnd) continue
            found += DrawCallCulprit(entry.node, nameOf(entry.node), entry.reason, entry.calls)
        }
        // Stable, so equal counts keep the order they were drawn in.
        return found.sortedByDescending { it.calls }
    }

    /** Forgets this frame. The entries are kept, so the next frame reuses them. */
    fun clear() {
        for (index in 0 until used) entries[index].node = null
        used = 0
        total = 0
        node = null
    }

    private class Entry {
        var node: UiNode? = null
        var reason: BatchBreak = BatchBreak.End
        var calls = 0
    }

    private companion object {
        fun nameOf(node: UiNode?): String {
            if (node == null) return "outside the tree"
            val tag = node.testTag ?: return node.name
            return "${node.name}#$tag"
        }
    }
}
