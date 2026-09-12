package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.graphics.BlendMode

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
        // Pushing and popping the state stacks must not build a batch either: a blend mode reaches
        // the driver only through the batch, and outside a frame there is no batch to reach it by.
        canvas.pushBlend(BlendMode.Additive)
        canvas.popBlend()
        // The two queries a caller is told to ask before it draws. Answering them must not reach
        // the driver either, and a backend that said yes here and then turned nothing and added
        // nothing would be the one dishonesty these two additions could commit.
        if (!canvas.rotatesImages || !canvas.supports(BlendMode.Additive)) {
            System.err.println("this backend does turn a picture and does add light; it should say so")
            kotlin.system.exitProcess(3)
        }
        // Closing must never build the thing it is about to destroy.
        canvas.close()
        println(Done)
    }
}
