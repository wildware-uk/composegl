package dev.wildware.composegl.ui.backend

import dev.wildware.composegl.ui.input.PointerIcon

/**
 * The mouse cursor the operating system draws, and the one call that changes its shape.
 *
 * The toolkit never draws a cursor. It decides which shape the pointer should be — an I-beam over a
 * text field — and asks for it here; [dev.wildware.composegl.ui.input.PointerRouter] does the
 * deciding, once, when the answer changes rather than on every mouse move.
 *
 * A no-op on a phone or a console, where there is no cursor to change. A widget asks for its icon
 * anyway, rather than checking what platform it is on.
 */
interface SystemCursor {

    /** Shows [icon] until asked for something else. */
    fun set(icon: PointerIcon)

    companion object {

        /**
         * A cursor that is not there.
         *
         * What a touch screen gets, and what a game gets before it has wired a real one up: asking
         * does nothing, rather than a router having to know whether there is a mouse.
         */
        val None: SystemCursor = object : SystemCursor {
            override fun set(icon: PointerIcon) = Unit
        }
    }
}

/** For tests. Remembers what it was asked, in order, so a test can check which shape was shown when. */
class RecordingSystemCursor : SystemCursor {

    /** The shape last asked for. [PointerIcon.Default] before anything has asked. */
    var icon: PointerIcon = PointerIcon.Default
        private set

    /** Every change, in order, so a test can assert nothing was asked twice for the same shape. */
    val requests = mutableListOf<PointerIcon>()

    override fun set(icon: PointerIcon) {
        requests += icon
        this.icon = icon
    }
}
