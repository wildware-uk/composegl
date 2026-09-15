package dev.wildware.composegl.ui.saveable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.remember

/**
 * `remember`, except the value is still there when the screen holding it is left and come back to.
 *
 * ```kotlin
 * var tab by rememberSaveable { mutableStateOf(0) }
 * var name by rememberSaveable { mutableStateOf("") }
 * ```
 *
 * The value is kept by whichever [SaveableStateHolder] is above it: when that holder's screen
 * leaves the tree the value is put away under a key, and when the screen comes back the same call
 * gets the same object back instead of running [init] again. Outside a holder there is nowhere to
 * put it, and this behaves exactly like `remember`.
 *
 * The key is where the call sits in the code, which is right for nearly everything. Pass [key]
 * when that is not stable — the same field drawn from two different layouts of one screen, say.
 *
 * In memory only. Nothing needs to be serialisable, and nothing survives the game closing.
 *
 * @param inputs when any of these change the value is thrown away and [init] runs again, as with
 *   `remember(inputs)`. That holds while the screen is away too: a value saved with other inputs
 *   than the ones it comes back to is not restored.
 */
@Composable
fun <T : Any> rememberSaveable(vararg inputs: Any?, key: String? = null, init: () -> T): T {
    val finalKey = if (!key.isNullOrEmpty()) key else currentCompositeKeyHashCode.toString(36)
    val registry = LocalSaveableStateRegistry.current

    val holder = remember {
        val saved = registry?.consumeRestored(finalKey) as? Saved
        @Suppress("UNCHECKED_CAST")
        val restored = saved?.takeIf { it.inputs.contentEquals(inputs) }?.value as T?
        SaveableHolder(registry, finalKey, restored ?: init(), inputs)
    }
    val value = if (holder.inputs.contentEquals(inputs)) holder.value else init()
    SideEffect { holder.update(registry, finalKey, value, inputs) }
    return value
}

/** What goes into the registry: the value, and the inputs it was made for. */
private class Saved(val value: Any, val inputs: Array<out Any?>)

/**
 * The value, and its registration with the registry for as long as it is in the tree.
 *
 * Registration happens on remember rather than during composition, so a composition that is
 * abandoned half way leaves nothing behind in the registry to be saved later.
 */
private class SaveableHolder<T : Any>(
    private var registry: SaveableStateRegistry?,
    private var key: String,
    var value: T,
    var inputs: Array<out Any?>,
) : RememberObserver {

    private var entry: SaveableStateRegistry.Entry? = null
    private val provider = { Saved(value, inputs) }

    fun update(registry: SaveableStateRegistry?, key: String, value: T, inputs: Array<out Any?>) {
        this.value = value
        this.inputs = inputs
        if (this.registry !== registry || this.key != key) {
            entry?.unregister()
            this.registry = registry
            this.key = key
            register()
        }
    }

    private fun register() {
        entry = registry?.registerProvider(key, provider)
    }

    override fun onRemembered() = register()

    override fun onForgotten() {
        entry?.unregister()
        entry = null
    }

    override fun onAbandoned() = onForgotten()
}
