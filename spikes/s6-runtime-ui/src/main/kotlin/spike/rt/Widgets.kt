package spike.rt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode

/**
 * The whole widget set.
 *
 * `ComposeNode` is the public function that lets a composable emit a node of *our* type. Everything
 * below is a thin call to it — which is the honest summary of what this approach buys and costs:
 * the wiring is trivial, and every widget is one we have to write ourselves.
 */
@Composable
fun Node(style: GlStyle, content: @Composable () -> Unit = {}) {
    ComposeNode<GlNode, GlNodeApplier>(
        factory = ::GlNode,
        update = { set(style) { this.style = it } },
        content = content,
    )
}

@Composable
fun Column(
    padding: Float = 0f,
    gap: Float = 0f,
    background: Long? = null,
    border: Long? = null,
    width: Float? = null,
    height: Float? = null,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    alpha: Float = 1f,
    content: @Composable () -> Unit,
) = Node(
    GlStyle(
        direction = Direction.Column, padding = padding, gap = gap, background = background,
        border = border, width = width, height = height, offsetX = offsetX, offsetY = offsetY,
        alpha = alpha,
    ),
    content,
)

@Composable
fun Row(
    padding: Float = 0f,
    gap: Float = 0f,
    background: Long? = null,
    border: Long? = null,
    width: Float? = null,
    height: Float? = null,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    alpha: Float = 1f,
    content: @Composable () -> Unit,
) = Node(
    GlStyle(
        direction = Direction.Row, padding = padding, gap = gap, background = background,
        border = border, width = width, height = height, offsetX = offsetX, offsetY = offsetY,
        alpha = alpha,
    ),
    content,
)

@Composable
fun Text(
    text: String,
    size: Float = 18f,
    colour: Long = 0xFFFFFFFF,
    padding: Float = 0f,
    alpha: Float = 1f,
) = Node(GlStyle(text = text, textSize = size, textColour = colour, padding = padding, alpha = alpha))

@Composable
fun Button(
    label: String,
    size: Float = 18f,
    background: Long = 0xFF1B2A38,
    border: Long = 0xFF39C0ED,
    colour: Long = 0xFFDFF6FF,
    onClick: () -> Unit,
) = Node(
    GlStyle(
        text = label, textSize = size, textColour = colour, padding = 10f,
        background = background, border = border, onClick = onClick,
    ),
)

/** A bar that fills left to right. Painted by hand, to show a custom painter working. */
@Composable
fun Bar(fraction: Float, width: Float, height: Float = 10f, colour: Long = 0xFF39C0ED) {
    ComposeNode<GlNode, GlNodeApplier>(
        factory = ::GlNode,
        update = {
            set(GlStyle(width = width, height = height, background = 0xFF102030)) { this.style = it }
            set(fraction) { value ->
                painter = Painter { renderer, node ->
                    renderer.fill(node.x, node.y, node.width * value.coerceIn(0f, 1f), node.height, colour, 1f)
                }
                markDirty()
            }
        },
    )
}
