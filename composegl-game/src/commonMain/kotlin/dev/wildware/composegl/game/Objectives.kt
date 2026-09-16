package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Animatable
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.FloatVectoriser
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.wait
import dev.wildware.composegl.ui.backend.Haptic
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadHandler
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.LocalUiSounds
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.Spacer
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.drawInFront
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onShortcutGamepad
import dev.wildware.composegl.ui.modifier.onShortcutKey
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.saveable.rememberSaveable
import dev.wildware.composegl.ui.skin.LocalSkin
import dev.wildware.composegl.ui.skin.flatColour
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.text.LocalLocale
import dev.wildware.composegl.ui.text.LocalStrings
import dev.wildware.composegl.ui.widget.DisableSelection
import dev.wildware.composegl.ui.widget.LocalHaptics
import dev.wildware.composegl.ui.widget.ProvideContentStyle
import dev.wildware.composegl.ui.widget.Text

/**
 * How far along a step is: two wolves of the five the quest asked for.
 *
 * Counts rather than a fraction, because "3 / 5" is what the player is shown and a fraction cannot
 * be turned back into it. [fraction] is there for anything that wants the bar rather than the words.
 *
 * @param have how many are done. Larger than [need] is clamped when it is read, since a game that
 *   counted a sixth wolf is not a game that should crash.
 * @param need how many are wanted.
 */
class ObjectiveProgress(val have: Int, val need: Int) {

    init {
        require(have >= 0) { "a step cannot be minus done, was $have" }
        require(need > 0) { "a step that needs nothing is a step with no counter, was $need" }
    }

    /** Nought to one, for a bar or a tween. */
    val fraction: Float get() = (have.toFloat() / need).coerceIn(0f, 1f)

    /** Whether the counter itself says the step is finished. */
    val isComplete: Boolean get() = have >= need

    override fun equals(other: Any?): Boolean =
        this === other || (other is ObjectiveProgress && have == other.have && need == other.need)

    override fun hashCode(): Int = have * 31 + need

    override fun toString(): String = "$have / $need"
}

/**
 * Where one quest's lines are declared, inside [ObjectiveTracker]'s block. See [title] and [step].
 *
 * Declared rather than composed, for the reason [WorldMarkerScope] is: the block runs into a list
 * only when something it reads changes, so a tracker sitting on a HUD costs nothing per frame and a
 * step that has not changed keeps the node — and the half-finished animation — it already had.
 */
interface ObjectiveScope {

    /**
     * The quest's name, written above its steps. Called once; a second call replaces the first.
     *
     * @param style a skin style for this one name — a main quest drawn differently from a side one.
     *   Null takes the tracker's `"<style>.title"`.
     */
    fun title(text: String, style: String? = null)

    /**
     * One thing to do. Called once per step, in the order they should be read.
     *
     * @param text what the player has to do, in their own language.
     * @param done whether it has been done. Turning this from false to true is what plays the
     *   tick, the strike-through and the slide away; a step that is **already** done the first time
     *   it is declared is history and is not drawn at all, so a loaded save does not replay its
     *   own quest log. `keepCompleted` on the tracker keeps done steps on the list instead.
     * @param progress how far along it is, drawn as "3 / 5" beside the words. Null for a step that
     *   is simply done or not done.
     * @param key what this step is, so that it keeps its node while the list around it changes.
     *   Null uses [text], which is right until two steps of one quest read the same. One key per
     *   step: a repeated key is one step here, and only the first of them is drawn.
     * @param style a skin style for this one step. Null takes the tracker's `"<style>.step"`, and
     *   `"<style>.step.done"` once it is finished. A step with a style of its own keeps it when it
     *   is finished, taking `"<its style>.done"` where the skin has one and the tracker's
     *   `"<style>.step.done"` where it has not.
     */
    fun step(
        text: String,
        done: Boolean = false,
        progress: ObjectiveProgress? = null,
        key: Any? = null,
        style: String? = null,
    )
}

/**
 * The pinned list of what the player is meant to be doing, in the corner of the HUD.
 *
 * ```kotlin
 * val notices = rememberNotifications()
 *
 * ObjectiveTracker(
 *     quests = tracked,
 *     modifier = Modifier.align(Alignment.TopEnd).padding(20f),
 *     visible = !inCutscene,
 *     notify = notices,
 * ) { quest ->
 *     title(quest.name)
 *     quest.steps.forEach { step(it.text, done = it.done, progress = it.progress) }
 * }
 * ```
 *
 * **Finishing a step is the animation that matters.** A step whose `done` turns true draws its
 * tick, has a line struck through it, waits long enough to be read, and slides away with the rest
 * of the list closing up over it. That is the whole point of a tracker over a list of strings: the
 * player finds out something happened without looking away from the fight.
 *
 * **New quests slide in, and can say so out loud.** Given a [notify] queue, a quest that arrives
 * raises "New objective" and a quest whose last step is finished raises "Objective complete" — the
 * ordinary [Notifications] toast, in the corner the game already puts them in. The quests that are
 * there when the tracker first appears say nothing, so loading a save is not five toasts.
 *
 * **It is a readout, not a control.** It takes no turn in the focus order and swallows no click, so
 * a tracker over a fight can never be the thing that ate the button press. The one exception is the
 * "+2 more" row, which is a real control and is still only focusable when [focusable] says so; a
 * pad reaches it then, and [expandKey] and [expandButton] reach it from anywhere. Even those two
 * take the press only while there is really something folded away — a tracker showing everything it
 * has does not quietly eat the key the rest of the time.
 *
 * **It hides on request.** `visible = false` fades the whole thing away for a cutscene, a
 * photo mode or a map screen, and once it has gone it composes nothing, draws nothing and asks for
 * no frames. So does an empty list.
 *
 * @param quests what the player is tracking, in the order they should be read.
 * @param keyOf what a quest *is*, so a row keeps its node and its animations while the list around
 *   it changes. The quest itself by default, which is right when it is a data class or an object
 *   held for the quest's lifetime, and wrong when a new instance is built every frame. One key per
 *   quest: two quests that answer it the same are one quest here, and only the first is drawn.
 * @param maxVisible how many quests are on the list at once. The rest are folded behind a "+2 more"
 *   row the player opens. A HUD with eight quests on it is unreadable long before it is slow. A
 *   folded quest is not drawn at all, so a step finished behind the fold comes up un-ticked and
 *   plays its tick when the player opens it rather than to nobody.
 * @param visible false to fade it away — a cutscene, a cinematic, a map screen.
 * @param notify the toast queue new and finished objectives are announced through, or null for a
 *   tracker that announces nothing.
 * @param keepCompleted true to leave finished steps on the list, struck through, instead of sliding
 *   them away. For a quest log rather than a HUD.
 * @param focusable true to let the "+2 more" row take a turn in the focus order, for a game whose
 *   HUD is reached by a pad. Off by default: a tracker that focus stops on during a fight is worse
 *   than one a player has to click.
 * @param expandKey a key that folds the extra quests in and out from anywhere, or null for none. It
 *   is claimed only while there is a fold row to open; the rest of the time the press is the game's.
 * @param expandButton the same on a pad.
 * @param width how wide the list is, in interface pixels. Zero is as wide as its longest line;
 *   a width is what puts every counter in a column down the end.
 * @param slide how far a row travels as it arrives, in interface pixels. Positive comes in from the
 *   side the language ends on — the right in English, the left in Arabic.
 * @param style the skin style of the panel. `"<style>.title"` is a quest's name, `"<style>.step"` a
 *   line under it and `"<style>.step.done"` a finished one, `"<style>.bullet"` the little box beside
 *   a step with `"<style>.tick"` the mark drawn in it, `"<style>.count"` the "3 / 5" and
 *   `"<style>.more"` the fold row.
 * @param clock which clock the animations and the pause between the strike-through and the slide
 *   run on. The interface's by default, so a step finished as the game pauses still finishes.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod")
@Composable
fun <T> ObjectiveTracker(
    quests: List<T>,
    modifier: Modifier = Modifier,
    keyOf: (T) -> Any = { it as Any },
    maxVisible: Int = 3,
    visible: Boolean = true,
    notify: NotificationQueue? = null,
    keepCompleted: Boolean = false,
    focusable: Boolean = false,
    expandKey: Key? = null,
    expandButton: GamepadButton? = null,
    width: Float = 0f,
    slide: Float = 24f,
    style: String = "objective",
    clock: Clock = Clock.Ui,
    content: ObjectiveScope.(T) -> Unit,
) {
    require(maxVisible > 0) { "a tracker shows at least one quest, not $maxVisible" }

    val tracker = remember { ObjectiveList() }
    tracker.sync(rememberQuestDeclarations(quests, keyOf, content), keepCompleted)

    // Said after the composition that noticed it rather than during it: raising a toast is a change
    // to somebody else's queue, and composition is not where a widget changes the world.
    val announcements = rememberAnnouncements()
    SideEffect { tracker.announce(notify, announcements) }

    // The fade, and everything that depends on it, before the early return below — a tracker that
    // let go of its own animation while it was hidden would never come back.
    val clocks = LocalClocks.current
    val fade = remember(clocks, clock) { Animatable(if (visible) 1f else 0f, FloatVectoriser, clock, clocks) }
    LaunchedEffect(visible) {
        // Only when it has somewhere to go: a tracker that was already visible must not ask for a
        // frame on the composition that first drew it.
        val target = if (visible) 1f else 0f
        if (fade.value != target) fade.animateTo(target, Tween(FadeMillis, easing = Easings.EaseInOut))
    }

    var expanded by rememberSaveable { mutableStateOf(false) }
    val folded = tracker.rows.size - maxVisible
    // Nothing folded away any more, because a quest was dropped or the limit went up. The fold row
    // goes, and the state behind it with it: a list that grows again folds shut rather than
    // springing open on a tap the player made three quests ago.
    LaunchedEffect(folded > 0) { if (folded <= 0) expanded = false }

    // Whether there is anything to unfold, read when the key is pressed rather than when the handler
    // was made: the list grows and shrinks under it. With nothing folded away the key is not ours —
    // it does not touch `expanded`, which would otherwise stay set with no fold row to reset it, and
    // it is not claimed, so the game still gets the press.
    val foldable = rememberUpdatedState(folded > 0)
    val keys = remember(expandKey) {
        KeyHandler { event ->
            val hit = expandKey != null && foldable.value &&
                event.key == expandKey && event.type == KeyEventType.Down && !event.repeat
            if (hit) expanded = !expanded
            hit
        }
    }
    val buttons = remember(expandButton) {
        GamepadHandler { event ->
            val hit = expandButton != null && foldable.value &&
                event is GamepadEvent.ButtonDown && event.button == expandButton
            if (hit) expanded = !expanded
            hit
        }
    }

    // Nothing at all while it is hidden or empty: no rows composed, no animation running, no frame
    // asked for. The state above still keeps up, so a step finished behind a cutscene is finished.
    if (tracker.rows.isEmpty() || (!visible && fade.value <= 0f)) {
        // With nothing composed there is no row to run a way out, so a quest the game dropped
        // behind the cutscene would still be waiting to slide away when the cutscene ended. It goes
        // now instead: the player never sees a quest they finished an hour ago being waved off.
        tracker.dropLeaving()
        return
    }

    val shown = if (expanded || folded <= 0) tracker.rows else tracker.rows.take(maxVisible)

    var list = modifier.alpha(fade.value)
    if (width > 0f) list = list.width(width)
    if (expandKey != null) list = list.onShortcutKey(keys)
    if (expandButton != null) list = list.onShortcutGamepad(buttons)

    Column(list.styled(style), verticalArrangement = Arrangement.spacedBy(QuestGap)) {
        // A HUD's own words are not selectable: a game that wrapped its screen in a
        // SelectionContainer so a seed could be copied must not lose the shot fired through its
        // objective list. Hotbar, Notifications and Subtitles all do the same.
        DisableSelection {
            shown.forEach { row ->
                key(row) { Quest(tracker, row, slide, width, keepCompleted, style, clock) }
            }
        }
        if (folded > 0) {
            FoldRow(folded, expanded, focusable, style) { expanded = !expanded }
        }
    }
}

/** One quest: its name, and the steps still worth reading. */
@Suppress("LongParameterList")
@Composable
private fun Quest(
    tracker: ObjectiveList,
    row: QuestRow,
    slide: Float,
    width: Float,
    keepCompleted: Boolean,
    style: String,
    clock: Clock,
) {
    val clocks = LocalClocks.current
    val show = remember(clocks, clock) { Animatable(if (row.entering) 0f else 1f, FloatVectoriser, clock, clocks) }

    // The way in and the way out, in one effect so that one cannot be left half done by the other.
    // Retiring is what takes a quest off the list, so a quest handed back while it was still sliding
    // away slides in again from wherever it had got to rather than sticking there.
    LaunchedEffect(row.leaving) {
        row.entering = false
        if (row.leaving) {
            show.animateTo(0f, Tween(LeaveMillis, easing = Easings.EaseIn))
            tracker.retire(row)
        } else if (show.value != 1f) {
            show.animateTo(1f, Tween(EnterMillis, easing = Easings.EaseOut))
        }
    }

    val travel = slide * (1f - show.value) * endwards()
    Shrinking(show.value) {
        Column(
            Modifier.offset(x = travel).alpha(show.value),
            verticalArrangement = Arrangement.spacedBy(StepGap),
        ) {
            row.title?.let { Text(it, style = row.titleStyle ?: "$style.title") }
            row.steps.forEach { step ->
                key(step) { Step(row, step, slide, width, keepCompleted, style, clock) }
            }
        }
    }
}

/**
 * One step: the box, the words, the counter — and the little ceremony when it is finished.
 *
 * Tick, then the line through the words, then a pause long enough to read it, then away. In that
 * order and on the tracker's clock, so a step finished as a pause menu opens finishes behind it
 * rather than disappearing while nobody is looking.
 */
@Suppress("LongParameterList")
@Composable
private fun Step(
    quest: QuestRow,
    row: StepRow,
    slide: Float,
    width: Float,
    keepCompleted: Boolean,
    style: String,
    clock: Clock,
) {
    val clocks = LocalClocks.current
    val show = remember(clocks, clock) { Animatable(if (row.entering) 0f else 1f, FloatVectoriser, clock, clocks) }
    // Ticked and struck from the start only if that already happened where somebody could see it.
    // A step finished while its quest was folded away — or behind a cutscene — has never been
    // composed, so it comes up bare and plays the whole ceremony the moment the player opens it.
    val mark = remember(clocks, clock) { Animatable(if (row.ticked) 1f else 0f, FloatVectoriser, clock, clocks) }
    val strike = remember(clocks, clock) { Animatable(if (row.ticked) 1f else 0f, FloatVectoriser, clock, clocks) }

    // In and out in one effect, for the reason a quest's are one: a step handed back while it was
    // sliding away comes back rather than being left at whatever height it had reached.
    LaunchedEffect(row.leaving) {
        row.entering = false
        if (row.leaving) {
            show.animateTo(0f, Tween(LeaveMillis, easing = Easings.EaseIn))
            quest.retire(row)
        } else if (show.value != 1f) {
            show.animateTo(1f, Tween(EnterMillis, easing = Easings.EaseOut))
        }
    }

    LaunchedEffect(row.done) {
        if (!row.done) {
            // Un-finished: a quest step a game took back, or a save loaded over a finished one. The
            // slide away below may already have started, so the row comes back up first and undoes
            // its mark afterwards — unless the game is taking the whole step away regardless.
            row.ticked = false
            if (show.value != 1f && !row.leaving) show.animateTo(1f, Tween(EnterMillis, easing = Easings.EaseOut))
            if (mark.value != 0f) mark.animateTo(0f, Tween(TickMillis, easing = Easings.EaseIn))
            if (strike.value != 0f) strike.animateTo(0f, Tween(StrikeMillis, easing = Easings.EaseIn))
            return@LaunchedEffect
        }
        // Already marked and already struck: a step a `keepCompleted` list has been showing since
        // it opened. Nothing to play, and nothing to ask a frame for.
        if (keepCompleted && mark.value == 1f && strike.value == 1f) return@LaunchedEffect
        mark.animateTo(1f, Tween(TickMillis, easing = Easings.Overshoot))
        strike.animateTo(1f, Tween(StrikeMillis, easing = Easings.EaseOut))
        // Played, and played to somebody: a `keepCompleted` row hidden and shown again does not run
        // it a second time.
        row.ticked = true
        if (keepCompleted) return@LaunchedEffect
        clocks.wait(clock, ReadMillis)
        show.animateTo(0f, Tween(LeaveMillis, easing = Easings.EaseIn))
        quest.retire(row)
    }

    val travel = slide * (1f - show.value) * endwards()
    // A step's own style covers it finished as well as unfinished, where the skin has a finished
    // version of it: a line styled as a main quest should not turn into an ordinary one the instant
    // it is done, the way a quest's own title style never does. Otherwise the tracker's own.
    val skin = LocalSkin.current
    val words = if (row.done) {
        row.style?.let { "$it.done" }?.takeIf { skin.has(it) } ?: "$style.step.done"
    } else {
        row.style ?: "$style.step"
    }
    val ink = rememberStyle(words).textColour
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val line = remember(ink, strike.value, rtl) { struckThrough(ink, strike.value, rtl) }

    Shrinking(show.value) {
        Row(
            Modifier.offset(x = travel).alpha(show.value).then(if (width > 0f) Modifier.fillMaxWidth() else Modifier),
            horizontalArrangement = Arrangement.spacedBy(BulletGap),
            verticalAlignment = VerticalAlignment.Centre,
        ) {
            Bullet(mark.value, style)
            Text(row.text, Modifier.drawInFront(line), style = words)
            row.progress?.let { progress ->
                if (width > 0f) Spacer(Modifier.weight(1f))
                Counter(progress, style, clock)
            }
        }
    }
}

/** The little box beside a step, with the tick drawn into it as the step is finished. */
@Composable
private fun Bullet(drawn: Float, style: String) {
    val colour = rememberStyle("$style.tick").let { it.background.flatColour ?: it.textColour }
    val mark = remember(colour, drawn) { tick(colour, drawn) }
    Box(Modifier.size(BulletSize).styled("$style.bullet").drawInFront(mark))
}

/** "3 / 5", with a small jump the moment the number changes, because that is the news. */
@Composable
private fun Counter(progress: ObjectiveProgress, style: String, clock: Clock) {
    val strings = LocalStrings.current
    val locale = LocalLocale.current
    val text = strings.get(locale, ProgressKey, progress.have, progress.need)
        .takeIf { it != ProgressKey } ?: "${progress.have} / ${progress.need}"

    val clocks = LocalClocks.current
    val pop = remember(clocks, clock) { Animatable(1f, FloatVectoriser, clock, clocks) }
    var counted by remember { mutableStateOf(progress.have) }
    LaunchedEffect(progress.have) {
        if (progress.have == counted) return@LaunchedEffect
        counted = progress.have
        pop.animateTo(PopScale, Tween(PopUpMillis, easing = Easings.EaseOut))
        pop.animateTo(1f, Tween(PopDownMillis, easing = Easings.EaseIn))
    }

    // Scaled only while it is actually moving: scaling draws through an offscreen picture, and a
    // counter standing still should cost what any other two words cost.
    val jumped = if (pop.value == 1f) Modifier else Modifier.scale(pop.value)
    Text(text, jumped, style = "$style.count")
}

/** "+2 more", and "Show fewer" once it is open. The one control on the whole tracker. */
@Composable
private fun FoldRow(folded: Int, expanded: Boolean, focusable: Boolean, style: String, onToggle: () -> Unit) {
    val strings = LocalStrings.current
    val locale = LocalLocale.current
    val text = if (expanded) {
        strings.get(locale, FewerKey).takeIf { it != FewerKey } ?: "Show fewer"
    } else {
        strings.get(locale, MoreKey, folded).takeIf { it != MoreKey } ?: "+$folded more"
    }

    val sounds = LocalUiSounds.current
    val haptics = LocalHaptics.current
    val interaction = remember { InteractionState() }
    val states = rememberStates(interaction)
    val resolved = rememberStyle("$style.more", states)
    val toggle = remember(sounds, haptics, onToggle) {
        {
            sounds.change()
            haptics.perform(Haptic.LightTap)
            onToggle()
        }
    }

    Box(
        Modifier
            .interaction(interaction)
            .then(if (focusable) Modifier.focusable(interaction) else Modifier)
            .clickable(onClick = toggle)
            .styled("$style.more", states),
    ) {
        ProvideContentStyle(resolved) { DisableSelection { Text(text) } }
    }
}

/**
 * One row, cut off from the bottom as [fraction] goes to nothing, so the rows below close up over it.
 *
 * The same trick a `CollapsingHeader`'s body uses: the child is measured at its real size and the
 * row simply says it wants less of it, so a step slides away rather than being squashed on the way
 * out. Clipped only while it is moving, since a clip costs something and a row at rest needs none.
 */
@Composable
private fun Shrinking(fraction: Float, content: @Composable () -> Unit) {
    val policy = remember(fraction) { ShrinkPolicy(fraction) }
    Layout(
        modifier = if (fraction < 1f) Modifier.clip() else Modifier,
        name = "objective.row",
        content = content,
        measurePolicy = policy,
    )
}

/** As tall as [fraction] of what is inside it, which is laid out at its own full height regardless. */
private class ShrinkPolicy(private val fraction: Float) : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        if (measurables.isEmpty()) {
            return layout(constraints.constrainWidth(0f), constraints.constrainHeight(0f)) {}
        }
        val child = measurables[0].measure(constraints.copy(minHeight = 0f, maxHeight = Float.POSITIVE_INFINITY))
        val width = constraints.constrainWidth(child.width)
        val height = constraints.constrainHeight(child.height * fraction.coerceIn(0f, 1f))
        // Right to left puts the row's own contents against the other edge, exactly as a folding
        // body does: the box is the child's size, so this only matters when something above made it
        // wider.
        val x = if (layoutDirection == LayoutDirection.Rtl) width - child.width else 0f
        return layout(width, height) { child.at(x, 0f) }
    }
}

// --- the list behind it ---------------------------------------------------------------------------

/**
 * What is on the tracker, which is not quite what the game declared.
 *
 * The difference is the whole widget: a step the game has finished is still on the list until its
 * tick, its line and its slide have been seen, and a quest the game has dropped is still on the
 * list until it has gone. So the rows are kept here, synced against the declarations rather than
 * rebuilt from them, and retired by the animations themselves.
 */
@Stable
private class ObjectiveList {

    val rows = mutableStateListOf<QuestRow>()

    /** What is waiting to be said through a [NotificationQueue], drained by a `SideEffect`. */
    private val pending = ArrayList<Pair<Boolean, String>>()

    /** False until the first sync has been through, which is what makes a loaded save silent. */
    private var started = false

    fun sync(declared: List<QuestDeclaration>, keepCompleted: Boolean) {
        val order = ArrayList<QuestRow>(declared.size + rows.size)
        val live = HashSet<Any>(declared.size)

        declared.forEach { quest ->
            // Two quests that answer `keyOf` the same are one quest as far as the list is concerned:
            // they would share a row, be put on the list twice and then be composed twice under the
            // same key. The first one declared wins, which at least leaves a list that is drawable.
            if (!live.add(quest.key)) return@forEach
            val row = rows.firstOrNull { it.key == quest.key } ?: QuestRow(quest.key).also {
                it.entering = started
                if (started) pending += true to quest.title.orEmpty()
            }
            row.leaving = false
            row.title = quest.title
            row.titleStyle = quest.titleStyle
            row.sync(quest.steps, keepCompleted)
            // A quest whose last step is done is finished, said once. It goes back to unsaid if a
            // step comes back, so a game that takes a step away does not lose its next announcement.
            val complete = quest.steps.isNotEmpty() && quest.steps.all { it.done }
            if (complete && !row.announced && started) pending += false to quest.title.orEmpty()
            row.announced = complete
            order += row
        }

        // The rows on their way out keep the place they had, so nothing jumps while one slides away.
        rows.forEachIndexed { index, row ->
            if (row.key in live) return@forEachIndexed
            row.leaving = true
            order.add(index.coerceAtMost(order.size), row)
        }

        started = true
        // Written only when it really changed: sync runs on every composition, and a list rebuilt
        // into itself would recompose everything on it for nothing.
        if (differs(order, rows)) {
            rows.clear()
            rows.addAll(order)
        }
    }

    /** Takes a quest off once it has finished sliding away, unless the game asked for it back. */
    fun retire(row: QuestRow) {
        if (row.leaving) rows.remove(row)
    }

    /**
     * Takes off everything on its way out at once, for when there is nobody composed to watch it go.
     *
     * A hidden tracker composes no rows, so nothing runs the animation that would normally retire
     * one. Without this a quest dropped behind a cutscene waits, and is waved off in front of the
     * player the moment the cutscene ends.
     */
    fun dropLeaving() {
        // Only when there is really something to drop: this runs from composition, and writing to a
        // list the same composition read would ask for a recomposition every frame.
        if (rows.any { it.leaving }) rows.removeAll { it.leaving }
        rows.forEach { it.dropLeaving() }
    }

    /** Says what the last sync noticed. Called from a `SideEffect`, never from composition. */
    fun announce(queue: NotificationQueue?, words: Announcements) {
        if (pending.isEmpty()) return
        pending.forEach { (isNew, title) ->
            queue?.show(if (isNew) words.added else words.completed, title.takeIf { it.isNotEmpty() })
        }
        pending.clear()
    }
}

/** One quest on the list, with the steps still worth drawing. */
@Stable
private class QuestRow(val key: Any) {

    var title by mutableStateOf<String?>(null)
    var titleStyle by mutableStateOf<String?>(null)
    var leaving by mutableStateOf(false)

    val steps = mutableStateListOf<StepRow>()

    /** True until it has slid in. Not state: it is read once, when its row is first composed. */
    var entering = true

    /** Whether "objective complete" has already been said for this one. */
    var announced = false

    /** Steps that have been finished and have gone. Kept so the next sync does not bring them back. */
    private val retired = HashSet<Any>()

    /** False until this quest's first sync, which is what makes its opening steps static. */
    private var started = false

    fun sync(declared: List<StepDeclaration>, keepCompleted: Boolean) {
        val order = ArrayList<StepRow>(declared.size + steps.size)
        val live = HashSet<Any>(declared.size)

        declared.forEach { step ->
            // One row per key, for the reason a quest gets one: a repeated key is two lines that
            // would share a row and be composed twice under the same key. The first one wins.
            if (!live.add(step.key)) return@forEach
            if (step.done && step.key in retired) return@forEach
            // A step un-finished by the game comes back, which is the only way out of `retired`.
            if (!step.done) retired -= step.key
            val row = steps.firstOrNull { it.key == step.key } ?: StepRow(step.key).also {
                // A step that is already done the first time it is seen is history rather than news:
                // it is not drawn, and it does not play a tick nobody was waiting for.
                if (step.done && !keepCompleted) {
                    retired += step.key
                    return@forEach
                }
                // The one a `keepCompleted` list opens with is history too, so it comes up ticked
                // and struck rather than playing a ceremony for something done before it opened.
                it.ticked = step.done
                // A step slides in on its own only once the quest around it is already standing
                // still. The ones a quest opens with ride in under its title instead — or, at the
                // tracker's very first sync, do not move at all, because neither does the title.
                it.entering = started
            }
            row.leaving = false
            row.text = step.text
            row.progress = step.progress
            row.style = step.style
            row.done = step.done
            order += row
        }

        steps.forEachIndexed { index, row ->
            if (row.key in live) return@forEachIndexed
            row.leaving = true
            order.add(index.coerceAtMost(order.size), row)
        }

        started = true
        if (differs(order, steps)) {
            steps.clear()
            steps.addAll(order)
        }
    }

    /** Takes a step off once its own little ceremony is over. */
    fun retire(row: StepRow) {
        if (steps.remove(row)) retired += row.key
    }

    /** Takes off every step on its way out at once, for when there is nobody composed to see them go. */
    fun dropLeaving() {
        if (steps.any { it.leaving }) steps.removeAll { it.leaving }
    }
}

/** One step on the list. Everything drawn from it is state, so changing one redraws that row alone. */
@Stable
private class StepRow(val key: Any) {

    var text by mutableStateOf("")
    var progress by mutableStateOf<ObjectiveProgress?>(null)
    var style by mutableStateOf<String?>(null)
    var done by mutableStateOf(false)
    var leaving by mutableStateOf(false)

    /** True until it has slid in. Not state, for the reason [QuestRow.entering] is not. */
    var entering = true

    /**
     * Whether the tick and the line have already been played where somebody could see them.
     *
     * The difference between a step that is finished and one that has been *seen* to finish. A step
     * finished while its quest was folded away, or behind a cutscene, was never composed, so nothing
     * played: it starts bare and plays the whole thing when the player opens it. Not state, for the
     * reason [entering] is not — it is read once, when the row is composed.
     */
    var ticked = false
}

/** Whether two lists hold the same rows in the same order, by identity: a row is itself or it is new. */
private fun <T> differs(next: List<T>, current: List<T>): Boolean =
    next.size != current.size || next.indices.any { next[it] !== current[it] }

/** One quest as the game declared it. Made when the block runs, never per frame. */
private class QuestDeclaration(
    val key: Any,
    val title: String?,
    val titleStyle: String?,
    val steps: List<StepDeclaration>,
)

/** One step as the game declared it. */
private class StepDeclaration(
    val key: Any,
    val text: String,
    val done: Boolean,
    val progress: ObjectiveProgress?,
    val style: String?,
)

/** The scope itself: it collects declarations and does nothing else. */
private class QuestDeclarations(val key: Any) : ObjectiveScope {

    private var name: String? = null
    private var nameStyle: String? = null
    private val steps = ArrayList<StepDeclaration>()

    override fun title(text: String, style: String?) {
        name = text
        nameStyle = style
    }

    override fun step(text: String, done: Boolean, progress: ObjectiveProgress?, key: Any?, style: String?) {
        steps += StepDeclaration(key ?: text, text, done, progress, style)
    }

    fun build(): QuestDeclaration = QuestDeclaration(key, name, nameStyle, steps)
}

/**
 * The block, run into a fresh list of declarations only when something it reads changes.
 *
 * The same reason `WorldMarkerLayer` does it: a tracker on a HUD is composed alongside everything
 * else on the screen, and a block re-run on every composition would build a new declaration for
 * every step of every quest each time the health bar moved.
 */
@Composable
private fun <T> rememberQuestDeclarations(
    quests: List<T>,
    keyOf: (T) -> Any,
    content: ObjectiveScope.(T) -> Unit,
): List<QuestDeclaration> {
    val latest = rememberUpdatedState(quests)
    val latestKey = rememberUpdatedState(keyOf)
    val latestContent = rememberUpdatedState(content)
    val declared by remember {
        derivedStateOf {
            latest.value.map { quest ->
                QuestDeclarations(latestKey.value(quest)).apply { latestContent.value(this, quest) }.build()
            }
        }
    }
    return declared
}

/** The two things a tracker says out loud, in the player's language. */
private class Announcements(val added: String, val completed: String)

@Composable
private fun rememberAnnouncements(): Announcements {
    val strings = LocalStrings.current
    val locale = LocalLocale.current
    return remember(strings, locale) {
        fun of(key: String, english: String) = strings.get(locale, key).takeIf { it != key } ?: english
        Announcements(of(AddedKey, "New objective"), of(CompletedKey, "Objective complete"))
    }
}

// --- drawing ---------------------------------------------------------------------------------------

/** Which way a row travels as it arrives: in from the side the language ends on. */
@Composable
private fun endwards(): Float = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f

/**
 * The line through a finished step, drawn out from the side the words start on.
 *
 * In the words' own colour rather than a colour of its own: a line struck through a sentence is
 * part of the sentence, and a skin that dims a finished step dims the line with it.
 */
private fun struckThrough(colour: Colour, fraction: Float, rtl: Boolean): UiCanvas.(Rect) -> Unit = { box ->
    if (fraction > 0f) {
        val length = box.width * fraction.coerceAtMost(1f)
        val middle = (box.top + box.bottom) / 2f
        val thickness = maxOf(1f, box.height * StrikeThickness)
        val left = if (rtl) box.right - length else box.left
        rect(Rect(left, middle - thickness / 2f, left + length, middle + thickness / 2f), colour)
    }
}

/**
 * The checkmark, drawn stroke by stroke as [fraction] runs from nothing to one.
 *
 * Drawn rather than written, so a tick needs no glyph in the game's font — and a game whose font
 * is a bitmap of the Latin alphabet still gets one.
 */
private fun tick(colour: Colour, fraction: Float): UiCanvas.(Rect) -> Unit = { box ->
    if (fraction > 0f) {
        val corner = Offset(box.left + box.width * 0.26f, box.top + box.height * 0.52f)
        val bottom = Offset(box.left + box.width * 0.44f, box.top + box.height * 0.74f)
        val top = Offset(box.left + box.width * 0.78f, box.top + box.height * 0.28f)
        val thickness = maxOf(1.5f, box.height * TickThickness)
        val short = (fraction / TickSplit).coerceIn(0f, 1f)
        line(corner, towards(corner, bottom, short), thickness, colour)
        if (fraction > TickSplit) {
            val long = ((fraction - TickSplit) / (1f - TickSplit)).coerceIn(0f, 1f)
            line(bottom, towards(bottom, top, long), thickness, colour)
        }
    }
}

/** [fraction] of the way from [from] to [to]. */
private fun towards(from: Offset, to: Offset, fraction: Float): Offset =
    Offset(from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction)

// --- the numbers -----------------------------------------------------------------------------------

/** The gap between two quests on the list, and between a quest's name and its steps. */
private const val QuestGap = 10f

private const val StepGap = 4f

/** Between the little box and the words beside it. */
private const val BulletGap = 8f

private const val BulletSize = 12f

private const val EnterMillis = 220

private const val LeaveMillis = 180

private const val TickMillis = 200

private const val StrikeMillis = 200

/** How long a struck-through step stays up before it slides away: long enough to read it. */
private const val ReadMillis = 700

private const val FadeMillis = 220

private const val PopUpMillis = 90

private const val PopDownMillis = 150

/** How much bigger a counter goes the moment its number changes. */
private const val PopScale = 1.28f

/** How much of the tick's time the short stroke takes. */
private const val TickSplit = 0.38f

private const val TickThickness = 0.14f

private const val StrikeThickness = 0.06f

private const val AddedKey = "objective.added"

private const val CompletedKey = "objective.completed"

private const val ProgressKey = "objective.progress"

private const val MoreKey = "objective.more"

private const val FewerKey = "objective.fewer"
