package composegl.snake.android

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import composegl.gdx.GdxCanvas
import composegl.gdx.GdxClipboard
import composegl.gdx.GdxFonts
import composegl.gdx.GdxKeyboardInput
import composegl.gdx.GdxPointerInput
import composegl.gdx.GdxSoftKeyboard
import composegl.snake.SnakeApp
import composegl.ui.geometry.Size
import composegl.ui.input.InputSource
import composegl.ui.input.PointerType
import composegl.ui.layout.ScalePolicy
import composegl.ui.layout.Viewport

/**
 * Snake, as a LibGDX application.
 *
 * This is the whole of the Android port. It opens fonts and a canvas, hands LibGDX's touches and
 * keys to the toolkit's routers, and calls [SnakeApp] four times a frame — the same four calls the
 * desktop launcher makes, in the same order, with a different backend underneath.
 *
 * Nothing in `composegl-demo-snake-core` was changed to make this work, and nothing in the toolkit
 * was either. That is the claim the whole rebuild was for, and this file is what makes it checkable.
 */
class SnakeGdxApp : ApplicationAdapter() {

    private lateinit var fonts: GdxFonts
    private lateinit var sprites: SpriteBatch
    private lateinit var canvas: GdxCanvas
    private lateinit var app: SnakeApp

    private var viewport = Viewport.oneToOne(SnakeApp.Design)

    override fun create() {
        fonts = GdxFonts()
        val typeface = Gdx.files.internal("fonts/DejaVuSans.ttf")
        fonts.registerTrueType("body", typeface, listOf(13, 16, 20))
        fonts.registerTrueType("display", typeface, listOf(34))

        sprites = SpriteBatch()
        canvas = GdxCanvas(sprites, fonts.atlas)
        app = SnakeApp(
            fonts,
            GdxHighScores(),
            GdxClipboard(),
            GdxSoftKeyboard(),
            // A phone is a touch screen until something else is plugged into it.
            InputSource.Touch,
        )

        // Touches and the on-screen keyboard, translated by the backend and answered by the same
        // routers a mouse and a keyboard go through.
        Gdx.input.inputProcessor = InputMultiplexer(
            // Touch rather than mouse, which is the difference between a widget that lights up
            // under the finger and one that does not pretend the finger is hovering.
            GdxPointerInput(app.input, { viewport }, PointerType.Touch),
            GdxKeyboardInput(app.input),
        )
    }

    override fun render() {
        app.update(Gdx.graphics.deltaTime.coerceAtMost(0.1f))

        viewport = Viewport(
            design = SnakeApp.Design,
            physical = Size(
                Gdx.graphics.backBufferWidth.toFloat(),
                Gdx.graphics.backBufferHeight.toFloat(),
            ),
            policy = ScalePolicy.Fit,
        )
        app.layout(viewport, System.nanoTime())

        Gdx.gl.glClearColor(0.043f, 0.055f, 0.075f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        canvas.begin(viewport)
        app.draw(canvas)
        canvas.end()

        app.endFrame(canvas.drawCalls)
    }

    override fun pause() {
        // A phone takes the window away without asking. Anything mid-press has to be let go of, or
        // the button comes back held down.
        app.input.windowLostFocus()
    }

    override fun dispose() {
        app.close()
        canvas.dispose()
        sprites.dispose()
        fonts.dispose()
    }
}
