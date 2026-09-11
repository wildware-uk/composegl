package dev.wildware.composegl.robovm

/**
 * How much of the screen a keyboard is covering, worked out from where it is.
 *
 * Split from [UiKitSoftKeyboard] because this is the part with the arithmetic in it, and the rest
 * of that class is UIKit calls that cannot run anywhere but an iPhone. This runs in a test.
 *
 * iOS reports the keyboard as a rectangle in the screen's own coordinates rather than as a height,
 * and the rectangle is still a rectangle when the keyboard is away — it is simply below the bottom
 * of the screen. So "how tall is the keyboard" is the wrong question; "how far up the screen does
 * its top edge reach" is the right one, and that is what the interface needs to keep a field above.
 */
internal object KeyboardCover {

    /**
     * @param keyboardTop the top edge of the keyboard's rectangle, in points.
     * @param screenHeight the screen, in points.
     * @param scale pixels per point — 2 or 3 on every iPhone that has ever shipped. The toolkit
     *   lays out in pixels, and UIKit measures in points, and the difference is the whole reason a
     *   phone interface drawn without it comes out a third of the size it should be.
     * @return how many pixels at the bottom of the screen are covered. Zero when the keyboard is
     *   away, which is the same answer as "its top edge is at or below the bottom".
     */
    fun pixels(keyboardTop: Double, screenHeight: Double, scale: Double): Float {
        val covered = screenHeight - keyboardTop
        if (covered <= 0.0) return 0f
        // Taller than the screen means something is wrong with the numbers rather than with the
        // phone; covering everything is a less bad answer than a layout with a negative height.
        return (covered.coerceAtMost(screenHeight) * scale).toFloat()
    }
}
