package dev.wildware.composegl.kool

import java.io.File

/** The fonts the raw OpenGL frontend's goldens were drawn with, read from where that frontend keeps them. */
object TestFonts {
    fun dejaVu(): ByteArray = File("../composegl-lwjgl3/src/test/resources/fonts/DejaVuSans.ttf").readBytes()
}
