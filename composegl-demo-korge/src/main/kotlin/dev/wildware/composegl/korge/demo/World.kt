package dev.wildware.composegl.korge.demo

import dev.wildware.composegl.ui.game.WorldAnchor
import korlibs.image.color.RGBA
import korlibs.korge.view.Container
import korlibs.korge.view.SolidRect
import korlibs.korge.view.View
import korlibs.korge.view.addTo
import korlibs.korge.view.solidRect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The game: ordinary KorGE views, moved by the game's own loop. The interface is drawn over them and
 * is told only numbers — health, score, and where each hit landed.
 *
 * Everything moves on [step]'s time rather than the wall clock, so a screenshot taken at a given
 * second always shows the same frame.
 */
class World(parent: Container, private val state: DemoState) {

    val root = Container().addTo(parent)

    private val random = Random(7)

    init {
        // A floor of tiles, so the scene reads as a place rather than a colour.
        for (row in 0 until 9) for (column in 0 until 16) {
            val light = (row + column) % 2 == 0
            root.solidRect(80.0, 80.0, if (light) RGBA(0x16, 0x1B, 0x26) else RGBA(0x12, 0x16, 0x1F)).also {
                it.x = column * 80.0
                it.y = row * 80.0
            }
        }
    }

    val player: SolidRect = root.solidRect(44.0, 44.0, RGBA(0x4C, 0xC2, 0xFF))

    /** Drones circling the player. Each hit on one puts a number over it. */
    val drones: List<SolidRect> = List(5) { index ->
        root.solidRect(34.0, 34.0, if (index == 0) RGBA(0xFF, 0x5A, 0x4E) else RGBA(0xE0, 0x7A, 0x3A))
    }

    /** Where the panel in the world hangs: a post the terminal is fixed to. */
    val post: Container = Container().addTo(root)

    private var time = 0.0
    private var sinceHit = 0.0

    fun centreOf(view: View) = WorldAnchor { it.set((view.x + view.width / 2).toFloat(), (view.y + view.height / 2).toFloat()) }

    fun step(seconds: Double) {
        time += seconds
        player.x = 640.0 - 22 + sin(time * 0.7) * 160
        player.y = 430.0 - 22 + cos(time * 0.5) * 50

        drones.forEachIndexed { index, drone ->
            val angle = time * (0.6 + index * 0.13) + index * 2 * PI / drones.size
            val radius = 170.0 + 40 * sin(time + index)
            drone.x = player.x + 22 - 17 + cos(angle) * radius
            drone.y = player.y + 22 - 17 + sin(angle) * radius * 0.6
        }

        post.x = 1010.0 + sin(time * 0.4) * 30
        post.y = 360.0 + sin(time * 1.3) * 8

        // Out and back over six seconds, so the logo's dissolve is always somewhere in the middle.
        state.dissolve = (((time * 0.33) % 2.0) - 1.0).let { if (it < 0) -it else it }.toFloat() * 0.7f

        if (state.screen == DemoScreen.Game) fight(seconds)
        else state.health = (state.health + seconds.toFloat() * 0.2f).coerceAtMost(1f)
    }

    /** Something is always being hit, because a HUD with nothing happening proves nothing. */
    private fun fight(seconds: Double) {
        sinceHit += seconds
        if (sinceHit < 0.4) return
        sinceHit = 0.0

        val drone = drones[random.nextInt(drones.size)]
        val critical = random.nextInt(5) == 0
        val amount = if (critical) random.nextInt(150, 300) else random.nextInt(12, 80)
        state.damage.show(if (critical) "$amount!" else "$amount", centreOf(drone), critical)
        state.score += amount

        // Now and then a drone hits back, and the health bar drains with its trail behind it.
        if (random.nextInt(3) == 0) {
            state.health = (state.health - random.nextFloat() * 0.18f).coerceAtLeast(0.1f)
            state.damage.show("-${random.nextInt(5, 30)}", centreOf(player))
        } else {
            state.health = (state.health + 0.02f).coerceAtMost(1f)
        }
        state.shield = 0.3f + 0.5f * ((sin(time * 0.8) + 1) / 2).toFloat()
    }
}
