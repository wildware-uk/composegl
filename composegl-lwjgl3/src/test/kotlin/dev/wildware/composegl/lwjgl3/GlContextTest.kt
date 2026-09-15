package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.render.gl.GlApi
import dev.wildware.composegl.render.gl.GlDevice
import dev.wildware.composegl.render.gl.GlslDialect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The suite runs on the OpenGL its task asked for, and not on whatever the driver felt like: an ES
 * run that quietly got desktop GL would pass every other test here and prove nothing.
 */
class GlContextTest {

    @Test
    fun `the context is the one the run asked for, and the renderer compiles its dialect`() = Gl.render {
        val version = Gl.version()
        val profile = Gl.gl.profile
        val dialect = GlDevice(Gl.gl).dialect
        when (Gl.context) {
            GlfwContext.Desktop -> {
                assertTrue(!version.startsWith("OpenGL ES"), "desktop GL was asked for, and the driver says $version")
                assertEquals(GlApi.Desktop, profile.api)
            }
            GlfwContext.Es3 -> {
                assertTrue(version.startsWith("OpenGL ES 3"), "OpenGL ES 3 was asked for, and the driver says $version")
                assertTrue(Gl.shadingVersion().startsWith("OpenGL ES GLSL ES 3"), Gl.shadingVersion())
                assertEquals(GlApi.Es, profile.api)
                assertEquals(GlslDialect.Es300, dialect)
            }
            GlfwContext.Es2 -> {
                assertTrue(version.startsWith("OpenGL ES 2.0"), "OpenGL ES 2 was asked for, and the driver says $version")
                assertTrue(Gl.shadingVersion().startsWith("OpenGL ES GLSL ES 1.0"), Gl.shadingVersion())
                assertEquals(GlApi.Es, profile.api)
                assertEquals(GlslDialect.Es100, dialect)
            }
        }
    }
}
