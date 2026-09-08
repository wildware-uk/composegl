package composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Cursor.SystemCursor
import composegl.ComposeGlContext
import composegl.ComposeGlUnsupportedException
import composegl.ContextConfig
import composegl.CursorShape
import composegl.HostServices

/**
 * The one [ComposeGlContext] for the game's GL context, plus the [HostServices] that connect
 * Compose to LibGDX.
 *
 * You do not have to call [init] — the first `ComposeOverlay` or `ComposeTexture` creates the
 * context on demand. Call it explicitly if you would rather find out about an unsupported GL
 * version at startup than at the first frame of UI.
 *
 * Call [dispose] from `ApplicationListener.dispose()`, after disposing your overlays.
 */
object ComposeGdx {

    private var context: ComposeGlContext? = null

    /** Density, cursor, clipboard and frame requests, wired to LibGDX. */
    val hostServices: HostServices = GdxHostServices

    /**
     * Creates the Skia context on the current GL context. Optional; called implicitly otherwise.
     *
     * @throws ComposeGlUnsupportedException if the GL context is older than 3.0.
     */
    fun init(config: ContextConfig = ContextConfig()) {
        context ?: run { context = createContext(config) }
    }

    internal fun context(config: ContextConfig = ContextConfig()): ComposeGlContext =
        context ?: createContext(config).also { context = it }

    private fun createContext(config: ContextConfig): ComposeGlContext {
        requireGlVersion(Gdx.graphics.glVersion.majorVersion, Gdx.graphics.glVersion.minorVersion)
        return ComposeGlContext.create(config)
    }

    /** Disposes the Skia context and every surface still on it. Safe to call twice. */
    fun dispose() {
        context?.dispose()
        context = null
    }
}

/**
 * Skia's GL backend needs a 3.0-era context. LibGDX defaults to GL 2.0, so this is the mistake
 * every first-time user makes, and it deserves a message that says exactly what to change.
 */
internal fun requireGlVersion(major: Int, minor: Int) {
    if (major >= 3) return
    throw ComposeGlUnsupportedException(
        "ComposeGL needs OpenGL 3.0+, but this context is $major.$minor. " +
            "Call config.useOpenGL3(true, 3, 2) before Lwjgl3Application.",
    )
}

/** Maps a Compose cursor to the nearest LibGDX system cursor. */
internal fun systemCursorFor(shape: CursorShape): SystemCursor = when (shape) {
    CursorShape.Default -> SystemCursor.Arrow
    CursorShape.Text -> SystemCursor.Ibeam
    CursorShape.Hand -> SystemCursor.Hand
    CursorShape.Crosshair -> SystemCursor.Crosshair
}

/**
 * Physical pixels per logical pixel.
 *
 * Deliberately not `Gdx.graphics.density`, which reports the monitor's DPI scale — a different
 * number, and the wrong one. What Compose needs is the ratio between the framebuffer we draw into
 * and the coordinate space input events arrive in.
 */
internal fun densityOf(backBufferWidth: Int, logicalWidth: Int): Float =
    if (logicalWidth <= 0) 1f else backBufferWidth.toFloat() / logicalWidth

private object GdxHostServices : HostServices {

    override val density: Float
        get() = densityOf(Gdx.graphics.backBufferWidth, Gdx.graphics.width)

    /** Matters only for games that render on demand; continuous rendering ignores it. */
    override fun requestFrame() = Gdx.graphics.requestRendering()

    override fun setCursor(cursor: CursorShape) =
        Gdx.graphics.setSystemCursor(systemCursorFor(cursor))

    override fun getClipboard(): String? = Gdx.app.clipboard?.contents

    override fun setClipboard(text: String) {
        Gdx.app.clipboard?.contents = text
    }

    /** A no-op on desktop; real on the Android backend, which is why it goes through LibGDX. */
    override fun showSoftKeyboard(visible: Boolean) =
        Gdx.input.setOnscreenKeyboardVisible(visible)
}
