package dev.wildware.composegl.demo.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.widget.SceneView
import dev.wildware.composegl.ui.widget.SceneViewState
import dev.wildware.composegl.ui.widget.rememberSceneViewState
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The worked example on the wiki's Scene view page, whole: a model you turn with the mouse or the
 * arrow keys, drawn by the game's own OpenGL.
 *
 * Everything in [Cube] is the game's. The toolkit knows nothing about it: it gives the draw block a
 * picture with a depth buffer, bound, and draws that picture as a panel. The doc shots of the page
 * are taken of these two functions, so the pictures and the example cannot drift apart.
 */
@Composable
fun ModelViewer(
    cube: Cube,
    modifier: Modifier = Modifier,
    state: SceneViewState = rememberSceneViewState(),
    tint: Colour = Colour.White,
) {
    // Not Compose state: only the draw block reads it, and only when the view is dirty.
    val turn = remember { Turn() }

    SceneView(
        state,
        modifier,
        onPointer = { e ->
            when (e) {
                is PointerEvent.Press -> {
                    turn.grab = e.position
                    true
                }
                is PointerEvent.Move -> if (e.pressed.isEmpty()) false else {
                    // Positions are in the picture's pixels, so half its width is half a turn.
                    turn.yaw += (e.position.x - turn.grab.x) / state.width.coerceAtLeast(1) * 180f
                    turn.pitch += (e.position.y - turn.grab.y) / state.height.coerceAtLeast(1) * 90f
                    turn.grab = e.position
                    state.invalidate()
                    true
                }
                else -> false
            }
        },
        onKey = { e ->
            val by = when (e.key) {
                Key.Left -> -15f
                Key.Right -> 15f
                else -> 0f
            }
            if (by != 0f && e.type == KeyEventType.Down) {
                turn.yaw += by
                state.invalidate()
            }
            by != 0f
        },
    ) {
        clear(Colour.rgb(0x10141C))
        raw { cube.draw(width, height, turn.yaw, turn.pitch, tint) }
    }
}

/** A drag in progress and the angles it has turned the model to. */
private class Turn {
    var yaw = 35f
    var pitch = 25f
    var grab = Offset.Zero
}

/** The game's own [Cube], made on the GL thread and given back when the composition lets it go. */
@Composable
fun rememberCube(): Cube {
    val cube = remember { Cube() }
    DisposableEffect(cube) { onDispose { cube.close() } }
    return cube
}

/**
 * A lit cube, drawn with plain OpenGL 2: one shader, one buffer, the depth test. Nothing in it is
 * the toolkit's; it stands for whatever renderer a game already has.
 *
 * Made lazily on the first [draw], so it can be created anywhere and only ever touches GL on the
 * thread that holds the context.
 */
class Cube : AutoCloseable {

    private var program = 0
    private var buffer = 0

    /**
     * Draws the cube into whatever is bound, over a [width] by [height] viewport, turned [yaw]
     * degrees round and tipped [pitch] degrees towards the camera, painted [tint].
     */
    fun draw(width: Int, height: Int, yaw: Float, pitch: Float, tint: Colour = Colour.White) {
        if (program == 0) create()

        GL11.glEnable(GL11.GL_DEPTH_TEST)
        GL11.glDepthFunc(GL11.GL_LESS)
        GL20.glUseProgram(program)
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "u_matrix"), false, matrix(width, height, yaw, pitch))
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "u_tint"), tint.red / 255f, tint.green / 255f, tint.blue / 255f)

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer)
        GL20.glEnableVertexAttribArray(Position)
        GL20.glEnableVertexAttribArray(Shade)
        GL20.glVertexAttribPointer(Position, 3, GL11.GL_FLOAT, false, 4 * Float.SIZE_BYTES, 0L)
        GL20.glVertexAttribPointer(Shade, 1, GL11.GL_FLOAT, false, 4 * Float.SIZE_BYTES, 3L * Float.SIZE_BYTES)
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, Faces.size / 4)

        // Tidy, though the toolkit puts its own state back after the block either way.
        GL20.glDisableVertexAttribArray(Position)
        GL20.glDisableVertexAttribArray(Shade)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
        GL20.glUseProgram(0)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
    }

    private fun create() {
        program = GL20.glCreateProgram()
        val vertex = shader(GL20.GL_VERTEX_SHADER, VertexSource)
        val fragment = shader(GL20.GL_FRAGMENT_SHADER, FragmentSource)
        GL20.glAttachShader(program, vertex)
        GL20.glAttachShader(program, fragment)
        GL20.glBindAttribLocation(program, Position, "a_position")
        GL20.glBindAttribLocation(program, Shade, "a_shade")
        GL20.glLinkProgram(program)
        check(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != 0) { GL20.glGetProgramInfoLog(program) }
        GL20.glDeleteShader(vertex)
        GL20.glDeleteShader(fragment)

        buffer = GL15.glGenBuffers()
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer)
        val data = BufferUtils.createFloatBuffer(Faces.size).put(Faces).flip()
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GL20.glCreateShader(type)
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)
        check(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != 0) { GL20.glGetShaderInfoLog(shader) }
        return shader
    }

    override fun close() {
        if (program != 0) GL20.glDeleteProgram(program)
        if (buffer != 0) GL15.glDeleteBuffers(buffer)
        program = 0
        buffer = 0
    }

    private companion object {
        const val Position = 0
        const val Shade = 1

        const val VertexSource = """
            #version 110
            attribute vec3 a_position;
            attribute float a_shade;
            uniform mat4 u_matrix;
            varying float v_shade;
            void main() {
                v_shade = a_shade;
                gl_Position = u_matrix * vec4(a_position, 1.0);
            }
        """

        const val FragmentSource = """
            #version 110
            uniform vec3 u_tint;
            varying float v_shade;
            void main() {
                gl_FragColor = vec4(u_tint * v_shade, 1.0);
            }
        """

        /** Six faces, two triangles each: x, y, z and how brightly that face is lit. */
        val Faces: FloatArray = buildList {
            fun face(shade: Float, vararg corners: Float) {
                // Two triangles from four corners: 0 1 2 and 0 2 3.
                listOf(0, 1, 2, 0, 2, 3).forEach { i ->
                    add(corners[i * 3]); add(corners[i * 3 + 1]); add(corners[i * 3 + 2]); add(shade)
                }
            }
            face(1.00f, -1f, -1f, 1f, 1f, -1f, 1f, 1f, 1f, 1f, -1f, 1f, 1f) // front
            face(0.45f, 1f, -1f, -1f, -1f, -1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f) // back
            face(0.85f, -1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, -1f, -1f, 1f, -1f) // top
            face(0.35f, -1f, -1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, -1f, -1f, 1f) // bottom
            face(0.70f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f, 1f) // right
            face(0.55f, -1f, -1f, -1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, -1f) // left
        }.toFloatArray()

        /** Perspective, then the camera four units back, then the model tipped and turned. Column-major. */
        fun matrix(width: Int, height: Int, yaw: Float, pitch: Float): FloatArray {
            val aspect = width.toFloat() / height.coerceAtLeast(1)
            val f = 1f / tan(Math.toRadians(25.0)).toFloat()
            val near = 0.1f
            val far = 20f
            val projection = floatArrayOf(
                f / aspect, 0f, 0f, 0f,
                0f, f, 0f, 0f,
                0f, 0f, (far + near) / (near - far), -1f,
                0f, 0f, 2f * far * near / (near - far), 0f,
            )
            val y = Math.toRadians(yaw.toDouble())
            val p = Math.toRadians(pitch.toDouble())
            val turnY = floatArrayOf(
                cos(y).toFloat(), 0f, -sin(y).toFloat(), 0f,
                0f, 1f, 0f, 0f,
                sin(y).toFloat(), 0f, cos(y).toFloat(), 0f,
                0f, 0f, 0f, 1f,
            )
            val tipX = floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, cos(p).toFloat(), sin(p).toFloat(), 0f,
                0f, -sin(p).toFloat(), cos(p).toFloat(), 0f,
                0f, 0f, 0f, 1f,
            )
            val back = floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, -6f, 1f,
            )
            return times(projection, times(back, times(tipX, turnY)))
        }

        fun times(a: FloatArray, b: FloatArray): FloatArray {
            val out = FloatArray(16)
            for (column in 0 until 4) {
                for (row in 0 until 4) {
                    var sum = 0f
                    for (k in 0 until 4) sum += a[k * 4 + row] * b[column * 4 + k]
                    out[column * 4 + row] = sum
                }
            }
            return out
        }
    }
}
