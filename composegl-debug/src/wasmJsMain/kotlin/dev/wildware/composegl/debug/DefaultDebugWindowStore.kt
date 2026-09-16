package dev.wildware.composegl.debug

/** No file to keep them in here, so the windows remember for as long as the game runs. */
actual fun defaultDebugWindowStore(): DebugWindowStore = MemoryDebugWindowStore()
