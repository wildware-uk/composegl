package composegl.ui.backend

/**
 * The on-screen keyboard a phone raises when a text field takes focus.
 *
 * A no-op on desktop, where the keyboard is already there. Text fields ask for it rather than
 * checking what platform they are on.
 */
interface SoftKeyboard {

    val isVisible: Boolean

    /**
     * How much of the bottom of the window the keyboard is covering, in real pixels. Zero when it
     * is not on screen, and zero on any platform that cannot say.
     *
     * Real pixels rather than the interface's own units, because that is what a viewport's safe
     * area is in: a launcher adds this to `safeArea.bottom` and the whole interface lays out above
     * the keyboard, with no widget having to know a keyboard exists.
     *
     * Default zero, so a backend that only knows how to raise a keyboard is still a valid one —
     * [composegl.ui.layout.Viewport] then behaves exactly as it did before.
     */
    val heightPixels: Float get() = 0f

    fun show()

    fun hide()

    companion object {

        /**
         * A keyboard that is not there.
         *
         * What a field gets on a desktop, and before a game has wired a phone's up: asking for it
         * does nothing, rather than a field having to know which platform it is on.
         */
        val None: SoftKeyboard = object : SoftKeyboard {
            override val isVisible: Boolean get() = false
            override fun show() = Unit
            override fun hide() = Unit
        }
    }
}

/** For tests. Remembers what it was asked, in order, so a test can check when a field asked. */
class RecordingSoftKeyboard : SoftKeyboard {

    override var isVisible: Boolean = false
        private set

    /** What a phone would be covering. Settable, so a test can put a keyboard over a field. */
    override var heightPixels: Float = 0f

    /** Every show and hide, in order, so a test can assert the field asked at the right moments. */
    val requests = mutableListOf<String>()

    override fun show() {
        requests += "show"
        isVisible = true
    }

    override fun hide() {
        requests += "hide"
        isVisible = false
    }
}
