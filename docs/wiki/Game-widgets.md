# Game widgets

The in-play widgets: health bars with a damage trail, a crosshair, damage numbers
anchored in the world, nameplates and waypoints pinned to points in the world,
cooldowns, a hotbar, a weapon wheel, a minimap frame, a compass bar,
notifications and particles. These are the ones that made this toolkit worth
building.

---

## Adding the module

They live in `composegl-game`, a separate module on top of `composegl-ui`, so a
tool or a menu-only project carries none of them.

```kotlin
implementation("dev.wildware.composegl:composegl-game:0.6.0-SNAPSHOT")
```

```kotlin
import dev.wildware.composegl.game.Bar
import dev.wildware.composegl.game.Reticle
import dev.wildware.composegl.game.rememberReticleState
```

It targets everything `composegl-ui` does: the JVM, Linux, iOS and the browser.

**Coming from 0.5.0?** These widgets used to be inside `composegl-ui`, in
`dev.wildware.composegl.ui.game`. Add the dependency above and change those
imports to `dev.wildware.composegl.game`. Nothing else about them changed.

**No special access.** The module is built only from the public API of
`composegl-ui`, the same one your own widgets use; the build checks it has no
other dependency. So anything a bar or a crosshair can do, a widget of yours can
too. The pieces it needed became public for that reason: `UiCanvas.textRun`, for
text that takes a [[ring|Widgets#outlined-text]] only when there is one,
`SkinDrawable.flatColour`, for a widget that draws shapes rather than boxes, and
`RectCache`, for drawing the same rectangles every frame without allocating.

**Their look is in the skin.** The default and high-contrast [[skins|Skins]]
already have every style these use (`bar.*`, `reticle.*`, `damage.*`,
`marker.*`, `cooldown.*`, `hotbar.*`, `wheel.*`, `minimap.*`, `compass.*`,
`notification.*`), so they look right with no setup. Your own skin file styles
them by the same names.

`Typewriter`, `PromptGlyph` and `ProvidePrompts` stay in `composegl-ui`: they
are not only for games. See [[Widgets]].

## Bars

```kotlin
Bar(hull, Modifier.fillMaxWidth())
Bar(
    health,
    segments = 5,                   // pips, not a smooth bar
    thresholds = listOf(BarThreshold(below = 0.25f, style = "bar.critical")),
    trail = true,                   // the white "you just lost this much" tail
    holdMillis = 300,
    drainMillis = 450,
)
```

![four bars: health, a segmented shield, stamina, and stamina under its threshold](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-bars.png)

The trail is the thing: a hit drops the bar instantly and leaves a pale tail that
catches up a moment later, which is how a player sees *how much* they just lost.

A bar shows a known amount. For "working on it" with no amount to show, use
`IndeterminateBar` or `Spinner` from the core toolkit: see
[Widgets](Widgets.md#spinners-and-indeterminate-progress).

## Reticle

```kotlin
val reticle = rememberReticleState()
Reticle(reticle, Modifier.align(Alignment.Centre))

// …from the game
reticle.spread = movement * 1.4f
reticle.hit(kill = true)
```

![a crosshair standing still, and a wider red one over something hostile](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-reticle.png)

Spread, bloom, hit markers. It is driven from your game code, not from state the
interface owns.

## Damage numbers

```kotlin
val numbers = rememberDamageNumbers()
DamageNumberLayer(numbers, projection = projection)

// …when something is hit
numbers.show("148", WorldAnchor.at(enemy.x, enemy.y + 2f, enemy.z), critical = true)
```

They float, fade, and are placed in the world through the same `WorldProjection`
your camera already provides.

## World markers

Nameplates, health bars over enemies, quest waypoints, interaction prompts, ping
markers — interface that belongs to a point in the world rather than to a corner
of the screen.

```kotlin
WorldMarkerLayer(projection = camera, maxVisible = 12) {
    enemies.forEach { enemy ->
        marker(
            key = enemy.id,
            position = { it.set(enemy.x, enemy.y + 2f, enemy.z) },
            fadeDistance = 30f..40f,
            anchor = Alignment.BottomCentre,
        ) {
            Column {
                Text(enemy.name)
                Bar(enemy.health, Modifier.width(60f), thickness = 4f)
            }
        }
    }

    marker(
        key = "objective",
        x = objective.x, y = objective.y, z = objective.z,
        offScreen = OffScreen.ClampToEdge(arrow = true, inset = 24f),
        priority = 1f,
    ) {
        WaypointIcon(player.distanceTo(objective))
    }
}
```

![nine nameplates over a patrol at nine different distances, each a name and a health bar, the near ones full size and solid and the far ones smaller and fading out; at the left edge a gold waypoint icon held just inside the screen with a small triangle pointing off to the left and 24 m under it](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-world-markers.png)

That is one `WorldMarkerLayer` over a scene drawn by the same camera:
the nameplates sit over the figures because the projection agrees with itself, not
because anything was nudged into place. The far ones are smaller and fainter
because they are further away, and the waypoint is at the edge because the thing it
belongs to is off to the left.

A marker is a real node, so what goes in one is any interface at all — a bar, a
portrait, a button — and it is clicked, hovered and reached by a pad the same way
anything else is.

**Moving a marker does not recompose it.** Your `position` is asked where it is
once a frame and the answer *places* the node, which is the cheap half of layout:
a hundred nameplates following a hundred running enemies recompose nothing.

**Off the edge** is `OffScreen.Hide` (the default), `OffScreen.Show`, or
`OffScreen.ClampToEdge`, which holds the whole marker just inside the layer and
draws a triangle beside it turned towards the thing. It is the marker's whole box
that is measured against the edge, so a wide nameplate starts sliding in while its
point is still on screen instead of jumping half its own width when the point
crosses over. The triangle is held inside too — an arrow off the edge tells nobody
anything — so asking for one costs about another `arrowSize` of room on top of
your `inset`. Something *behind* the
camera is held at the edge the player has to turn towards, with `OffScreen.Show`
as well: a projection mirrors a point behind the lens, so there is no "where it
landed" to leave it at. Say which points are behind by writing a negative depth:

```kotlin
val camera = WorldProjection { point, view, onto ->
    val p = scene.camera.project(point)
    onto.set(p.x, view.height - p.y, distanceTo(point))   // negative when behind
    true
}
```

That third number is the depth, in whatever units your game measures distance in.
It is what `fadeDistance` and `scaleDistance` compare against, and what decides
which markers draw on top: nearer over further, always. The minus sign only says
*behind*; how far away counts the same either way, so a waypoint fifty metres
behind the player fades like one fifty metres in front, and never covers — or
takes the place of — something they can actually see.

![the camera turning left across the patrol and back again: every nameplate slides with the figure it belongs to, ones that reach the edge stop being drawn, and the waypoint stays pinned to the left edge with its triangle turning until the mast itself comes into view and the marker settles on it, the range counting down from 25 m to 21 m as the player walks](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-world-markers.gif)

That is one camera really turning, a frame at a time. Nothing inside a marker is
built again as it moves: the layer asks the camera where each point is and places
the node there. The waypoint is held at the edge the whole time the mast is off
screen — including while it is *behind* the player at the start — and lets go of
the edge by itself when the mast comes into view.

**When there are too many**, `maxVisible` keeps the best of them and `declutter =
true` drops any that would sit on top of one already kept. Best means the highest
`priority`, and among equal priorities the nearest — so the objective survives and
the sixteenth nameplate does not. A marker that is not shown is drawn at nothing,
which means it is not clickable and focus cannot land on it either.

![the same moment of the same scene twice, one above the other: on top all nine nameplates including the small faint ones along the horizon, and underneath only five — the waypoint, and the four nameplates that neither sat on top of another nor ran out of room](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-world-markers-declutter.png)

The same frame twice. Underneath, the layer was told `maxVisible = 5` and
`declutter = true`: the objective survives because it asked for the highest
priority, and what goes is the crowd along the horizon and anything that landed on
top of a plate already kept — which is why the one tucked behind the nearest
figure is gone even though it is close.

**The skin** supplies `marker.arrow`, the colour of those off-screen triangles.
Everything else about a marker is whatever you put inside it.

![the same layer in the high-contrast skin on a screen that reads right to left: the top strip has swapped ends, the health bars are green and white, the waypoint is still held at the left edge beside its triangle, and three of the patrol have no nameplate at all because their points have gone off the right-hand side](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-world-markers-rtl.png)

The high-contrast skin, reading from the right. The writing turns round and the
world does not: a marker lands where its point is whichever way the screen reads,
because a world is a picture rather than a line of text. Three of the patrol have
no plate here — their points went off the right-hand side, and `OffScreen.Hide` is
the default.

`scaleDistance` is the one thing to be careful with: scaling draws through an
offscreen picture, so it is crisp shrinking and soft growing — see
[[Modifiers]].

## Cooldowns, hotbars, minimaps, notifications

```kotlin
val dash = rememberCooldown(durationMillis = 4_000)
RadialCooldown(dash)                   // the sweeping wedge over an ability icon

Hotbar(slots, selected = selected, onSelect = { selected = it }, onUse = { use(it) })

MinimapFrame(Modifier.size(160f), markers = contacts, range = 120f) { bounds ->
    drawWorld(bounds)               // `this` is the UiCanvas
}

val alerts = rememberNotifications()
Notifications(alerts)
alerts.show("SHIELD DOWN")
```

| | |
|---|---|
| ![a dark wedge over an ability icon, with the seconds left on it](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-cooldown.png) | the wedge sweeps away as the ability comes back |
| ![five hotbar slots, one selected, two holding charges](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-hotbar.png) | slots, charges, and which one is selected |
| ![a minimap frame with a compass and three markers](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-minimap.png) | your own map in the middle, the chrome and the markers from the skin |

## Compass bar

The heading strip along the top of the screen, as Skyrim, Far Cry and Fortnite
have it: a window onto a circle, with the names of the compass points sliding
past as the player turns.

```kotlin
CompassBar(
    heading = player.yaw,                    // degrees clockwise from north
    fieldOfView = 180f,                      // how much of the circle is on show
    Modifier.width(600f).height(48f),
    readout = { "${it.roundToInt()}°" },     // optional; null for no number
    distanceText = { "${it.roundToInt()}m" },
) {
    pin(bearing = bearingTo(quest), icon = "icons/quest", distance = rangeTo(quest))
    pin(bearing = bearingTo(enemy), icon = "icons/enemy", fadeWithDistance = true)
}
```

![a heading strip: NW, N, NE and E sliding past a blue centre line reading 24 degrees, with three pins over it and one pinned to the right hand end](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-compass.png)

On a screen, over a game. Nothing here was placed by hand: the hut, the two
raiders and the ridge behind them are put on screen from their own bearings,
through the same arithmetic the strip uses, so each pin sits over the thing it
is a pin for. The third pin is the pickup, behind the player, stuck to the
left-hand end with an arrow on it.

![a first-person view of a ridge at dusk with a compass strip across the top: a red pin over two raiders on the left, a yellow pin over a hut on the right, and a faint pin with an arrow stuck to the left-hand end](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-compass-hud.png)

Four things it gets right, which are the four a hand-rolled one gets wrong:

- **It wraps at 360 with no seam.** Every mark on it is drawn from its bearing,
  so facing 350 puts north ten degrees to the right rather than 350 degrees to
  the left. There is no wound-up position to unwind.
- **A pin outside the field of view sticks to the end it left by**, with an
  arrow saying which way to turn. `clamp = false` on a pin that should simply
  not be there instead.
- **The ends fade out**, so a name sliding off is not chopped in half — and a
  pin never sits in the faint part, it stops just inside it.
- **Only the strip redraws.** It is one leaf node, so a heading that changes
  every frame lays nothing out again. Read the heading inside a composable of
  its own and the recomposition stops there too:

```kotlin
@Composable
private fun Heading(player: Player) = CompassBar(player.yaw, 180f, Modifier.width(600f))
```

Here is the same player turning on the spot, a frame at a time. West goes by,
north comes round with no seam, the raiders slide off the left-hand end and the
pickup's pin swaps ends as it passes behind:

![the same view animated as the player turns right: the ridge and the names slide left, north passes the centre line, and the pins follow the things they point at until they stick to one end or the other](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-compass-turn.gif)

The same three things out there, from three headings — so you can see what a pin
does when it runs out of strip. It stops at the end it left by with an arrow on
it, rather than disappearing and leaving the player to guess which way to turn:

![three heading strips one above another, showing the pickup pin clamped to the right end, then to the left end, and then the camp and raider pins clamped one at each end, each with a small arrow](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-compass-clamped.png)

The pins are named in the trailing lambda, which runs **while the strip is
drawn** rather than while it is composed. So a pin is a little arithmetic and
nothing else: no list to build, no object per quest marker per frame, and the
bearings are the ones the game has this frame. Leave `live = false` on a paused
screen and the strip is not redrawn at all.

### The names are the player's language

`N` is not north everywhere. The names come from your
[[string table|Localisation]] by default, as `compass.n`, `compass.ne` and so
on, and a point nobody has translated keeps its English letter:

```
compass.n = И
compass.ne = СВ
```

```kotlin
CompassBar(heading, labels = rememberCompassLabels(points = 16))  // or 4, or 8
CompassBar(heading, labels = CompassLabels(listOf("N", "E", "S", "W")))
```

The strip itself **does not mirror** in a right-to-left language, unlike
everything else on the screen. East is to the right of north for an Arabic
player standing in the same field as an English one, and a strip that mirrored
would slide the wrong way as they turned.

The same strip twice, through the [[high-contrast skin|Skins]]: once in English
and once in Hebrew, at the same heading. The names change and the ordinary row
under the strip swaps to the other side, but the strip does not — north is in
the same place in both:

![two heading strips in the black and white high-contrast skin, one with N and E and a line of English under it on the left, one with the Hebrew names and a line of Hebrew under it on the right, both reading 42 degrees](https://raw.githubusercontent.com/wildware-uk/composegl/master/docs/wiki/images/game-compass-rtl.png)

## The weapon wheel

```kotlin
var wheelOpen by remember { mutableStateOf(false) }

RadialMenu(
    open = wheelOpen,
    items = weapons,
    selected = equipped,
    onSelect = { equipped = it },
    centre = { Text(it?.name ?: "", style = "wheel.label") },
) { weapon, highlighted ->
    Image(weapon.icon, Modifier.size(if (highlighted) 44f else 36f))
}
```

The point of a wheel, and the reason it is not a ring of buttons: **nothing has to
be reached**. The stick's *angle* picks a slice however far past the dead zone it
is pushed, and the mouse picks the slice its *direction from the middle* points
at, however far away the pointer is. Slamming the stick south-west and letting go
is the fastest menu input there is, and the only one that works while the player
is also driving.

`open` is yours, never the wheel's. It never closes itself — it says what
happened and your game decides. That is what makes hold-to-open work: the button
being down *is* the state.

```kotlin
// Hold Q, flick, let go. The wheel is up exactly while the key is down, and the key is
// heard wherever focus happens to be, because nobody clicks a wheel open first.
val held = remember {
    KeyHandler { event ->
        if (event.key != Key.Q) false
        else {
            wheelOpen = event.type == KeyEventType.Down
            true
        }
    }
}
Box(Modifier.fillMaxSize().onShortcutKey(held)) {
    RadialMenu(open = wheelOpen, items = weapons, onSelect = { equipped = it }) { weapon, _ ->
        Text(weapon.name)
    }
}
```

| | |
|---|---|
| `confirm = RadialConfirm.Release` | the weapon wheel: the slice being pointed at is taken when the wheel closes. The default |
| `confirm = RadialConfirm.Press` | the emote wheel: the wheel stays up, and South, Enter, Space or a click takes the slice. East or Escape backs out |
| `onCancel` | confirmed with the stick inside its dead zone or the pointer still on the hub, backed out of, or taken off the screen while still open |
| `onOpenChange` | told when it comes up and goes away. Where you slow or stop the world |
| `startAngleTurns` | where the first slice's middle sits, clockwise from straight up. Mirrored in a right-to-left language, so the first slice stays where the eye starts |
| `children` | a category's contents: pointing at it opens a second ring, and pushing the stick to the edge chooses out of that one |
| `deadZone`, `stick` | how far the stick must travel to count, and which stick aims it. The mouse's dead zone is the hub |

Moving from one slice to the next ticks: `UiSounds.focusMove()` and a
`Haptic.Tick`, the same pair a pad's focus move makes, and the new slice swells
into place. Taking one is a `UiSounds.change()` and a `Haptic.LightTap`.

**Slowing the world.** The wheel runs on the interface clock, so it keeps
animating while the game does not:

```kotlin
val clocks = LocalClocks.current
RadialMenu(open = wheelOpen, onOpenChange = { clocks.setRunning(Clock.World, !it) }, …)
```

See [Animation](Animation.md#clocks) for what a clock is and why there are two.

`onOpenChange(false)` also arrives when the wheel is taken **off the screen** while
it is still open — a screen swapped, the whole widget switched off — so a world
clock you stopped for it is always started again. Nothing can leave your game
paused with no wheel on screen to close.

Going away like that **cancels**: `onCancel` is called and nothing is equipped,
even on a release wheel. A screen changing underneath a player is not the player
letting go of the button, and a gun equipped that way is one they never chose and
cannot undo. Only `open` actually going false takes the slice.

The wheel is **modal** over the pointer, the stick and the pad's South and East
while it is up, so a click or a pad press cannot reach a button behind it. On a
release wheel South and East are swallowed and do nothing at all — the choice
belongs to the button holding the wheel open.

**Nested rings.** A category with two or three children gets a **wider** ring than
its own slice, because three children inside a quarter turn are thirty degrees
each and no stick can hit that. So the ring spills over its neighbours, and while
the stick is out at it the ring belongs to the category that opened it: aiming at
a child drawn past the parent's edge takes that child, not the slice underneath
it. Sweeping right round, out of the ring altogether, moves to the next category.

A ring never goes the whole way round, however many children a category has: it
stops at seven eighths of a turn so that there is always an angle outside it to
sweep back out through. Past eight children, put them on a second wheel rather
than a ring.

## Particles

```kotlin
val sparks = rememberParticles(capacity = 240, seed = 11L)
ParticleLayer(sparks, Modifier.fillMaxSize())

sparks.burst(count = 24, x = hit.x, y = hit.y, style = HitSparks)
```

Bursts and streams, simulated with no allocation per particle. Seeded, so the same
seed gives the same burst — which is what makes it testable against a golden image.

---

## What next

- **[[Widgets]]** — the ordinary controls these sit alongside
- **[[Skins]]** — how all of these get their look
- **[[Animation]]** — the world clock that bars, reticles and cooldowns run on, and pausing it
- **[[Testing]]** — driving a HUD with no window, from a click to a key to a pad button
