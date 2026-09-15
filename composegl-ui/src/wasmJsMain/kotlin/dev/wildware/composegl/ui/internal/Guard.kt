package dev.wildware.composegl.ui.internal

/**
 * No lock at all, because a browser page runs Kotlin on one thread.
 *
 * WebAssembly here has no shared memory and no second thread that could reach the queue this
 * guards, so there is nothing to wait for. A spin lock would be correct and pointless; this is the
 * honest version.
 */
internal actual class Guard actual constructor() {

    actual fun <T> hold(block: () -> T): T = block()
}
