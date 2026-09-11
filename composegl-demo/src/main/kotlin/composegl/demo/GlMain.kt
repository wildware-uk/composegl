package composegl.demo

import composegl.lwjgl3.GlfwClipboard
import composegl.lwjgl3.GlCanvas
import composegl.lwjgl3.GlTexture
import composegl.lwjgl3.GlfwGamepadInput
import composegl.lwjgl3.GlfwKeyboardInput
import composegl.lwjgl3.GlfwPointerInput
import composegl.lwjgl3.GlfwWindow
import composegl.lwjgl3.StbFonts
import composegl.ui.draw.DrawPass
import composegl.ui.geometry.Size
import composegl.ui.graphics.ArtAtlas
import composegl.ui.host.UiHost
import composegl.ui.layout.MeasurePass
import composegl.ui.layout.ScalePolicy
import composegl.ui.layout.run
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL11

/**
 * The same example, on the backend that has never heard of LibGDX.
 *
 * Every line of the interface itself — `Screen`, the widgets, the skin — is shared with the other
 * one, unchanged, because none of it names an engine. What differs is this file: a window, a
 * canvas, a font registry and a pointer translator, all from `composegl-lwjgl3`.
 *
 * That is the whole argument for having a second backend. If the toolkit had quietly assumed
 * anything about LibGDX, this file could not exist without changing the interface it draws.
 *
 * Run it with `./gradlew :composegl-demo:runGl`. Set `COMPOSEGL_DEMO_SHOT` to a path to draw one
 * frame, save it and exit.
 */
@Suppress("LongMethod")
fun main() {
    val window = GlfwWindow("ComposeGL — raw OpenGL", 1280, 720)
    val fonts = StbFonts(pageSize = 1024)
    val typeface = resource("fonts/DejaVuSans.ttf")
    fonts.register("body", typeface, listOf(13, 16, 20))
    fonts.register("display", typeface, listOf(34))

    val art = GlTexture.decode(resource("ui/ui.png"))
    val canvas = GlCanvas(fonts)
    val state = DemoState()
    val host = UiHost()
    val skin = demoSkin(atlas(art), fonts)
    host.setContent { Screen(fonts, skin.skin, state, GlfwClipboard(window)) }

    var viewport = window.viewport(Design, ScalePolicy.Fit)
    val input = DemoInput(state, host.root)
    val pointerInput = GlfwPointerInput(
        sink = input,
        viewport = { viewport },
        pixelScale = { window.pixelScale },
    )
    // Not attached when a pad script is running: GLFW reports the real cursor as soon as the
    // window opens, and a picture meant to show what a pad does should not have a mouse in it.
    if (System.getenv("COMPOSEGL_DEMO_PAD") == null && System.getenv("COMPOSEGL_DEMO_KEYS") == null) {
        pointerInput.attachTo(window)
    }

    GlfwKeyboardInput(input).attachTo(window)

    // GLFW has no event for a pad, so this one is read once a frame rather than pushed.
    val padInput = GlfwGamepadInput(input)

    val shot: String? = System.getenv("COMPOSEGL_DEMO_SHOT")
    // A screenshot of a hover state is otherwise impossible to take: the pointer has to be
    // somewhere, and a script cannot move a real mouse. `x,y` hovers; `x,y,press` holds it down.
    // Re-applied every frame, because the real mouse is still there and still reporting.
    val scriptedPointer: String? = System.getenv("COMPOSEGL_DEMO_POINTER")
    // The same problem for a pad, which cannot be plugged in from a script either. Played once,
    // after the first layout, because focus moves by geometry and there is none before then.
    val scriptedPad: String? = System.getenv("COMPOSEGL_DEMO_PAD")
    // One keystroke per frame: a step often depends on what the last one put on screen.
    val scriptedKeys: List<String> = System.getenv("COMPOSEGL_DEMO_KEYS")?.let { input.keyScript(it) } ?: emptyList()
    // How long to let the demo run before the shot, for anything that is a movement rather than a
    // state: a bar's trail is only there for a moment after the value drops.
    val shotAt: Float = System.getenv("COMPOSEGL_DEMO_SHOT_AT")?.toFloatOrNull() ?: 0f
    var frames = 0
    var elapsed = 0f

    try {
        while (!window.shouldClose()) {
            elapsed = GLFW.glfwGetTime().toFloat()
            state.tick(elapsed)

            scriptedPointer?.let { input.pretendPointerIsAt(it) }
            padInput.poll()
            // One look at a timestamp. An artist saving the skin file is seen on the next frame.
            skin.reloadIfChanged()
            host.frame(System.nanoTime())

            viewport = window.viewport(Design, ScalePolicy.Fit)
            MeasurePass().run(host.root, viewport)
            input.frame(System.nanoTime() / 1_000_000)
            if (frames == 0) scriptedPad?.let { input.pretendPadDid(it) }
            if (frames < scriptedKeys.size) input.pretendKeyWas(scriptedKeys[frames])

            GL11.glClearColor(0.03f, 0.04f, 0.05f, 1f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            DrawPass(canvas).draw(host.root)
            canvas.end()

            window.present()

            frames++
            if (shot != null && frames >= scriptedKeys.size + 2 && elapsed >= shotAt) {
                save(shot, window.framebuffer, canvas.renderCalls)
                break
            }
        }
    } finally {
        host.dispose()
        canvas.close()
        art.close()
        fonts.close()
        window.close()
    }
}

private val Design = Size(1280f, 720f)

/**
 * The art, by name.
 *
 * Two regions of one picture, and nothing about what they mean: which of them is a panel, where its
 * slices are and how far in its contents sit are all in the skin file. The LibGDX example reads the
 * same names out of an `.atlas` file beside the picture, which is why the same skin file works on
 * both without knowing that either backend exists.
 */
private fun atlas(art: GlTexture) = ArtAtlas.of(
    mapOf(
        "panel" to art.region(0, 0, 48, 48),
        "ribbon" to art.region(52, 0, 24, 24),
        "icon/crest" to art.region(80, 0, 24, 24),
    ),
)

private fun resource(path: String): ByteArray =
    checkNotNull(object {}.javaClass.classLoader.getResourceAsStream(path)) { "no $path on the classpath" }
        .use { it.readBytes() }

/** One frame, written out as a PNG, the right way up. */
private fun save(path: String, size: Size, renderCalls: Int) {
    val width = size.width.toInt()
    val height = size.height.toInt()
    val bytes = org.lwjgl.BufferUtils.createByteBuffer(width * height * 4)
    GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes)

    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until height) {
        for (x in 0 until width) {
            // OpenGL hands back the bottom row first.
            val at = ((height - 1 - y) * width + x) * 4
            image.setRGB(
                x,
                y,
                (bytes.get(at).toInt() and 0xFF shl 16) or
                    (bytes.get(at + 1).toInt() and 0xFF shl 8) or
                    (bytes.get(at + 2).toInt() and 0xFF),
            )
        }
    }
    ImageIO.write(image, "png", File(path))
    println("wrote $path ($renderCalls draw calls)")
}
