package dev.wildware.composegl.render.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three shape programs, read as a GLSL compiler's preprocessor reads them: which paths each one
 * has, and how wide each number it is handed is.
 */
class ShapeProgramsTest {

    /**
     * [source] with its `#ifdef`, `#if defined(...)`, `#else` and `#endif` worked out and every
     * `#define` applied, given [defined] up front: just enough preprocessor for these shaders.
     */
    private fun preprocess(source: String, vararg defined: String): String {
        val macros = defined.associateWith { "" }.toMutableMap()
        val keeping = ArrayDeque<Boolean>()
        val out = StringBuilder()
        fun live() = keeping.all { it }
        fun isDefined(name: String) = name in macros
        for (raw in source.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#ifdef ") -> keeping.addLast(isDefined(line.removePrefix("#ifdef ").trim()))
                line.startsWith("#if ") -> {
                    // `||` of `&&` of `defined(X)` and `!defined(X)`: all these shaders use.
                    val condition = line.removePrefix("#if ").split("||").any { either ->
                        either.split("&&").all { term ->
                            val t = term.trim()
                            if (t.startsWith("!")) !isDefined(t.removePrefix("!defined(").removeSuffix(")"))
                            else isDefined(t.removePrefix("defined(").removeSuffix(")"))
                        }
                    }
                    keeping.addLast(condition)
                }
                line == "#else" -> keeping.addLast(!keeping.removeLast())
                line == "#endif" -> keeping.removeLast()
                !live() -> Unit
                line.startsWith("#define ") -> {
                    val parts = line.removePrefix("#define ").split(' ', limit = 2)
                    macros[parts[0]] = parts.getOrElse(1) { "" }
                }
                else -> {
                    var text = raw
                    macros.forEach { (name, value) -> text = Regex("\\b$name\\b").replace(text, value) }
                    out.append(text).append('\n')
                }
            }
        }
        assertTrue(keeping.isEmpty(), "every #if is closed")
        return out.toString()
    }

    private val es = "GL_ES"
    private val full = "CG_FULL"
    private val held = "CG_HELD"

    /** The varying named [name] as [text] declares it, its words squeezed to single spaces. */
    private fun declared(text: String, name: String): String =
        text.lines().single { Regex("\\bvarying\\b.*\\b$name;").containsMatchIn(it) }.trim().split(Regex("\\s+")).joinToString(" ")

    private val wide = listOf("v_texCoord", "v_local", "v_halfSize", "v_radii")
    private val small = listOf("v_color", "v_borderColor", "v_shadowColor", "v_shape", "v_gradient")

    @Test
    fun `the full program has every path and the common one leaves out the three heavy ones`() {
        val common = preprocess(GlslSources.ShapeFragment, es)
        val everything = preprocess(GlslSources.FullShape + GlslSources.ShapeFragment, es)
        // The lit surface, the run of stops, and the shade inside a shape.
        val heavy = listOf("v_gradient.x > 4.5", "v_gradient.x > 2.5", "spread < 0.0", "towardsEdge(", "slopeOf(")
        heavy.forEach {
            assertTrue(it in everything, "the full program has $it")
            assertFalse(it in common, "the common program leaves out $it")
        }
        listOf("v_gradient.x > 0.5", "spread > 0.0", "borderWidth != 0.0", "between(", "masked()").forEach {
            assertTrue(it in common && it in everything, "both have $it")
        }
    }

    @Test
    fun `only the held and full programs read the radii of a picture - the common one never loads them for a letter`() {
        val hold = "v_radii.z > 0.0"
        val common = preprocess(GlslSources.ShapeFragment, es)
        val holding = preprocess(GlslSources.HeldShape + GlslSources.ShapeFragment, es)
        val everything = preprocess(GlslSources.FullShape + GlslSources.ShapeFragment, es)
        assertFalse(hold in common)
        assertTrue(hold in holding && hold in everything)
        // Otherwise the held program is the common one: none of the heavy paths.
        listOf("v_gradient.x > 4.5", "v_gradient.x > 2.5", "spread < 0.0").forEach { assertFalse(it in holding, it) }
        // The common one reads the radii only where a shape needs its corners, never on the picture path.
        val picturePath = common.substringAfter("if (aa <= 0.0) {").substringBefore("return v_color * sampled;")
        assertFalse("v_radii" in picturePath, picturePath)
    }

    @Test
    fun `on an ES device the common and held programs read colours and small numbers at mediump and nothing else`() {
        listOf(GlslSources.ShapeVertex, GlslSources.ShapeFragment).forEach { shader ->
            listOf(preprocess(shader, es), preprocess(shader, es, held)).forEach { text ->
                small.forEach { assertTrue(declared(text, it).startsWith("varying mediump "), declared(text, it)) }
                // A wide panel keeps an exact edge and a big glyph page stays sharp: mediump never
                // reaches a position, a size, a corner or a texture coordinate.
                wide.forEach { assertFalse("mediump" in declared(text, it), declared(text, it)) }
            }
        }
    }

    @Test
    fun `the full program and a desktop GL read every number whole`() {
        listOf(GlslSources.ShapeVertex, GlslSources.ShapeFragment).forEach { shader ->
            listOf(preprocess(GlslSources.FullShape + shader, es), preprocess(shader), preprocess(GlslSources.FullShape + shader)).forEach { text ->
                (small + wide).forEach { assertFalse("mediump" in declared(text, it), declared(text, it)) }
            }
        }
    }

    @Test
    fun `both halves of each program declare each varying alike`() {
        listOf(arrayOf(es), arrayOf(es, held), arrayOf(es, full), arrayOf<String>()).forEach { defined ->
            val vertex = preprocess(GlslSources.ShapeVertex, *defined)
            val fragment = preprocess(GlslSources.ShapeFragment, *defined)
            (small + wide).forEach { assertEquals(declared(vertex, it), declared(fragment, it), "$it with ${defined.toList()}") }
        }
    }

    @Test
    fun `every dialect puts the full program's switch after its own header`() {
        GlslDialect.entries.forEach { dialect ->
            listOf(GlslSources.FullShape, GlslSources.HeldShape).forEach { switch ->
                val text = dialect.fragment(switch + GlslSources.ShapeFragment, highPrecision = true)
                val version = text.lines().first()
                if (version.startsWith("#version")) assertFalse("CG_" in version, "#version must stay first")
                assertTrue(switch in text)
            }
        }
    }
}
