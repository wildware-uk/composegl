package dev.wildware.composegl.kool

import de.fabmax.kool.KoolContext
import de.fabmax.kool.KoolSystem
import dev.wildware.composegl.render.AtlasFonts
import dev.wildware.composegl.render.FrameTarget
import dev.wildware.composegl.render.RenderCanvas
import dev.wildware.composegl.render.gl.GlDevice
import dev.wildware.composegl.render.gl.HostState
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.SceneSurface
import dev.wildware.composegl.ui.graphics.SceneTarget
import dev.wildware.composegl.ui.layout.Viewport

/**
 * What `raw` hands a game on the Kool frontend: Kool's context, and where to draw.
 *
 * Inside a frame that is Kool's framebuffer; inside a `SceneView`'s scene it is the scene's picture,
 * which stays bound for as long as the block runs. [projection] is already in the interface's
 * coordinates with y measured up, and [viewport] is the one it was set up with. Kool's own GL state is
 * back in force while the block runs, so whatever Kool remembers about it is true.
 */
class KoolFrame(val ctx: KoolContext, val projection: FloatArray, val viewport: Viewport)

/**
 * The shared renderer, inside a Kool frame.
 *
 * All the drawing is [RenderCanvas]'s. This class says which GL to call ([KoolGl], on Kool's desktop
 * OpenGL or Android OpenGL ES context), which textures it can draw ([KoolTexture]) and what a game gets from `raw` ([KoolFrame]) —
 * and it keeps Kool's renderer honest. Kool remembers the GL state it last set and believes it: the
 * program it bound, whether depth testing and writing are on, which faces it culls. So the device
 * hands the context back with [HostState.Restore]: it saves what Kool left when a frame begins, and
 * puts every value back when the frame ends and around `raw`.
 *
 * A frame draws into whatever framebuffer Kool has bound, which is where [ComposeGlScene] draws: while
 * Kool renders one of its scenes. Kool's OpenGL context is current only on Kool's render thread, so
 * that is the only thread a frame or a scene can be drawn on.
 *
 * Merely having a canvas needs no OpenGL, so a game object that owns one can be built anywhere.
 *
 * @param fonts where text is measured, and where solid colour is sampled from.
 */
class KoolCanvas(fonts: AtlasFonts? = null) : RenderCanvas(GlDevice(KoolGl, HostState.Restore), fonts, KoolTexture.Resolver) {

    /**
     * Yes: Kool's OpenGL backend is OpenGL 3.3 core or later on the desktop and OpenGL ES 3 on Android,
     * both of which always have framebuffers. Answered
     * without asking the driver, so it can be asked on any thread.
     */
    override val drawsScenes: Boolean get() = true

    /**
     * The driver's biggest texture, asked on Kool's context. The scene pass asks before anything has
     * been drawn in the frame, which on Kool's render thread is fine: the context is current for the
     * whole of Kool's render. Anywhere else there is no context to ask, and there is nothing to cut a
     * scene down to yet — [scene] is only ever rendered on that thread, where the real limit applies.
     */
    override val maxSceneSize: Int get() = if (KoolGl.current) super.maxSceneSize else Int.MAX_VALUE

    override fun handOver(projection: FloatArray, viewport: Viewport): Any =
        KoolFrame(KoolSystem.requireContext(), projection, viewport)

    /**
     * Runs the block with Kool's state in force, then puts Kool's state back before the device takes
     * the context again.
     *
     * Kool can only draw through its own passes, so whatever a block draws with is OpenGL behind Kool's
     * back — and Kool's memory of what it set cannot be cleared from outside. So what a block leaves
     * does not become what Kool is believed to have set: a culled face, a closed colour mask, a depth
     * comparison or a program of the block's own would still be in force the next time Kool draws, and
     * Kool would skip setting it. The device puts back what it saved; [KoolState] puts back the rest of
     * what Kool remembers. Inside a scene the picture, its viewport and its scissor are the device's
     * again straight after.
     *
     * While the block runs it sees Kool's conventions, not the toolkit's: on a context with clip control
     * Kool draws with reversed depth, so the depth comparison in force is a reversed one. A block that tests depth sets
     * the comparison it wants.
     */
    override fun lend(lent: Any, projection: FloatArray, block: (Any) -> Unit) {
        // Outside a frame or a scene the device holds nothing of Kool's to put back.
        val kool = if (holdsKoolState) KoolState.save() else null
        try {
            block(lent)
        } finally {
            if (kool != null) {
                device.suspend()
                kool.restore()
            }
        }
    }

    /** Whether the device has saved Kool's state: inside a frame, or inside a scene. */
    private var holdsKoolState = false

    override fun begin(viewport: Viewport, into: FrameTarget, clear: Colour?) {
        super.begin(viewport, into, clear)
        holdsKoolState = true
    }

    override fun end() {
        holdsKoolState = false
        super.end()
    }

    override fun scene(surface: SceneSurface?, width: Int, height: Int, draw: (SceneTarget) -> Unit): SceneSurface? {
        holdsKoolState = true
        try {
            return super.scene(surface, width, height, draw)
        } finally {
            holdsKoolState = false
        }
    }
}
