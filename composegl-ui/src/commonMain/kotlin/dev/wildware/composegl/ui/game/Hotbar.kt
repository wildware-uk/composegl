package dev.wildware.composegl.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.animation.Animatable
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.FloatVectoriser
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.styled
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.widget.Image
import dev.wildware.composegl.ui.widget.Text

/**
 * One slot of a [Hotbar]: what is in it, and whether it can be used.
 *
 * An **empty** slot is one with nothing in it — no icon and no label. It is drawn as a hole in the
 * bar and cannot be used or focused. A **disabled** slot has something in it that cannot be used
 * right now: no mana, no ammunition, silenced. The two look different on purpose, because they
 * mean different things to a player deciding what to press.
 *
 * @param icon a region in the skin's atlas.
 * @param label what to draw when there is no art — a letter, a number, an abbreviation.
 * @param prompt the key or pad button that uses this slot, drawn small in the corner. It is a
 *   label rather than a binding: the game decides what actually presses it.
 * @param cooldown the ability's cooldown, if it has one. A slot that is cooling down cannot be
 *   used, and draws the sweep over itself.
 * @param charges how many uses are left, or a negative number for a slot that does not count them.
 *   Zero charges is a slot that cannot be used.
 */
data class HotbarSlot(
    val icon: String? = null,
    val label: String? = null,
    val prompt: String? = null,
    val cooldown: Cooldown? = null,
    val charges: Int = -1,
    val enabled: Boolean = true,
) {

    val isEmpty: Boolean get() = icon == null && label == null

    /** Whether pressing it would do anything: something in it, switched on, off cooldown, loaded. */
    val isUsable: Boolean
        get() = !isEmpty && enabled && charges != 0 && cooldown?.isRunning != true
}

/**
 * The row of abilities along the bottom of a game.
 *
 * All three ways of using it work at once, because a player uses all three: the number keys press a
 * slot wherever focus happens to be, a click presses the one under the pointer, and a pad moves
 * focus along the row and presses with South. Nothing here polls anything — the keys arrive as key
 * events and bubble outwards, so a hotbar under a dialogue never steals them.
 *
 * A press that does nothing is not drawn as a press: a slot that is empty, switched off, out of
 * charges or still cooling down does not flash, and [onUse] is not called for it. The game is
 * asked once, for slots that can actually be used, which is the only thing it wants to hear about.
 *
 * Everything it looks like is the skin's: `"<style>.slot"`, `"<style>.slot.selected"`,
 * `"<style>.slot.empty"`, `"<style>.prompt"` for the key in the corner, and `"<style>.charges"`
 * for the count.
 *
 * ```kotlin
 * Hotbar(
 *     slots = abilities,
 *     selected = chosen,
 *     onUse = { player.use(it) },
 *     onSelect = { chosen = it },
 * )
 * ```
 *
 * @param selected which slot is the chosen one, or -1 for none. A bar where a slot is "the one in
 *   hand" — a weapon, a block to place — has one; a bar of abilities usually does not.
 * @param onUse called when a slot that can be used is pressed, however it was pressed.
 * @param state hoist one to press slots from outside the bar: a game's own key bindings, a pad
 *   trigger, a script. See [HotbarState].
 * @param hotkeys whether the bar itself answers the number keys — 1 to 9 then 0, which is what a
 *   player's fingers already expect. A key event only reaches the bar while focus is inside it, so
 *   a game whose hotbar must answer wherever the player is puts [HotbarState.onKey] on its screen
 *   instead, where every key already passes through.
 */
@Composable
fun Hotbar(
    slots: List<HotbarSlot>,
    modifier: Modifier = Modifier,
    selected: Int = -1,
    onUse: (Int) -> Unit = {},
    onSelect: (Int) -> Unit = {},
    state: HotbarState = remember { HotbarState() },
    style: String = "hotbar",
    slotSize: Float = 48f,
    spacing: Float = 8f,
    hotkeys: Boolean = true,
    clock: Clock = Clock.Ui,
) {
    state.slots = slots
    state.onUse = onUse
    state.onSelect = onSelect

    val keys = if (!hotkeys) Modifier else Modifier.onKeyEvent(state::onKey)

    Row(
        modifier = modifier.then(keys),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        slots.forEachIndexed { index, slot ->
            SlotView(
                slot = slot,
                style = style,
                size = slotSize,
                selected = index == selected,
                presses = state.presses[index] ?: 0,
                clock = clock,
                onClick = { state.use(index) },
            )
        }
    }
}

/**
 * A hotbar's press, pulled out so that anything can do it.
 *
 * A key event only reaches a widget while focus is inside it, and a hotbar is the one thing on
 * screen that must answer wherever the player happens to be — reading a map, in a menu, halfway
 * through a dialogue. So the press lives here rather than inside the bar: hoist one of these, hand
 * it to [Hotbar], and put [onKey] on the screen, or call [use] from whatever binding the game has.
 *
 * It is not the bar's data — the slots stay where the game keeps them, and the bar hands them over
 * as it composes. This is only the part that presses.
 */
class HotbarState {

    /** How many times each slot has been pressed, so the same slot twice looks like two presses. */
    internal val presses = mutableStateMapOf<Int, Int>()

    internal var slots: List<HotbarSlot> = emptyList()
    internal var onUse: (Int) -> Unit = {}
    internal var onSelect: (Int) -> Unit = {}

    /**
     * Presses a slot, from wherever.
     *
     * @return whether anything happened. False for a slot that is empty, switched off, out of
     *   charges or still cooling down — and for one that is not there at all.
     */
    fun use(index: Int): Boolean {
        val slot = slots.getOrNull(index) ?: return false
        if (!slot.isUsable) return false
        presses[index] = (presses[index] ?: 0) + 1
        onSelect(index)
        onUse(index)
        return true
    }

    /**
     * The number keys, for a game that wants them answered wherever focus is.
     *
     * `Modifier.onKeyEvent(hotbar::onKey)` on the screen's outermost node. It answers a key it has
     * a slot for and leaves every other key alone, so it can sit above everything else without
     * swallowing anything.
     */
    fun onKey(event: KeyEvent): Boolean {
        // A held number key is one press. Nobody means to fire an ability sixty times a second.
        if (event.type != KeyEventType.Down || event.repeat) return false
        val index = Digits.indexOf(event.key)
        if (index < 0 || index >= slots.size) return false
        return use(index)
    }
}

/** The number keys, in the order a hotbar uses them: one to nine, then zero for the tenth. */
private val Digits = listOf(
    Key.Digit1, Key.Digit2, Key.Digit3, Key.Digit4, Key.Digit5,
    Key.Digit6, Key.Digit7, Key.Digit8, Key.Digit9, Key.Digit0,
)

/** How far a slot dips when it is used, and for how long. Felt rather than watched. */
private const val PunchOffset = 3f

private const val PunchMillis = 90

@Composable
private fun SlotView(
    slot: HotbarSlot,
    style: String,
    size: Float,
    selected: Boolean,
    presses: Int,
    clock: Clock,
    onClick: () -> Unit,
) {
    val interaction = remember { InteractionState() }
    val name = when {
        slot.isEmpty -> "$style.slot.empty"
        selected -> "$style.slot.selected"
        else -> "$style.slot"
    }
    val resolved = rememberStyle(name, rememberStates(interaction, slot.enabled && !slot.isEmpty))
    val prompt = rememberStyle("$style.prompt")
    val charges = rememberStyle("$style.charges")

    // Down the instant it is pressed, then eased back up: a press is a thing that happened, not a
    // thing that is starting to happen, so the drop is not animated and the return is.
    val clocks = LocalClocks.current
    val dip = remember(clocks, clock) { Animatable(0f, FloatVectoriser, clock, clocks) }
    LaunchedEffect(presses) {
        if (presses == 0) return@LaunchedEffect
        dip.snapTo(PunchOffset)
        dip.animateTo(0f, Tween(PunchMillis, easing = Easings.EaseOut))
    }

    Box(
        modifier = Modifier
            .interaction(interaction)
            .focusable(interaction, enabled = !slot.isEmpty && slot.enabled)
            .clickable(enabled = !slot.isEmpty, onClick = onClick)
            .size(size)
            .offset(y = dip.value)
            .styled(resolved),
        contentAlignment = Alignment.Centre,
    ) {
        // A picture stays put under the sweep and the seconds sit on top of it. A slot that has no
        // art has letters instead, and two lots of text in one square is unreadable, so while it
        // is cooling down the countdown *is* the slot's label.
        val cooling = slot.cooldown?.isRunning == true
        val body: @Composable () -> Unit = {
            when {
                slot.icon != null -> Image(slot.icon, Modifier.size(size * IconFraction), tint = resolved.textColour)
                slot.label != null && !cooling ->
                    Text(slot.label, textStyle = resolved.textStyle, colour = resolved.textColour)
            }
        }

        if (slot.cooldown != null) {
            RadialCooldown(slot.cooldown, Modifier.fillMaxSize(), content = body)
        } else {
            body()
        }

        slot.prompt?.let {
            Text(
                it,
                modifier = Modifier.align(Alignment.TopStart).padding(left = 3f, top = 1f),
                textStyle = prompt.textStyle,
                colour = prompt.textColour,
            )
        }

        if (slot.charges >= 0) {
            Text(
                slot.charges.toString(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(right = 3f, bottom = 1f),
                textStyle = charges.textStyle,
                colour = charges.textColour,
            )
        }
    }
}

/** How much of a slot the picture in it takes up. The rest is the frame and the corners' prompts. */
private const val IconFraction = 0.6f
