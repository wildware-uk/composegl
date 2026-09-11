package dev.wildware.composegl.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Animatable
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.FloatVectoriser
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.wait
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.widget.Text

/**
 * One thing the game wants to tell the player about: a quest step, an item picked up, a trophy.
 *
 * Identity is the object, not the text, so two identical pickups are two notifications rather than
 * one that will not go away.
 *
 * @param text the line the player reads.
 * @param detail a smaller second line, for the part that is nice to know rather than the point.
 * @param style a skin style for this one, if the game draws achievements differently from pickups.
 *   Null takes the queue's own.
 */
class Notification(
    val text: String,
    val detail: String? = null,
    val style: String? = null,
) {

    /** Set when it is on the way out, by its hold running out or by the player dismissing it. */
    internal var leaving by mutableStateOf(false)
}

/**
 * The queue of things waiting to be said, and the few that are being said now.
 *
 * A queue rather than a list on purpose. Games hand out notifications in bursts — a chest with
 * twenty things in it, a quest that finishes four steps at once — and a widget that simply shows
 * everything it is given covers the screen at exactly the moment the player needs to see it. So
 * [capacity] are on screen and the rest wait their turn, with [waiting] saying how many, which is
 * the honest thing to draw instead of the twentieth notification.
 *
 * Nothing here costs anything while it is empty: with nothing on screen there is no card to
 * compose, no animation to run and no frame to ask for.
 *
 * ```kotlin
 * val notices = rememberNotifications()
 * Notifications(notices, Modifier.align(Alignment.TopEnd).padding(20f))
 * // when something happens:
 * notices.show("Quest updated", "Find the relay")
 * ```
 *
 * @param capacity how many are on screen at once.
 * @param holdMillis how long one stays up once it has arrived, before it leaves by itself.
 * @param backlog how many may wait behind the ones on screen. Past this the **oldest** waiting one
 *   is dropped: a player who set off twenty pickups wants the last few, not the first few, and a
 *   queue that plays all of them is a queue still playing a minute later.
 * @param clock which clock the holds and the animations run on. The interface's by default, so
 *   notifications still arrive and leave over a paused world.
 */
@Stable
class NotificationQueue(
    val capacity: Int = 3,
    val holdMillis: Int = 2_600,
    val backlog: Int = 32,
    val clock: Clock = Clock.Ui,
) {

    private val onScreen = mutableStateListOf<Notification>()
    private val queued = mutableStateListOf<Notification>()

    /** What is up now, oldest first. */
    val shown: List<Notification> get() = onScreen

    /** How many are waiting their turn. What a "+7 more" line is drawn from. */
    val waiting: Int get() = queued.size

    /** Nothing on screen and nothing waiting, which is when this costs nothing at all. */
    val isIdle: Boolean get() = onScreen.isEmpty() && queued.isEmpty()

    /** Says something. It is shown now if there is room and queued behind the others if not. */
    fun show(text: String, detail: String? = null, style: String? = null): Notification =
        show(Notification(text, detail, style))

    fun show(notification: Notification): Notification {
        if (onScreen.size < capacity) {
            onScreen.add(notification)
        } else {
            queued.add(notification)
            while (queued.size > backlog) queued.removeAt(0)
        }
        return notification
    }

    /**
     * Takes one away early — a click on it, or the game deciding it is no longer true.
     *
     * It leaves the way it would have left anyway, and the next one waiting takes its place as
     * soon as it has gone, so dismissing is how a player gets through a burst quickly.
     */
    fun dismiss(notification: Notification) {
        notification.leaving = true
    }

    /** Everything gone at once: a screen change, a death, a new game. */
    fun clear() {
        onScreen.clear()
        queued.clear()
    }

    /** Called by a card once it has finished sliding away. */
    internal fun retire(notification: Notification) {
        if (!onScreen.remove(notification)) return
        if (queued.isNotEmpty() && onScreen.size < capacity) onScreen.add(queued.removeAt(0))
    }
}

/** A queue that lives as long as the screen it is on. */
@Composable
fun rememberNotifications(
    capacity: Int = 3,
    holdMillis: Int = 2_600,
    backlog: Int = 32,
    clock: Clock = Clock.Ui,
): NotificationQueue = remember(capacity, holdMillis, backlog, clock) {
    NotificationQueue(capacity, holdMillis, backlog, clock)
}

/**
 * Draws [queue]: the few on screen, stacked, and a line saying how many are still waiting.
 *
 * Where it goes is the game's — this is a column, and a game puts it in the corner it wants.
 *
 * Each card slides in from the side it is stacked towards, holds, and slides away; clicking one
 * takes it away early and lets the next in. With nothing to show it draws nothing and asks for no
 * frames.
 *
 * @param style the skin style for a card. `"<style>.detail"` is the second line and
 *   `"<style>.more"` is the "and N more" line, both falling back to [style].
 * @param slide how far a card travels as it arrives, in interface pixels. Positive comes in from
 *   the right, negative from the left.
 */
@Composable
fun Notifications(
    queue: NotificationQueue,
    modifier: Modifier = Modifier,
    style: String = "notification",
    slide: Float = 28f,
    width: Float = 0f,
    dismissOnClick: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8f)) {
        queue.shown.forEach { notification ->
            key(notification) {
                NotificationCard(queue, notification, notification.style ?: style, slide, width, dismissOnClick)
            }
        }

        // The honest thing to draw instead of the twentieth notification.
        if (queue.waiting > 0) {
            val more = Modifier.styled("$style.more")
            Text("and ${queue.waiting} more", if (width > 0f) more.width(width) else more, style = "$style.more")
        }
    }
}

/** One card: arrives, holds, leaves. Its own animation, because each one has its own timing. */
@Composable
private fun NotificationCard(
    queue: NotificationQueue,
    notification: Notification,
    style: String,
    slide: Float,
    width: Float,
    dismissOnClick: Boolean,
) {
    val clocks = LocalClocks.current
    val show = remember(clocks, queue.clock) { Animatable(0f, FloatVectoriser, queue.clock, clocks) }

    // Arrive, stay up for as long as the queue says, then start leaving. The wait is the clock's,
    // so a queue on Clock.World holds behind a pause menu instead of running out behind it.
    LaunchedEffect(notification) {
        show.animateTo(1f, Tween(EnterMillis, easing = Easings.EaseOut))
        clocks.wait(queue.clock, queue.holdMillis)
        queue.dismiss(notification)
    }

    // And the way out, whether the hold ran out or the player clicked it. Retiring is what lets
    // the next one waiting in, so a player clicking through a burst gets through it quickly.
    LaunchedEffect(notification, notification.leaving) {
        if (!notification.leaving) return@LaunchedEffect
        show.animateTo(0f, Tween(LeaveMillis, easing = Easings.EaseIn))
        queue.retire(notification)
    }

    val travel = slide * (1f - show.value)
    var card = Modifier
        .offset(x = travel)
        .alpha(show.value)
        .styled(style)
    if (width > 0f) card = card.width(width)
    if (dismissOnClick) card = card.clickable { queue.dismiss(notification) }

    Column(card, horizontalAlignment = HorizontalAlignment.Start) {
        Text(notification.text, style = style)
        notification.detail?.let { Text(it, style = "$style.detail") }
    }
}

private const val EnterMillis = 180

private const val LeaveMillis = 160
