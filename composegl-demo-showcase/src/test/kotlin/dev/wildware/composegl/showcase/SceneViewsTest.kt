package dev.wildware.composegl.showcase

import androidx.compose.runtime.remember
import dev.wildware.composegl.debug.DebugWindowHost
import dev.wildware.composegl.debug.MemoryDebugWindowStore
import dev.wildware.composegl.debug.DebugWindowsState
import dev.wildware.composegl.debug.rememberDebugWindowsState
import dev.wildware.composegl.debug.rememberDevConsole
import dev.wildware.composegl.showcase.ui.GroupTagPrefix
import dev.wildware.composegl.showcase.ui.ModuleSections
import dev.wildware.composegl.showcase.ui.PictureInPicture
import dev.wildware.composegl.showcase.ui.SceneDockTag
import dev.wildware.composegl.showcase.ui.SceneEditorTag
import dev.wildware.composegl.showcase.ui.SceneGroupTitle
import dev.wildware.composegl.showcase.ui.SceneLiveTag
import dev.wildware.composegl.showcase.ui.ScenePipTag
import dev.wildware.composegl.showcase.ui.ScenePipToggleTag
import dev.wildware.composegl.showcase.ui.SceneWindow
import dev.wildware.composegl.showcase.ui.SceneWindowTitle
import dev.wildware.composegl.showcase.ui.SceneWindowToggleTag
import dev.wildware.composegl.showcase.ui.SectionScrollTag
import dev.wildware.composegl.showcase.ui.scenePreviewTag
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.DragAndDropHost
import dev.wildware.composegl.ui.widget.TooltipHost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The composegl-ui section's scene views: the three cases `SceneView` is for, each a view into the
 * showcase's own world, driven with a mouse, a keyboard and a pad.
 *
 * No GPU here. The canvas writes each scene render down, so what is asserted is what the design
 * promises: the editor redraws when its camera moves and while it follows the fight, the one
 * preview that is turning is the only one that costs a frame, and the picture in picture follows
 * whichever drone it was told to.
 */
class SceneViewsTest {

    /** What each view asked the world to draw, in order, so a test can see the camera it was given. */
    private class Filmed : WorldViews {
        val shots = mutableListOf<String>()
        override fun orbit(frame: Any, width: Int, height: Int, yaw: Float, pitch: Float, distance: Float) {
            shots += "orbit"
        }
        override fun model(frame: Any, width: Int, height: Int, drone: Int, turn: Float) {
            shots += "model $drone"
        }
        override fun chase(frame: Any, width: Int, height: Int, drone: Int) {
            shots += "chase $drone"
        }
    }

    private fun state() = ShowcaseState().apply {
        listOf("RAVEN-2", "KITE-7", "MOTH-1").forEach { targets.add(TargetReadout(it)) }
        holdFire = true
        tuningOpen = false
        section = Module.Ui
    }

    private fun screen(
        state: ShowcaseState,
        direction: LayoutDirection = LayoutDirection.Ltr,
        windows: (DebugWindowsState) -> Unit = {},
    ): UiTest = uiTest {
        ProvideLayoutDirection(direction) {
            // A store of its own, in memory: the default one is a file, and a window docked by one
            // test would still be docked in the next, and in the showcase the next time it runs.
            val host = rememberDebugWindowsState(remember { MemoryDebugWindowStore() })
            windows(host)
            DebugWindowHost(state = host) {
                DragAndDropHost {
                    TooltipHost {
                        Box(Modifier.fillMaxSize()) {
                            ModuleSections(state, FrameBudget(), host, rememberDevConsole {}, null, Filmed())
                            PictureInPicture(state, Filmed())
                        }
                        SceneWindow(state, Filmed())
                    }
                }
            }
        }
    }

    private val UiTest.canvas get() = backend.canvas as RecordingCanvas

    private fun UiTest.openGroup() {
        click(GroupTagPrefix + SceneGroupTitle)
    }

    /** Scrolls the section with the wheel until [tag] is wholly inside it, as a person would. */
    private fun UiTest.reveal(tag: String) {
        repeat(20) {
            val column = node(SectionScrollTag).boundsInRoot
            val box = node(tag).boundsInRoot
            if (box.top >= column.top && box.bottom <= column.bottom) return
            scroll(SectionScrollTag, Offset(0f, if (box.bottom > column.bottom) 1f else -1f))
        }
        error("could not scroll $tag into view:\n" + dump())
    }

    @Test
    fun `the section offers the three views and the window opens from it`() {
        val state = state()
        screen(state).use { ui ->
            ui.openGroup()
            ui.assertExists(SceneWindowToggleTag)
            ui.assertExists(ScenePipToggleTag)
            ui.assertExists(scenePreviewTag(0))
            ui.assertDoesNotExist(SceneEditorTag)
            ui.reveal(SceneWindowToggleTag)

            ui.click(SceneWindowToggleTag)

            assertTrue(state.views.editorOpen, "the switch opens the scene window")
            ui.assertExists(SceneEditorTag)
            ui.render()
            assertEquals(1L, state.views.editor.draws, "the window's view is drawn once it has a size:\n" + ui.dump())
            assertTrue(state.views.editor.width > 0 && state.views.editor.height > 0)
        }
    }

    @Test
    fun `dragging the editor orbits its camera and the wheel zooms it`() {
        val state = state().apply { views.editorOpen = true; views.editorLive = false }
        screen(state).use { ui ->
            ui.render()
            val drawn = state.views.editor.draws
            val yaw = state.views.yaw
            val box = ui.node(SceneEditorTag).boundsInRoot

            ui.press(box.centre)
            ui.dragTo(box.centre + Offset(60f, 20f))
            ui.release()

            assertNotEquals(yaw, state.views.yaw, "a drag turns the camera")
            assertTrue(state.views.editor.dirty, "and asks for the view again")
            ui.render()
            assertEquals(drawn + 1, state.views.editor.draws)

            val distance = state.views.distance
            ui.scroll(SceneEditorTag, Offset(0f, -1f))
            assertTrue(state.views.distance < distance, "a notch towards the screen moves the camera in")
        }
    }

    @Test
    fun `a still editor costs nothing until the fight is followed`() {
        val state = state().apply { views.editorOpen = true; views.editorLive = false }
        screen(state).use { ui ->
            ui.render()
            val drawn = state.views.editor.draws
            repeat(3) {
                state.views.frame(1f / 60f)
                ui.render()
            }
            assertEquals(drawn, state.views.editor.draws, "nothing moved the camera, so nothing is drawn")

            ui.click(SceneLiveTag)
            assertTrue(state.views.editorLive)
            repeat(3) {
                state.views.frame(1f / 60f)
                ui.render()
            }
            assertEquals(drawn + 3, state.views.editor.draws, "a view that follows the fight is drawn every frame")
        }
    }

    @Test
    fun `the keyboard turns and zooms the editor while it has focus`() {
        val state = state().apply { views.editorOpen = true; views.editorLive = false }
        screen(state).use { ui ->
            ui.click(SceneEditorTag)
            ui.assertFocused(SceneEditorTag)

            val yaw = state.views.yaw
            ui.key(Key.Right)
            assertTrue(state.views.yaw > yaw, "Right turns the camera")
            ui.assertFocused(SceneEditorTag)

            val pitch = state.views.pitch
            ui.key(Key.Up)
            assertTrue(state.views.pitch > pitch, "Up tips it")

            val distance = state.views.distance
            ui.key(Key.Equals)
            assertTrue(state.views.distance < distance, "= moves it in")
            ui.key(Key.Minus)
            assertEquals(distance, state.views.distance, 0.001f, "and - back out")

            ui.key(Key.F)
            assertEquals(SceneViews.StartYaw, state.views.yaw, "F puts the camera back where it started")

            ui.key(Key.Tab)
            assertFalse(ui.focus.focused?.testTag == SceneEditorTag, "Tab still leaves it")
        }
    }

    @Test
    fun `the pad's stick flies the editor and the d-pad leaves it`() {
        val state = state().apply { views.editorOpen = true; views.editorLive = false }
        screen(state).use { ui ->
            ui.click(SceneEditorTag)
            ui.render()
            val drawn = state.views.editor.draws

            ui.stick(0.9f, 0f)
            ui.assertFocused(SceneEditorTag)
            val yaw = state.views.yaw
            state.views.frame(0.5f)
            assertTrue(state.views.yaw > yaw, "a held stick keeps turning the camera, frame by frame")
            ui.render()
            assertEquals(drawn + 1, state.views.editor.draws)

            ui.stick(0f, 0f)
            val still = state.views.yaw
            state.views.frame(0.5f)
            assertEquals(still, state.views.yaw, "a stick let go of stops it")

            val distance = state.views.distance
            ui.pad(GamepadButton.RightBumper)
            assertTrue(state.views.distance < distance, "the right bumper moves in")

            // Pitch comes from the right stick too, which a debug window keeps for moving itself:
            // the view takes it first while it has focus.
            val pitch = state.views.pitch
            ui.stick(0f, -0.9f, horizontal = GamepadAxis.RightX, vertical = GamepadAxis.RightY)
            state.views.frame(0.5f)
            assertTrue(state.views.pitch > pitch, "the right stick pushed up tips the camera up")
            ui.stick(0f, 0f, horizontal = GamepadAxis.RightX, vertical = GamepadAxis.RightY)

            ui.pad(GamepadButton.DpadUp)
            assertFalse(ui.focus.focused?.testTag == SceneEditorTag, "the d-pad still moves focus on:\n" + ui.dump())
        }
    }

    @Test
    fun `resizing the window stretches the picture and then remakes it`() {
        val state = state().apply { views.editorOpen = true; views.editorLive = false }
        screen(state).use { ui ->
            ui.render()
            val before = state.views.editor.width

            val edge = ui.node("debugwindow:$SceneWindowTitle:edge:right").boundsInRoot
            ui.press(edge.centre)
            ui.dragTo(edge.centre + Offset(120f, 0f))
            ui.release()
            val wider = ui.node(SceneEditorTag).boundsInRoot.width

            ui.render()
            assertEquals(before, state.views.editor.width, "the first frame at a new size stretches the old picture")
            ui.render()
            assertTrue(
                kotlin.math.abs(wider - state.views.editor.width) <= 1f,
                "and once the size holds it is remade to fit: $wider wide, picture ${state.views.editor.width}",
            )
        }
    }

    @Test
    fun `the window docks from the section`() {
        val state = state().apply { views.editorOpen = true }
        var windows: DebugWindowsState? = null
        screen(state, windows = { windows = it }).use { ui ->
            ui.openGroup()
            ui.reveal(SceneDockTag)
            ui.click(SceneDockTag)
            assertTrue(windows!!.isDocked(SceneWindowTitle), "the button docks the scene window")
            ui.render()
            assertTrue(state.views.editor.width > 0, "and the view inside it is still drawn")
        }
    }

    @Test
    fun `only the preview that is turning costs a frame`() {
        val state = state()
        screen(state).use { ui ->
            ui.openGroup()
            ui.reveal(scenePreviewTag(2))
            ui.render()
            val previews = state.targets.indices.map { state.views.preview(it) }
            previews.forEach { assertEquals(1L, it.draws, "every preview is drawn once:\n" + ui.dump()) }

            repeat(4) {
                state.views.frame(1f / 60f)
                ui.render()
            }
            assertEquals(listOf(5L, 1L, 1L), previews.map { it.draws }, "only the turning one is drawn again")

            // A click, Enter and the pad's South all choose which one turns.
            ui.click(scenePreviewTag(2))
            assertEquals(2, state.views.spinning)

            ui.key(Key.Tab, dev.wildware.composegl.ui.input.Modifiers.Shift)
            ui.assertFocused(scenePreviewTag(1))
            ui.key(Key.Enter)
            assertEquals(1, state.views.spinning)

            ui.key(Key.Tab, dev.wildware.composegl.ui.input.Modifiers.Shift)
            ui.pad(GamepadButton.South)
            assertEquals(0, state.views.spinning)

            state.views.frame(1f / 60f)
            ui.render()
            assertEquals(listOf(6L, 1L, 1L), previews.map { it.draws })
        }
    }

    @Test
    fun `the picture in picture follows the drone it is told to, by mouse, key or pad`() {
        val state = state()
        screen(state).use { ui ->
            ui.openGroup()
            ui.reveal(ScenePipToggleTag)
            ui.click(ScenePipToggleTag)
            assertTrue(state.views.pipOpen)
            ui.assertExists(ScenePipTag)

            ui.render()
            val drawn = state.views.pip.draws
            assertEquals(1L, drawn)
            state.views.frame(1f / 60f)
            ui.render()
            assertEquals(2L, state.views.pip.draws, "a live feed is drawn every frame")
            assertEquals(0.5f, state.views.pip.resolutionScale, "at half the panel's pixels each way")

            assertEquals(0, state.views.chasing)
            ui.click(ScenePipTag)
            assertEquals(1, state.views.chasing, "a click moves it to the next drone")
            ui.assertFocused(ScenePipTag)
            ui.key(Key.Enter)
            assertEquals(2, state.views.chasing, "Enter too")
            ui.pad(GamepadButton.South)
            assertEquals(0, state.views.chasing, "and South, wrapping round")

            state.views.pipOpen = false
            ui.settle()
            ui.assertDoesNotExist(ScenePipTag)
        }
    }

    @Test
    fun `every view lays out on the screen and draws both ways round`() {
        LayoutDirection.entries.forEach { direction ->
            val state = state().apply { views.editorOpen = true; views.pipOpen = true }
            screen(state, direction).use { ui ->
                ui.openGroup()
                ui.reveal(scenePreviewTag(0))
                listOf(SceneEditorTag, ScenePipTag, scenePreviewTag(0)).forEach { tag ->
                    val box = ui.node(tag).boundsInRoot
                    assertTrue(
                        box.width > 0f && box.height > 0f && box.left >= 0f && box.right <= ui.size.width,
                        "$tag in $direction is on the screen at $box:\n" + ui.dump(),
                    )
                }
                ui.render()
                assertTrue(ui.canvas.scenes.isNotEmpty())
            }
        }
    }
}
