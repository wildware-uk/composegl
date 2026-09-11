package dev.wildware.composegl.ui.internal

internal actual class Guard actual constructor() {

    private val monitor = Any()

    actual fun <T> hold(block: () -> T): T = synchronized(monitor) { block() }
}
