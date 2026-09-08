package composegl.smoke

import composegl.CursorShape
import composegl.HostServices
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL32C.GL_ARRAY_BUFFER
import org.lwjgl.opengl.GL32C.GL_BLEND
import org.lwjgl.opengl.GL32C.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL32C.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL32C.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL32C.GL_DEPTH24_STENCIL8
import org.lwjgl.opengl.GL32C.GL_DEPTH_STENCIL_ATTACHMENT
import org.lwjgl.opengl.GL32C.GL_DEPTH_TEST
import org.lwjgl.opengl.GL32C.GL_ELEMENT_ARRAY_BUFFER
import org.lwjgl.opengl.GL32C.GL_FRAGMENT_SHADER
import org.lwjgl.opengl.GL32C.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL32C.GL_FRAMEBUFFER_COMPLETE
import org.lwjgl.opengl.GL32C.GL_LINEAR
import org.lwjgl.opengl.GL32C.GL_LINK_STATUS
import org.lwjgl.opengl.GL32C.GL_NO_ERROR
import org.lwjgl.opengl.GL32C.GL_ONE
import org.lwjgl.opengl.GL32C.GL_ONE_MINUS_SRC_ALPHA
import org.lwjgl.opengl.GL32C.GL_RENDERBUFFER
import org.lwjgl.opengl.GL32C.GL_RGBA
import org.lwjgl.opengl.GL32C.GL_RGBA8
import org.lwjgl.opengl.GL32C.GL_SCISSOR_TEST
import org.lwjgl.opengl.GL32C.GL_SRC_ALPHA
import org.lwjgl.opengl.GL32C.GL_STENCIL_TEST
import org.lwjgl.opengl.GL32C.GL_TEXTURE0
import org.lwjgl.opengl.GL32C.GL_TEXTURE_2D
import org.lwjgl.opengl.GL32C.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL32C.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL32C.GL_TRIANGLES
import org.lwjgl.opengl.GL32C.GL_TRUE
import org.lwjgl.opengl.GL32C.GL_UNPACK_ALIGNMENT
import org.lwjgl.opengl.GL32C.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL32C.GL_VERTEX_SHADER
import org.lwjgl.opengl.GL32C.glActiveTexture
import org.lwjgl.opengl.GL32C.glAttachShader
import org.lwjgl.opengl.GL32C.glBindBuffer
import org.lwjgl.opengl.GL32C.glBindFramebuffer
import org.lwjgl.opengl.GL32C.glBindRenderbuffer
import org.lwjgl.opengl.GL32C.glBindTexture
import org.lwjgl.opengl.GL32C.glBindVertexArray
import org.lwjgl.opengl.GL32C.glBlendFunc
import org.lwjgl.opengl.GL32C.glCheckFramebufferStatus
import org.lwjgl.opengl.GL32C.glClearColor
import org.lwjgl.opengl.GL32C.glColorMask
import org.lwjgl.opengl.GL32C.glCompileShader
import org.lwjgl.opengl.GL32C.glCreateProgram
import org.lwjgl.opengl.GL32C.glCreateShader
import org.lwjgl.opengl.GL32C.glDeleteFramebuffers
import org.lwjgl.opengl.GL32C.glDeleteRenderbuffers
import org.lwjgl.opengl.GL32C.glDeleteShader
import org.lwjgl.opengl.GL32C.glDeleteTextures
import org.lwjgl.opengl.GL32C.glDepthMask
import org.lwjgl.opengl.GL32C.glDisable
import org.lwjgl.opengl.GL32C.glDrawArrays
import org.lwjgl.opengl.GL32C.glEnable
import org.lwjgl.opengl.GL32C.glFramebufferRenderbuffer
import org.lwjgl.opengl.GL32C.glFramebufferTexture2D
import org.lwjgl.opengl.GL32C.glGenFramebuffers
import org.lwjgl.opengl.GL32C.glGenRenderbuffers
import org.lwjgl.opengl.GL32C.glGenTextures
import org.lwjgl.opengl.GL32C.glGenVertexArrays
import org.lwjgl.opengl.GL32C.glGetError
import org.lwjgl.opengl.GL32C.glGetProgramInfoLog
import org.lwjgl.opengl.GL32C.glGetProgrami
import org.lwjgl.opengl.GL32C.glGetShaderInfoLog
import org.lwjgl.opengl.GL32C.glGetShaderi
import org.lwjgl.opengl.GL32C.glGetUniformLocation
import org.lwjgl.opengl.GL32C.glLinkProgram
import org.lwjgl.opengl.GL32C.glPixelStorei
import org.lwjgl.opengl.GL32C.glReadPixels
import org.lwjgl.opengl.GL32C.glRenderbufferStorage
import org.lwjgl.opengl.GL32C.glShaderSource
import org.lwjgl.opengl.GL32C.glTexImage2D
import org.lwjgl.opengl.GL32C.glTexParameteri
import org.lwjgl.opengl.GL32C.glUniform1i
import org.lwjgl.opengl.GL32C.glUseProgram
import org.lwjgl.opengl.GL32C.glViewport
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A GL context, a framebuffer, and a quad — with no game engine anywhere.
 *
 * This exists to keep the core/adapter seam honest. If `composegl-core` ever needs something only
 * LibGDX happens to provide, this host stops compiling, and that is the point: the second engine
 * adapter should be a weekend, not a rewrite.
 *
 * It is deliberately plain: raw GLFW and raw OpenGL, no abstractions, no cleverness.
 */
class GlHost(
    private val windowWidth: Int = 800,
    private val windowHeight: Int = 600,
    private val visible: Boolean = false,
) : AutoCloseable {

    var window: Long = 0L
        private set

    private var fbo = 0
    private var colorTexture = 0
    private var depthStencil = 0
    private var vao = 0
    private var blitProgram = 0
    private var textureUniform = -1

    var frameBufferWidth = 0
        private set
    var frameBufferHeight = 0
        private set

    /** Everything ComposeGL needs from a platform, on a host with no toolkit at all. */
    val hostServices: HostServices = object : HostServices {
        override val density: Float get() = 1f
        override fun requestFrame() = Unit
        override fun setCursor(cursor: CursorShape) = Unit
        override fun getClipboard(): String? = GLFW.glfwGetClipboardString(window)
        override fun setClipboard(text: String) = GLFW.glfwSetClipboardString(window, text)
    }

    fun start() {
        check(GLFW.glfwInit()) { "GLFW failed to start. On a headless box, run under xvfb-run." }
        GLFW.glfwDefaultWindowHints()
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2)
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE)
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, if (visible) GLFW.GLFW_TRUE else GLFW.GLFW_FALSE)

        window = GLFW.glfwCreateWindow(windowWidth, windowHeight, "composegl-smoke", 0L, 0L)
        check(window != 0L) { "GLFW could not create a GL 3.2 core window." }
        GLFW.glfwMakeContextCurrent(window)
        GLFW.glfwSwapInterval(0)
        GL.createCapabilities()

        vao = glGenVertexArrays()
        blitProgram = buildBlitProgram()
        textureUniform = glGetUniformLocation(blitProgram, "uTexture")
        resize(windowWidth, windowHeight)
    }

    /** Recreates the offscreen framebuffer. A zero size releases it and draws nothing. */
    fun resize(width: Int, height: Int) {
        releaseFrameBuffer()
        frameBufferWidth = width
        frameBufferHeight = height
        if (width <= 0 || height <= 0) return

        fbo = glGenFramebuffers()
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)

        colorTexture = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, colorTexture)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null as ByteBuffer?)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0)

        // Packed depth-stencil: Skia needs the stencil for path clipping, and a stencil-only
        // attachment is unreliable across drivers.
        depthStencil = glGenRenderbuffers()
        glBindRenderbuffer(GL_RENDERBUFFER, depthStencil)
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthStencil)

        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        check(status == GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete: 0x${status.toString(16)}" }

        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glBindTexture(GL_TEXTURE_2D, 0)
        glBindRenderbuffer(GL_RENDERBUFFER, 0)
    }

    /** The handle to hand to `RenderTarget.Gl`. Zero when there is no framebuffer. */
    val frameBufferId: Int get() = fbo

    /**
     * Puts GL back to defaults after Skia has drawn. Every adapter owes the engine this; see the
     * doc comment on `RenderTarget.Gl`.
     */
    fun resetGlState() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, windowWidth, windowHeight)
        glUseProgram(0)
        glBindVertexArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, 0)
        glDisable(GL_SCISSOR_TEST)
        glDisable(GL_STENCIL_TEST)
        glDepthMask(true)
        glColorMask(true, true, true, true)
        glDisable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
    }

    /** Draws the offscreen framebuffer over the window as one full-screen quad. */
    fun blit() {
        if (fbo == 0) return
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, windowWidth, windowHeight)
        glEnable(GL_BLEND)
        // Skia writes premultiplied pixels.
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glDisable(GL_DEPTH_TEST)
        glUseProgram(blitProgram)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, colorTexture)
        glUniform1i(textureUniform, 0)
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glBindVertexArray(0)
        glUseProgram(0)
        glDisable(GL_BLEND)
    }

    fun clear(red: Float, green: Float, blue: Float, alpha: Float = 1f) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, windowWidth, windowHeight)
        glClearColor(red, green, blue, alpha)
        org.lwjgl.opengl.GL32C.glClear(GL_COLOR_BUFFER_BIT)
    }

    fun swap() {
        GLFW.glfwSwapBuffers(window)
        GLFW.glfwPollEvents()
    }

    fun glError(): Int = glGetError()

    /**
     * Reads the offscreen framebuffer back as premultiplied ARGB, row by row from the **top** —
     * the same order Compose thinks in, and the opposite of what GL hands back.
     */
    fun readFrameBuffer(): IntArray {
        check(fbo != 0) { "no framebuffer" }
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        val pixels = readPixels(0, 0, frameBufferWidth, frameBufferHeight)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        return pixels
    }

    /** The same, for the window itself. */
    fun readWindow(): IntArray {
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        return readPixels(0, 0, windowWidth, windowHeight)
    }

    private fun readPixels(x: Int, y: Int, width: Int, height: Int): IntArray {
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        glReadPixels(x, y, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        val out = IntArray(width * height)
        for (row in 0 until height) {
            // GL row 0 is the bottom; Compose row 0 is the top.
            val source = (height - 1 - row) * width * 4
            for (column in 0 until width) {
                val i = source + column * 4
                val r = buffer.get(i).toInt() and 0xff
                val g = buffer.get(i + 1).toInt() and 0xff
                val b = buffer.get(i + 2).toInt() and 0xff
                val a = buffer.get(i + 3).toInt() and 0xff
                out[row * width + column] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    override fun close() {
        releaseFrameBuffer()
        if (blitProgram != 0) org.lwjgl.opengl.GL32C.glDeleteProgram(blitProgram)
        if (vao != 0) org.lwjgl.opengl.GL32C.glDeleteVertexArrays(vao)
        if (window != 0L) GLFW.glfwDestroyWindow(window)
        GLFW.glfwTerminate()
    }

    private fun releaseFrameBuffer() {
        if (colorTexture != 0) glDeleteTextures(colorTexture)
        if (depthStencil != 0) glDeleteRenderbuffers(depthStencil)
        if (fbo != 0) glDeleteFramebuffers(fbo)
        colorTexture = 0
        depthStencil = 0
        fbo = 0
    }

    private fun buildBlitProgram(): Int {
        // A single oversized triangle covering the screen; no vertex buffer needed.
        val vertex = """
            #version 150 core
            out vec2 vTexCoord;
            void main() {
                vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
                // Compose's row 0 is in the framebuffer's row 0, and GL counts up from the
                // bottom, so the texture coordinate is flipped once, here, and never again.
                vTexCoord = vec2(corner.x, 1.0 - corner.y);
            }
        """.trimIndent()
        val fragment = """
            #version 150 core
            in vec2 vTexCoord;
            uniform sampler2D uTexture;
            out vec4 fragColor;
            void main() { fragColor = texture(uTexture, vTexCoord); }
        """.trimIndent()

        val program = glCreateProgram()
        val vs = compile(GL_VERTEX_SHADER, vertex)
        val fs = compile(GL_FRAGMENT_SHADER, fragment)
        glAttachShader(program, vs)
        glAttachShader(program, fs)
        glLinkProgram(program)
        check(glGetProgrami(program, GL_LINK_STATUS) == GL_TRUE) {
            "blit program did not link: ${glGetProgramInfoLog(program)}"
        }
        glDeleteShader(vs)
        glDeleteShader(fs)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        check(glGetShaderi(shader, GL_COMPILE_STATUS) == GL_TRUE) {
            "shader did not compile: ${glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    companion object {
        const val NO_ERROR = GL_NO_ERROR
    }
}
