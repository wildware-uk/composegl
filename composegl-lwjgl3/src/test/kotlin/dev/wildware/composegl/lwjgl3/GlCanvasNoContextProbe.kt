package dev.wildware.composegl.lwjgl3

/**
 * Builds, reads and closes a [GlCanvas] in a JVM that has never had an OpenGL context.
 *
 * Run as its own process by [GlCanvasHeadlessTest]; see that class for why it is a process rather
 * than a method. It prints [Done] and stops, or it says what went wrong and stops with a code that
 * is not zero — and if the canvas reaches the driver it never gets to do either, because the
 * native side takes the process down. All three outcomes are the parent's to read.
 */
object GlCanvasNoContextProbe {

    /** Printed on the way out. The parent looks for it, so that a silent exit is not a pass. */
    const val Done = "no-gl-context: built, read and closed a canvas"

    @JvmStatic
    fun main(args: Array<String>) {
        val canvas = GlCanvas()
        val calls = canvas.drawCalls
        if (calls != 0) {
            System.err.println("a canvas that has drawn nothing reported $calls draw calls")
            kotlin.system.exitProcess(2)
        }
        // Closing must never build the thing it is about to destroy.
        canvas.close()
        println(Done)
    }
}
