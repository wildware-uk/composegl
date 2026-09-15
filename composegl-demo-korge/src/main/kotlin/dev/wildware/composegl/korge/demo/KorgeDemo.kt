package dev.wildware.composegl.korge.demo

import dev.wildware.composegl.korge.ComposeGlView
import dev.wildware.composegl.korge.KorgeBackend
import dev.wildware.composegl.korge.KorgeFonts
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.widget.ProvideFonts
import korlibs.graphics.readColor
import korlibs.image.bitmap.Bitmap32
import korlibs.image.format.PNG
import korlibs.korge.render.RenderContext
import korlibs.korge.view.Stage
import korlibs.korge.view.addUpdater
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * What the demo is told from outside: where to save a picture of itself and when, and what input to
 * pretend was given. Read from environment variables by [fromEnvironment], the same names the LibGDX
 * demo uses.
 */
data class DemoOptions(
    /** `COMPOSEGL_DEMO_SHOT`: draw, save the window to this PNG, and close. */
    val shot: String? = null,
    /** `COMPOSEGL_DEMO_SHOT_AT`: how many seconds to let it run before the shot. */
    val shotAt: Double = 0.0,
    /** `COMPOSEGL_DEMO_SCREEN`: which screen to start on — Menu, Game or Settings. */
    val screen: DemoScreen = DemoScreen.Menu,
    /** `COMPOSEGL_DEMO_KEYS`: a key script; see [DemoScript]. */
    val keys: String? = null,
    /** `COMPOSEGL_DEMO_PAD`: a pad script. */
    val pad: String? = null,
    /** `COMPOSEGL_DEMO_POINTER`: `x,y` or `x,y,press`, in window pixels. */
    val pointer: String? = null,
    /** Whether a shot closes the window. A test that boots the demo keeps it open. */
    val closeAfterShot: Boolean = true,
) {
    companion object {
        fun fromEnvironment(env: (String) -> String? = System::getenv) = DemoOptions(
            shot = env("COMPOSEGL_DEMO_SHOT"),
            shotAt = env("COMPOSEGL_DEMO_SHOT_AT")?.toDoubleOrNull() ?: 0.0,
            screen = env("COMPOSEGL_DEMO_SCREEN")?.let { name -> DemoScreen.entries.first { it.name.equals(name, ignoreCase = true) } } ?: DemoScreen.Menu,
            keys = env("COMPOSEGL_DEMO_KEYS"),
            pad = env("COMPOSEGL_DEMO_PAD"),
            pointer = env("COMPOSEGL_DEMO_POINTER"),
        )
    }
}

/**
 * The whole integration, in one place: fonts, a backend, the game's views, one line that puts the
 * interface on the stage, and a panel on a sprite.
 */
class KorgeDemo(val stage: Stage, val options: DemoOptions = DemoOptions()) : AutoCloseable {

    val state = DemoState().also { it.screen = options.screen }

    val fonts = KorgeFonts().apply {
        val dejaVu = resource("fonts/DejaVuSans.ttf")
        // Every size the toolkit's two skins ask for, under the name they ask for.
        registerTrueType("default", dejaVu, Sizes)
        // Where characters DejaVu does not have come from: small cuts of Noto Sans CJK, and emoji as PNGs.
        registerTrueType("cjk", resource("fonts/NotoSansSC-Subset.ttf"), Sizes)
        registerTrueType("korean", resource("fonts/NotoSansKR-Subset.ttf"), Sizes)
        registerEncodedPictures(
            "emoji",
            mapOf(
                "🎮" to resource("emoji/emoji_u1f3ae.png"),
                "👍" to resource("emoji/emoji_u1f44d.png"),
                "🔥" to resource("emoji/emoji_u1f525.png"),
            ),
            Sizes,
        )
        fallBackTo(listOf("cjk", "korean", "emoji"))
    }

    val backend = KorgeBackend(fonts, window = { stage.views.gameWindow })

    /** The game, under everything. */
    val world = World(stage, state)

    /** The terminal on the post, drawn into a texture and shown as a sprite. */
    val terminal = InWorldPanel(world.post, backend.canvas, TerminalWidth, TerminalHeight).also { panel ->
        // A world panel is a bare composition: nothing is provided until the game provides it.
        panel.panel.setContent { ProvideFonts(fonts) { TerminalScreen(state) } }
    }

    /**
     * The interface, over the game. `stage.composeGl(backend, size) { … }` is the one-line version; the
     * view is built by hand here only because the content wants the view's own frame budget.
     */
    val ui: ComposeGlView = ComposeGlView(backend, Size(1280f, 720f)).also { view ->
        view.onBack = state::back
        view.setContent { DemoUi(state, view.renderer.budget) }
        stage.addChild(view)
    }

    /** Work a test posts into the game's frames, run after KorGE has drawn and before it swaps. */
    val afterFrame = ConcurrentLinkedQueue<(RenderContext) -> Unit>()

    private val script = DemoScript(options.keys, options.pad, options.pointer)
    private var frames = 0
    private var seconds = 0.0
    private var shotTaken = false
    private val closeables = mutableListOf<AutoCloseable>()

    init {
        state.onQuit = { stage.views.gameWindow.close(0) }

        world.root.addUpdater { delta ->
            val step = (delta.inWholeMicroseconds / 1_000_000.0).coerceAtMost(0.1)
            seconds += step
            world.step(step)
        }

        closeables += stage.views.onAfterRender { ctx ->
            frames++
            options.shot?.let { path ->
                if (!shotTaken && script.done && frames > WarmUpFrames + 2 && seconds >= options.shotAt) {
                    shotTaken = true
                    save(ctx, File(path))
                    if (options.closeAfterShot) stage.views.gameWindow.close(0)
                }
            }
            // A few frames first: focus moves by where things are, and nothing is anywhere before layout.
            if (frames > WarmUpFrames) script.frame(stage.views)
            // Only the work already waiting: a test that posts again from inside its work gets the
            // next frame, not this one a second time.
            repeat(afterFrame.size) { (afterFrame.poll() ?: return@repeat).invoke(ctx) }
        }
    }

    /** The window as it is now, top row first. */
    fun window(ctx: RenderContext): Bitmap32 =
        Bitmap32(ctx.mainFrameBuffer.width, ctx.mainFrameBuffer.height, premultiplied = false).also {
            ctx.ag.readColor(ctx.mainFrameBuffer, it)
        }

    private fun save(ctx: RenderContext, file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        file.writeBytes(PNG.encode(window(ctx)))
        println("wrote ${file.path} (${backend.canvas.drawCalls} draw calls, terminal redrawn ${terminal.draws} times in $frames frames)")
    }

    override fun close() {
        closeables.forEach { it.close() }
        ui.close()
        terminal.close()
        backend.close()
    }

    companion object {
        val Sizes = listOf(12, 13, 14, 16, 18, 22, 26)
        const val WarmUpFrames = 3

        private fun resource(path: String): ByteArray =
            requireNotNull(KorgeDemo::class.java.classLoader.getResourceAsStream(path)) { "no $path on the classpath" }.use { it.readBytes() }
    }
}
