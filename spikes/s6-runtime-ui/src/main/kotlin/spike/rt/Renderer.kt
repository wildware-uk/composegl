package spike.rt

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.utils.Disposable

/**
 * Draws the tree with OpenGL, through LibGDX.
 *
 * Everything is one `SpriteBatch`: rectangles are a 1x1 white texture stretched, text is a
 * `BitmapFont` from the same atlas machinery. One batch means no state switching between a
 * background and the label sitting on it, and it means the whole interface is a handful of draw
 * calls rather than a Skia surface upload.
 *
 * Layout works in screen coordinates with y growing downwards, because that is how interfaces are
 * described. OpenGL wants y upwards, so the flip happens here and nowhere else.
 */
class Renderer(private val fontPath: String = "fonts/DejaVuSans.ttf") : TextMetrics, Disposable {

    var screenWidth = 0f
    var screenHeight = 0f

    private val batch = SpriteBatch()
    private val white = Texture(Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
        setColor(Color.WHITE)
        fill()
    }).also { it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }

    private val generator = FreeTypeFontGenerator(Gdx.files.internal(fontPath))
    private val fonts = mutableMapOf<Int, BitmapFont>()
    private val layout = GlyphLayout()
    private val tint = Color()

    private fun font(size: Float): BitmapFont = fonts.getOrPut(size.toInt().coerceAtLeast(6)) {
        generator.generateFont(FreeTypeFontGenerator.FreeTypeFontParameter().apply {
            this.size = size.toInt().coerceAtLeast(6)
            // Games are drawn on top of moving scenery; a border keeps text readable over anything.
            borderWidth = 1f
            borderColor = Color(0f, 0f, 0f, 0.75f)
        })
    }

    override fun width(text: String, size: Float): Float {
        layout.setText(font(size), text)
        return layout.width
    }

    override fun lineHeight(size: Float): Float = font(size).lineHeight

    fun draw(root: GlNode) {
        batch.projectionMatrix.setToOrtho2D(0f, 0f, screenWidth, screenHeight)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        batch.begin()
        paint(root)
        batch.end()
    }

    private fun paint(node: GlNode) {
        val style = node.style

        style.background?.let { fill(node.x, node.y, node.width, node.height, it, style.alpha) }
        style.border?.let { stroke(node.x, node.y, node.width, node.height, it, style.alpha) }

        style.text?.let { text ->
            val bitmapFont = font(style.textSize)
            bitmapFont.color = colour(style.textColour, style.alpha)
            bitmapFont.draw(batch, text, node.x + style.padding, screenHeight - node.y - style.padding)
        }

        node.painter?.paint(this, node)
        node.children.forEach { paint(it) }
    }

    /** A single string, on its own, outside any tree. Used for the spike's own counters. */
    fun text(value: String, x: Float, y: Float, size: Float, argb: Long) {
        batch.projectionMatrix.setToOrtho2D(0f, 0f, screenWidth, screenHeight)
        batch.begin()
        val bitmapFont = font(size)
        bitmapFont.color = colour(argb, 1f)
        bitmapFont.draw(batch, value, x, screenHeight - y)
        batch.end()
    }

    /** @param y the top edge, in interface coordinates. */
    fun fill(x: Float, y: Float, width: Float, height: Float, argb: Long, alpha: Float) {
        batch.color = colour(argb, alpha)
        batch.draw(white, x, screenHeight - y - height, width, height)
    }

    fun stroke(x: Float, y: Float, width: Float, height: Float, argb: Long, alpha: Float, thickness: Float = 1f) {
        fill(x, y, width, thickness, argb, alpha)
        fill(x, y + height - thickness, width, thickness, argb, alpha)
        fill(x, y, thickness, height, argb, alpha)
        fill(x + width - thickness, y, thickness, height, argb, alpha)
    }

    private fun colour(argb: Long, alpha: Float): Color = tint.set(
        ((argb shr 16) and 0xFF) / 255f,
        ((argb shr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f,
        (((argb shr 24) and 0xFF) / 255f) * alpha,
    )

    override fun dispose() {
        batch.dispose()
        white.dispose()
        fonts.values.forEach { it.dispose() }
        generator.dispose()
    }
}
