package dev.wildware.composegl.kool

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Typeface
import android.opengl.GLES30
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import de.fabmax.kool.KoolConfigAndroid
import de.fabmax.kool.KoolContext
import de.fabmax.kool.createKoolContext
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.modules.ksl.KslUnlitShader
import de.fabmax.kool.pipeline.ClearColor
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.pipeline.ClearDepthFill
import de.fabmax.kool.pipeline.ClearDepthLoad
import de.fabmax.kool.platform.KoolContextAndroid
import de.fabmax.kool.scene.OrthographicCamera
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.scene.addColorMesh
import de.fabmax.kool.util.Color
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * The activity Kool draws in: Kool's own `GLSurfaceView`, exactly [KoolDevice.size] pixels each way, in
 * the top-left corner, so the tests measure the framebuffer the way the desktop ones do.
 */
class KoolTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = KoolDevice.context(applicationContext)
        val frame = FrameLayout(this)
        frame.addView(ctx.surfaceView, FrameLayout.LayoutParams(KoolDevice.size, KoolDevice.size, Gravity.TOP or Gravity.START))
        setContentView(frame)
    }

    /**
     * Stays open for the whole run. The test runner finishes every activity after each test, and a
     * finished activity takes Kool's view off the window, which destroys the OpenGL ES context Kool
     * cannot rebuild its GPU objects on — there is one Kool context a process. The process ends when
     * the run does, and the activity with it.
     */
    override fun finish() = Unit

    override fun onPause() {
        super.onPause()
        KoolDevice.ctx.onPause()
    }

    override fun onResume() {
        super.onResume()
        KoolDevice.ctx.onResume()
    }
}

/**
 * One real Kool application on the device, shared by every test that needs pixels — the Android twin of
 * the desktop tests' `KoolApp`.
 *
 * Kool allows one context a process, so it is made once and its activity is kept open for the whole
 * run. Tests post work into its frames, where a [ComposeGlScene] draws: on Kool's `GLSurfaceView` render
 * thread, while Kool renders a scene of its own, with Kool's OpenGL ES context current.
 *
 * Scenes, drawn in this order every frame: the first, which clears to opaque black and runs the test's
 * work; `kool`, where Kool draws a magenta square of its own; whatever a test adds with [addScene]; and
 * the last, which clears nothing and runs the test's second half.
 */
object KoolDevice {

    const val size = 400

    /** Where Kool draws its magenta square, in the framebuffer's pixels from the top-left: x, y, width, height. */
    private val KoolSquare = intArrayOf(300, 20, 80, 80)

    private val firstWork = ConcurrentLinkedQueue<(KoolContext) -> Unit>()
    private val lastWork = ConcurrentLinkedQueue<(KoolContext) -> Unit>()

    lateinit var ctx: KoolContextAndroid
        private set

    private lateinit var kool: Scene

    private lateinit var last: Scene

    @Volatile
    private var scenario: ActivityScenario<KoolTestActivity>? = null

    internal fun context(app: android.content.Context): KoolContextAndroid {
        if (::ctx.isInitialized) return ctx
        ctx = createKoolContext(KoolConfigAndroid(app))
        ctx.addScene(workScene("first", ClearColorFill(Color.BLACK), firstWork).apply { clearDepth = ClearDepthFill })
        kool = Scene("kool").apply {
            clearColor = ClearColorLoad
            clearDepth = ClearDepthLoad
            camera = OrthographicCamera().apply {
                // One unit a pixel, from the framebuffer's bottom-left corner.
                isClipToViewport = true
                setupCamera(position = Vec3f(0f, 0f, 10f), lookAt = Vec3f.ZERO)
                clipNear = 1f
                clipFar = 100f
            }
            addColorMesh("kool-square") {
                generate {
                    rect {
                        isCenteredOrigin = true
                        origin.set(KoolSquare[0] + KoolSquare[2] / 2f, KoolDevice.size - (KoolSquare[1] + KoolSquare[3] / 2f), 0f)
                        size.set(KoolSquare[2].toFloat(), KoolSquare[3].toFloat())
                    }
                }
                shader = KslUnlitShader { color { constColor(Color.MAGENTA) } }
            }
        }
        ctx.addScene(kool)
        last = workScene("last", ClearColorLoad, lastWork)
        ctx.addScene(last)
        return ctx
    }

    private fun workScene(name: String, clear: ClearColor, work: ConcurrentLinkedQueue<(KoolContext) -> Unit>) =
        Scene(name).apply {
            clearColor = clear
            clearDepth = ClearDepthLoad
            mainRenderPass.defaultView.onSetupView {
                repeat(work.size) { (work.poll() ?: return@repeat).invoke(ctx) }
            }
        }

    /** Opens Kool's activity if it is not open yet: Kool must be running before a Kool scene can be made. */
    @Synchronized
    fun start() {
        if (scenario == null) scenario = ActivityScenario.launch(KoolTestActivity::class.java)
    }

    /** Adds [scene] to the frame, after Kool's square and before the last work scene, and waits for it. */
    fun addScene(scene: Scene) = render { ctx ->
        ctx.removeScene(last)
        ctx.addScene(scene)
        ctx.addScene(last)
    }

    /** Takes [scene] out of the frame again, and waits for that. */
    fun removeScene(scene: Scene) = render { ctx -> ctx.removeScene(scene) }

    /**
     * Runs [during] in the next frame's first scene and [after] in the same frame's last scene, on Kool's
     * render thread, and waits for both.
     */
    fun <T, R> frame(during: (KoolContext) -> T, after: (KoolContext, T) -> R): R {
        start()
        val answer = ArrayBlockingQueue<Result<R>>(1)
        var first: Result<T>? = null
        firstWork += { ctx -> first = runCatching { during(ctx) } }
        fun second(ctx: KoolContext) {
            val made = first ?: return run { lastWork += ::second }
            answer.put(made.mapCatching { after(ctx, it) })
        }
        lastWork += ::second
        val result = answer.poll(60, TimeUnit.SECONDS) ?: throw IllegalStateException("Kool stopped drawing frames")
        return result.getOrThrow()
    }

    fun <T> render(block: (KoolContext) -> T): T = frame(block) { _, made -> made }

    /** A whole frame, read back at its end as `0xRRGGBB`, top row first. */
    fun capture(): IntArray = frame({}) { _, _ -> readPixels() }

    /** The bottom-left [width] by [height] of the framebuffer, as `0xRRGGBB`, y down from the top. Only inside [frame]. */
    fun readPixels(width: Int = size, height: Int = size): IntArray {
        val bytes = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
        return IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val at = ((height - 1 - y) * width + x) * 4
            (bytes.get(at).toInt() and 0xFF shl 16) or (bytes.get(at + 1).toInt() and 0xFF shl 8) or (bytes.get(at + 2).toInt() and 0xFF)
        }
    }

    /** A finger on Kool's view at [x], [y] in its pixels, delivered on the main thread as the system delivers one. */
    fun touch(action: Int, x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { ctx.surfaceView.dispatchTouchEvent(event) }
        event.recycle()
    }

    /** DejaVu Sans, the font the raw OpenGL frontend's goldens were drawn with. */
    fun dejaVu(): Typeface {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(app.cacheDir, "DejaVuSans.ttf")
        if (!file.exists()) {
            checkNotNull(KoolDevice::class.java.getResourceAsStream("/fonts/DejaVuSans.ttf")).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        return Typeface.createFromFile(file)
    }

    /** Leaves [pixels] as a PNG in the app's files, for a person to pull off the device and look at. */
    fun save(name: String, width: Int, height: Int, pixels: IntArray) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(app.getExternalFilesDir(null), "kool-shots").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(IntArray(pixels.size) { pixels[it] or 0xFF000000.toInt() }, width, height, Bitmap.Config.ARGB_8888)
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
