@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package spike

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.PointerEventResult
import androidx.compose.ui.scene.hasInvalidations
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.PlatformTextInputService
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color as GdxColor
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL30
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

private const val W = 1280
private const val H = 720

/** Minimal game-loop dispatcher: queue in, drained by the render thread. */
class GameLoopDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()
    override fun isDispatchNeeded(context: CoroutineContext) = true
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(queue) { queue.addLast(block) }
    }
    fun drain() {
        while (true) {
            val next = synchronized(queue) { queue.removeFirstOrNull() } ?: return
            next.run()
        }
    }
}

class SpikeTextInputService : PlatformTextInputService {
    var onEditCommand: ((List<EditCommand>) -> Unit)? = null
    var started = false
    override fun startInput(
        value: TextFieldValue,
        imeOptions: ImeOptions,
        onEditCommand: (List<EditCommand>) -> Unit,
        onImeActionPerformed: (ImeAction) -> Unit,
    ) {
        this.onEditCommand = onEditCommand
        started = true
    }
    override fun stopInput() { onEditCommand = null; started = false }
    override fun showSoftwareKeyboard() = Unit
    override fun hideSoftwareKeyboard() = Unit
    override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) = Unit
}

class SpikePlatformContext : PlatformContext.Empty() {
    val legacy = SpikeTextInputService()
    var sessionRequest: PlatformTextInputMethodRequest? = null
    /** Which path Compose actually used, for the findings table. */
    var pathUsed: String = "none"

    override val textInputService: PlatformTextInputService
        get() { if (pathUsed == "none") pathUsed = "legacy"; return legacy }

    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        sessionRequest = request
        pathUsed = "session"
        try {
            awaitCancellation()
        } finally {
            sessionRequest = null
        }
    }

    /** Commit a character through whichever path is live. Returns true if delivered. */
    fun sendChar(c: Char): Boolean {
        val cmd = listOf<EditCommand>(CommitTextCommand(c.toString(), 1))
        sessionRequest?.let { it.onEditCommand(cmd); return true }
        legacy.onEditCommand?.let { it(cmd); return true }
        return false
    }
}

/** anyChangeConsumed is internal in Compose; recover it with the public constructor. */
fun PointerEventResult.changeConsumed(): Boolean =
    listOf(false, true).any { m ->
        listOf(false, true).any { d ->
            this == PointerEventResult(
                anyMovementConsumed = m,
                anyChangeConsumed = true,
                dispatchedToAPointerInputModifier = d,
            )
        }
    }

class SpikeApp : ApplicationAdapter() {
    val results = mutableListOf<String>()
    private fun pass(id: String, note: String = "") = results.add("PASS $id${if (note.isEmpty()) "" else "  — $note"}")
    private fun fail(id: String, note: String) = results.add("FAIL $id  — $note")

    private lateinit var dispatcher: GameLoopDispatcher
    private lateinit var recomposer: FrameRecomposer
    private lateinit var platform: SpikePlatformContext
    private lateinit var scene: ComposeScene
    private var directContext: DirectContext? = null
    private var skiaSurface: Surface? = null
    private var backendTarget: BackendRenderTarget? = null
    private lateinit var fbo: FrameBuffer

    private lateinit var batch: SpriteBatch
    private lateinit var modelBatch: ModelBatch
    private lateinit var cubeModel: Model
    private lateinit var cube: ModelInstance
    private lateinit var cam: PerspectiveCamera

    // Compose state driven by the checks
    private var clicks = 0
    private var markerColor by mutableStateOf(Color.Red)
    private var textValue by mutableStateOf(TextFieldValue(""))
    private var showExtra by mutableStateOf(false)
    private val focusRequester = FocusRequester()
    private var composeThread: String? = null

    private var frame = 0
    private var origin = SurfaceOrigin.TOP_LEFT
    private var renderCount = 0L
    private var invalidateCalls = 0

    override fun create() {
        results.add("GL_VERSION = ${Gdx.gl.glGetString(GL20.GL_VERSION)}")
        results.add("GL_RENDERER = ${Gdx.gl.glGetString(GL20.GL_RENDERER)}")

        batch = SpriteBatch()
        modelBatch = ModelBatch()
        val mb = ModelBuilder()
        cubeModel = mb.createBox(
            2f, 2f, 2f,
            Material(ColorAttribute.createDiffuse(GdxColor.GREEN)),
            (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal).toLong(),
        )
        cube = ModelInstance(cubeModel)
        cam = PerspectiveCamera(67f, W.toFloat(), H.toFloat()).apply {
            position.set(3f, 3f, 3f); lookAt(0f, 0f, 0f); near = 0.1f; far = 100f; update()
        }

        // --- a / c: Skia on the engine's context, scene with our own plumbing ---
        try {
            directContext = DirectContext.makeGL()
            pass("a1", "DirectContext.makeGL() succeeded on the LibGDX/GLFW context")
        } catch (t: Throwable) {
            fail("a1", "DirectContext.makeGL() threw: $t")
            Gdx.app.exit(); return
        }

        dispatcher = GameLoopDispatcher()
        platform = SpikePlatformContext()
        try {
            recomposer = FrameRecomposer(dispatcher) { invalidateCalls++ }
            scene = CanvasLayersComposeScene(
                frameRecomposer = recomposer,
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                size = IntSize(W, H),
                platformContext = platform,
                invalidateLayout = { invalidateCalls++ },
                invalidateDraw = { invalidateCalls++ },
            )
            scene.setContent { Content() }
            pass("c", "CanvasLayersComposeScene built with our PlatformContext + dispatcher, no AWT window")
        } catch (t: Throwable) {
            fail("c", "scene construction threw: $t")
            Gdx.app.exit(); return
        }

        fbo = FrameBuffer(Pixmap.Format.RGBA8888, W, H, true, true)
        makeSkiaSurface(origin)

        // --- d: Compose KeyEvent without java.awt.event.KeyEvent ---
        try {
            val ev = KeyEvent(key = Key.A, type = KeyEventType.KeyDown, codePoint = 'a'.code)
            pass("d", "KeyEvent(key=..., type=...) built from androidx.compose.ui.input.key, no AWT event: $ev")
        } catch (t: Throwable) {
            fail("d", "KeyEvent construction threw: $t")
        }
    }

    private fun makeSkiaSurface(o: SurfaceOrigin) {
        skiaSurface?.close(); backendTarget?.close()
        backendTarget = BackendRenderTarget.makeGL(
            fbo.width, fbo.height, /*sampleCnt*/ 0, /*stencilBits*/ 8,
            fbo.framebufferHandle, org.jetbrains.skia.FramebufferFormat.GR_GL_RGBA8,
        )
        skiaSurface = Surface.makeFromBackendRenderTarget(
            directContext!!, backendTarget!!, o,
            SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
        ) ?: error("Surface.makeFromBackendRenderTarget returned null")
    }

    @Composable
    private fun Content() {
        composeThread = Thread.currentThread().name
        Box(Modifier.fillMaxSize()) {
            // marker: top-left 100x100 block, used to decide SurfaceOrigin
            Box(Modifier.size(100.dp).background(markerColor).align(Alignment.TopStart))
            Button(onClick = { clicks++ }, modifier = Modifier.align(Alignment.Center)) {
                Text("Click me")
            }
            TextField(
                value = textValue,
                onValueChange = { textValue = it },
                modifier = Modifier.align(Alignment.BottomStart).focusRequester(focusRequester),
            )
            if (showExtra) {
                Box(Modifier.size(50.dp).background(Color.Yellow).align(Alignment.TopEnd))
            }
        }
    }

    private fun update(nanos: Long) {
        dispatcher.drain()
        recomposer.performFrame(nanos)
        dispatcher.drain()
        scene.measureAndLayout()
    }

    private fun renderCompose() {
        val s = skiaSurface ?: return
        directContext!!.resetAll()
        s.canvas.clear(0x00000000)
        scene.draw(s.canvas.asComposeCanvas())
        directContext!!.flush()
        renderCount++
    }

    /** Reset GL to defaults so LibGDX is safe again. */
    private fun resetGlState() {
        val gl = Gdx.gl30!!
        gl.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
        gl.glViewport(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        gl.glUseProgram(0)
        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL30.GL_ARRAY_BUFFER, 0)
        gl.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, 0)
        gl.glActiveTexture(GL30.GL_TEXTURE0)
        gl.glBindTexture(GL30.GL_TEXTURE_2D, 0)
        gl.glDisable(GL30.GL_SCISSOR_TEST)
        gl.glDisable(GL30.GL_STENCIL_TEST)
        gl.glDepthMask(true)
        gl.glColorMask(true, true, true, true)
        gl.glDisable(GL30.GL_BLEND)
        gl.glBlendFunc(GL30.GL_SRC_ALPHA, GL30.GL_ONE_MINUS_SRC_ALPHA)
        gl.glPixelStorei(GL30.GL_UNPACK_ALIGNMENT, 4)
    }

    private fun blit() {
        val region = TextureRegion(fbo.colorBufferTexture)
        if (origin == SurfaceOrigin.BOTTOM_LEFT) region.flip(false, true)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA)
        batch.begin()
        batch.draw(region, 0f, 0f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        batch.end()
        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    /** Reads one pixel from the currently bound framebuffer. GL coords: y up from the bottom. */
    private fun readPixel(x: Int, y: Int): Int {
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        Gdx.gl.glReadPixels(x, y, 1, 1, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, buf)
        val r = buf.get(0).toInt() and 0xff
        val g = buf.get(1).toInt() and 0xff
        val b = buf.get(2).toInt() and 0xff
        val a = buf.get(3).toInt() and 0xff
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun hex(c: Int) = "#%08X".format(c)
    private fun isReddish(c: Int) = ((c shr 16) and 0xff) > 150 && ((c shr 8) and 0xff) < 100 && (c and 0xff) < 100

    override fun render() {
        frame++
        val nanos = System.nanoTime()
        Gdx.gl.glClearColor(0f, 0f, 0.2f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        cube.transform.rotate(0f, 1f, 0f, 1f)
        modelBatch.begin(cam); modelBatch.render(cube); modelBatch.end()
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)

        update(nanos)
        val needs = scene.hasInvalidations()
        if (needs) renderCompose()
        resetGlState()
        blit()

        step(needs)
        if (frame > 40) Gdx.app.exit()
    }

    private fun step(neededRedraw: Boolean) { when (frame) {
        3 -> {
            // --- a2: the FBO actually holds Compose pixels.
            // With SurfaceOrigin.TOP_LEFT, Compose row 0 lands in GL row 0 of the FBO.
            fbo.begin()
            val composeTopLeft = readPixel(5, 5)
            val composeBottomLeft = readPixel(5, H - 5)
            fbo.end()
            if (isReddish(composeTopLeft)) {
                pass("a2", "Compose drew into the FBO: top-left=${hex(composeTopLeft)} (marker), bottom-left=${hex(composeBottomLeft)} (text field)")
            } else {
                fail("a2", "FBO Compose top-left is ${hex(composeTopLeft)}; expected the red marker")
            }
        }
        4 -> {
            // --- b: which SurfaceOrigin puts the Compose top-left at the screen top-left
            val screenTopLeft = readPixel(5, Gdx.graphics.backBufferHeight - 5)
            if (isReddish(screenTopLeft)) {
                pass("b", "SurfaceOrigin.$origin + plain TextureRegion(fbo.colorBufferTexture), no flip, is upright (screen top-left = ${hex(screenTopLeft)})")
            } else {
                fail("b", "SurfaceOrigin.$origin gives ${hex(screenTopLeft)} at screen top-left; blit is flipped")
            }
            renderCount = 0
        }
        // --- f1: static, untouched content must not redraw. Runs before any input.
        15 -> {
            if (renderCount == 0L) pass("f1", "static content: 11 frames after first render, 0 Compose renders")
            else fail("f1", "static content rendered $renderCount times over 11 frames")
        }
        16 -> { showExtra = true; renderCount = 0 }
        18 -> {
            if (renderCount > 0) pass("f2", "state change triggered $renderCount render(s)")
            else fail("f2", "state change did not set hasInvalidations()")
        }
        20 -> {
            // --- e: sendPointerEvent consumption is readable on return
            val before = clicks
            val cx = W / 2f; val cy = H / 2f
            scene.sendPointerEvent(PointerEventType.Move, Offset(cx, cy))
            val down = scene.sendPointerEvent(PointerEventType.Press, Offset(cx, cy), button = PointerButton.Primary)
            val up = scene.sendPointerEvent(PointerEventType.Release, Offset(cx, cy), button = PointerButton.Primary)
            val clicked = clicks > before
            if (down.changeConsumed() && up.changeConsumed() && clicked) {
                pass("e", "PointerEventResult reports consumption synchronously on return; onClick fired in the same call (clicks=$clicks)")
            } else {
                fail("e", "press consumed=${down.changeConsumed()} release consumed=${up.changeConsumed()} onClick fired=$clicked")
            }
            val miss = scene.sendPointerEvent(PointerEventType.Press, Offset(W - 5f, H - 5f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(W - 5f, H - 5f), button = PointerButton.Primary)
            if (!miss.changeConsumed()) pass("e2", "press on empty space is not consumed (click-through works)")
            else fail("e2", "press on empty space reported as consumed")
        }
        22 -> focusRequester.requestFocus()
        24 -> {
            val delivered = platform.sendChar('a') && platform.sendChar('b')
            results.add("INFO text input path used: ${platform.pathUsed}, scene.focusManager.hasFocus=${scene.focusManager.hasFocus}")
            if (!delivered) fail("g", "no text input session was started when the TextField took focus")
        }
        26 -> {
            if (platform.pathUsed == "none") fail("g", "no text input path was used")
            else if (textValue.text == "ab") pass("g", "typing via the ${platform.pathUsed} path + CommitTextCommand produced \"${textValue.text}\"")
            else fail("g", "TextField value is \"${textValue.text}\", expected \"ab\" (path=${platform.pathUsed})")
            scene.sendKeyEvent(KeyEvent(Key.Backspace, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Backspace, KeyEventType.KeyUp))
        }
        28 -> {
            if (textValue.text == "a") pass("g2", "Key.Backspace deleted a character; no AWT key event involved")
            else fail("g2", "after Backspace the value is \"${textValue.text}\", expected \"a\"")
        }
        30 -> {
            // --- h: GL firewall. Cube and SpriteBatch still correct after Compose render + reset.
            val err = Gdx.gl.glGetError()
            val centre = readPixel(Gdx.graphics.backBufferWidth / 2, Gdx.graphics.backBufferHeight / 2)
            val bg = readPixel(Gdx.graphics.backBufferWidth - 3, 3)
            val cubeVisible = ((centre shr 8) and 0xff) > 60
            if (err == GL20.GL_NO_ERROR && cubeVisible && bg != 0) {
                pass("h", "glGetError clean; cube visible under the HUD (centre=${hex(centre)}); clear colour intact (${hex(bg)})")
            } else {
                fail("h", "glGetError=$err centre=${hex(centre)} bg=${hex(bg)} — state firewall leaked")
            }
            // --- f3: a focused TextField blinks its caret, so it is never static. Record it.
            renderCount = 0
        }
        36 -> {
            results.add("INFO with a focused TextField on screen, 6 frames caused $renderCount Compose renders (caret blink)")
        }
        37 -> {
            // --- i: 1080p cost
            fbo.dispose()
            fbo = FrameBuffer(Pixmap.Format.RGBA8888, 1920, 1080, true, true)
            makeSkiaSurface(origin)
            scene.size = IntSize(1920, 1080)
        }
        39 -> {
            results.add("INFO resized 1280x720 -> 1920x1080, no crash, glGetError=${Gdx.gl.glGetError()}")
            markerColor = if (markerColor == Color.Red) Color.Magenta else Color.Red
            update(System.nanoTime())
            val t0 = System.nanoTime()
            renderCompose()
            val dt = (System.nanoTime() - t0) / 1_000_000.0
            System.gc(); Thread.sleep(50)
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            pass("i", "1080p full HUD render = %.2f ms on llvmpipe, heap after GC = %d MB".format(dt, usedMb))
        }
        40 -> {
            try {
                scene.size = IntSize(1, 1)
                update(System.nanoTime())
                pass("resize0", "degenerate 1x1 resize did not throw")
            } catch (t: Throwable) { fail("resize0", "$t") }
            results.add("INFO composition ran on thread \"$composeThread\"; GL thread is \"${Thread.currentThread().name}\"")
        }
        else -> Unit
    } }

    override fun dispose() {
        try {
            scene.close(); recomposer.close()
            skiaSurface?.close(); backendTarget?.close(); directContext?.close()
            fbo.dispose(); batch.dispose(); modelBatch.dispose(); cubeModel.dispose()
            pass("dispose", "clean teardown, no throw")
        } catch (t: Throwable) { fail("dispose", "$t") }
    }
}
