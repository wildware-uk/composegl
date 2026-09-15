package dev.wildware.composegl.ui.saveable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SaveableStateRegistryTest {

    @Test
    fun `what was registered is what is saved`() {
        val registry = SaveableStateRegistry()
        var tab = 1
        registry.registerProvider("tab") { tab }
        tab = 2

        assertEquals(mapOf("tab" to listOf<Any?>(2)), registry.performSave(), "read when saving and not when registering")
    }

    @Test
    fun `an unregistered value is not saved`() {
        val registry = SaveableStateRegistry()
        val entry = registry.registerProvider("tab") { 1 }
        registry.registerProvider("name") { "Ada" }

        entry.unregister()

        assertEquals(mapOf("name" to listOf<Any?>("Ada")), registry.performSave())
    }

    @Test
    fun `a restored key hands its values out once each in order`() {
        val registry = SaveableStateRegistry(mapOf("row" to listOf(1, 2)))

        assertEquals(1, registry.consumeRestored("row"))
        assertEquals(2, registry.consumeRestored("row"))
        assertNull(registry.consumeRestored("row"))
        assertNull(registry.consumeRestored("missing"))
    }

    @Test
    fun `restored values nobody asked for are saved again`() {
        val registry = SaveableStateRegistry(mapOf("notes" to listOf("page two"), "row" to listOf(1, 2)))
        registry.consumeRestored("row")
        registry.registerProvider("row") { 5 }

        assertEquals(
            mapOf("notes" to listOf<Any?>("page two"), "row" to listOf<Any?>(5, 2)),
            registry.performSave(),
        )
    }
}
