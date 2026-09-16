package dev.wildware.composegl.showcase.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import dev.wildware.composegl.debug.DebugWindow
import dev.wildware.composegl.debug.DebugWindowsState
import dev.wildware.composegl.debug.DockSide
import dev.wildware.composegl.showcase.SceneViews
import dev.wildware.composegl.showcase.ShowcaseState
import dev.wildware.composegl.showcase.WorldViews
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.aspectRatio
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.SceneView
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Toggle

/** The group's title in the composegl-ui section, which is also what its header is tagged by. */
internal const val SceneGroupTitle = "Scene views"

/** The title of the window the editor's view is in, which is also what it is docked by. */
internal const val SceneWindowTitle = "Scene view"

/** The editor's `SceneView`, inside its window. */
internal const val SceneEditorTag = "showcase.ui.scene.editor"

/** The switch in the section that opens and closes the editor's window. */
internal const val SceneWindowToggleTag = "showcase.ui.scene.window"

/** The button in the section that docks the editor's window down the right-hand side. */
internal const val SceneDockTag = "showcase.ui.scene.dock"

/** The switch in the editor's window that has it follow the fight. */
internal const val SceneLiveTag = "showcase.ui.scene.live"

/** The switch in the section for the picture in picture. */
internal const val ScenePipToggleTag = "showcase.ui.scene.pip.toggle"

/** The picture in picture's `SceneView`. */
internal const val ScenePipTag = "showcase.ui.scene.pip"

/** The row of drone [index] in the list of previews. */
internal fun scenePreviewTag(index: Int) = "showcase.ui.scene.preview.$index"

/**
 * The group in the composegl-ui section with the three things `SceneView` is for: a level editor's
 * viewport, a model preview beside ordinary UI, and a picture in picture of the live world.
 *
 * The editor and the feed are not in the section — one is a debug window, the other sits over the
 * scene — so they stay where they are when the section closes. Only their switches are here. The
 * previews are, because a preview beside a list row is the case.
 */
@Composable
internal fun SceneViewsGroup(state: ShowcaseState, windows: DebugWindowsState, world: WorldViews) {
    val views = state.views
    Group(SceneGroupTitle, "The game's own world, drawn inside a panel.") {
        Labelled("Editor window") {
            Toggle(views.editorOpen, { views.editorOpen = it }, Modifier.testTag(SceneWindowToggleTag))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6f)) {
            Button(
                "Dock it right",
                {
                    views.editorOpen = true
                    windows.dockToScreen(SceneWindowTitle, DockSide.Right)
                },
                Modifier.testTag(SceneDockTag),
                style = "button.quiet",
            )
            Button("Float it", { windows.undock(SceneWindowTitle) }, enabled = windows.isDocked(SceneWindowTitle), style = "button.quiet")
        }
        Text("Drag in it to orbit, wheel to zoom. Arrows, = and - or a stick with it focused. F resets.", style = "label.dim")

        Labelled("Picture in picture") {
            Toggle(views.pipOpen, { views.pipOpen = it }, Modifier.testTag(ScenePipToggleTag))
        }
        Text("A live feed from behind a drone, at half its pixels. Click it for the next drone.", style = "label.dim")

        Text("Previews", style = "label")
        state.targets.forEachIndexed { index, target ->
            PreviewRow(views, world, index, target.callsign)
        }
        Text("Only the turning one is drawn again each frame. Pick one to turn it.", style = "label.dim")
    }
}

/**
 * One drone in the list: a small scene beside its name. The row is the button — the preview has no
 * handlers of its own, so it is not a tab stop, and a click on it lands on the row.
 */
@Composable
private fun PreviewRow(views: SceneViews, world: WorldViews, index: Int, callsign: String) {
    val turning = views.spinning == index
    Row(
        Modifier.testTag(scenePreviewTag(index))
            .fillMaxWidth()
            .background(if (turning) Colour.argb(0x404CC2FF) else Colour.argb(0x200A1018), corner = 4f)
            .padding(4f)
            .focusable()
            .clickable { views.spinning = index },
        horizontalArrangement = Arrangement.spacedBy(10f),
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        SceneView(views.preview(index), Modifier.size(56f).clip(4f)) {
            clear(PreviewGround)
            raw { frame -> world.model(frame, width, height, index, if (views.spinning == index) views.turn else 0f) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2f)) {
            Text(callsign, style = "label")
            Text(if (turning) "turning, drawn every frame" else "still, drawn once", style = "label.dim")
        }
    }
}

/**
 * The level editor's case: the whole world in a debug window you can move, resize, dock and tab.
 *
 * The view is as wide as the window and a fixed shape, so pulling an edge resizes the picture: it is
 * stretched while the edge moves and remade once it stops.
 */
@Composable
internal fun SceneWindow(state: ShowcaseState, world: WorldViews) {
    val views = state.views
    if (!views.editorOpen) return

    DebugWindow(
        SceneWindowTitle,
        // Below the picture in picture and right of a section, so the three are all in sight at once.
        initialPosition = Offset(490f, BelowMenus + 200f),
        initialSize = Size(360f, 330f),
        minSize = Size(200f, 160f),
        onClose = { views.editorOpen = false },
    ) {
        val interaction = remember { InteractionState() }
        // A stick held while focus leaves would keep the camera turning with nobody at the pad.
        val focused = interaction.isFocused
        SideEffect {
            if (!focused) {
                views.turnX = 0f
                views.turnY = 0f
                views.tipX = 0f
                views.tipY = 0f
            }
        }
        val input = remember(views) { EditorInput(views) }
        SceneView(
            views.editor,
            Modifier.testTag(SceneEditorTag).fillMaxWidth().aspectRatio(16f / 10f),
            onPointer = input::pointer,
            onKey = input::key,
            onPad = input::pad,
            interaction = interaction,
        ) {
            clear(SkyColour)
            raw { frame -> world.orbit(frame, width, height, views.yaw, views.pitch, views.distance) }
        }
        Labelled("Follow the fight") {
            Toggle(views.editorLive, { views.editorLive = it }, Modifier.testTag(SceneLiveTag))
        }
        Labelled("Half resolution") {
            Toggle(views.editorHalf, { views.editorHalf = it })
        }
    }
}

/**
 * What the editor's view does with input. It interprets nothing on the widget's behalf: every
 * position is already in the picture's pixels, and what a drag means is decided here.
 */
private class EditorInput(private val views: SceneViews) {
    private var grabbed = false
    private var last = Offset.Zero

    fun pointer(event: PointerEvent): Boolean = when (event) {
        is PointerEvent.Press -> {
            grabbed = true
            last = event.position
            true
        }
        is PointerEvent.Move -> if (!grabbed || event.pressed.isEmpty()) false else {
            val by = event.position - last
            last = event.position
            views.orbit(by.x * DegreesPerPixel, by.y * DegreesPerPixel)
            true
        }
        is PointerEvent.Release -> {
            val was = grabbed
            grabbed = false
            was
        }
        is PointerEvent.Scroll -> {
            views.zoom(-event.delta.y * MetresPerNotch)
            true
        }
        else -> false
    }

    fun key(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.Down) return event.key in Keys
        when (event.key) {
            Key.Left -> views.orbit(-KeyDegrees, 0f)
            Key.Right -> views.orbit(KeyDegrees, 0f)
            Key.Up -> views.orbit(0f, KeyDegrees)
            Key.Down -> views.orbit(0f, -KeyDegrees)
            Key.Equals -> views.zoom(MetresPerNotch)
            Key.Minus -> views.zoom(-MetresPerNotch)
            Key.F -> views.reset()
            else -> return false
        }
        return true
    }

    fun pad(event: GamepadEvent): Boolean = when (event) {
        is GamepadEvent.Axis -> when (event.axis) {
            GamepadAxis.LeftX -> { views.turnX = deadZone(event.value); true }
            GamepadAxis.LeftY -> { views.turnY = deadZone(event.value); true }
            GamepadAxis.RightX -> { views.tipX = deadZone(event.value); true }
            GamepadAxis.RightY -> { views.tipY = deadZone(event.value); true }
            else -> false
        }
        is GamepadEvent.ButtonDown -> when (event.button) {
            GamepadButton.RightBumper -> { views.zoom(MetresPerNotch); true }
            GamepadButton.LeftBumper -> { views.zoom(-MetresPerNotch); true }
            GamepadButton.West -> { views.reset(); true }
            else -> false
        }
        else -> false
    }

    private fun deadZone(value: Float) = if (value in -StickDeadZone..StickDeadZone) 0f else value

    private companion object {
        val Keys = setOf(Key.Left, Key.Right, Key.Up, Key.Down, Key.Equals, Key.Minus, Key.F)
        const val DegreesPerPixel = 0.4f
        const val KeyDegrees = 10f
        const val MetresPerNotch = 1f
        const val StickDeadZone = 0.2f
    }
}

/**
 * The picture in picture: a live feed from behind a drone, over the scene it is a view of.
 *
 * Half the panel's pixels each way, because a feed this small does not need them, and redrawn every
 * frame by the game's loop. A click, Enter or South moves it on to the next drone.
 */
@Composable
internal fun PictureInPicture(state: ShowcaseState, world: WorldViews) {
    val views = state.views
    if (!views.pipOpen) return

    Panel(
        Modifier.align(Alignment.TopEnd).padding(right = PipFromEdge, top = BelowMenus).width(PipFromEdge + PipWidth + 24f),
        style = "panel.quiet",
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6f)) {
            val callsign = state.targets.getOrNull(views.chasing)?.callsign ?: "—"
            Text("Behind $callsign", style = "label.dim")
            SceneView(
                views.pip,
                Modifier.testTag(ScenePipTag).fillMaxWidth().aspectRatio(16f / 9f).clip(6f),
                onPointer = { event ->
                    if (event is PointerEvent.Press) views.chaseNext(state.targets.size)
                    event is PointerEvent.Press || event is PointerEvent.Release
                },
                onKey = { event ->
                    val next = event.key == Key.Enter || event.key == Key.Space
                    if (next && event.type == KeyEventType.Down) views.chaseNext(state.targets.size)
                    next
                },
                onPad = { event ->
                    val south = event is GamepadEvent.ButtonDown && event.button == GamepadButton.South
                    if (south) views.chaseNext(state.targets.size)
                    south
                },
            ) {
                clear(SkyColour)
                raw { frame -> world.chase(frame, width, height, views.chasing) }
            }
        }
    }
}

/**
 * How far in from the end of the screen the picture in picture sits: clear of the frame budget
 * overlay, which has that corner while a section is open.
 */
private const val PipFromEdge = 290f

/**
 * How wide the picture in picture is, in design units. The panel's width takes in the padding that
 * places it and its own padding as well, which is why [PipFromEdge] and 24 are added to it.
 */
private const val PipWidth = 240f

/** What an empty sky is cleared to, the same as the world behind the interface. */
private val SkyColour = Colour.rgb(0x080A10)

/** What a preview is cleared to: a shade lighter than the sky, so the drone stands off it. */
private val PreviewGround = Colour.rgb(0x121A26)
