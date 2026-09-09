package spike.rt

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The spike's actual experiment, with no window and no OpenGL.
 *
 * The question S6 exists to answer is whether recomposition still buys us "nothing changed, so
 * nothing redraws" once Compose UI is out of the picture. That question is about the runtime, not
 * about pixels, so it can be answered on a headless JVM in a few milliseconds — which is also the
 * strongest evidence that this path has no native dependency at all.
 */
object SelfCheck {

    private var failures = 0

    private fun check(name: String, condition: Boolean) {
        println((if (condition) "PASS  " else "FAIL  ") + name)
        if (!condition) failures++
    }

    private object Metrics : TextMetrics {
        override fun width(text: String, size: Float) = text.length * size * 0.55f
        override fun lineHeight(size: Float) = size * 1.25f
    }

    fun run() {
        val compose = GlCompose()
        var score by mutableStateOf(0)
        val log = mutableStateListOf<String>()
        var clicks = 0

        compose.setContent {
            Column(offsetX = 10f, offsetY = 10f, padding = 8f, gap = 4f, background = 0xFF102030) {
                Text("SCORE $score", size = 20f)
                Row(gap = 6f) {
                    Button("HIT") { clicks++ }
                    Button("HEAL") { }
                }
                log.forEach { Text(it, size = 14f) }
            }
        }

        var nanos = 0L
        fun frame(): Boolean = compose.frame(16_666_666L.let { nanos += it; nanos })

        check("the first frame composes the content", frame())
        check("the tree reached our applier", compose.root.children.size == 1)

        val quiet = (1..10).count { frame() }
        check("a static interface asks for no further work (10 frames, $quiet redraws)", quiet == 0)

        score = 40
        check("a state write causes exactly one redraw", frame())
        check("and then goes quiet again", !frame())

        log.add("first")
        log.add("second")
        check("adding to a list redraws", frame())
        val texts = mutableListOf<String>()
        compose.root.forEach { node -> node.style.text?.let { texts.add(it) } }
        check("both new rows are in the tree", texts.containsAll(listOf("first", "second")))
        check("the score row updated in place", texts.contains("SCORE 40"))

        log.clear()
        frame()
        val afterClear = mutableListOf<String>()
        compose.root.forEach { node -> node.style.text?.let { afterClear.add(it) } }
        check("removing rows removes nodes", afterClear.none { it == "first" || it == "second" })

        Layout.run(compose.root, 960f, 640f, Metrics)
        val panel = compose.root.children.first()
        check("layout gave the panel a size", panel.width > 0f && panel.height > 0f)
        check("layout placed it at its offset", panel.x == 10f && panel.y == 10f)
        check("children sit inside their parent", panel.children.all { it.x >= panel.x && it.y >= panel.y })

        val button = compose.root.forEachFind { it.style.text == "HIT" }
        check("the button was laid out", button != null && button.width > 0f)
        val hit = compose.root.hitTest(button!!.x + 2f, button.y + 2f)
        check("a press on the button finds the button", hit === button)
        hit?.style?.onClick?.invoke()
        check("and its handler ran", clicks == 1)

        // The handler wrote no state, so nothing should redraw; a click that does nothing costs nothing.
        check("a click with no state change causes no redraw", !frame())

        compose.dispose()
        println(if (failures == 0) "\nS6 self-check: all checks passed." else "\nS6 self-check: $failures failed.")
        if (failures > 0) kotlin.system.exitProcess(1)
    }

    private fun GlNode.forEachFind(predicate: (GlNode) -> Boolean): GlNode? {
        var found: GlNode? = null
        forEach { if (found == null && predicate(it)) found = it }
        return found
    }
}
