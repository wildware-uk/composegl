package dev.wildware.composegl.kool.demo

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.pipeline.backend.gl.RenderBackendGl
import org.lwjgl.glfw.GLFW

/**
 * The Kool demo: `./gradlew :composegl-demo-kool:run`.
 *
 * A Kool world of three cubes, with a ComposeGL panel on top: hover and click the button, and the panel's
 * scene view changes colour.
 */
fun main() {
    initGlfwOnX11()
    KoolApplication(
        KoolConfigJvm(
            // The ComposeGL frontend draws with OpenGL; Kool picks Vulkan by itself where it can.
            renderBackend = RenderBackendGl,
            windowTitle = "ComposeGL on Kool",
            windowSize = Vec2i(KoolDemo.Width, KoolDemo.Height),
        ),
    ) {
        KoolDemo.start(ctx)
    }
}

/**
 * Kool 0.19.0 asks GLFW for Wayland whenever the library supports it and does not fall back when there
 * is no Wayland display, which is every X11 desktop and Xvfb. GLFW started on X11 first stays on X11.
 * The stack size is Kool's own, set before anything touches LWJGL, as Kool does.
 */
fun initGlfwOnX11() {
    if (System.getenv("WAYLAND_DISPLAY") != null || !System.getProperty("os.name").orEmpty().startsWith("Linux")) return
    org.lwjgl.system.Configuration.STACK_SIZE.set(128)
    GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11)
    check(GLFW.glfwInit()) { "glfwInit failed on X11 (DISPLAY=${System.getenv("DISPLAY")})" }
}
