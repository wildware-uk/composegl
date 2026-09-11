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
}

/** For desktop, and for tests. Remembers what it was asked so a test can check. */
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
