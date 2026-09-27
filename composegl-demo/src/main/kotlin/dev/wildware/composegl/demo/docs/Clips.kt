package dev.wildware.composegl.demo.docs

import androidx.compose.runtime.CompositionLocalProvider
import dev.wildware.composegl.lwjgl3.GlCanvas
import dev.wildware.composegl.lwjgl3.GlfwWindow
import dev.wildware.composegl.lwjgl3.StbFonts
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.skin.ProvideSkin
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.text.FontProvider
import dev.wildware.composegl.ui.widget.LocalFonts
import java.io.File
import org.lwjgl.opengl.GL11

/**
 * The moving pictures, regenerated.
 *
 * The still ones are [main] in `Main.kt`; this is the same machinery with the shutter left open.
 * Frames land in `build/clips/<name>`, and if `ffmpeg` is on the path they are muxed into a GIF in
 * `docs/wiki/images` and an MP4 beside the frames. Needs a display, like every other picture:
 * `xvfb-run -a ./gradlew :composegl-demo:docClips`.
 *
 * `COMPOSEGL_DOC_ONLY=<text>` takes only the clips whose name holds that text.
 */
fun main() {
    val images = File(System.getenv("COMPOSEGL_DOC_SHOTS") ?: "docs/wiki/images")
    val frames = File(System.getenv("COMPOSEGL_DOC_CLIPS") ?: "composegl-demo/build/clips")
    images.mkdirs()
    frames.mkdirs()

    val window = GlfwWindow("composegl doc clips", ClipWindow, ClipWindow, visible = false, vsync = false)
    val fonts = StbFonts(pageSize = 1024)
    val typeface = resource("fonts/DejaVuSans.ttf")
    bakedSizes(listOf(Skin.Default)).forEach { (family, sizes) -> fonts.register(family, typeface, sizes) }
    val canvas = GlCanvas(fonts)

    val only = System.getenv("COMPOSEGL_DOC_ONLY")
    try {
        docClips().filter { only == null || only in it.name }.forEach { clip ->
            check(clip.width <= ClipWindow && clip.height <= ClipWindow) {
                "${clip.name} is ${clip.width}x${clip.height}, which needs COMPOSEGL_DOC_WINDOW"
            }
            val folder = File(frames, clip.name)
            folder.deleteRecursively()
            folder.mkdirs()
            val taken = take(clip, canvas, fonts, folder)
            println("wrote $taken frames to ${folder.path}")
            mux(clip, folder, images)
        }
    } finally {
        canvas.close()
        fonts.close()
        window.close()
    }
}

/** Every moving picture. */
private fun docClips(): List<DocClip> = listOf(
    // A hand coming in from off the panel, pressing Resume, holding it, letting go, and going on
    // to press Map: the press itself is the picture, and a still of it is only half of one.
    DocClip("relief-press", 560, 400, hand = press()) { ReliefPanel() },
)

/**
 * The hand: in from the corner, down on Resume, held, let go, across to Map, down, and away.
 *
 * The holds are what make it readable. A press that lasts two frames at thirty a second is a
 * flicker, and the thing worth seeing — every edge on the button turning over at once — needs to
 * be on the screen long enough to be looked at.
 */
private fun press(): List<Hand> {
    val resume = Offset(120f, 173f)
    val map = Offset(275f, 173f)
    return listOf(
        Hand.Wait(8),
        Hand.Move(Offset(36f, 340f)),
        Hand.Move(Offset(70f, 280f)),
        Hand.Move(Offset(100f, 215f)),
        Hand.Move(resume),
        Hand.Wait(10),
        Hand.Press(resume),
        Hand.Wait(16),
        Hand.Release(resume),
        Hand.Wait(14),
        Hand.Move(Offset(200f, 173f)),
        Hand.Move(map),
        Hand.Wait(8),
        Hand.Press(map),
        Hand.Wait(14),
        Hand.Release(map),
        Hand.Wait(16),
    )
}

/** How big the window every clip is taken in is; the same lever the stills have. */
private val ClipWindow = System.getenv("COMPOSEGL_DOC_WINDOW")?.toIntOrNull() ?: 640

/** Frames after the hand has finished, so the last thing it did has settled before the clip ends. */
private const val ClipSettle = 4

/**
 * One clip, a frame at a time.
 *
 * The hand's steps are fed in after layout, exactly as a still's are, so a press lands on whatever
 * is really under the pointer. A step therefore shows on the frame after the one it was made on,
 * which is what a real frame of input does too.
 */
private fun take(clip: DocClip, canvas: GlCanvas, fonts: FontProvider, folder: File): Int {
    val size = Size(clip.width.toFloat(), clip.height.toFloat())
    val viewport = Viewport(design = size, physical = size, policy = ScalePolicy.Fit)
    val host = UiHost()
    val focus = FocusManager(host.root)
    val mouse = PointerRouter(host.root, focus)

    host.setContent {
        CompositionLocalProvider(LocalFonts provides fonts) {
            ProvideSkin(Skin.Default) { clip.content() }
        }
    }

    val steps: List<() -> Unit> = clip.hand.flatMap { step ->
        when (step) {
            is Hand.Move -> listOf({ mouse.onPointer(PointerEvent.Move(PointerId.Mouse, step.to)) })
            is Hand.Press -> listOf({ mouse.onPointer(PointerEvent.Press(PointerId.Mouse, step.at)) })
            is Hand.Release -> listOf({ mouse.onPointer(PointerEvent.Release(PointerId.Mouse, step.at)) })
            is Hand.Wait -> List(step.frames) { {} }
        }
    }
    var done = 0

    val renderer = UiRenderer(host, canvas, FrameBudget())
    renderer.onLaidOut = { if (done < steps.size) steps[done++]() }

    val nanos = 1_000_000_000L / clip.fps
    try {
        val count = steps.size + ClipSettle
        for (frame in 0 until count) {
            GL11.glClearColor(0f, 0f, 0f, 1f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            renderer.render(viewport, frame * nanos)
            save(File(folder, "frame-%04d.png".format(frame)), read(clip.width, clip.height))
        }
        return count
    } finally {
        host.dispose()
    }
}

/**
 * The frames as a GIF and an MP4, if there is an `ffmpeg` to do it with.
 *
 * Not a build dependency: without it the frames are still there and the command to run is printed,
 * which is the same bargain the doc shots make with a display.
 */
private fun mux(clip: DocClip, folder: File, images: File) {
    val pattern = File(folder, "frame-%04d.png").path
    val palette = File(folder, "palette.png").path
    val gif = File(images, "${clip.name}.gif").path
    val mp4 = File(folder.parentFile, "${clip.name}.mp4").path
    val rate = clip.fps.toString()

    val runs = listOf(
        listOf("ffmpeg", "-y", "-framerate", rate, "-i", pattern, "-vf", "palettegen=stats_mode=diff", palette),
        listOf(
            "ffmpeg", "-y", "-framerate", rate, "-i", pattern, "-i", palette,
            "-lavfi", "paletteuse=dither=bayer:bayer_scale=3", gif,
        ),
        listOf(
            "ffmpeg", "-y", "-framerate", rate, "-i", pattern,
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18",
            "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2", mp4,
        ),
    )

    for (run in runs) {
        val built = runCatching {
            ProcessBuilder(run).redirectErrorStream(true).start().let { process ->
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor() to output
            }
        }.getOrElse {
            println("no ffmpeg: the frames are in ${folder.path}, run `${run.joinToString(" ")}` yourself")
            return
        }
        check(built.first == 0) { "ffmpeg failed: ${built.second.takeLast(400)}" }
    }
    println("wrote $gif and $mp4")
}
