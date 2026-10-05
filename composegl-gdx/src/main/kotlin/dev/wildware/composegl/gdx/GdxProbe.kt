package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import dev.wildware.composegl.render.gl.Gl
import dev.wildware.composegl.render.gl.ProbeGl
import dev.wildware.composegl.render.gl.probeReport
import java.io.File

/**
 * PROFILING ONLY (#242). With `-Dcomposegl.probe=<file>`, every GdxCanvas draws through one shared
 * [ProbeGl], frames are told apart by LibGDX's frame number, and each is labelled with the
 * `composegl.probe.phase` system property as it stood when the frame ended. The report for a
 * phase is written when the phase changes and when the JVM exits.
 */
internal object GdxProbe {
    private val file: File? = System.getProperty("composegl.probe")?.let(::File)
    private val probe: ProbeGl? = file?.let { ProbeGl(GdxGl) }
    private var frameId = -1L
    private var phase = ""
    private var from = 0

    val gl: Gl get() = probe ?: GdxGl

    init {
        if (probe != null) Runtime.getRuntime().addShutdownHook(Thread { synchronized(this) { flush() } })
    }

    @Synchronized
    fun frame() {
        val probe = probe ?: return
        val graphics = Gdx.graphics ?: return
        val id = graphics.frameId
        if (frameId == -1L) frameId = id
        if (id == frameId) return
        frameId = id
        val now = System.getProperty("composegl.probe.phase") ?: ""
        probe.endFrame(graphics.backBufferWidth.toLong() * graphics.backBufferHeight, phase)
        if (now != phase) {
            flush()
            phase = now
        }
    }

    private fun flush() {
        val probe = probe ?: return
        val list = probe.frames.subList(from, probe.frames.size).filter { it.phase == phase }
        from = probe.frames.size
        if (phase.isEmpty() || list.isEmpty()) return
        // The first quarter of a phase is it settling in.
        val settled = list.drop(list.size / 4)
        file?.appendText(probeReport(phase, settled) + "\n")
    }
}
