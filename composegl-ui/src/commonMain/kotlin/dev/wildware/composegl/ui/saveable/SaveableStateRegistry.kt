package dev.wildware.composegl.ui.saveable

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Where [rememberSaveable] puts a value when the part of the tree holding it goes away, and where
 * it finds it again when that part comes back.
 *
 * Kept in memory, by key, for as long as whatever owns the registry lives. Nothing is written to
 * disk and nothing has to be serialisable: what is saved is the object itself, so a `MutableState`
 * comes back as the same `MutableState` and a [dev.wildware.composegl.ui.widget.ScrollState] as the
 * same scroll state.
 *
 * A game rarely makes one of these by hand. [SaveableStateHolder] makes one per screen and provides
 * it as [LocalSaveableStateRegistry]; this is the piece to reach for when state has to outlive
 * something the holder does not cover, such as the whole content of a host being swapped.
 */
interface SaveableStateRegistry {

    /**
     * The value saved under [key] the last time this part of the tree went away, or null.
     *
     * Consuming, because two things can share a key — the same composable called twice in a loop
     * with no `key()` around it — and each of them wants its own value back, in the order they
     * were saved.
     */
    fun consumeRestored(key: String): Any?

    /**
     * Asks for [valueProvider] to be called when this registry saves. Unregister it when the value
     * it provides stops existing, or it is saved anyway.
     */
    fun registerProvider(key: String, valueProvider: () -> Any?): Entry

    /**
     * Everything that is registered right now, by key, plus whatever was restored and not yet
     * consumed. The second part is what lets a screen inside a screen keep state it never got as
     * far as showing.
     */
    fun performSave(): Map<String, List<Any?>>

    /** One registration, to be let go of. */
    fun interface Entry {
        fun unregister()
    }
}

/** A registry that starts from [restored], which is what an earlier one's [SaveableStateRegistry.performSave] gave. */
fun SaveableStateRegistry(restored: Map<String, List<Any?>>? = null): SaveableStateRegistry =
    InMemoryRegistry(restored)

/**
 * The registry a [rememberSaveable] below saves into. Null outside any [SaveableStateHolder], where
 * [rememberSaveable] is exactly `remember`.
 */
val LocalSaveableStateRegistry = staticCompositionLocalOf<SaveableStateRegistry?> { null }

private class InMemoryRegistry(restored: Map<String, List<Any?>>?) : SaveableStateRegistry {

    private val restored: MutableMap<String, MutableList<Any?>> =
        restored?.mapValuesTo(LinkedHashMap()) { it.value.toMutableList() } ?: LinkedHashMap()

    private val providers = LinkedHashMap<String, MutableList<() -> Any?>>()

    override fun consumeRestored(key: String): Any? {
        val values = restored[key] ?: return null
        val value = values.removeAt(0)
        if (values.isEmpty()) restored.remove(key)
        return value
    }

    override fun registerProvider(key: String, valueProvider: () -> Any?): SaveableStateRegistry.Entry {
        require(key.isNotBlank()) { "a saveable key cannot be blank" }
        providers.getOrPut(key) { mutableListOf() }.add(valueProvider)
        return SaveableStateRegistry.Entry {
            val list = providers[key] ?: return@Entry
            list.remove(valueProvider)
            if (list.isEmpty()) providers.remove(key)
        }
    }

    override fun performSave(): Map<String, List<Any?>> {
        val saved = LinkedHashMap<String, MutableList<Any?>>()
        restored.forEach { (key, values) -> saved[key] = values.toMutableList() }
        providers.forEach { (key, list) ->
            // Registered values go first: they are the ones that were on the screen, so they are
            // the ones the same composables will ask for first when it comes back.
            saved[key] = (list.map { it() } + saved[key].orEmpty()).toMutableList()
        }
        return saved
    }
}
