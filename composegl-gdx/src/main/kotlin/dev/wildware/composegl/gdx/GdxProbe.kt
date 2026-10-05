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

    private var attributed = 0

    init {
        if (probe != null) {
            Runtime.getRuntime().addShutdownHook(Thread { synchronized(this) { flush() } })
            probe.whoAsked = {
                Throwable().stackTrace
                    .filter { (it.className.startsWith("dev.wildware.composegl.ui") || it.className.startsWith("uk.wildware")) }
                    .take(30)
                    .joinToString("\n    ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            }
        }
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
        val f = probe.frames.last()
        val csv = File(file!!.path + ".csv")
        if (!csv.exists()) csv.writeText("frame,phase,draws,calls,drawsOffscreen,framebuffersMade,textureAllocationBytes,livePictureBytes,reloads,screenReloads,reloadPixels,hostPixels,offscreenPixels,vertexBytes,reloadTilePixels,screenReloadTilePixels,draws2,draws4,textureOnlyBreaks,checkFramebufferStatus,reloadBoxPixels,screenReloadBoxPixels,insidePixels,insideBorderPixels,handBackBreaks\n")
        csv.appendText("${probe.frames.size},${f.phase},${f.draws},${f.calls},${f.drawsOffscreen},${f.framebuffersMade},${f.textureAllocationBytes},${f.livePictureBytes},${f.reloads},${f.screenReloads},${f.reloadPixels},${f.hostPixels},${f.offscreenPixels},${f.vertexBytes},${f.reloadTilePixels},${f.screenReloadTilePixels},${f.draws2},${f.draws4},${f.breaks["texture"] ?: 0},${f.byName["checkFramebufferStatus"] ?: 0},${f.reloadBoxPixels},${f.screenReloadBoxPixels},${f.insidePixels},${f.insideBorderPixels},${f.breaks.filterKeys { it.contains("hand-back") }.values.sum()}\n")
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
        val fresh = probe.attributions.entries.drop(attributed)
        attributed = probe.attributions.size
        // The first quarter of a phase is it settling in.
        val settled = list.drop(list.size / 4)
        val who = fresh.joinToString("") { (size, stack) -> "picture $size first asked for by:\n    $stack\n" }
        file?.appendText(probeReport(phase, settled) + who + "\n")
    }
}
