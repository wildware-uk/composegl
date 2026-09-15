package dev.wildware.composegl.demo.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.AnimatedContent
import dev.wildware.composegl.ui.animation.AnimatedVisibility
import dev.wildware.composegl.ui.animation.Crossfade
import dev.wildware.composegl.ui.animation.Spring
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.animateFloatAsState
import dev.wildware.composegl.ui.animation.fadeIn
import dev.wildware.composegl.ui.animation.fadeOut
import dev.wildware.composegl.ui.animation.scaleIn
import dev.wildware.composegl.ui.animation.scaleOut
import dev.wildware.composegl.ui.animation.slideLeft
import dev.wildware.composegl.ui.animation.slideRight
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.PointerParallax
import dev.wildware.composegl.ui.modifier.animateContentSize
import dev.wildware.composegl.ui.modifier.animatePlacement
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.marquee
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.parallax
import dev.wildware.composegl.ui.modifier.rememberShake
import dev.wildware.composegl.ui.modifier.shake
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.modifier.zIndex
import dev.wildware.composegl.ui.widget.AnimatedImage
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.rememberSpriteAnimation

@Composable
fun AnimationPage() {
    Page(Section.Animation, "Animations run on the interface's clock and ask for frames only while something is moving.") {
        Visibility()
        CrossfadeCard()
        Carousel()
        Springs()
        ShakeCard()
        Sprites()
        MarqueeCard()
        ContentSize()
        Placement()
        ParallaxCard()
    }
}

@Composable
private fun Visibility() = Card("AnimatedVisibility", "A menu that fades and shrinks away, instead of vanishing.") {
    var open by remember { mutableStateOf(true) }
    Button(if (open) "Hide" else "Show", { open = !open }, style = "button.quiet")
    Box(Modifier.fillMaxWidth().height(100f), contentAlignment = Alignment.Centre) {
        AnimatedVisibility(open, enter = fadeIn() + scaleIn(from = 0.6f), exit = fadeOut() + scaleOut(to = 0.6f)) {
            Panel(Modifier.width(200f)) {
                Column(verticalArrangement = Arrangement.spacedBy(6f)) {
                    Text("PAUSED")
                    Text("Resume · Options · Quit", style = "label.dim")
                }
            }
        }
    }
}

@Composable
private fun CrossfadeCard() = Card("Crossfade", "Both pages are on screen for a moment: one going, one coming.") {
    var options by remember { mutableStateOf(false) }
    Button("Switch page", { options = !options }, style = "button.quiet")
    Crossfade(options, Modifier.fillMaxWidth().height(90f), spec = Tween(450)) { showingOptions ->
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6f)) {
                Text(if (showingOptions) "OPTIONS" else "MAIN MENU")
                Text(if (showingOptions) "Volume · Controls · Video" else "Play · Continue · Quit", style = "label.dim")
            }
        }
    }
}

@Composable
private fun Carousel() = Card("AnimatedContent", "Cards slide in from the side they came from.") {
    var card by remember { mutableIntStateOf(0) }
    val spells = listOf("Fire bolt" to "Burns for 12", "Ice lance" to "Slows for 3 seconds", "Chain spark" to "Jumps to 4 targets")
    Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
        Button("Previous", { card = (card + spells.size - 1) % spells.size }, style = "button.quiet")
        Button("Next", { card = (card + 1) % spells.size }, style = "button.quiet")
    }
    AnimatedContent(
        card,
        Modifier.fillMaxWidth().clip(),
        transition = { from, to -> if (to > from) slideLeft(Tween(350)) else slideRight(Tween(350)) },
    ) { shown ->
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6f)) {
                Text(spells[shown].first.uppercase())
                Text(spells[shown].second, style = "label.dim")
            }
        }
    }
}

@Composable
private fun Springs() = Card("Springs", "Three dampings. Press go and watch the overshoot.") {
    var there by remember { mutableStateOf(false) }
    Button("Go", { there = !there }, style = "button.quiet")
    val track = (LocalCardWidth.current - 60f).coerceAtLeast(160f)
    listOf("No wobble" to Spring.NoWobble, "Gentle" to Spring.Gentle, "Bouncy" to Spring.Bouncy).forEach { (name, damping) ->
        val x by animateFloatAsState(if (there) 1f else 0f, Spring(damping = damping, stiffness = Spring.Low))
        Labelled(name) {
            Box(Modifier.size(track, 20f).background(Ink, corner = 10f)) {
                Box(Modifier.offset(x = x * (track - 20f)).size(20f, 20f).background(Accent, corner = 10f)) {}
            }
        }
    }
}

@Composable
private fun ShakeCard() = Card("Shake", "Trauma, not a timer: harder hits shake more, and it settles by itself.") {
    val shake = rememberShake(maxOffset = 16f)
    Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
        Button("Light hit", { shake.trigger(0.5f) }, style = "button.quiet")
        Button("Heavy hit", { shake.trigger(1f) }, style = "button.danger")
    }
    Box(Modifier.fillMaxWidth().height(80f), contentAlignment = Alignment.Centre) {
        Panel(Modifier.shake(shake).width(200f)) { Text("HULL BREACHED") }
    }
}

@Composable
private fun Sprites() = Card("Sprite animation", "Eight frames cut from one sheet. Change the rate.") {
    var fps by remember { mutableFloatStateOf(12f) }
    val still = LocalShowcase.current.reduceMotion
    Row(horizontalArrangement = Arrangement.spacedBy(16f), verticalAlignment = VerticalAlignment.Centre) {
        AnimatedImage(rememberSpriteAnimation("coin_", fps = fps, loop = !still), Modifier.size(64f))
        Column(verticalArrangement = Arrangement.spacedBy(6f)) {
            Slider(fps, { fps = it }, range = 2f..30f, step = 1f, length = 160f)
            Text("${fps.toInt()} frames a second", style = "label.dim")
        }
    }
}

@Composable
private fun MarqueeCard() = Card("Marquee", "A name too long for its slot scrolls; one that fits stays still.") {
    listOf("Iron Sword", "Sword of a Thousand Truths, Forged in the Heart of the Mountain").forEach { name ->
        Box(Modifier.width(220f).background(Steel, corner = 4f).padding(horizontal = 8f, vertical = 5f)) {
            Text(name, Modifier.marquee(speed = 40f, delayMillis = 800))
        }
    }
}

@Composable
private fun ContentSize() = Card("animateContentSize", "The entry grows to fit its text instead of jumping.") {
    var open by remember { mutableStateOf(false) }
    Button(if (open) "Collapse" else "Expand", { open = !open }, style = "button.quiet")
    Column(Modifier.width(240f).animateContentSize(Tween(400)).background(Steel, corner = 6f).padding(10f), verticalArrangement = Arrangement.spacedBy(6f)) {
        Text("The lost ring")
        if (open) {
            Text("Search the well at Oakmere, then take what you find back to Edda at the mill.", Modifier.fillMaxWidth(), style = "label.dim")
            Text("Reward: 120 gold", style = "label.good")
        }
    }
}

@Composable
private fun Placement() = Card("animatePlacement", "Sort the scores; rows slide to their new places.") {
    var sorted by remember { mutableStateOf(false) }
    Button(if (sorted) "Unsort" else "Sort by score", { sorted = !sorted }, style = "button.quiet")
    val rows = listOf("Ada" to 120, "Cy" to 90, "Di" to 210, "Bo" to 340)
    Column(Modifier.width(200f), verticalArrangement = Arrangement.spacedBy(6f)) {
        (if (sorted) rows.sortedByDescending { it.second } else rows).forEach { (name, score) ->
            key(name) {
                Row(
                    Modifier.width(200f).animatePlacement(Tween(450)).zIndex(if (name == "Bo") 1f else 0f)
                        .background(if (name == "Bo") Deep else Steel, corner = 6f).padding(8f),
                ) {
                    Text(name, Modifier.width(140f))
                    Text("$score")
                }
            }
        }
    }
}

@Composable
private fun ParallaxCard() = Card("Parallax", "Move the pointer over the scene; near layers move more.") {
    val parallax = remember { PointerParallax(Offset.Zero) }
    val placed = remember { PlacedHandler { node -> parallax.centre = node.boundsInRoot.centre } }
    val follow = remember { PointerHandler { event -> parallax.saw(event); false } }
    Box(Modifier.fillMaxWidth().height(130f).background(Ink, corner = 8f).clip(corner = 8f).onPlaced(placed).onPointer(follow)) {
        Box(Modifier.fillMaxSize().parallax(parallax, factor = -0.05f).drawBehind { bounds ->
            repeat(9) { i -> rect(Rect.of(bounds.left - 80f + i * 70f, bounds.top + 50f, 80f, 70f), Steel, corner = 35f) }
        }) {}
        Box(Modifier.fillMaxSize().parallax(parallax, factor = -0.15f).drawBehind { bounds ->
            repeat(11) { i -> rect(Rect.of(bounds.left - 110f + i * 60f, bounds.top + 84f, 70f, 56f), Deep, corner = 26f) }
        }) {}
        Box(Modifier.offset(x = 40f, y = 26f).parallax(parallax, factor = -0.3f).background(Accent, corner = 4f).padding(horizontal = 12f, vertical = 4f)) {
            Text("PLAY", colour = Ink)
        }
    }
}
