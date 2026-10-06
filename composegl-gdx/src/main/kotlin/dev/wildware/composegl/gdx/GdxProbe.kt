package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import dev.wildware.composegl.render.gl.Gl
import dev.wildware.composegl.render.gl.ProbeGl
import dev.wildware.composegl.render.gl.probeReport
import dev.wildware.composegl.ui.host.RenderProbe
import java.io.File
import java.lang.management.ManagementFactory

/**
 * PROFILING ONLY (#242, #267). Two switches, used one at a time:
 *
 * - `-Dcomposegl.probe=<file>`: every GdxCanvas draws through one shared [ProbeGl], which counts
 *   what each frame asks of GL.
 * - `-Dcomposegl.timing=<file>`: nothing is wrapped; the game thread's CPU time and the bytes it
 *   allocates are read once a frame, so a frame is everything the thread did from one frame's first
 *   canvas to the next's: the game, the toolkit and the driver calls.
 *
 * Frames are told apart by LibGDX's frame number, and each is labelled with the
 * `composegl.probe.phase` system property as it stood when the frame ended. The report for a phase
 * is written when the phase changes and when the JVM exits.
 */
internal object GdxProbe {
    private val file: File? = System.getProperty("composegl.probe")?.let(::File)
    private val timingFile: File? = System.getProperty("composegl.timing")?.let(::File)
    private val probe: ProbeGl? = file?.let { ProbeGl(GdxGl) }
    private var frameId = -1L
    private var phase = ""
    private var from = 0

    val gl: Gl get() = probe ?: GdxGl

    private var attributed = 0

    // --- timing ---
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private var cpuAt = 0L
    private var bytesAt = 0L
    private var wallAt = 0L
    private val cpu = LongArray(100_000)
    private val bytes = LongArray(100_000)
    private val wall = LongArray(100_000)
    private val phases = arrayOfNulls<String>(100_000)
    private val uiCpu = LongArray(100_000)
    private val uiBytes = LongArray(100_000)

    // What UiRenderer.render took this frame, summed over every call: the toolkit's own share.
    private var depth = 0
    private var uiCpuAt = 0L
    private var uiBytesAt = 0L
    private var uiCpuSum = 0L
    private var uiBytesSum = 0L
    private var timed = 0
    private var timedFrom = 0

    init {
        if (timingFile != null) {
            RenderProbe.hook = object : RenderProbe.Hook {
                override fun begin() {
                    if (depth++ == 0) {
                        uiCpuAt = threads.currentThreadCpuTime
                        uiBytesAt = threads.currentThreadAllocatedBytes
                    }
                }

                override fun end() {
                    if (--depth == 0) {
                        uiCpuSum += threads.currentThreadCpuTime - uiCpuAt
                        uiBytesSum += threads.currentThreadAllocatedBytes - uiBytesAt
                    }
                }
            }
        }
        if (probe != null || timingFile != null) {
            Runtime.getRuntime().addShutdownHook(Thread { synchronized(this) { flush(); flushTiming() } })
        }
        if (probe != null) {
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
        if (probe == null && timingFile == null) return
        val graphics = Gdx.graphics ?: return
        val id = graphics.frameId
        if (frameId == -1L) {
            frameId = id
            if (timingFile != null) {
                cpuAt = threads.currentThreadCpuTime
                bytesAt = threads.currentThreadAllocatedBytes
                wallAt = System.nanoTime()
            }
            return
        }
        if (id == frameId) return
        frameId = id
        val now = System.getProperty("composegl.probe.phase") ?: ""
        if (timingFile != null) {
            val c = threads.currentThreadCpuTime
            val b = threads.currentThreadAllocatedBytes
            val w = System.nanoTime()
            if (timed < cpu.size) {
                cpu[timed] = c - cpuAt
                bytes[timed] = b - bytesAt
                wall[timed] = w - wallAt
                uiCpu[timed] = uiCpuSum
                uiBytes[timed] = uiBytesSum
                phases[timed] = phase
                timed++
            }
            uiCpuSum = 0L
            uiBytesSum = 0L
            cpuAt = c
            bytesAt = b
            wallAt = w
            if (now != phase) {
                flushTiming()
                phase = now
            }
            return
        }
        val probe = probe!!
        probe.endFrame(graphics.backBufferWidth.toLong() * graphics.backBufferHeight, phase)
        val f = probe.frames.last()
        val csv = File(file!!.path + ".csv")
        if (!csv.exists()) csv.writeText("frame,phase,draws,calls,drawsOffscreen,picturesDrawn,pictureArea,framebuffersMade,hostPixels,offscreenPixels,vertexBytes\n")
        csv.appendText("${probe.frames.size},${f.phase},${f.draws},${f.calls},${f.drawsOffscreen},${f.picturesDrawn},${f.pictureArea},${f.framebuffersMade},${f.hostPixels},${f.offscreenPixels},${f.vertexBytes}\n")
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
        if (file != null && probe.shaderTexts.isNotEmpty()) {
            val dir = File(file.path + ".shaders").also { it.mkdirs() }
            probe.shaderTexts.forEachIndexed { i, text -> File(dir, "$i.glsl").writeText(text) }
        }
    }

    private fun flushTiming() {
        val out = timingFile ?: return
        val indices = (timedFrom until timed).filter { phases[it] == phase }
        val csv = File(out.path + ".csv")
        if (!csv.exists()) csv.writeText("phase,cpu_ns,alloc_bytes,wall_ns,ui_cpu_ns,ui_alloc_bytes\n")
        csv.appendText((timedFrom until timed).joinToString("") { "${phases[it]},${cpu[it]},${bytes[it]},${wall[it]},${uiCpu[it]},${uiBytes[it]}\n" })
        timedFrom = timed
        if (phase.isEmpty() || indices.isEmpty()) return
        // The first quarter of a phase is it settling in.
        val settled = indices.drop(indices.size / 4)
        out.appendText(timingReport(phase, settled.map { cpu[it] }, settled.map { bytes[it] }, settled.map { wall[it] }))
        out.appendText(timingReport("$phase (UiRenderer.render only)", settled.map { uiCpu[it] }, settled.map { uiBytes[it] }, settled.map { 0L }))
    }
}

/** PROFILING ONLY (#267): per-frame CPU, allocation and wall time summed up. */
internal fun timingReport(label: String, cpu: List<Long>, bytes: List<Long>, wall: List<Long>): String {
    fun stats(values: List<Long>, scale: Double, unit: String): String {
        val sorted = values.sorted()
        val mean = values.average() / scale
        fun q(p: Double) = sorted[((sorted.size - 1) * p).toInt()] / scale
        return "mean %.3f median %.3f p90 %.3f max %.3f %s".format(mean, q(0.5), q(0.9), q(1.0), unit)
    }
    return buildString {
        appendLine("== $label: ${cpu.size} frames")
        appendLine("thread cpu: ${stats(cpu, 1e6, "ms")}")
        appendLine("allocated: ${stats(bytes, 1024.0, "KB")}")
        appendLine("wall: ${stats(wall, 1e6, "ms")}")
    }
}
