package dev.wildware.composegl.korge.demo

import dev.wildware.composegl.korge.KorgeCanvas
import dev.wildware.composegl.korge.KorgeRenderTarget
import dev.wildware.composegl.korge.KorgeRenderTargetView
import dev.wildware.composegl.ui.world.WorldPanel
import korlibs.korge.render.RenderContext
import korlibs.korge.view.Container
import korlibs.korge.view.View
import korlibs.korge.view.addTo

/**
 * An interface on a sprite: a [WorldPanel] drawn into a [KorgeRenderTarget], shown by a
 * [KorgeRenderTargetView] that moves, scales and fades with the container it is in.
 *
 * Three pieces. The panel is an ordinary composition. The painter is an invisible view put just before
 * the picture, so each frame it gets KorGE's render context first and redraws the texture — only when
 * the composition changed. The picture is a sprite like any other.
 */
class InWorldPanel(
    parent: Container,
    private val canvas: KorgeCanvas,
    width: Int,
    height: Int,
    private val clock: () -> Long = System::nanoTime,
) : AutoCloseable {

    val panel = WorldPanel(width.toFloat(), height.toFloat())

    val target = KorgeRenderTarget(width, height)

    private val painter = object : View() {
        override fun renderInternal(ctx: RenderContext) {
            if (panel.needsRedraw(clock())) target.draw(canvas, ctx) { panel.draw(canvas) }
        }
    }.addTo(parent)

    val view = KorgeRenderTargetView(target).addTo(parent)

    /** How many times the texture was actually redrawn: far fewer than the frames drawn. */
    val draws: Long get() = panel.draws

    override fun close() {
        painter.removeFromParent()
        view.removeFromParent()
        panel.close()
        target.close()
    }
}
