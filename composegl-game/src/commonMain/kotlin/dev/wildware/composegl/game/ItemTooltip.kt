package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadHandler
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerWatcher
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.IntrinsicMeasurable
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onShortcutGamepad
import dev.wildware.composegl.ui.modifier.onShortcutKey
import dev.wildware.composegl.ui.modifier.styled
import dev.wildware.composegl.ui.modifier.watchPointer
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.widget.Divider
import dev.wildware.composegl.ui.widget.Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * When the comparison against what is already equipped is on screen.
 *
 * [Held] is the one nearly every looter uses, and it is the default: the card on its own is what a
 * player reads most of the time, and the comparison is a question they ask by holding a key. A card
 * that is always twice as wide covers the fight it was picked up in.
 */
enum class ItemCompare {

    /** The comparison is always there. For an inventory screen, where nothing is happening behind it. */
    Always,

    /** While the key or the pad button is held down. Diablo's Ctrl, Destiny's trigger. */
    Held,

    /** A press turns it on, another press turns it off. For a player who would rather not hold anything. */
    Toggled,

    /** Never, whatever is passed as the equipped item. */
    Never,
}

/**
 * The little glyphs beside a difference.
 *
 * **These carry the answer, not the colour.** Red and green are the first thing a looter player
 * learns and the one thing eight per cent of the men playing cannot see, so the mark says whether
 * the change is an improvement and the sign in front of the number says which way the number
 * itself moved. That is why a lighter weapon shows `-0.4 ▲`: the number went down, and that is
 * good. A card that only turned the number green says nothing at all to a player with deuteranopia.
 *
 * They are strings rather than characters so that a game whose font atlas has no triangles in it
 * can pass `ItemMarks("up", "down", "same")`, or its own art as text, without losing the meaning.
 */
data class ItemMarks(
    val better: String = "▲",
    val worse: String = "▼",
    val same: String = "=",
) {
    companion object {

        /** A triangle up for better and down for worse, which is what a player expects. */
        val Arrows = ItemMarks()
    }
}

/**
 * The lines of one item's card, written out by the game.
 *
 * The block is run **once for the item and once for what it would replace**, and the two runs are
 * matched up by the label a stat was given. That is the whole trick that makes the suggested shape
 * work: the game writes `stat("Damage", it.damage)` once and gets the difference for free, rather
 * than being asked for the hovered value, the equipped value and which of them is better.
 *
 * It is plain data — no widgets — so the same declaration is drawn twice, in the card and in the
 * one beside it, and a test can read the numbers without a screen.
 */
interface ItemCardScope {

    /** The name at the top, written in the item's rarity colour. */
    fun title(text: String)

    /** The smaller line under it: what kind of thing it is, what slot it goes in, its level. */
    fun subtitle(text: String)

    /**
     * A number that can be compared against the same number on the equipped item.
     *
     * @param label what it is called. It is also the identity used to pair this stat with the
     *   equipped item's, so the same stat must be given the same label in both runs — which it is,
     *   because it is the same line of code running twice.
     * @param higherIsBetter whether a bigger number is an improvement. False for weight, for
     *   reload time, for anything a player wants less of.
     * @param format how the number is written. The default writes whole numbers whole and
     *   everything else to one decimal place. A game showing percentages or its own digits passes
     *   its own; see [dev.wildware.composegl.game.CompassBar] for why this is a lambda and not a
     *   number of decimal places.
     */
    fun stat(
        label: String,
        value: Float,
        higherIsBetter: Boolean = true,
        format: (Float) -> String = PlainNumber,
    )

    /** The same for a whole number, written with no decimal point and no difference of half a point. */
    fun stat(label: String, value: Int, higherIsBetter: Boolean = true)

    /** A labelled line with nothing to compare: `line("Slot", "Main hand")`. */
    fun line(label: String, value: String)

    /** A line of ordinary words — a rule the item breaks, a set bonus. */
    fun text(text: String)

    /** The fiction at the bottom: what the item is, rather than what it does. */
    fun flavour(text: String)

    /** A rule across the card, for a game that wants its stats fenced off from its story. */
    fun separator()
}

/**
 * The item card a looter lives in: what this is, and how it compares with what is already on.
 *
 * Diablo, Destiny and Borderlands are all played through this widget. A player picking something
 * up does not read the numbers — they read the **green and red arrows**, decide in about a third
 * of a second, and carry on fighting. So the comparison is the point of it, and the plain card is
 * what is left when there is nothing to compare against.
 *
 * ```kotlin
 * ItemTooltip(
 *     item = hovered,
 *     compareWith = equipped[hovered?.slot],
 *     rarity = { it.rarity.colour },
 * ) {
 *     stat("Damage", it.damage)
 *     stat("Speed", it.speed, higherIsBetter = true)
 *     stat("Weight", it.weight, higherIsBetter = false)
 *     flavour(it.description)
 * }
 * ```
 *
 * It is a layer rather than a wrapper round the icon, for the reason a tooltip is: the card has to
 * be drawn over the bag it came out of and the panel next to that bag, and it has to be allowed to
 * move so that none of it is off the screen. So a game gives it the room it may use — the whole
 * screen by default — says what the pointer or the focus is on, and the layer does the rest.
 *
 * **The layer is not in front of the bag.** It covers the screen, but it only *watches* the pointer
 * — `watchPointer`, not `onPointer` — so every slot underneath is hovered exactly as it was before
 * the card layer was there. A layer that handled the pointer instead would be the topmost thing the
 * mouse ever found and would take the hover its own `item` is worked out from.
 *
 * **Nothing is composed while [item] is null**, which is nearly all of the time. The layer's own
 * node stays, because the key that turns the comparison on has to be heard before the card
 * appears: a player who is already holding it when they hover the next sword expects to see the
 * arrows on it, not to have to let go and press it again. It is only *heard* there, never taken:
 * see [compareKey].
 *
 * Everything it looks like is the skin's: `"<style>"` is the card, `"<style>.rarity"` the edge when
 * the game names no colour, `"<style>.compare"` the equipped card beside it, and `"<style>.title"`,
 * `.subtitle`, `.label`, `.value`, `.better`, `.worse`, `.same`, `.flavour` and `.hint` are its
 * lines.
 *
 * @param item what is being looked at. Null draws nothing, and so does an item whose [content]
 *   writes no lines: an empty frame says less than no frame at all.
 * @param compareWith what the player has on now in the same slot. Null means there is nothing to
 *   compare against, so no differences are drawn and no second card appears, whatever [compare]
 *   says.
 * @param anchor the thing the card belongs to — a bag slot, a hotbar button — as a rectangle **in
 *   the root's coordinates**, which is what `onPlaced { node -> anchor = node.boundsInRoot }`
 *   gives you and what every other widget here takes. The layer turns it into its own coordinates,
 *   so it is still the right rectangle when the layer is inside something padded or offset. The
 *   card hangs under it, and above it near the bottom of the screen. **This is the pad's half of
 *   the widget**: nothing is ever hovered on a console, so a game that moves focus along a bag
 *   passes the focused slot's bounds here and the card follows focus. Null puts the card at the
 *   pointer instead.
 * @param rarity the colour of this item's tier: the card's edge and its title. Null takes the
 *   skin's `"<style>.rarity"`, so a game whose tiers live in its skin file rather than in its code
 *   can leave this alone.
 * @param compare when the comparison is on screen. See [ItemCompare].
 * @param compareKey the key that turns it on for [ItemCompare.Held] and [ItemCompare.Toggled].
 *   Heard wherever focus is, because a player holding it is not first clicking on anything. Only
 *   swallowed while a card with something to compare against is actually up, so the rest of the
 *   game keeps its Ctrl the other ninety-nine per cent of the time.
 *
 *   **Ctrl and not Shift**, which is the question a card over a bag asks. Shift held as a pile is
 *   picked up is what splits a stack in half, in this toolkit's own [InventoryGrid] and in every
 *   game that has a bag; one key doing both means a player who held it to read the arrows walks
 *   away with three of their five rings. Ctrl is the key Diablo compares with, and it is free.
 * @param compareButton the pad button that does the same. A bumper by default: the triggers are
 *   usually the game's.
 * @param sideBySide whether the equipped item gets a card of its own next to this one. False keeps
 *   the arrows and drops the second card, for a game with no room for two — which is what a screen
 *   narrower than `2 × width + gap` is, since two cards that will not fit are two cards one of
 *   which is against an edge.
 * @param comparedLabel the caption over the equipped card. English by default; a game that is
 *   translated passes its own, the way it does every other string it shows a player.
 * @param compareHint a line at the foot of the card saying how to see the comparison — "Hold Ctrl
 *   to compare". Null draws none, which is what a game with its own [PromptGlyph] row wants.
 * @param marks the glyphs that say better and worse without using colour. See [ItemMarks].
 * @param width how wide one card is. Fixed rather than grown to fit, because a column of numbers
 *   that changes width as the player moves along a row is a column nobody can read, and because
 *   flavour text has to know where to wrap.
 * @param gap how far the card sits from the pointer or from its anchor, and how far the two cards
 *   sit apart.
 * @param content the card's lines. See [ItemCardScope].
 */
@Composable
fun <T : Any> ItemTooltip(
    item: T?,
    modifier: Modifier = Modifier.fillMaxSize(),
    compareWith: T? = null,
    anchor: Rect? = null,
    rarity: (T) -> Colour? = { null },
    compare: ItemCompare = ItemCompare.Held,
    compareKey: Key = Key.Control,
    compareButton: GamepadButton = GamepadButton.LeftBumper,
    sideBySide: Boolean = true,
    comparedLabel: String = "Equipped",
    compareHint: String? = null,
    marks: ItemMarks = ItemMarks.Arrows,
    width: Float = 220f,
    gap: Float = 14f,
    style: String = "itemtip",
    content: ItemCardScope.(T) -> Unit,
) {
    require(width > 0f) { "an item card cannot be $width wide" }

    // The handlers are this object's own, so they are the same objects every recomposition and a
    // card following the pointer does not rebuild its modifiers a frame. What changes each pass is
    // only what they read. The same split [RadialMenu] makes, for the same reason.
    val input = remember { ItemCardInput() }
    input.mode = compare
    input.key = compareKey
    input.button = compareButton
    input.comparable = compareWith != null

    // The equipped item, but only while the comparison is actually on. Everything below asks this
    // rather than asking twice whether there is something to compare and whether it is wanted.
    val against = compareWith?.takeIf {
        when (compare) {
            ItemCompare.Always -> true
            ItemCompare.Never -> false
            ItemCompare.Held, ItemCompare.Toggled -> input.on
        }
    }

    // Worked out afresh every pass rather than remembered. The block is the caller's lambda, so it
    // is a new object on most compositions and a `remember` keyed on it would miss anyway; and what
    // it builds is a handful of small immutable rows, which is cheaper than being clever about it.
    val mine = item?.let { collect(content, it) }
    val theirs = against?.let { collect(content, it) }
    val againstRarity = against?.let(rarity)
    val deltas = if (mine != null && theirs != null) deltasOf(mine, theirs) else null

    // Told after the lines are collected rather than with the rest: an item whose block writes
    // nothing draws no card either, and the compare key is only this widget's while a card is up.
    input.showing = mine != null && mine.isNotEmpty()

    val hint = compareHint?.takeIf {
        compareWith != null && (compare == ItemCompare.Held || compare == ItemCompare.Toggled)
    }

    // A new policy each pass, so the node is laid out again when the pointer has moved. Placing it
    // is arithmetic on two numbers; the card itself is measured once and put somewhere else.
    //
    // Where the pointer is is read **only while a card is up**. Reading it always would make every
    // mouse move anywhere on the screen recompose this, for a layer that is drawing nothing.
    val placement = ItemCardPlacement(anchor, if (item == null) null else input.pointer, gap, input)

    Layout(
        modifier = modifier
            .onPlaced(input.placed)
            .watchPointer(input.moves)
            .onShortcutKey(input.keys)
            .onShortcutGamepad(input.pad),
        name = "itemtooltip",
        measurePolicy = placement,
        content = {
            if (item != null && mine != null && mine.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = VerticalAlignment.Top) {
                    ItemCard(
                        entries = mine,
                        deltas = deltas,
                        rarity = rarity(item),
                        caption = null,
                        hint = hint,
                        background = style,
                        style = style,
                        width = width,
                        marks = marks,
                    )
                    if (sideBySide && theirs != null) {
                        ItemCard(
                            entries = theirs,
                            deltas = null,
                            rarity = againstRarity,
                            caption = comparedLabel,
                            hint = null,
                            background = "$style.compare",
                            style = style,
                            width = width,
                            marks = marks,
                        )
                    }
                }
            }
        },
    )
}

/**
 * One card: its frame in the rarity colour, and its lines.
 *
 * @param background the skin style the frame is drawn from, which is the only thing that differs
 *   between the hovered item's card and the equipped one's.
 * @param style the base name every line's style hangs off, which is the *same* for both cards: the
 *   equipped card's stats are written the way the hovered one's are, or the two cannot be read
 *   against each other.
 */
@Composable
private fun ItemCard(
    entries: List<CardEntry>,
    deltas: FloatArray?,
    rarity: Colour?,
    caption: String?,
    hint: String?,
    background: String,
    style: String,
    width: Float,
    marks: ItemMarks,
) {
    val frame = rememberStyle(background)
    val fallback = rememberStyle("$style.rarity")
    // The game's colour first, then the skin's; a skin that names no rarity style at all leaves the
    // card edged the way it drew itself.
    val edge = rarity ?: (fallback.background as? SkinDrawable.Fill)?.colour?.takeIf { it.alpha > 0 }
    val skin = if (edge == null) frame else frame.copy(background = frame.background.edgedWith(edge))

    Column(
        Modifier.width(width).styled(skin),
        verticalArrangement = Arrangement.spacedBy(LineGap),
    ) {
        if (caption != null) Text(caption, style = "$style.subtitle")

        entries.forEachIndexed { index, entry ->
            when (entry) {
                is CardEntry.Title -> Text(entry.text, style = "$style.title", colour = edge)
                is CardEntry.Subtitle -> Text(entry.text, style = "$style.subtitle")
                is CardEntry.Words -> Text(entry.text, style = style)
                is CardEntry.Flavour -> Text(entry.text, style = "$style.flavour")
                CardEntry.Rule -> Divider(Modifier.fillMaxWidth())
                is CardEntry.Line -> StatLine(entry.label, entry.value, delta = null, deltaStyle = style, style = style)
                is CardEntry.Stat -> {
                    val delta = deltas?.getOrNull(index)?.takeIf { !it.isNaN() }
                    val verdict = verdictOf(delta, entry)
                    StatLine(
                        label = entry.label,
                        value = entry.format(entry.value),
                        delta = delta?.let { difference(it, entry, verdict, marks) },
                        deltaStyle = "$style.${verdict.suffix}",
                        style = style,
                    )
                }
            }
        }

        if (hint != null) Text(hint, style = "$style.hint")
    }
}

/** A stat's label on the start of the line and its number — with what it would become — on the end. */
@Composable
private fun StatLine(label: String, value: String, delta: String?, deltaStyle: String, style: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = VerticalAlignment.Top) {
        // The weight is what keeps the numbers in a column: the label takes whatever is left over,
        // so every value on the card starts at the same place however long its name is.
        Text(label, Modifier.weight(1f), style = "$style.label", maxLines = 1, ellipsis = "…")
        Row(horizontalArrangement = Arrangement.spacedBy(DeltaGap)) {
            Text(value, style = "$style.value")
            if (delta != null) Text(delta, style = deltaStyle)
        }
    }
}

/**
 * What a difference means to the player: an improvement, a step down, or neither.
 *
 * One answer used twice over — it picks the glyph and it picks the skin style, so the colour and
 * the mark can never disagree with each other. [suffix] is the name the skin knows it by.
 */
private enum class Verdict(val suffix: String) {
    Better("better"),
    Worse("worse"),
    Same("same"),
}

private fun verdictOf(delta: Float?, stat: CardEntry.Stat): Verdict = when {
    delta == null || stat.unchangedBy(delta) -> Verdict.Same
    (delta > 0f) == stat.higherIsBetter -> Verdict.Better
    else -> Verdict.Worse
}

/**
 * Whether a difference is one the card can actually show.
 *
 * Asked of the stat's own format rather than of the number, because the number is not what the
 * player reads: a speed of 1.80 against one of 1.82 differs by 0.02, which the default format
 * writes as `0`. A sign in front of that — `+0 ▲` — is a difference that says there is one and
 * then does not show it. So anything that comes out the way nothing comes out *is* nothing.
 */
private fun CardEntry.Stat.unchangedBy(delta: Float): Boolean = format(abs(delta)) == format(0f)

/**
 * A difference as a player reads it: the sign says which way the number moved, the mark says
 * whether that is an improvement.
 *
 * A stat that has not changed is the mark on its own. Writing `+0` there would be a number a player
 * has to look at twice to find out it says nothing.
 */
private fun difference(delta: Float, stat: CardEntry.Stat, verdict: Verdict, marks: ItemMarks): String {
    val mark = when (verdict) {
        Verdict.Better -> marks.better
        Verdict.Worse -> marks.worse
        Verdict.Same -> marks.same
    }
    if (stat.unchangedBy(delta)) return mark
    return "${if (delta > 0f) "+" else "-"}${stat.format(abs(delta))} $mark"
}

/**
 * The card's own frame, re-edged in the rarity colour.
 *
 * A copy of the skin's drawable rather than a border drawn over it, so the edge follows the corners
 * the skin chose and is as thick as the skin's own. Art — a nine-patch — is left alone: a legendary
 * frame painted by an artist already says what tier it is, and tinting it a flat colour would only
 * wreck it.
 */
private fun SkinDrawable.edgedWith(colour: Colour): SkinDrawable = when (this) {
    is SkinDrawable.Fill -> copy(border = colour, borderWidth = borderWidth.coerceAtLeast(RarityEdge))
    is SkinDrawable.Gradient -> copy(border = colour, borderWidth = borderWidth.coerceAtLeast(RarityEdge))
    else -> this
}

/** How thick the rarity edge is when the skin's own frame has no edge to recolour. */
private const val RarityEdge = 2f

/** The space between one line of a card and the next. */
private const val LineGap = 3f

/** The space between a value and the difference beside it. */
private const val DeltaGap = 6f

// --- what the game wrote --------------------------------------------------------------------------

/** One line of a card, as the game declared it. Plain data: it is drawn twice and read by tests. */
internal sealed interface CardEntry {

    class Title(val text: String) : CardEntry

    class Subtitle(val text: String) : CardEntry

    class Stat(
        val label: String,
        val value: Float,
        val higherIsBetter: Boolean,
        val format: (Float) -> String,
    ) : CardEntry

    class Line(val label: String, val value: String) : CardEntry

    class Words(val text: String) : CardEntry

    class Flavour(val text: String) : CardEntry

    data object Rule : CardEntry
}

/** The scope itself: it collects lines and does nothing else. */
private class CardLines : ItemCardScope {

    val entries = ArrayList<CardEntry>()

    override fun title(text: String) {
        entries += CardEntry.Title(text)
    }

    override fun subtitle(text: String) {
        entries += CardEntry.Subtitle(text)
    }

    override fun stat(label: String, value: Float, higherIsBetter: Boolean, format: (Float) -> String) {
        entries += CardEntry.Stat(label, value, higherIsBetter, format)
    }

    override fun stat(label: String, value: Int, higherIsBetter: Boolean) {
        entries += CardEntry.Stat(label, value.toFloat(), higherIsBetter, WholeNumber)
    }

    override fun line(label: String, value: String) {
        entries += CardEntry.Line(label, value)
    }

    override fun text(text: String) {
        entries += CardEntry.Words(text)
    }

    override fun flavour(text: String) {
        entries += CardEntry.Flavour(text)
    }

    override fun separator() {
        entries += CardEntry.Rule
    }
}

/** The block run for one item. */
internal fun <T : Any> collect(content: ItemCardScope.(T) -> Unit, item: T): List<CardEntry> =
    CardLines().apply { content(item) }.entries

/**
 * How much better each of this item's stats is than the equipped one's, or not-a-number for a stat
 * the other item does not have.
 *
 * Paired by label rather than by position, so a card that adds a line for a socket or a set bonus
 * on one item and not on the other still lines its numbers up. Each of the equipped item's stats is
 * used once: an item with two lines called "Damage" pairs the first with the first and the second
 * with the second, which is the only answer that is not arbitrary.
 */
internal fun deltasOf(mine: List<CardEntry>, theirs: List<CardEntry>): FloatArray {
    val out = FloatArray(mine.size) { Float.NaN }
    val used = BooleanArray(theirs.size)
    for (index in mine.indices) {
        val stat = mine[index] as? CardEntry.Stat ?: continue
        for (other in theirs.indices) {
            if (used[other]) continue
            val against = theirs[other] as? CardEntry.Stat ?: continue
            if (against.label != stat.label) continue
            used[other] = true
            out[index] = stat.value - against.value
            break
        }
    }
    return out
}

/** Whole numbers whole, everything else to one decimal place. */
internal val PlainNumber: (Float) -> String = { value ->
    val tenths = (value * 10f).roundToInt()
    if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10f}"
}

/** A count, a level, a number of sockets: never a decimal point. */
private val WholeNumber: (Float) -> String = { "${it.roundToInt()}" }

// --- turning the comparison on ---------------------------------------------------------------------

/**
 * The key and the pad button that show the comparison, and where the pointer is.
 *
 * The pointer is state, unlike the one a [dev.wildware.composegl.ui.widget.Tooltip] keeps, because
 * a card is a whole layout rather than something drawn straight onto the canvas: it has to be laid
 * out again when it moves. That costs a recomposition of a dozen nodes per pointer move — but only
 * while a card is up, which is only while the player is actually looking at something.
 */
private class ItemCardInput {

    // --- what the composition tells it, once a pass ---
    var mode: ItemCompare = ItemCompare.Held
    var key: Key = Key.Control
    var button: GamepadButton = GamepadButton.LeftBumper

    /** Whether there is anything to compare against. Nothing is swallowed when there is not. */
    var comparable: Boolean = false

    /** Whether a card is actually on the screen. Nothing is swallowed while there is none. */
    var showing: Boolean = false

    // --- what it works out ---

    /** Whether the comparison is on: the key is down, or the toggle was flipped. */
    var on by mutableStateOf(false)
        private set

    /** Where the pointer last was, or null before it has been anywhere and once it has left. */
    var pointer by mutableStateOf<Offset?>(null)
        private set

    /**
     * The pointer, watched and never taken.
     *
     * A watcher rather than a handler, which is the difference between a layer that knows where the
     * mouse is and a layer that stands in front of the bag: the slots underneath are what decide
     * which item is hovered, and a full-screen node the pointer *finds* would be hovered instead of
     * them and leave the game with nothing to put in [ItemTooltip]'s `item`.
     *
     * Every event and not only a move, because every one of them says where the pointer is: a bag
     * whose slots are clickable and draggable captures the press, and a card that only followed
     * moves would freeze where the player pressed and stay there until they let go. A pointer that
     * has left the window is nowhere, so the card goes back to having no place of its own.
     */
    val moves = PointerWatcher { event ->
        pointer = if (event is PointerEvent.Exit) null else event.position
    }

    /**
     * The layer's own node, once layout has put it somewhere.
     *
     * The card is placed in this Layout's coordinates, and an `anchor` is a slot's `boundsInRoot` —
     * two different things the moment the layer is not itself at the root's origin, which is what a
     * layer inside a padded panel or a split screen is. Kept so [ItemCardPlacement] can turn the
     * one into the other. Null only before the first layout, when there is nothing to draw anyway.
     *
     * A plain field rather than state: it is read while measuring, not while composing, so it wants
     * to be the latest value and not a reason to recompose.
     */
    var layer: UiNode? = null
        private set

    val placed = PlacedHandler { layer = it }

    /**
     * The compare key, heard wherever focus is.
     *
     * The key is **followed whether or not it is taken**. Those are two different questions: a
     * player holding the key already when they hover the next sword expects the arrows on it
     * straight away, so the key going down with nothing on screen still has to be noticed — while
     * swallowing it there would take the key from the rest of the game for the sake of a card that is
     * not up. So the state follows the key always, and only the answer depends on there being a
     * card and something to compare it with.
     *
     * A [ItemCompare.Held] card follows the key down and up. A [ItemCompare.Toggled] one flips on
     * the press and ignores the release, ignores a repeat as well — a held key that repeats would
     * otherwise flicker the comparison on and off — and flips only while a card is up, because a
     * toggle is a decision about the thing being looked at rather than the state of a finger.
     */
    val keys = KeyHandler { event ->
        if (event.key != key || !switchable) {
            false
        } else {
            when (mode) {
                ItemCompare.Held -> on = event.type == KeyEventType.Down
                ItemCompare.Toggled -> if (showing && event.type == KeyEventType.Down && !event.repeat) on = !on
                ItemCompare.Always, ItemCompare.Never -> Unit
            }
            tookKey(event.type == KeyEventType.Down, event.repeat)
        }
    }

    /** The pad's twin of [keys], with the same rules. */
    val pad = GamepadHandler { event ->
        when (event) {
            is GamepadEvent.ButtonDown -> if (event.button != button || !switchable) false else {
                when (mode) {
                    ItemCompare.Held -> on = true
                    ItemCompare.Toggled -> if (showing) on = !on
                    ItemCompare.Always, ItemCompare.Never -> Unit
                }
                tookButton(down = true)
            }
            // The button coming up always lets go, whatever is on screen: a release that was
            // ignored because the pointer had moved off the item would leave the comparison on
            // with nothing held, for good.
            is GamepadEvent.ButtonUp -> if (event.button != button || !switchable) false else {
                if (mode == ItemCompare.Held) on = false
                tookButton(down = false)
            }
            // Somebody pulled the cable with the button held. Let go of it rather than leaving the
            // comparison up forever with nothing able to take it down again.
            is GamepadEvent.Disconnected -> {
                if (mode == ItemCompare.Held) on = false
                padHeld = false
                false
            }
            is GamepadEvent.Connected, is GamepadEvent.Axis -> false
        }
    }

    /** Whether this card's comparison is something a key can switch at all. */
    private val switchable: Boolean get() = mode == ItemCompare.Held || mode == ItemCompare.Toggled

    /** Whether the key and the button still down were swallowed here, so their releases are too. */
    private var keyHeld: Boolean = false
    private var padHeld: Boolean = false

    /**
     * Whether this key event is this widget's to swallow, the state having already followed it.
     *
     * A press is swallowed only while the card is doing something a player can see: with no card up
     * — or nothing equipped to compare it against — the key stays the rest of the game's. The release
     * then goes the same way as its press whatever has happened in between, because a game handed
     * half a key is left holding one that never comes up. A repeat is part of the press it repeats.
     */
    private fun tookKey(down: Boolean, repeat: Boolean): Boolean {
        if (!down) return keyHeld.also { keyHeld = false }
        if (!repeat) keyHeld = showing && comparable
        return keyHeld
    }

    /** The same for the pad button, which the navigator would otherwise be left holding. */
    private fun tookButton(down: Boolean): Boolean {
        if (!down) return padHeld.also { padHeld = false }
        padHeld = showing && comparable
        return padHeld
    }
}

// --- putting it on the screen ----------------------------------------------------------------------

/**
 * The card beside the thing it is about, and all of it on the screen.
 *
 * The same two rules a tooltip follows, and for the same reason: a card half off the bottom of the
 * screen is the one thing a player cannot scroll to. Down and towards the end by choice, back the
 * other way when that side has no room, and slid along rather than flipped at the last step so it
 * is never cut off at an edge.
 *
 * Towards the **end**, not towards the right, so an Arabic screen puts the card to the left of the
 * pointer where a player who reads right to left is already looking. The two cards mirror with it,
 * because they are a [Row] and a row's children are laid out from the start.
 */
private class ItemCardPlacement(
    private val anchor: Rect?,
    private val pointer: Offset?,
    private val gap: Float,
    private val input: ItemCardInput,
) : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val width = if (constraints.maxWidth.isFinite()) constraints.maxWidth else constraints.minWidth
        val height = if (constraints.maxHeight.isFinite()) constraints.maxHeight else constraints.minHeight
        if (measurables.isEmpty()) return layout(width, height, 0)

        val placeables = placeables(1)
        // Never wider or taller than the room it was given: two cards and a long flavour line on a
        // phone-sized screen have to give something up, and being drawn off the edge is not it.
        val card = measurables[0].measure(Constraints.atMost(width, height))
        placeables[0] = card

        val rtl = layoutDirection == LayoutDirection.Rtl
        // The anchor arrives in the root's coordinates, because a slot's `boundsInRoot` is the only
        // thing a game has to hand; the card is placed in this layer's. The two are the same number
        // only while the layer sits at the root's origin, so a layer inside a padded panel would put
        // every card out by that padding without this.
        val here = anchor?.let { inLayer(it) }
        var x: Float
        var y: Float
        when {
            here != null -> {
                val below = here.bottom + gap
                y = if (below + card.height <= height) below else here.top - gap - card.height
                // Under its own middle, then slid along at the end of this to stay on screen.
                x = (here.left + here.right) / 2f - card.width / 2f
            }
            pointer != null -> {
                val forwards = if (rtl) pointer.x - gap - card.width else pointer.x + gap
                val fits = if (rtl) forwards >= 0f else forwards + card.width <= width
                x = if (fits) forwards else if (rtl) pointer.x + gap else pointer.x - gap - card.width
                val below = pointer.y + gap
                y = if (below + card.height <= height) below else pointer.y - gap - card.height
            }
            // Nothing has said where: a pad screen that passed no anchor, or a pointer that has not
            // moved yet. The middle is the one place that is never a surprise.
            else -> {
                x = (width - card.width) / 2f
                y = (height - card.height) / 2f
            }
        }

        val placements = placements(1)
        placements[0] = x.coerceIn(0f, (width - card.width).coerceAtLeast(0f))
        placements[1] = y.coerceIn(0f, (height - card.height).coerceAtLeast(0f))
        return layout(width, height, 1)
    }

    /**
     * [rect], given in the root's coordinates, in this layer's own.
     *
     * Corners rather than a translation, because the layer may be scaled or mirrored by something
     * above it, and `toLocal` is what knows about both. Mirroring swaps which corner is on the left,
     * so the sides are sorted again afterwards.
     *
     * The layer's node is unknown only until it has been laid out once, and nothing is drawn before
     * that, so the untouched rectangle is as good an answer as any for that one pass.
     */
    private fun inLayer(rect: Rect): Rect {
        val layer = input.layer ?: return rect
        val a = layer.toLocal(rect.topLeft)
        val b = layer.toLocal(Offset(rect.right, rect.bottom))
        return Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    }

    // A layer wants no size of its own, and a question must not move anything.
    override fun MeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) = 0f
    override fun MeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float) = 0f
    override fun MeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) = 0f
    override fun MeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float) = 0f
}
