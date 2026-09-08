package composegl

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.text.AnnotatedString

/**
 * The clipboard Compose sees, backed by [HostServices.getClipboard] and
 * [HostServices.setClipboard] instead of by whatever the platform artifact would reach for.
 *
 * ComposeGL provides this itself rather than letting Compose's desktop default apply, because the
 * default goes through AWT and core must stay AWT-free. LibGDX gives us `Gdx.app.clipboard`, which
 * works the same on every backend it supports.
 *
 * This is the deprecated-but-still-used `ClipboardManager` shape: plain text, no suspending.
 */
@Suppress("DEPRECATION")
internal class GameClipboardManager(private val host: HostServices) : ClipboardManager {
    override fun getText(): AnnotatedString? = host.getClipboard()?.let(::AnnotatedString)
    override fun setText(annotatedString: AnnotatedString) = host.setClipboard(annotatedString.text)
    override fun hasText(): Boolean = !host.getClipboard().isNullOrEmpty()
    @Suppress("OVERRIDE_DEPRECATION")
    override val nativeClipboard: NativeClipboard get() = host
}

/**
 * The current `Clipboard` shape, which is what `BasicTextField` and `SelectionContainer` use.
 *
 * `ClipEntry` is a platform type. On the desktop Compose artifact its payload is an AWT
 * `Transferable`, and foundation reads it back with AWT data flavors — so a clip entry holding a
 * bare `String` would silently paste nothing. [ClipEntryText] bridges that without core naming an
 * AWT type; see the note there.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class GameClipboard(private val host: HostServices) : Clipboard {

    override suspend fun getClipEntry(): ClipEntry? {
        val text = host.getClipboard() ?: return null
        return ClipEntry(ClipEntryText.payloadFor(text))
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (clipEntry == null) return
        ClipEntryText.textOf(clipEntry.nativeClipEntry)?.let(host::setClipboard)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override val nativeClipboard: NativeClipboard get() = host
}

/**
 * Converts between clipboard text and whatever payload the host platform's `ClipEntry` carries.
 *
 * On the desktop Compose artifact that payload is `java.awt.datatransfer.Transferable`, and core
 * is not allowed to import AWT — the whole point of that rule is that Android and iOS share a
 * runtime with no AWT in it. So this reaches AWT reflectively: the class names appear as strings,
 * never as type references, and when the classes are absent (Android, iOS) every lookup returns
 * null and the object falls back to plain text.
 *
 * If a future port has a `ClipEntry` that carries something else again, this is the one place to
 * teach about it.
 */
private object ClipEntryText {

    private val stringSelectionConstructor = runCatching {
        Class.forName("java.awt.datatransfer.StringSelection")
            .getConstructor(String::class.java)
    }.getOrNull()

    private val stringFlavor = runCatching {
        Class.forName("java.awt.datatransfer.DataFlavor").getField("stringFlavor").get(null)
    }.getOrNull()

    private val getTransferData = runCatching {
        val flavorClass = Class.forName("java.awt.datatransfer.DataFlavor")
        Class.forName("java.awt.datatransfer.Transferable")
            .getMethod("getTransferData", flavorClass)
    }.getOrNull()

    /** Wraps [text] in whatever the platform's clip entries are made of. */
    fun payloadFor(text: String): Any =
        runCatching { stringSelectionConstructor?.newInstance(text) }.getOrNull() ?: text

    /** Pulls plain text back out of a payload, or null when there is none. */
    fun textOf(payload: Any): String? {
        if (payload is CharSequence) return payload.toString()
        val flavor = stringFlavor ?: return null
        val method = getTransferData ?: return null
        return runCatching { method.invoke(payload, flavor) as? String }.getOrNull()
    }
}
