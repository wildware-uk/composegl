package dev.wildware.composegl.render.gl

import kotlin.test.Test
import kotlin.test.assertEquals

class GlslDialectTest {

    private val vertex = "attribute vec2 a_position;\nvarying vec2 v_texCoord;\nvoid main() { v_texCoord = a_position; }"

    private val fragment =
        "varying vec2 v_texCoord;\nuniform sampler2D u_texture;\nvoid main() { gl_FragColor = texture2D(u_texture, v_texCoord); }"

    @Test
    fun `each context compiles the dialect it understands`() {
        assertEquals(GlslDialect.Legacy, GlslDialect.of(GlProfile(GlApi.Desktop, 2, 1)))
        assertEquals(GlslDialect.Legacy, GlslDialect.of(GlProfile(GlApi.Desktop, 4, 5)), "a compatibility profile keeps GLSL 1.10")
        assertEquals(GlslDialect.Core130, GlslDialect.of(GlProfile(GlApi.Desktop, 3, 0, core = true)))
        assertEquals(GlslDialect.Core150, GlslDialect.of(GlProfile(GlApi.Desktop, 3, 2, core = true)))
        assertEquals(GlslDialect.Core150, GlslDialect.of(GlProfile(GlApi.Desktop, 4, 1, core = true)))
        assertEquals(GlslDialect.Es100, GlslDialect.of(GlProfile(GlApi.Es, 2, 0)))
        assertEquals(GlslDialect.Es300, GlslDialect.of(GlProfile(GlApi.Es, 3, 2)))
        assertEquals(GlslDialect.Es100, GlslDialect.of(GlProfile(GlApi.WebGl, 1, 0)))
        assertEquals(GlslDialect.Es300, GlslDialect.of(GlProfile(GlApi.WebGl, 2, 0)))
    }

    @Test
    fun `GL 2 compiles the source exactly as written`() {
        assertEquals(vertex, GlslDialect.Legacy.vertex(vertex))
        assertEquals(fragment, GlslDialect.Legacy.fragment(fragment))
    }

    @Test
    fun `a GL 3 core context gets version 150 and the new words`() {
        assertEquals(
            "#version 150\nin vec2 a_position;\nout vec2 v_texCoord;\nvoid main() { v_texCoord = a_position; }",
            GlslDialect.Core150.vertex(vertex),
        )
        assertEquals(
            "#version 150\nout vec4 cg_FragColor;\n" +
                "in vec2 v_texCoord;\nuniform sampler2D u_texture;\nvoid main() { cg_FragColor = texture(u_texture, v_texCoord); }",
            GlslDialect.Core150.fragment(fragment),
        )
    }

    @Test
    fun `a GL 3 forward compatible context before 3 point 2 gets version 130`() {
        assertEquals(
            "#version 130\nin vec2 a_position;\nout vec2 v_texCoord;\nvoid main() { v_texCoord = a_position; }",
            GlslDialect.Core130.vertex(vertex),
        )
        assertEquals(
            "#version 130\nout vec4 cg_FragColor;\n" +
                "in vec2 v_texCoord;\nuniform sampler2D u_texture;\nvoid main() { cg_FragColor = texture(u_texture, v_texCoord); }",
            GlslDialect.Core130.fragment(fragment),
        )
    }

    @Test
    fun `ES 2 and WebGL 1 get version 100 and a float precision in the fragment shader`() {
        assertEquals("#version 100\n$vertex", GlslDialect.Es100.vertex(vertex))
        assertEquals("#version 100\nprecision mediump float;\n$fragment", GlslDialect.Es100.fragment(fragment))
    }

    @Test
    fun `ES 3 and WebGL 2 get version 300 es with a precision and the new words`() {
        assertEquals(
            "#version 300 es\nin vec2 a_position;\nout vec2 v_texCoord;\nvoid main() { v_texCoord = a_position; }",
            GlslDialect.Es300.vertex(vertex),
        )
        assertEquals(
            "#version 300 es\nprecision mediump float;\nout vec4 cg_FragColor;\n" +
                "in vec2 v_texCoord;\nuniform sampler2D u_texture;\nvoid main() { cg_FragColor = texture(u_texture, v_texCoord); }",
            GlslDialect.Es300.fragment(fragment),
        )
    }

    @Test
    fun `only whole words are rewritten`() {
        val source = "uniform float my_varying_amount;\nvoid main() { gl_FragColor = texture2DLod(u_texture, v_texCoord, 0.0) * my_varying_amount; }"
        assertEquals(
            "#version 150\nout vec4 cg_FragColor;\n" +
                "uniform float my_varying_amount;\nvoid main() { cg_FragColor = texture2DLod(u_texture, v_texCoord, 0.0) * my_varying_amount; }",
            GlslDialect.Core150.fragment(source),
        )
    }

    @Test
    fun `the built in shaders have no header of their own to clash with a dialect's`() {
        listOf(GlslSources.ShapeVertex, GlslSources.ShapeFragment, GlslSources.EffectVertex, GlslSources.EffectPreamble).forEach {
            assertEquals(false, it.contains("#version"))
            assertEquals(false, it.contains("precision"))
        }
    }
}
