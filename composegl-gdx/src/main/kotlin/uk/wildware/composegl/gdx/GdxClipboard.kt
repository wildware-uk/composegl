package uk.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import uk.wildware.composegl.ui.backend.Clipboard

/**
 * LibGDX's clipboard, as the toolkit's.
 *
 * `Gdx.app.clipboard` is the whole implementation: it is the system clipboard on desktop and the
 * Android one on a phone, and neither needs AWT. The previous version of this project reached for
 * AWT through reflection to do this, which is the kind of dependency this toolkit exists not to
 * have.
 *
 * @param clipboard the engine's, or null to take `Gdx.app`'s when it is asked. Taking it lazily
 *   matters: a game builds its interface before `Gdx.app` exists.
 */
class GdxClipboard(
    private val clipboard: com.badlogic.gdx.utils.Clipboard? = null,
) : Clipboard {

    private val engine: com.badlogic.gdx.utils.Clipboard?
        get() = clipboard ?: Gdx.app?.clipboard

    /**
     * What is on the clipboard, or null if there is nothing to paste.
     *
     * An empty string comes back as null rather than as an empty paste, because a platform with
     * nothing on its clipboard reports the two interchangeably.
     */
    override fun read(): String? = engine?.contents?.takeIf { it.isNotEmpty() }

    override fun write(text: String) {
        engine?.contents = text
    }
}
