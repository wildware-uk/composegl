package dev.wildware.composegl.showcase.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.game.SkillEdge
import dev.wildware.composegl.game.SkillNode
import dev.wildware.composegl.game.SkillTree
import dev.wildware.composegl.game.skillTreeBounds
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.rememberPanZoomState

/** One upgrade on the ship's board: where it sits, how many ranks it has, and what it does. */
private class Upgrade(
    val id: String,
    val label: String,
    val x: Float,
    val y: Float,
    val ranks: Int = 1,
    val blurb: String,
)

private val Upgrades = listOf(
    Upgrade("core", "R", 0f, 0f, blurb = "Reactor — everything starts here"),
    Upgrade("guns", "G", 130f, -80f, ranks = 3, blurb = "Autocannon — more rounds a second"),
    Upgrade("shield", "S", 130f, 80f, ranks = 2, blurb = "Shield — soaks a hit and recharges"),
    Upgrade("burst", "B", 270f, -80f, blurb = "Burst fire — three rounds a trigger"),
    Upgrade("cloak", "C", 270f, 80f, blurb = "Cloak — nobody sees you coming"),
    Upgrade("drive", "D", 410f, 0f, blurb = "Overdrive — needs burst fire and a cloak"),
)

private val Links = listOf(
    SkillEdge("core", "guns"),
    SkillEdge("core", "shield"),
    SkillEdge("guns", "burst"),
    SkillEdge("shield", "cloak"),
    SkillEdge("burst", "drive"),
    SkillEdge("cloak", "drive"),
)

/**
 * The ship's upgrade board: a [SkillTree] on a plane, with points to spend.
 *
 * Everything the widget is for is on show here — the lines light up as a branch opens, holding a
 * node buys it, the pad walks the branches rather than the geometry, and the camera follows
 * whatever focus lands on. The game's part is the two lines at the bottom: take a point off, put a
 * rank on.
 */
@Composable
fun SkillBoard() = SkillBoardBody(
    Modifier.align(Alignment.BottomEnd).padding(right = 28f, bottom = 268f).size(360f, 222f),
)

/**
 * The board itself, wherever it is put: the heading that counts the points down, and the tree.
 *
 * Written apart from [SkillBoard] so the composegl-game section can show the same board inline,
 * in a column that is not the corner of the screen.
 */
@Composable
internal fun SkillBoardBody(modifier: Modifier = Modifier) {
    val bought = remember { mutableStateMapOf<String, Int>() }
    var points by remember { mutableStateOf(5) }

    val nodes = Upgrades.map { upgrade ->
        SkillNode(
            id = upgrade.id,
            x = upgrade.x,
            y = upgrade.y,
            ranks = upgrade.ranks,
            rank = bought[upgrade.id] ?: 0,
            label = upgrade.label,
            tooltip = upgrade.blurb,
            // Nothing opens once the points are gone, which is the one call the game makes.
            enabled = points > 0,
        )
    }

    val camera = rememberPanZoomState(
        zoom = 0.6f,
        minZoom = 0.4f,
        maxZoom = 1.6f,
        bounds = remember { skillTreeBounds(Upgrades.map { SkillNode(it.id, it.x, it.y) }, margin = 90f) },
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4f)) {
        Text("Upgrades — hold a node to buy it · $points left", style = "label.dim")
        SkillTree(
            nodes = nodes,
            edges = Links,
            modifier = Modifier.fillMaxWidth().weight(1f),
            state = camera,
            nodeSize = 44f,
            onActivate = { node ->
                if (points > 0) {
                    points--
                    bought[node.id as String] = (bought[node.id] ?: 0) + 1
                }
            },
        )
    }
}
