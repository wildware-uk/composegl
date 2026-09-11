package composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import composegl.ui.geometry.Rect
import composegl.ui.graphics.Colour
import composegl.ui.graphics.UiCanvas
import composegl.ui.input.Action
import composegl.ui.input.InputSourceTracker
import composegl.ui.input.PromptStyle
import composegl.ui.input.Prompts
import composegl.ui.layout.Constraints
import composegl.ui.layout.LeafLayout
import composegl.ui.layout.Measurable
import composegl.ui.layout.MeasurePolicy
import composegl.ui.layout.MeasureResult
import composegl.ui.layout.MeasureScope
import composegl.ui.modifier.Modifier
import composegl.ui.skin.ResolvedStyle
import composegl.ui.skin.rememberStyle
import composegl.ui.text.FontProvider
import composegl.ui.text.TextLayout
import composegl.ui.text.TextStyle

/**
 * What the player is using, for the widgets that draw it differently.
 *
 * Defaults to a tracker nothing updates, so a game that has not wired one up gets keyboard prompts
 * rather than an exception. Provide a real one with [ProvideInputSource] and every prompt on
 * screen follows the player's hands.
 */
val LocalInputSource = staticCompositionLocalOf { InputSourceTracker() }

/** The game's actions and what they are bound to. */
val LocalPrompts = staticCompositionLocalOf { Prompts() }

@Composable
fun ProvideInputSource(tracker: InputSourceTracker, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalInputSource provides tracker, content = content)

@Composable
fun ProvidePrompts(prompts: Prompts, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalPrompts provides prompts, content = content)

/**
 * The button a player is being asked to press.
 *
 * `PromptGlyph(Action.Confirm)` is **E** on a keyboard, **A** on an Xbox pad and **✕** on a
 * PlayStation one, and it changes the moment the player puts one down and picks the other up —
 * no reload, no event delivered to each prompt, because what device is in use is Compose state and
 * so are the bindings. A game that lets a player rebind writes the new binding into [Prompts] and
 * every prompt on screen follows on the next frame.
 *
 * It sits inline in a sentence: the box is one line high and its letter is on the same baseline as
 * the words either side of it, so `Press [E] to open` reads as one line rather than as a label
 * with something stuck to it.
 *
 * An action nobody has bound draws a dash rather than an empty box, because a blank prompt tells a
 * player to press nothing.
 *
 * The skin can give real art per button: `"<style>.<key>"` — `"prompt.pad.south"`,
 * `"prompt.key.e"` — falling back to `"<style>"`, which is a box with the label in it.
 *
 * ```kotlin
 * Row(verticalAlignment = VerticalAlignment.Centre) {
 *     Text("Hold")
 *     PromptGlyph(Action.Interact)
 *     Text("to force the door")
 * }
 * ```
 */
@Composable
fun PromptGlyph(
    action: Action,
    modifier: Modifier = Modifier,
    style: String = "prompt",
    promptStyle: PromptStyle? = null,
    lineStyle: TextStyle? = null,
) {
    val prompts = LocalPrompts.current
    val source = LocalInputSource.current
    val fonts = rememberFonts()

    val which = promptStyle ?: prompts.styleFor(source.current)
    val prompt = prompts.prompt(action, which)

    // The art for this exact button if the skin has any, and the plain box if it has not. The
    // fallback is the skin's own, so a skin that draws half the buttons is not half broken.
    val resolved = rememberStyle("$style.${prompt.key}")

    // The line the glyph sits on is the sentence's, not the glyph's: a prompt is usually drawn a
    // size smaller than the words around it, and a box centred on a smaller line puts the letter
    // off the line of the sentence. Inherited from whatever text style is around it, and from the
    // skin's ordinary label when there is nothing around it.
    val inherited = LocalContentStyle.current
    val line = lineStyle ?: inherited?.textStyle ?: rememberStyle("label").textStyle

    val painter = remember(prompt, resolved, line, fonts) { PromptPainter(prompt.label, resolved, line, fonts) }

    LeafLayout(modifier, name = "prompt", measurePolicy = painter, draw = painter.draw)
}

/**
 * The box and the letter in it.
 *
 * The height is a line of text and the letter sits on that line's baseline, which is what lets a
 * prompt live in the middle of a sentence. The width is the letter plus its padding, or the
 * height, whichever is bigger — so `E` is a square and `ESC` is a rectangle, and neither is a
 * squashed version of the other.
 */
private class PromptPainter(
    private val label: String,
    private val style: ResolvedStyle,
    private val line: TextStyle,
    private val fonts: FontProvider,
) : MeasurePolicy {

    private var measured: TextLayout? = null

    private var boxWidth = 0f

    private var boxHeight = 0f

    /** How far down the box the label is drawn, so that its baseline is the sentence's. */
    private var baseline = 0f

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val text = fonts.measure(label, style.textStyle)
        measured = text

        boxHeight = line.lineHeight
        boxWidth = maxOf(text.size.width + style.padding.horizontal, boxHeight)

        // Where the words around it put their baseline, so the letter lands on the same one.
        baseline = fonts.metrics(line).ascent - text.firstBaseline

        return layout(constraints.constrainWidth(boxWidth), constraints.constrainHeight(boxHeight)) {}
    }

    val draw: UiCanvas.(Rect) -> Unit = { bounds ->
        val text = measured
        style.background.drawInto(this, bounds, Colour.White)
        if (text != null) {
            // Dropped by the difference between the two baselines, which is what puts the letter
            // on the same line as the words either side of it.
            val x = bounds.left + (bounds.right - bounds.left - text.size.width) / 2f
            text(text, x, bounds.top + baseline, style.textColour)
        }
    }
}
