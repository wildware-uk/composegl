package dev.wildware.composegl.ui.preview

/**
 * Marks a composable as something to photograph.
 *
 * ```kotlin
 * @Preview(width = 400, height = 200)
 * @Composable
 * fun ButtonPreview() {
 *     Button("PLAY", onClick = {})
 * }
 * ```
 *
 * `./gradlew :your-module:renderPreviews` turns every one of these into a PNG, drawn by the real
 * renderer through the real skin and fonts. That is the whole point: a picture in a pull request or
 * in the wiki that was taken of the widget, not drawn of it, and taken again by one command when
 * the widget changes.
 *
 * `./gradlew :your-module:previewLive`, from `composegl-preview`, shows the same functions in a
 * window and redraws them each time a source file is saved.
 *
 * The function takes no arguments, because nothing would be there to pass them. It may be a
 * top-level function or a member of an `object` or a `companion object`, and private is fine.
 *
 * The same function is a test's starting point too: `uiTest(preview)` composes it at its size, so
 * a screen someone looked at in a picture is the screen a test clicks through.
 *
 * @param width the picture's width in pixels, which is also the screen the content is laid out in.
 * @param height the picture's height in pixels.
 * @param name the file it becomes, `<name>.png`. Empty uses the function's own name.
 * @param background what is behind the content, as `0xAARRGGBB`. Opaque black by default, the
 *   ground a game's interface usually sits on; a zero alpha leaves the picture transparent.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Preview(
    val width: Int = 400,
    val height: Int = 200,
    val name: String = "",
    val background: Long = 0xFF000000,
)
