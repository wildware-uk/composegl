package uk.wildware.composegl.showcase.world

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable

/**
 * Embers drifting up through the scene, drawn with OpenGL.
 *
 * They exist to make the compositing obvious: they are behind the interface, they are additive,
 * and they move — so if Compose were flattening the frame or fighting for GL state, you would see
 * it immediately.
 *
 * Each particle lives in 3D and is projected to the screen, so it shrinks with distance and passes
 * behind the pedestal the way anything else in the scene would.
 */
class Particles(private val count: Int = 130) : Disposable {

    private class Particle {
        val position = Vector3()
        val velocity = Vector3()
        var life = 0f
        var maxLife = 1f
        var size = 1f
        var hue = 0f
    }

    private val particles = List(count) { Particle() }
    private lateinit var dot: Texture
    private lateinit var batch: SpriteBatch
    private val projected = Vector3()

    fun create() {
        dot = softDot()
        batch = SpriteBatch()
        particles.forEach { reset(it, randomLife = true) }
    }

    fun update(delta: Float) {
        particles.forEach { particle ->
            particle.life += delta
            if (particle.life >= particle.maxLife) {
                reset(particle)
                return@forEach
            }
            particle.position.mulAdd(particle.velocity, delta)
            // A little sway, so they do not look like they are on rails.
            particle.position.x += MathUtils.sin(particle.life * 1.7f + particle.hue * 10f) * delta * 0.25f
        }
    }

    /**
     * @param camera the same camera the scene used, so the embers sit in the same space.
     */
    fun render(camera: PerspectiveCamera) {
        val screenHeight = Gdx.graphics.height.toFloat()
        batch.projectionMatrix.setToOrtho2D(0f, 0f, Gdx.graphics.width.toFloat(), screenHeight)

        Gdx.gl.glEnable(GL20.GL_BLEND)
        // Additive: embers add light rather than covering what is behind them.
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
        batch.begin()
        particles.forEach { particle ->
            projected.set(particle.position)
            camera.project(projected)
            if (projected.z > 1f) return@forEach   // behind the camera

            val age = particle.life / particle.maxLife
            // Fade in quickly, out slowly.
            val alpha = (if (age < 0.15f) age / 0.15f else 1f - (age - 0.15f) / 0.85f).coerceIn(0f, 1f)
            // Perspective: the projection gives us depth, so scale by it.
            val distance = camera.position.dst(particle.position).coerceAtLeast(0.5f)
            val size = particle.size * 900f / distance

            batch.setColor(
                MathUtils.lerp(0.45f, 1f, particle.hue),
                MathUtils.lerp(0.8f, 0.95f, particle.hue),
                1f,
                alpha * 0.6f,
            )
            batch.draw(dot, projected.x - size / 2f, projected.y - size / 2f, size, size)
        }
        batch.end()
        Gdx.gl.glDisable(GL20.GL_BLEND)
        batch.color = Color.WHITE
    }

    private fun reset(particle: Particle, randomLife: Boolean = false) {
        val angle = MathUtils.random(MathUtils.PI2)
        val radius = MathUtils.random(0.4f, 7.5f)
        particle.position.set(
            MathUtils.cos(angle) * radius,
            MathUtils.random(-0.2f, 0.6f),
            MathUtils.sin(angle) * radius,
        )
        particle.velocity.set(
            MathUtils.random(-0.06f, 0.06f),
            MathUtils.random(0.22f, 0.75f),
            MathUtils.random(-0.06f, 0.06f),
        )
        particle.maxLife = MathUtils.random(3.5f, 8f)
        particle.life = if (randomLife) MathUtils.random(0f, particle.maxLife) else 0f
        // Chosen so an ember about ten metres out is a handful of pixels across.
        particle.size = MathUtils.random(0.04f, 0.15f)
        particle.hue = MathUtils.random()
    }

    /** A round, soft-edged dot, built at runtime so the demo carries no image files. */
    private fun softDot(size: Int = 64): Texture {
        val pixmap = Pixmap(size, size, Pixmap.Format.RGBA8888)
        val centre = size / 2f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val distance = kotlin.math.hypot(x - centre + 0.5f, y - centre + 0.5f) / centre
                val falloff = (1f - distance).coerceIn(0f, 1f)
                // Squared falloff reads as a glow rather than a disc.
                pixmap.setColor(1f, 1f, 1f, falloff * falloff)
                pixmap.drawPixel(x, y)
            }
        }
        return Texture(pixmap).also { pixmap.dispose() }
    }

    override fun dispose() {
        dot.dispose()
        batch.dispose()
    }
}
