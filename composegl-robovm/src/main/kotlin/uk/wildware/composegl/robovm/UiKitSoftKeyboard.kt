package uk.wildware.composegl.robovm

import org.robovm.apple.foundation.NSNotificationCenter
import org.robovm.apple.foundation.NSValue
import org.robovm.apple.uikit.UIKeyboardAnimation
import org.robovm.apple.uikit.UIView
import org.robovm.apple.uikit.UIWindow
import uk.wildware.composegl.ui.backend.SoftKeyboard

/**
 * The iPhone's keyboard, answered by UIKit rather than by the engine.
 *
 * The same gap `composegl-android` fills, on the other phone. LibGDX can raise a keyboard on iOS
 * and that is all: it cannot say how tall the keyboard is, and it cannot say when the player put it
 * away themselves. Both answers arrive as notifications, which is a UIKit thing and not an engine
 * one.
 *
 * That matters for the two things a keyboard has to get right. A field near the bottom of the
 * screen sits behind the keyboard unless somebody knows what it is covering; and a keyboard
 * dismissed by the player leaves a field looking focused with nothing to type into.
 *
 * ```kotlin
 * val keyboard = UiKitSoftKeyboard(view) { focus.clearFocus() }
 * // …then, every frame:
 * Viewport(design, physical, Fit, safeArea = Padding(bottom = keyboard.heightPixels))
 * ```
 *
 * @param view the view the keyboard belongs to — the engine's GL view will do. It is what UIKit
 *   makes first responder, and a keyboard with no responder has nothing to type into.
 * @param onDismissed called when the keyboard goes away without this object asking it to, which is
 *   the player dismissing it. Called on the main thread.
 */
class UiKitSoftKeyboard(
    private val view: UIView,
    private val onDismissed: () -> Unit = {},
) : SoftKeyboard {

    /** What UIKit last said, not what was last asked for. A keyboard can leave on its own. */
    @Volatile
    override var isVisible: Boolean = false
        private set

    @Volatile
    override var heightPixels: Float = 0f
        private set

    /** True while [hide] is what is making it go, so its departure is not read as a dismissal. */
    private var asked = false

    init {
        // One notification rather than the show/hide pair: a keyboard that changes language, or
        // grows a suggestion bar, or shrinks to the floating one, changes frame without ever
        // hiding, and a listener on show/hide alone would keep the old height for ever.
        NSNotificationCenter.getDefaultCenter().addObserver(
            UIWindow.KeyboardWillChangeFrameNotification().toString(),
            null,
            null,
        ) { notification ->
            val info = notification.userInfo ?: return@addObserver
            val frame = (info[UIKeyboardAnimation.Keys.FrameEnd()] as? NSValue)?.rectValue()
                ?: return@addObserver
            // The view's own screen, not the main one: an iPad with an external display has
            // more than one, and the keyboard belongs to whichever the game is on.
            val screen = view.window?.screen ?: return@addObserver
            val covered = KeyboardCover.pixels(frame.minY, screen.bounds.height, screen.scale)

            val wasVisible = isVisible
            heightPixels = covered
            isVisible = covered > 0f

            if (wasVisible && !isVisible && !asked) onDismissed()
            asked = false
        }
    }

    override fun show() {
        asked = false
        view.becomeFirstResponder()
    }

    override fun hide() {
        // Set before resigning: the notification arrives synchronously on some iOS versions, and
        // reading it after would report the player's own dismissal for a keyboard we closed.
        asked = true
        view.resignFirstResponder()
    }
}
