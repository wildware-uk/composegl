package composegl.android

import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import composegl.ui.backend.SoftKeyboard

/**
 * The phone's keyboard, answered by the window rather than by the engine.
 *
 * LibGDX can raise a keyboard and that is all it can do: it cannot say how tall the keyboard is, and
 * it cannot say when the player dismissed it themselves with the back gesture. Both answers are in
 * the window's insets, which is an Android API and not an engine one — hence this module.
 *
 * That matters for two of the three things a keyboard has to get right. A field near the bottom of
 * the screen ends up behind the keyboard unless somebody knows its height; and a keyboard the player
 * swiped away leaves a field looking focused, with a caret blinking in it, and nothing to type into.
 *
 * ```kotlin
 * val keyboard = AndroidSoftKeyboard(activity.window, view) { focus.clearFocus() }
 * // …then, every frame:
 * Viewport(design, physical, Fit, safeArea = Padding(bottom = keyboard.heightPixels))
 * ```
 *
 * @param window the activity's window, which is what owns the keyboard.
 * @param view any view in that window — the engine's surface will do.
 * @param onDismissed called when the keyboard goes away without this object asking it to, which is
 *   the player dismissing it. Called on the main thread.
 */
class AndroidSoftKeyboard(
    private val window: Window,
    private val view: View,
    private val onDismissed: () -> Unit = {},
) : SoftKeyboard {

    private val controller: WindowInsetsControllerCompat
        get() = WindowCompat.getInsetsController(window, view)

    /**
     * What the window last said, not what was last asked for.
     *
     * The difference is the whole point of this class: a keyboard can go away without anybody here
     * asking it to.
     */
    @Volatile
    override var isVisible: Boolean = false
        private set

    @Volatile
    override var heightPixels: Float = 0f
        private set

    /**
     * Whether the last change was ours.
     *
     * Without it, hiding the keyboard deliberately — leaving a field, closing a dialogue — would
     * look exactly like the player swiping it away, and would fire [onDismissed] into work that is
     * already being done.
     */
    private var asked = false

    init {
        // Posted, because a game builds its interface on the render thread and a view may only be
        // touched on the thread that made it. Everything below therefore happens on the main
        // thread, and the two values this publishes are read from the render thread — hence the
        // volatiles above.
        view.post {
            // The window's root rather than the engine's surface. Insets are dispatched down the
            // hierarchy and a parent may swallow them before a leaf sees them; the root always
            // gets them. The listener returns them untouched, so the system's own handling of
            // status and navigation bars carries on as if nobody had looked.
            val root = view.rootView
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                apply(insets)
                insets
            }
            ViewCompat.requestApplyInsets(root)
            // A view that is not attached yet has nothing to ask, so ask again when it is.
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = ViewCompat.requestApplyInsets(v)
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }

    override fun show() {
        asked = true
        view.post {
            controller.show(WindowInsetsCompat.Type.ime())
        }
    }

    override fun hide() {
        asked = true
        view.post {
            controller.hide(WindowInsetsCompat.Type.ime())
        }
    }

    private fun apply(insets: WindowInsetsCompat) {
        val showing = insets.isVisible(WindowInsetsCompat.Type.ime())
        // The whole of it, measured from the bottom of the window. Android already counts the
        // navigation bar inside this when the keyboard is up, which is what a caller wanting to
        // keep a field clear of the keyboard actually needs.
        heightPixels = if (showing) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom.toFloat() else 0f

        val wasVisible = isVisible
        isVisible = showing
        // Insets arrive for the status bar and the navigation bar too, so the flag is only spent
        // when the keyboard itself actually changed — otherwise an unrelated inset would eat it and
        // the next real change would be misread.
        if (wasVisible == showing) return
        val dismissedByPlayer = wasVisible && !asked
        asked = false
        if (dismissedByPlayer) onDismissed()
    }
}
