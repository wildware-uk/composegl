package composegl.ui.backend

/**
 * The on-screen keyboard a phone raises when a text field takes focus.
 *
 * A no-op on desktop, where the keyboard is already there. Text fields ask for it rather than
 * checking what platform they are on.
 */
interface SoftKeyboard {

    val isVisible: Boolean

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
