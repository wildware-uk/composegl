package composegl.lwjgl3

import org.lwjgl.opengl.GL32C

/**
 * Draws one texture over the whole window, premultiplied.
 *
 * One oversized triangle, no vertex buffer, no matrices. The vertical flip lives in the shader
 * and happens exactly once: Compose's row 0 is in the framebuffer's row 0, and OpenGL counts rows
 * up from the bottom.
 */
internal class FullScreenBlit {

    private var program = 0
    private var vertexArray = 0
    private var textureUniform = -1

    fun draw(texture: Int, width: Int, height: Int) {
        if (program == 0) build()

        GL32C.glBindFramebuffer(GL32C.GL_FRAMEBUFFER, 0)
        GL32C.glViewport(0, 0, width, height)
        GL32C.glEnable(GL32C.GL_BLEND)
        GL32C.glBlendFunc(GL32C.GL_ONE, GL32C.GL_ONE_MINUS_SRC_ALPHA)
        GL32C.glDisable(GL32C.GL_DEPTH_TEST)

        GL32C.glUseProgram(program)
        GL32C.glActiveTexture(GL32C.GL_TEXTURE0)
        GL32C.glBindTexture(GL32C.GL_TEXTURE_2D, texture)
        GL32C.glUniform1i(textureUniform, 0)
        GL32C.glBindVertexArray(vertexArray)
        GL32C.glDrawArrays(GL32C.GL_TRIANGLES, 0, 3)

        GL32C.glBindVertexArray(0)
        GL32C.glUseProgram(0)
        GL32C.glDisable(GL32C.GL_BLEND)
    }

    fun dispose() {
        if (program != 0) GL32C.glDeleteProgram(program)
        if (vertexArray != 0) GL32C.glDeleteVertexArrays(vertexArray)
        program = 0
        vertexArray = 0
    }

    private fun build() {
        vertexArray = GL32C.glGenVertexArrays()
        val vertex = compile(
            GL32C.GL_VERTEX_SHADER,
            """
            #version 150 core
            out vec2 vTexCoord;
            void main() {
                vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
                vTexCoord = vec2(corner.x, 1.0 - corner.y);
            }
            """.trimIndent(),
        )
        val fragment = compile(
            GL32C.GL_FRAGMENT_SHADER,
            """
            #version 150 core
            in vec2 vTexCoord;
            uniform sampler2D uTexture;
            out vec4 fragColor;
            void main() { fragColor = texture(uTexture, vTexCoord); }
            """.trimIndent(),
        )

        program = GL32C.glCreateProgram()
        GL32C.glAttachShader(program, vertex)
        GL32C.glAttachShader(program, fragment)
        GL32C.glLinkProgram(program)
        check(GL32C.glGetProgrami(program, GL32C.GL_LINK_STATUS) == GL32C.GL_TRUE) {
            "ComposeGL's blit program did not link: ${GL32C.glGetProgramInfoLog(program)}"
        }
        GL32C.glDeleteShader(vertex)
        GL32C.glDeleteShader(fragment)
        textureUniform = GL32C.glGetUniformLocation(program, "uTexture")
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GL32C.glCreateShader(type)
        GL32C.glShaderSource(shader, source)
        GL32C.glCompileShader(shader)
        check(GL32C.glGetShaderi(shader, GL32C.GL_COMPILE_STATUS) == GL32C.GL_TRUE) {
            "ComposeGL's blit shader did not compile: ${GL32C.glGetShaderInfoLog(shader)}"
        }
        return shader
    }
}
