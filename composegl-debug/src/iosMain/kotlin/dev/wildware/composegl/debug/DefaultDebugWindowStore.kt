package dev.wildware.composegl.debug

/**
 * An app's own directory is read-only here and only the app knows where it may write, so the windows
 * remember for as long as the game runs. A game that wants them kept hands in a
 * [FileDebugWindowStore] pointing at its Documents directory.
 */
actual fun defaultDebugWindowStore(): DebugWindowStore = MemoryDebugWindowStore()
