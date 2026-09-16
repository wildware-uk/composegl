package dev.wildware.composegl.debug

/** A desktop, so a file beside the game, the same one the JVM writes. */
actual fun defaultDebugWindowStore(): DebugWindowStore = FileDebugWindowStore("composegl-debug-windows.txt")
