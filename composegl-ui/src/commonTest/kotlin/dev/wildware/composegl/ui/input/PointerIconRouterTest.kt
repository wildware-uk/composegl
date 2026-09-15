package dev.wildware.composegl.ui.input

import dev.wildware.composegl.ui.backend.Clipboard
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.backend.RecordingSystemCursor
import dev.wildware.composegl.ui.backend.SoftKeyboard
import dev.wildware.composegl.ui.backend.SystemCursor
import dev.wildware.composegl.ui.backend.TextureSource
import dev.wildware.composegl.ui.backend.UiBackend
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.hitShape
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.pointerHoverIcon
import dev.wildware.composegl.ui.modifier.resolve
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.testing.TestTree
import dev.wildware.composegl.ui.text.FontProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * How the router picks a cursor shape, on rectangles this test put where it wanted them.
 *
 * The composed-screen version of these questions is [PointerHoverIconTest]; this one pins the
 * corners that are awkward to build out of widgets — hit shapes, scale, invisibility, pointers
 * that are not mice — and the modifier and backend plumbing underneath.
 */
class PointerIconRouterTest {

    private val screen = TestTree()
    private val cursor = RecordingSystemCursor()
    private val router = PointerRouter(screen.root, cursor = cursor)

    init {
        screen.root.width = 200f
        screen.root.height = 200f
    }

    private fun move(x: Float, y: Float, type: PointerType = PointerType.Mouse) =
        router.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(x, y), type = type))

    @Test
    fun `the later icon on one node wins`() {
        val resolved = Modifier.pointerHoverIcon(PointerIcon.Hand).pointerHoverIcon(PointerIcon.Text).resolve()

        assertEquals(PointerIcon.Text, resolved.hoverIcon)
    }

    @Test
    fun `an icon makes a node findable and nothing else`() {
        val resolved = Modifier.pointerHoverIcon(PointerIcon.Hand).resolve()

        assertTrue(resolved.isInteractive, "a node the pointer cannot find could never show its icon")
        assertNull(resolved.click)
        assertTrue(resolved.handlers.isEmpty())
        assertNull(Modifier.resolve().hoverIcon, "nothing asked, nothing shown")
    }

    @Test
    fun `a press through an icon-only node still reaches the button under it`() {
        var clicks = 0
        screen.box("button", 0f, 0f, 100f, 100f, Modifier.clickable { clicks++ })
        screen.box("sight", 0f, 0f, 100f, 100f, Modifier.pointerHoverIcon(PointerIcon.Crosshair))

        move(50f, 50f)
        assertTrue(router.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(50f, 50f))))
        router.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(50f, 50f)))

        assertEquals(1, clicks)
        assertEquals(PointerIcon.Crosshair, cursor.icon)
    }

    @Test
    fun `a hit shape turns down its icon over the corner it does not own`() {
        val leftHalf = { p: Offset -> p.x < 50f }
        screen.box("half", 0f, 0f, 100f, 100f, Modifier.pointerHoverIcon(PointerIcon.Hand).hitShape(leftHalf))

        move(25f, 50f)
        assertEquals(PointerIcon.Hand, cursor.icon)

        move(75f, 50f)
        assertEquals(PointerIcon.Default, cursor.icon, "inside the rectangle but outside the shape")
    }

    @Test
    fun `a child does not borrow an icon from a parent whose shape turned the point down`() {
        val leftHalf = { p: Offset -> p.x < 50f }
        val parent = screen.box(
            "parent", 0f, 0f, 100f, 100f,
            Modifier.pointerHoverIcon(PointerIcon.Hand).hitShape(leftHalf),
        )
        screen.box("child", 60f, 0f, 40f, 40f, Modifier.interaction(InteractionState()), parent = parent)
        screen.box("near", 10f, 0f, 30f, 40f, Modifier.interaction(InteractionState()), parent = parent)

        move(80f, 20f)
        assertEquals(PointerIcon.Default, cursor.icon, "the parent does not own this point")

        move(20f, 20f)
        assertEquals(PointerIcon.Hand, cursor.icon, "it does own this one, so its child inherits")
    }

    @Test
    fun `a shrunk node shows its icon where it is drawn and not where it was laid out`() {
        screen.box("small", 0f, 0f, 100f, 100f, Modifier.scale(0.5f).pointerHoverIcon(PointerIcon.Move))

        move(10f, 10f)
        assertEquals(PointerIcon.Default, cursor.icon, "the corner is empty once it is drawn at half size")

        move(50f, 50f)
        assertEquals(PointerIcon.Move, cursor.icon)
    }

    @Test
    fun `an invisible node asks for nothing`() {
        screen.box("ghost", 0f, 0f, 100f, 100f, Modifier.alpha(0f).pointerHoverIcon(PointerIcon.NotAllowed))

        move(50f, 50f)

        assertTrue(cursor.requests.isEmpty())
    }

    @Test
    fun `a cancel puts the arrow back`() {
        screen.box("edge", 0f, 0f, 100f, 100f, Modifier.clickable {}.pointerHoverIcon(PointerIcon.ResizeVertical))

        move(50f, 50f)
        router.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(50f, 50f)))
        router.onPointer(PointerEvent.Cancel(PointerId.Mouse, Offset(50f, 50f)))

        assertEquals(listOf(PointerIcon.ResizeVertical, PointerIcon.Default), cursor.requests)
    }

    @Test
    fun `a ray in the world never touches the desktop cursor`() {
        screen.box("panel", 0f, 0f, 100f, 100f, Modifier.pointerHoverIcon(PointerIcon.Hand))

        move(50f, 50f, PointerType.Ray)
        router.onPointer(PointerEvent.Exit(PointerId.Mouse, Offset(50f, 50f), type = PointerType.Ray))

        assertTrue(cursor.requests.isEmpty())
    }

    @Test
    fun `a router with no cursor still says what the shape would be`() {
        val quiet = PointerRouter(screen.root)
        screen.box("panel", 0f, 0f, 100f, 100f, Modifier.pointerHoverIcon(PointerIcon.Hand))

        quiet.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(50f, 50f)))

        assertEquals(PointerIcon.Hand, quiet.pointerIcon, "what a game drawing its own cursor reads")
    }

    @Test
    fun `a backend that never heard of cursors still has one that does nothing`() {
        val headless = HeadlessBackend()
        val older = object : UiBackend {
            override val canvas: UiCanvas get() = headless.canvas
            override val fonts: FontProvider get() = headless.fonts
            override val clipboard: Clipboard get() = headless.clipboard
            override val softKeyboard: SoftKeyboard get() = headless.softKeyboard
            override val textures: TextureSource get() = headless.textures
        }

        assertSame(SystemCursor.None, older.cursor)
        older.cursor.set(PointerIcon.Text)
        val recording = headless.cursor
        assertTrue(recording is RecordingSystemCursor, "the headless one remembers, for tests")
        assertFalse(recording.requests.isNotEmpty(), "and nothing has asked it for anything yet")
    }
}
