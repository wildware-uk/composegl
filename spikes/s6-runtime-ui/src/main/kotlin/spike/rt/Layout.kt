package spike.rt

/** Whatever can tell us how big a string will be. Kept separate so layout can run headless. */
interface TextMetrics {
    fun width(text: String, size: Float): Float
    fun lineHeight(size: Float): Float
}

/**
 * Measure, then place. About seventy lines.
 *
 * This is the part Compose UI would otherwise give us, and it is worth being honest about the gap:
 * Compose UI's layout does constraints, intrinsics, alignment, subcomposition and a lot more. This
 * does boxes in a column, a row or a pile. It is enough to draw a game interface, and not much more.
 */
object Layout {

    fun run(root: GlNode, width: Float, height: Float, metrics: TextMetrics) {
        measure(root, width, height, metrics)
        // The root always fills the screen, whatever its content measured.
        root.width = width
        root.height = height
        place(root, 0f, 0f)
    }

    private fun measure(node: GlNode, availableWidth: Float, availableHeight: Float, metrics: TextMetrics) {
        val style = node.style
        val pad = style.padding
        val innerWidth = (availableWidth - pad * 2).coerceAtLeast(0f)
        val innerHeight = (availableHeight - pad * 2).coerceAtLeast(0f)

        node.children.forEach { measure(it, innerWidth, innerHeight, metrics) }

        var contentWidth = 0f
        var contentHeight = 0f

        style.text?.let { text ->
            contentWidth = metrics.width(text, style.textSize)
            contentHeight = metrics.lineHeight(style.textSize)
        }

        if (node.children.isNotEmpty()) {
            val gaps = style.gap * (node.children.size - 1)
            val widest = node.children.maxOf { it.width }
            val tallest = node.children.maxOf { it.height }
            val stackedWidth = node.children.fold(0f) { sum, child -> sum + child.width } + gaps
            val stackedHeight = node.children.fold(0f) { sum, child -> sum + child.height } + gaps
            when (style.direction) {
                Direction.Column -> {
                    contentWidth = maxOf(contentWidth, widest)
                    contentHeight = maxOf(contentHeight, stackedHeight)
                }
                Direction.Row -> {
                    contentWidth = maxOf(contentWidth, stackedWidth)
                    contentHeight = maxOf(contentHeight, tallest)
                }
                Direction.Stack -> {
                    contentWidth = maxOf(contentWidth, widest)
                    contentHeight = maxOf(contentHeight, tallest)
                }
            }
        }

        node.width = style.width ?: if (style.fillWidth) availableWidth else contentWidth + pad * 2
        node.height = style.height ?: contentHeight + pad * 2
        // A child is never allowed to be bigger than the space it was offered.
        node.width = node.width.coerceAtMost(availableWidth)
        node.height = node.height.coerceAtMost(availableHeight.coerceAtLeast(node.height))
    }

    private fun place(node: GlNode, x: Float, y: Float) {
        node.x = x + node.style.offsetX
        node.y = y + node.style.offsetY

        val pad = node.style.padding
        var cursorX = node.x + pad
        var cursorY = node.y + pad

        node.children.forEach { child ->
            place(child, cursorX, cursorY)
            when (node.style.direction) {
                Direction.Column -> cursorY += child.height + node.style.gap
                Direction.Row -> cursorX += child.width + node.style.gap
                Direction.Stack -> Unit
            }
        }
    }
}
