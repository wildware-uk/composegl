package dev.wildware.composegl.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import dev.wildware.composegl.ui.node.UiNode

/**
 * Where the interface asks for a sound, and the game decides what plays.
 *
 * ```kotlin
 * ProvideUiSounds(object : UiSounds {
 *     override fun hover() = audio.play("tick")
 *     override fun press() = audio.play("click")
 *     override fun focusMove() = audio.play("move")
 *     override fun change() = audio.play("toggle")
 * }) {
 *     MainMenu()
 * }
 * ```
 *
 * The toolkit never plays audio itself; it has no idea what a game's mixer is. What it does know is
 * when something happened, and it knows that in one place rather than in every widget: the pointer
 * router hears every hover and press, and the focus manager hears every step the arrows and the pad
 * take. So a button in a game's own widget, built out of `clickable` and `focusable`, ticks exactly
 * like a stock one without a line of sound code in it.
 *
 * Only something the player can use makes a sound: a node that is enabled and `clickable`, or
 * enabled and `focusable`. A panel that only watches the pointer to light itself up stays quiet, and
 * so does a greyed-out button.
 *
 * Every method does nothing unless it is overridden, so a game names only the sounds it has.
 * They are called on the thread input arrives on, in the middle of routing an event: start a sound
 * and return, and leave anything slow — loading a file — to have been done already.
 */
interface UiSounds {

    /**
     * The mouse came onto something usable. Once per arrival: moving about inside a button is
     * silent, and moving from a panel onto a button inside it ticks for the button alone.
     */
    fun hover() {}

    /**
     * Something usable went down: a mouse press or a tap on it, or Enter, Space or the pad's South
     * button on whatever has focus. On the way down rather than on the click, because that is when
     * a real button clicks, and a press that is dragged off and abandoned has still been felt.
     */
    fun press() {}

    /**
     * Focus stepped to another control because the player asked it to: an arrow key, Tab, the
     * d-pad or the stick. Not when a click focuses what it clicked — that has already made its
     * press — and not when focus puts itself somewhere on a screen's first frame.
     */
    fun focusMove() {}

    /**
     * A control's value changed because of the player: a checkbox ticked, a switch flipped, a radio
     * button chosen, a slider stepped.
     *
     * A game's own control reports here too, through [LocalUiSounds]:
     * `val sounds = LocalUiSounds.current` while composing, then `sounds.change()` when it changes.
     */
    fun change() {}

    companion object {
        /** No sounds at all. What a screen gets when nothing provided any. */
        val None: UiSounds = object : UiSounds {
            override fun toString() = "UiSounds.None"
        }
    }
}

/**
 * The sounds for everything composed inside, which is how a pause menu and the HUD behind it can
 * sound different. Read by every node as it is created, so the routers can find it without asking
 * the composition.
 */
val LocalUiSounds: ProvidableCompositionLocal<UiSounds> = staticCompositionLocalOf { UiSounds.None }

/** Plays [sounds] for every control inside [content]. See [UiSounds]. */
@Composable
fun ProvideUiSounds(sounds: UiSounds, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUiSounds provides sounds, content = content)
}

/**
 * Whether the player can use this node, which is the test for whether it makes a sound: enabled
 * and clickable, or enabled and focusable. One rule, read by the pointer and by focus alike, so a
 * node cannot tick when hovered and stay silent when pressed.
 */
internal val UiNode.usable: Boolean
    get() = resolved.click?.enabled == true || resolved.focusable?.enabled == true
