package dev.wildware.composegl.ui.input

import dev.wildware.composegl.ui.layout.Viewport

/**
 * One window's input, handed out to the players sharing it.
 *
 * Split-screen is several interfaces in one window — a `UiHost` each, with its own focus, its own
 * menus and its own prompts — and a backend that knows none of that. It pushes every pad, the
 * keyboard and the mouse into one sink. This is that sink, and it sends each event on to the player
 * whose device it came from, so player two's stick never moves player one's focus.
 *
 * ```kotlin
 * val router = InputRouter(unclaimed = lobby)       // pads nobody owns yet: "press Start to join"
 * router.assignGamepad(GamepadId(0), playerOne)
 * router.assignGamepad(GamepadId(1), playerTwo)
 * router.assignKeyboard(playerOne)
 * viewports.forEachIndexed { i, viewport -> router.assignPointer(viewport, players[i]) }
 *
 * GdxGamepadInput(router).start()
 * GdxPointerInput(router, { Viewport.oneToOne(screen) })   // the router wants window pixels
 * ```
 *
 * Three kinds of device, three rules:
 *
 * - **Pads go by number.** Every [GamepadEvent] carries its [GamepadId], so there is nothing to
 *   guess. A pad changing hands is told it was let go of first — a [GamepadEvent.Disconnected] to
 *   its old owner — so a stick held while the pads were being handed out does not keep scrolling
 *   the menu it left. A pad that is really unplugged keeps its owner: the backends give a pad back
 *   the lowest free number, so plugging it back in returns it to the same player.
 * - **The keyboard goes to one player**, because there is one of it. Text goes with the keys.
 * - **The pointer goes by where it is.** Each player's [Viewport] says which part of the window is
 *   theirs, and an event there is handed over in that player's own coordinates. A press keeps the
 *   pointer until it is released, so a slider dragged past the middle of the screen stays with the
 *   player who grabbed it; and moving from one half into the other ends the hover in the first.
 *
 * Whatever belongs to nobody — a pad not assigned, keys with no keyboard player, a click on the
 * divider — goes to [unclaimed], in the window's own coordinates, or is refused when that is null.
 *
 * Players are [InputSink]s rather than hosts, because a player's sink is what a game already has:
 * the routers and navigators behind one [SourceAware] (see the Input wiki page). Give each player
 * their own [InputSourceTracker] and their prompts follow their own device, so the player on the
 * keyboard reads **E** while the one on the pad reads **A**.
 *
 * Not thread-safe, like the rest of input: call it from wherever the backend delivers events.
 */
class InputRouter(var unclaimed: InputSink? = null) : InputSink {

    private val pads = mutableMapOf<GamepadId, InputSink>()
    private var keyboard: InputSink? = null

    /** Pointer areas, in the order they were assigned. A later one wins where two overlap. */
    private val areas = mutableListOf<Area>()

    /** Which area a pressed pointer belongs to until it is released. */
    private val captured = mutableMapOf<PointerId, Area>()

    /** Which area each pointer was last over, so leaving it can be said. */
    private val hovering = mutableMapOf<PointerId, Area>()

    private class Area(val viewport: Viewport, val sink: InputSink)

    // --- handing out devices -----------------------------------------------------------------------

    /**
     * Gives [gamepad] to [player]. A pad that belonged to somebody else is let go of there first.
     */
    fun assignGamepad(gamepad: GamepadId, player: InputSink) {
        val previous = pads[gamepad]
        if (previous === player) return
        previous?.onGamepad(GamepadEvent.Disconnected(gamepad))
        pads[gamepad] = player
    }

    /** Takes [gamepad] back from whoever had it, letting go of anything it held. */
    fun unassignGamepad(gamepad: GamepadId) {
        pads.remove(gamepad)?.onGamepad(GamepadEvent.Disconnected(gamepad))
    }

    /** Who [gamepad] belongs to, or null for nobody. What a join screen asks. */
    fun ownerOf(gamepad: GamepadId): InputSink? = pads[gamepad]

    /** Every pad [player] holds, lowest number first. */
    fun gamepadsOf(player: InputSink): List<GamepadId> =
        pads.filterValues { it === player }.keys.sortedBy { it.value }

    /** Keys and text go to [player] from now on; null sends them to [unclaimed]. */
    fun assignKeyboard(player: InputSink?) {
        keyboard = player
    }

    /**
     * The part of the window [viewport] covers belongs to [player]'s pointer.
     *
     * The same viewport the player's interface is laid out and drawn with, so the area a click is
     * routed by and the area the buttons are drawn in cannot disagree. Assigning the same player
     * again replaces their area, which is what a window resize does.
     */
    fun assignPointer(viewport: Viewport, player: InputSink) {
        areas.firstOrNull { it.sink === player }?.let { old -> forget(old) }
        areas += Area(viewport, player)
    }

    /**
     * [player] has left: every device they held goes back to nobody, and whatever they had held
     * down is let go of first.
     */
    fun unassign(player: InputSink) {
        gamepadsOf(player).forEach { unassignGamepad(it) }
        if (keyboard === player) keyboard = null
        areas.filter { it.sink === player }.forEach { forget(it) }
    }

    // --- the sink ------------------------------------------------------------------------------------

    override fun onGamepad(event: GamepadEvent): Boolean {
        val owner = pads[event.gamepadId] ?: return unclaimed?.onGamepad(event) ?: false
        return owner.onGamepad(event)
    }

    override fun onKey(event: KeyEvent): Boolean = (keyboard ?: unclaimed)?.onKey(event) ?: false

    override fun onText(event: TextEvent): Boolean = (keyboard ?: unclaimed)?.onText(event) ?: false

    override fun onPointer(event: PointerEvent): Boolean {
        val id = event.pointerId
        val held = captured[id]
        return when (event) {
            is PointerEvent.Press -> {
                val area = held ?: under(event)
                // Moved into this area without a Move first — a finger landing — still ends the
                // hover wherever the pointer was.
                enter(id, area, event)
                if (area == null) return unclaimed?.onPointer(event) ?: false
                captured[id] = area
                send(area, event)
            }
            is PointerEvent.Move -> {
                if (held != null) return send(held, event)
                val area = under(event)
                enter(id, area, event)
                if (area == null) unclaimed?.onPointer(event) ?: false else send(area, event)
            }
            is PointerEvent.Release -> {
                if (held == null) return route(under(event), event)
                captured.remove(id)
                send(held, event)
            }
            is PointerEvent.Cancel -> {
                captured.remove(id)
                route(held ?: hovering[id], event)
            }
            is PointerEvent.Scroll -> route(held ?: under(event), event)
            is PointerEvent.Exit -> {
                val was = hovering.remove(id)
                route(held ?: was, event)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** The area under the event, the last assigned winning. */
    private fun under(event: PointerEvent): Area? = areas.lastOrNull { event.position in it.viewport.area }

    /** Hovering [area] now: the one the pointer was over before hears it left. */
    private fun enter(id: PointerId, area: Area?, event: PointerEvent) {
        val was = hovering[id]
        if (was === area) return
        if (was != null) {
            send(was, PointerEvent.Exit(id, event.position, event.type, event.timeMillis))
        }
        if (area == null) hovering.remove(id) else hovering[id] = area
    }

    private fun route(area: Area?, event: PointerEvent): Boolean =
        if (area == null) unclaimed?.onPointer(event) ?: false else send(area, event)

    /** Hands [event] to [area]'s player, in that player's own coordinates. */
    private fun send(area: Area, event: PointerEvent): Boolean =
        area.sink.onPointer(event.movedTo(area.viewport.toDesign(event.position)))

    /**
     * Drops an area, cancelling a press it held and ending a hover, so a player's buttons are not
     * left pressed or lit when their half of the screen is taken away.
     */
    private fun forget(area: Area) {
        areas.remove(area)
        captured.entries.filter { it.value === area }.forEach { (id, _) ->
            captured.remove(id)
            send(area, PointerEvent.Cancel(id, area.viewport.area.centre))
        }
        hovering.entries.filter { it.value === area }.forEach { (id, _) ->
            hovering.remove(id)
            send(area, PointerEvent.Exit(id, area.viewport.area.centre))
        }
    }
}
