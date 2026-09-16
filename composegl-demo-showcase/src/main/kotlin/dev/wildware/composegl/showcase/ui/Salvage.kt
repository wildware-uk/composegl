package dev.wildware.composegl.showcase.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.game.ItemTooltip
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.Text

/**
 * A gun that has dropped, as a game would model one. The toolkit knows nothing about this class.
 *
 * Three of them, one of each tier, so the rarity colour on the card's edge and its name has
 * something to say.
 */
internal class Salvage(
    val name: String,
    val tier: String,
    val damage: Float,
    val rateOfFire: Float,
    val mass: Float,
    val rarity: Colour,
    val flavour: String,
)

/** What the player is carrying. Everything on the bench is weighed against this. */
private val Carried = Salvage(
    name = "MK II REPEATER",
    tier = "Standard",
    damage = 42f,
    rateOfFire = 3.4f,
    mass = 5.6f,
    rarity = Colour.rgb(0x8E9AAB),
    flavour = "Issued with the ship. Fires until it does not.",
)

private val Drops = listOf(
    Salvage(
        name = "ASHFALL",
        tier = "Rare",
        damage = 51f,
        rateOfFire = 3.1f,
        mass = 4.9f,
        rarity = Colour.rgb(0x5B8DEF),
        flavour = "Pulled out of a wreck that was still warm.",
    ),
    Salvage(
        name = "TIN CARBINE",
        tier = "Common",
        damage = 33f,
        rateOfFire = 4.6f,
        mass = 6.8f,
        rarity = Colour.rgb(0x8E9AAB),
        flavour = "Cheap and loud. Mostly loud.",
    ),
    Salvage(
        name = "SUNBREAKER",
        tier = "Legendary",
        damage = 74f,
        rateOfFire = 1.9f,
        mass = 9.2f,
        rarity = Colour.rgb(0xFFB020),
        flavour = "One shot, and then a long think about the next one.",
    ),
)

/**
 * Three drops on a bench, and the card a player actually decides with.
 *
 * The thing to watch is the **arrows**, not the numbers: hold Ctrl, or the pad's left bumper, and
 * every stat says whether taking this would be an improvement. The heaviest gun here hits hardest
 * and is worse in both of the other columns, which is exactly the decision a looter is for.
 *
 * Lighter is better, so the mass line shows a falling number with a rising arrow. That disagreement
 * is deliberate: the sign says which way the number went, the arrow says whether that is good, and
 * a player who cannot tell red from green still reads it.
 */
@Composable
internal fun SalvageBench() {
    Panel(Modifier.align(Alignment.BottomStart).padding(left = 28f, bottom = 190f)) {
        Column(verticalArrangement = Arrangement.spacedBy(8f)) {
            Text("SALVAGE", style = "label.heading")
            SalvageCards()
        }
    }
}

/**
 * The slots and the card that hangs off them, wherever they are put.
 *
 * The card is written outside the row on purpose: it is given the whole screen and takes what it
 * needs of it, so a drop's card is drawn past whatever panel the drop is sitting in.
 */
@Composable
internal fun SalvageCards() {
    val bench = remember { Bench() }

    Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
        Drops.forEach { drop -> SalvageSlot(drop, bench) }
    }

    ItemTooltip(
        item = bench.looking,
        compareWith = Carried,
        anchor = bench.anchor,
        rarity = { it.rarity },
        compareHint = "Hold Ctrl or LB to compare",
    ) {
        title(it.name)
        subtitle("${it.tier} · Main hand")
        separator()
        stat("Damage", it.damage)
        stat("Rate of fire", it.rateOfFire)
        stat("Mass", it.mass, higherIsBetter = false)
        flavour(it.flavour)
    }
}

/** What the bench is being looked at with, and where. */
private class Bench {

    var looking by mutableStateOf<Salvage?>(null)

    /** Where the slot is, for a pad player, who never hovers anything. Null follows the pointer. */
    var anchor by mutableStateOf<Rect?>(null)

    fun look(drop: Salvage, at: Rect?) {
        looking = drop
        anchor = at
    }

    /** Only if it is still this one: a pointer crossing from one slot to the next arrives in that order. */
    fun leave(drop: Salvage) {
        if (looking === drop) {
            looking = null
            anchor = null
        }
    }
}

/** One drop. Hovering it or putting focus on it is what puts its card up. */
@Composable
private fun SalvageSlot(drop: Salvage, bench: Bench) {
    val interaction = remember { InteractionState() }
    val states = rememberStates(interaction)

    // Written after layout and read only when focus lands here, so a slot moving costs nothing.
    val box = remember { FloatArray(4) }
    val placed = remember {
        PlacedHandler { node ->
            val at = node.boundsInRoot
            box[0] = at.left
            box[1] = at.top
            box[2] = at.right
            box[3] = at.bottom
        }
    }

    // Focus counts as hovering, which is the whole of the pad story: on a console nothing is ever
    // pointed at, so focus landing here is what puts the card up — and the card hangs off the slot
    // rather than off a pointer that has not moved since the game started.
    val hovered = interaction.isHovered
    val focused = interaction.isFocused
    DisposableEffect(hovered, focused, drop) {
        when {
            hovered -> bench.look(drop, null)
            focused -> bench.look(drop, Rect(box[0], box[1], box[2], box[3]))
            else -> bench.leave(drop)
        }
        onDispose { bench.leave(drop) }
    }

    Box(
        Modifier.size(56f)
            .interaction(interaction)
            // The state has to be handed to `focusable` as well: `interaction` on its own is told
            // about the pointer, and focus is only ever reported to the state focus itself was
            // given. Without it `isFocused` is false forever and the pad never puts a card up.
            .focusable(interaction)
            .onPlaced(placed)
            .styled("hotbar.slot", states),
        contentAlignment = Alignment.Centre,
    ) {
        Text(drop.name.take(2), style = "label", colour = drop.rarity)
    }
}
