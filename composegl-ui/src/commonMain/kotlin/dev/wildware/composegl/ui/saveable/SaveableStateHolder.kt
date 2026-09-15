package dev.wildware.composegl.ui.saveable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember

/**
 * Keeps each screen's [rememberSaveable] state while that screen is away.
 *
 * ```kotlin
 * val screens = rememberSaveableStateHolder()
 * screens.SaveableStateProvider(current) { Screen(current) }
 * ```
 *
 * Every key gets a registry of its own. When the screen under a key leaves the tree — the player
 * opened the map instead of the codex — everything it saved is put away under that key; when the
 * same key is shown again it gets it all back. The selected tab, how far the inventory was
 * scrolled, the half-typed name in a field.
 *
 * A holder can sit inside a screen of another holder. Its own saved screens are kept with the
 * outer screen and come back with it.
 */
interface SaveableStateHolder {

    /**
     * Shows [content] with the state last saved for [key], and saves it again when it goes.
     *
     * One key may only be showing once at a time: two live copies would each save over the other.
     */
    @Composable
    fun SaveableStateProvider(key: Any, content: @Composable () -> Unit)

    /**
     * Forgets everything saved for [key]. The next time it is shown it starts fresh — a chest that
     * was emptied, a conversation that ended. Calling it while [key] is showing forgets it when it
     * next leaves instead.
     */
    fun removeState(key: Any)
}

/** A [SaveableStateHolder] that is itself kept by any holder above it. */
@Composable
fun rememberSaveableStateHolder(): SaveableStateHolder = rememberSaveable { SaveableStateHolderImpl() }

/**
 * The one-line form: shows [current], and keeps the state of every other screen it has shown.
 *
 * ```kotlin
 * var screen by rememberSaveable { mutableStateOf(Screen.Codex) }
 * SaveableStateHolder(screen) { key -> Screen(key) }
 * ```
 */
@Composable
fun <K : Any> SaveableStateHolder(current: K, content: @Composable (K) -> Unit) {
    val holder = rememberSaveableStateHolder()
    holder.SaveableStateProvider(current) { content(current) }
}

private class SaveableStateHolderImpl : SaveableStateHolder {

    private val saved = HashMap<Any, Map<String, List<Any?>>>()
    private val showing = HashMap<Any, SaveableStateRegistry>()
    private val forgetOnLeave = HashSet<Any>()

    @Composable
    override fun SaveableStateProvider(key: Any, content: @Composable () -> Unit) {
        key(key) {
            val registry = remember { SaveableStateRegistry(saved[key]) }
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry, content = content)
            // After the content, and that is the whole trick. A group leaving the tree forgets what
            // it remembered in reverse order, so this effect is disposed — and saves — while every
            // rememberSaveable inside is still registered. Put it first and there is nothing to save.
            DisposableEffect(Unit) {
                check(key !in showing) { "the saveable key $key is already showing somewhere else" }
                saved -= key
                showing[key] = registry
                onDispose {
                    if (showing[key] === registry) showing -= key
                    if (forgetOnLeave.remove(key)) return@onDispose
                    // A screen with nothing to keep keeps no entry: a lazy list of thousands of rows
                    // makes one of these per row it ever showed.
                    val state = registry.performSave()
                    if (state.isNotEmpty()) saved[key] = state
                }
            }
        }
    }

    override fun removeState(key: Any) {
        if (key in showing) forgetOnLeave += key else saved -= key
    }
}
