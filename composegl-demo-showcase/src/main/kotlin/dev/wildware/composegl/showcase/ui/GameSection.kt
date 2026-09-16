package dev.wildware.composegl.showcase.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.composegl.game.HitKind
import dev.wildware.composegl.game.Notifications
import dev.wildware.composegl.game.ObjectiveTracker
import dev.wildware.composegl.game.SubtitleSize
import dev.wildware.composegl.game.rememberNotifications
import dev.wildware.composegl.showcase.Exhibit
import dev.wildware.composegl.showcase.Module
import dev.wildware.composegl.showcase.ShowcaseState
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Stepper
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Toggle

/** So a test can find the compass this section draws. */
internal const val GameCompassTag = "showcase.game.compass"

/** And the hold, which is the one thing here a drag has to be able to reach. */
internal const val GameHoldTag = "showcase.game.hold"

/**
 * What is in **composegl-game**: the widgets a game needs and nobody wants to write twice.
 *
 * Every control here reaches the fight going on behind the panel. "Land a hit" flashes the marker
 * in the middle of the screen and moves the objective counter with it; "take fire" draws the arc
 * that says which way it came from; the wheel switch opens the real weapon wheel over the scene;
 * and the hold, the loot cards and the upgrade board are the widgets themselves, not pictures of
 * them — drag a crate between the squares, hold Control over a drop, hold a node to buy it.
 */
@Composable
internal fun GameSection(state: ShowcaseState) {
    Group(
        "Markers over the world",
        "Where things are, drawn through the game's own camera.",
        open = true,
    ) {
        SectionCompass(state)
        Labelled("Tags on the drones") {
            Toggle(state.isOn(Exhibit.Tracking), { state.toggle(Exhibit.Tracking) })
        }
        Text(
            "WorldMarkerLayer is handed world positions, not screen ones: it projects, fades with " +
                "distance and pins a drone that has drifted off the edge with an arrow on it.",
            style = "label.dim",
        )
    }

    Group("Being shot at", "The two halves of a fight, and the ring that closes in as the hull goes.") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6f)) {
            Button("Land a hit", { state.hitMarker.hit(HitKind.Normal) }, style = "button.quiet")
            Button("Critical", { state.hitMarker.hit(HitKind.Critical) }, style = "button.quiet")
            Button("Kill", { state.hitMarker.hit(HitKind.Kill) }, style = "button.quiet")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6f)) {
            Button("Fire from the left", { state.incoming.hit(-75f, 0.8f) }, style = "button.quiet")
            Button("From behind", { state.incoming.hit(180f, 1f) }, style = "button.quiet")
        }
        Labelled("Hull") { Slider(state.hull, { state.hull = it }, length = 180f) }
        Labelled("Markers sounded") { Text("${state.hitsMarked}", style = "label") }
        Text("Below a third of a hull the vignette starts closing in on its own.", style = "label.dim")
    }

    Group("The weapon wheel", "Direction, not distance: slam the stick and let go.") {
        Labelled("Wheel up") { Toggle(state.wheelOpen, { state.wheelOpen = it }) }
        Labelled("Equipped") { Text(state.weapon, style = "label") }
        Text(
            "Hold Q or the pad's left bumper over the scene. The rifle has ammunition under it, so " +
                "pointing at it opens a second ring. The world stops while it is up.",
            style = "label.dim",
        )
    }

    Group("Subtitles", "The accessibility menu a game would have, beside what it changes.") {
        Labelled("Size") {
            Stepper(SubtitleSize.entries, state.subtitleSize, { state.subtitleSize = it })
        }
        Labelled("Background") {
            Slider(state.subtitleBackground, { state.subtitleBackground = it }, step = 0.05f, length = 170f)
        }
        Labelled("Speaker names") {
            Toggle(state.subtitleSpeakers, { state.subtitleSpeakers = it })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6f)) {
            Button("Say something now", { state.sayNow = true }, style = "button.quiet")
            Toggle(state.isOn(Exhibit.Comms), { state.toggle(Exhibit.Comms) })
        }
        Text("Nothing says how long a line holds: the queue works it out from how long the line is.", style = "label.dim")
    }

    Group("The conversation", "One line at a time, and what the player said about it.") {
        Labelled("On show") { Toggle(state.isOn(Exhibit.Dialogue), { state.toggle(Exhibit.Dialogue) }) }
        Labelled("Auto") { Toggle(state.commsAuto, { state.commsAuto = it }) }
        Labelled("Skipping") { Toggle(state.commsSkipping, { state.commsSkipping = it }) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6f)) {
            Button("Next line", { state.commsAt++ }, style = "button.quiet")
            Button(
                "Start again",
                {
                    state.commsAt = 0
                    state.commsReply = null
                },
                style = "button.quiet",
            )
            Button("Open the log", { state.commsLogOpen = true }, style = "button.quiet")
        }
        Labelled("Lines said") { Text("${state.commsLog.entries.size}", style = "label") }
    }

    Group("The ship's hold", "Stacks that merge, splits, and a rifle two squares long.") {
        CargoGrids(state, Modifier.testTag(GameHoldTag))
        Text(
            "Drag a crate between the two grids, hold Shift as you pick a stack up to take half, " +
                "press R while a long item is in the air to turn it, right-click a pile to split it.",
            style = "label.dim",
        )
    }

    Group("Loot cards", "The card a player actually decides with.") {
        SalvageCards()
        Text("Hover a drop, or let focus land on it, then hold Control or LB to compare.", style = "label.dim")
    }

    Group("The upgrade board", "A skill tree on a plane, with points to spend.") {
        SkillBoardBody(Modifier.fillMaxWidth().height(200f))
        Text("Hold a node to buy it. The pad walks the branches rather than the geometry.", style = "label.dim")
    }

    Group("Objectives", "Built from the fight's own numbers, not kept beside them.") {
        SectionObjectives(state)
        Text("Land hits and the counter climbs; clear the sector and the next objective slides in.", style = "label.dim")
    }

    Group("Squad chat", "Channels, clickable names and a line to type on.") {
        SquadChat(Modifier.fillMaxWidth(), width = SectionBody, historyHeight = 110f)
    }

    Group("What this module draws", "The exhibits composegl-game owns.") {
        Exhibit.entries.filter { it.module == Module.Game }.forEach { ExhibitSwitch(state, it) }
    }
}

/** The heading strip, inline: the same arithmetic the HUD's own compass does. */
@Composable
private fun SectionCompass(state: ShowcaseState) {
    CompassStrip(
        state,
        Modifier.testTag(GameCompassTag).fillMaxWidth(),
        fieldOfView = 140f,
    )
}

/** The objectives, inline, with the toasts they raise underneath. */
@Composable
private fun SectionObjectives(state: ShowcaseState) {
    val notices = rememberNotifications(capacity = 2)
    val quests = remember(state.sectorClear, state.dronesDown) { showcaseQuests(state) }

    ObjectiveTracker(
        quests = quests,
        modifier = Modifier.fillMaxWidth(),
        keyOf = { it.name },
        maxVisible = 1,
        notify = notices,
        expandKey = Key.J,
        expandButton = GamepadButton.LeftStick,
        width = SectionBody,
    ) { quest ->
        title(quest.name)
        quest.steps.forEach { step(it.text, done = it.done, progress = it.progress) }
    }

    Notifications(notices, Modifier.fillMaxWidth(), width = SectionBody)
}
