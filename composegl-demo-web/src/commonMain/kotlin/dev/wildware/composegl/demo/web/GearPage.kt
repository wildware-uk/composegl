package dev.wildware.composegl.demo.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.game.ChatBox
import dev.wildware.composegl.game.ChatChannel
import dev.wildware.composegl.game.ChatMessage
import dev.wildware.composegl.game.InventoryCell
import dev.wildware.composegl.game.InventoryGrid
import dev.wildware.composegl.game.InventoryItem
import dev.wildware.composegl.game.InventoryState
import dev.wildware.composegl.game.ItemTooltip
import dev.wildware.composegl.game.Notifications
import dev.wildware.composegl.game.ObjectiveProgress
import dev.wildware.composegl.game.ObjectiveTracker
import dev.wildware.composegl.game.SkillEdge
import dev.wildware.composegl.game.SkillNode
import dev.wildware.composegl.game.SkillTree
import dev.wildware.composegl.game.rememberChatState
import dev.wildware.composegl.game.rememberNotifications
import dev.wildware.composegl.game.skillTreeBounds
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.animation.wait
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.FlowRow
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text

/**
 * The composegl-game widgets for what a player carries and what they are working towards: a bag,
 * the card that compares loot, a skill tree, objectives and chat.
 */
@Composable
fun GearPage() {
    Page(Section.Gear, "The screens a player opens between fights. Drag works with a mouse or a finger; a pad or the keyboard picks up with Enter and puts down with Enter.") {
        BagCard()
        LootCard()
        SkillsCard()
        ObjectivesCard()
        ChatCard()
    }
}

/** The inside of a card, which is what a widget told its width has to fit. */
@Composable
private fun cardInside(): Float = LocalCardWidth.current - 40f

@Composable
private fun BagCard() = Card("Inventory grid", "Drag between the two bags. Piles of the same thing merge; the bow is two squares long. Hold Shift as you pick up to take half; R turns what you carry; right-click or long-press a pile for more.") {
    val pack = remember {
        InventoryState(
            columns = 4,
            rows = 3,
            items = listOf(
                InventoryItem(id = "bow", kind = "BOW", at = InventoryCell(0, 0), width = 2, height = 1),
                InventoryItem(id = "arrows", kind = "ARR", at = InventoryCell(0, 1), count = 14, stackLimit = 20),
                InventoryItem(id = "potion", kind = "POT", at = InventoryCell(3, 2), count = 2, stackLimit = 5),
            ),
        )
    }
    val chest = remember {
        InventoryState(
            columns = 4,
            rows = 3,
            items = listOf(
                InventoryItem(id = "more-arrows", kind = "ARR", at = InventoryCell(1, 0), count = 9, stackLimit = 20),
                InventoryItem(id = "shield", kind = "SHD", at = InventoryCell(2, 1), width = 2, height = 2),
            ),
        )
    }
    // Two grids side by side where they fit, one over the other on a phone.
    val cell = ((cardInside() - 16f) / 8f - 4f).coerceIn(30f, 44f)
    FlowRow(horizontalSpacing = 16f, verticalSpacing = 10f) {
        Labelled("PACK") { Bag(pack, cell, Modifier.testTag("pack")) }
        Labelled("CHEST") { Bag(chest, cell, Modifier.testTag("chest")) }
    }
}

@Composable
private fun Bag(bag: InventoryState, cell: Float, modifier: Modifier) {
    InventoryGrid(
        state = bag,
        modifier = modifier,
        cellSize = cell,
        spacing = 4f,
        menu = { item ->
            Item("Drop") { bag.remove(item) }
            Item("Tidy the bag") { bag.sort() }
        },
    ) { item ->
        Text(item.kind.toString(), style = "label")
    }
}

/** A sword on the floor, as a game would model one. The toolkit knows nothing about this class. */
private class Loot(
    val name: String,
    val tier: String,
    val damage: Float,
    val speed: Float,
    val weight: Float,
    val rarity: Colour,
    val flavour: String,
)

private val Carried = Loot("IRON SWORD", "Common", 24f, 1.1f, 4.5f, Colour.rgb(0x9AA4B2), "It has seen better days, and so have you.")

private val Drops = listOf(
    Loot("GLASSWING", "Rare", 31f, 1.4f, 2.2f, Colour.rgb(0x4CC2FF), "Lighter than it looks. Sharper, too."),
    Loot("OLD CLEAVER", "Common", 20f, 0.9f, 6.1f, Colour.rgb(0x9AA4B2), "Found in a kitchen. Probably."),
    Loot("SUNBRAND", "Legendary", 48f, 0.8f, 7.4f, Colour.rgb(0xF2A65A), "Warm to the touch, even in winter."),
)

/** What is being looked at, and where. */
private class Bench {
    var looking by mutableStateOf<Loot?>(null)

    /** The slot's box, for focus from a keyboard or a pad. Null follows the pointer. */
    var anchor by mutableStateOf<Rect?>(null)

    fun leave(drop: Loot) {
        if (looking === drop) {
            looking = null
            anchor = null
        }
    }
}

@Composable
private fun LootCard() = Card("Item card and comparison", "Hover a drop, tap it, or Tab onto it. Hold Ctrl, or a pad's left bumper, to see it beside what you carry.") {
    val bench = remember { Bench() }
    val inside = cardInside()
    Box(Modifier.fillMaxWidth().height(290f).testTag("loot")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
            Drops.forEachIndexed { index, drop -> LootSlot(drop, bench, Modifier.testTag("loot-$index")) }
        }
        ItemTooltip(
            item = bench.looking,
            modifier = Modifier.fillMaxSize(),
            compareWith = Carried,
            anchor = bench.anchor,
            rarity = { it.rarity },
            compareHint = "Hold Ctrl or LB to compare",
            // Two cards only where two fit; a phone gets the arrows on one.
            sideBySide = inside >= 2 * LootCardWidth + 10f,
            width = LootCardWidth,
            gap = 10f,
        ) {
            title(it.name)
            subtitle("${it.tier} · Main hand")
            separator()
            stat("Damage", it.damage)
            stat("Speed", it.speed)
            stat("Weight", it.weight, higherIsBetter = false)
            flavour(it.flavour)
        }
    }
    Text(bench.looking?.let { "Looking at ${it.name}" } ?: "Nothing picked up", Modifier.testTag("looking"), style = "label.dim")
}

private const val LootCardWidth = 170f

@Composable
private fun LootSlot(drop: Loot, bench: Bench, modifier: Modifier) {
    val interaction = remember { InteractionState() }
    val states = rememberStates(interaction)
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
    val hovered = interaction.isHovered
    val focused = interaction.isFocused
    val pressed = interaction.isPressed
    DisposableEffect(hovered, focused, pressed, drop) {
        when {
            hovered -> {
                bench.looking = drop
                bench.anchor = null
            }
            // A finger never hovers, and neither does a pad: both are answered by the slot's own box.
            focused || pressed -> {
                bench.looking = drop
                bench.anchor = Rect(box[0], box[1], box[2], box[3])
            }
            else -> bench.leave(drop)
        }
        onDispose { bench.leave(drop) }
    }
    Box(
        modifier.size(56f)
            .interaction(interaction)
            .focusable(interaction)
            .onPlaced(placed)
            .styled("hotbar.slot", states),
        contentAlignment = Alignment.Centre,
    ) {
        Text(drop.name.take(2), colour = drop.rarity)
    }
}

private class Talent(val id: String, val label: String, val x: Float, val y: Float, val ranks: Int = 1, val blurb: String)

private val Talents = listOf(
    Talent("root", "S", 0f, 0f, blurb = "Swordplay: where every warrior starts"),
    Talent("edge", "E", 120f, -70f, ranks = 3, blurb = "Keen edge: more damage per rank"),
    Talent("guard", "G", 120f, 70f, ranks = 2, blurb = "Guard: block a blow and recover"),
    Talent("flurry", "F", 240f, -70f, blurb = "Flurry: three quick cuts"),
    Talent("wall", "W", 240f, 70f, blurb = "Iron wall: nothing gets past"),
    Talent("master", "M", 360f, 0f, blurb = "Blademaster: needs Flurry and Iron wall"),
)

private val TalentLinks = listOf(
    SkillEdge("root", "edge"),
    SkillEdge("root", "guard"),
    SkillEdge("edge", "flurry"),
    SkillEdge("guard", "wall"),
    SkillEdge("flurry", "master"),
    SkillEdge("wall", "master"),
)

@Composable
private fun SkillsCard() = Card("Skill tree", "Hold a node to buy it — mouse, finger, Enter or a pad's A. Drag and pinch to look round.") {
    val bought = remember { mutableStateMapOf<String, Int>() }
    var points by remember { mutableIntStateOf(5) }
    val nodes = Talents.map { talent ->
        SkillNode(
            id = talent.id,
            x = talent.x,
            y = talent.y,
            ranks = talent.ranks,
            rank = bought[talent.id] ?: 0,
            label = talent.label,
            tooltip = talent.blurb,
            enabled = points > 0,
        )
    }
    val camera = dev.wildware.composegl.ui.widget.rememberPanZoomState(
        zoom = 0.6f,
        minZoom = 0.3f,
        maxZoom = 1.6f,
        bounds = remember { skillTreeBounds(Talents.map { SkillNode(it.id, it.x, it.y) }, margin = 80f) },
    )
    SkillTree(
        nodes = nodes,
        edges = TalentLinks,
        modifier = Modifier.fillMaxWidth().height(210f).testTag("skills"),
        state = camera,
        nodeSize = 40f,
        onActivate = { node ->
            if (points > 0) {
                points--
                bought[node.id as String] = (bought[node.id] ?: 0) + 1
            }
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10f)) {
        Text("$points points left", Modifier.testTag("points"), style = "label.dim")
        Button("Refund", { bought.clear(); points = 5 }, style = "button.quiet")
    }
}

private class Quest(val name: String, val steps: List<QuestStep>)

private class QuestStep(val text: String, val done: Boolean = false, val progress: ObjectiveProgress? = null)

@Composable
private fun ObjectivesCard() = Card("Objective tracker", "Collect the shards. Finish a step and it ticks off; finish the quest and the next one slides in with a toast.") {
    var shards by remember { mutableIntStateOf(0) }
    var told by remember { mutableStateOf(false) }
    val needed = 3
    val notices = rememberNotifications(capacity = 2)
    val quests = buildList {
        add(
            Quest(
                "THE BROKEN LENS",
                listOf(
                    QuestStep("Collect shards", done = shards >= needed, progress = ObjectiveProgress(shards.coerceAtMost(needed), needed)),
                    QuestStep("Speak to Edda", done = told),
                ),
            ),
        )
        if (shards >= needed) add(Quest("THE NORTH ROAD", listOf(QuestStep("Reach the watchtower"))))
    }
    ObjectiveTracker(
        quests = quests,
        modifier = Modifier.fillMaxWidth().testTag("objectives"),
        keyOf = { it.name },
        maxVisible = 2,
        notify = notices,
        width = cardInside(),
    ) { quest ->
        title(quest.name)
        quest.steps.forEach { step(it.text, done = it.done, progress = it.progress) }
    }
    FlowRow(horizontalSpacing = 8f, verticalSpacing = 8f) {
        Button("Find a shard", { shards++ }, Modifier.testTag("shard"), style = "button.primary")
        Button("Speak to Edda", { told = true }, style = "button.quiet")
        Button("Start over", { shards = 0; told = false }, style = "button.quiet")
    }
    Notifications(notices, Modifier.fillMaxWidth(), width = cardInside())
}

private val Party = ChatChannel("party", "Party", prefix = "/p")

private val Guild = ChatChannel("guild", "Guild", prefix = "/g", style = "chat.squad")

private val Channels = listOf(ChatChannel.Say, Party, Guild)

private val Chatter = listOf(
    ChatMessage("anyone seen the lens?", from = "Brann", channel = Party),
    ChatMessage("north road, past the well", from = "Edda", channel = Party),
    ChatMessage("raid at nine tonight", from = "Oren", channel = Guild),
)

@Composable
private fun ChatCard() = Card("Chat box", "Open it, pick a channel tab, type and press Enter. Click a name for a menu. Lines fade when the box is shut.") {
    val state = LocalShowcase.current
    val chat = rememberChatState(idleLines = 4, idleMillis = 6_000, clock = Clock.Ui)
    val clocks = LocalClocks.current
    // The party talking among itself, so there is something to read. Held while motion is reduced,
    // so a still screen stays still.
    val chatty = !state.reduceMotion
    LaunchedEffect(chat, chatty) {
        if (chat.messages.isEmpty()) chat.system("Welcome to the tavern.")
        var at = 0
        while (chatty) {
            clocks.wait(Clock.Ui, 4_000)
            chat.receive(Chatter[at % Chatter.size])
            at++
        }
    }
    // Tagged out here: the box puts its own tag on itself.
    Column(Modifier.fillMaxWidth().testTag("chat"), verticalArrangement = Arrangement.spacedBy(8f)) {
        ChatBox(
            state = chat,
            modifier = Modifier.fillMaxWidth(),
            channels = Channels,
            onSend = { channel, text -> chat.receive(ChatMessage(text, from = "You", channel = channel)) },
            // Enter belongs to the rest of the page here; the button opens the box instead.
            openKey = null,
            openButton = null,
            width = cardInside(),
            historyHeight = 120f,
            maxLength = 120,
            placeholder = "Say something",
            nameMenu = { message ->
                Item("Whisper") { chat.open(Party) }
                Item("Mute ${message.from}") { chat.system("${message.from} muted") }
            },
        )
        Button(if (chat.isOpen) "Close chat" else "Open chat", { if (chat.isOpen) chat.close() else chat.open() }, Modifier.testTag("open-chat"), style = "button.quiet")
    }
}
