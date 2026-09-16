# Game widgets

The in-play widgets: health bars with a damage trail, a crosshair, damage numbers
anchored in the world, nameplates and waypoints pinned to points in the world,
cooldowns, a hotbar, a minimap frame, notifications and particles. These are the
ones that made this toolkit worth building.

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
`marker.*`, `cooldown.*`, `hotbar.*`, `minimap.*`, `notification.*`), so they
look right with no setup. Your own skin file styles them by the same names.

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

**When there are too many**, `maxVisible` keeps the best of them and `declutter =
true` drops any that would sit on top of one already kept. Best means the highest
`priority`, and among equal priorities the nearest — so the objective survives and
the sixteenth nameplate does not. A marker that is not shown is drawn at nothing,
which means it is not clickable and focus cannot land on it either.

**The skin** supplies `marker.arrow`, the colour of those off-screen triangles.
Everything else about a marker is whatever you put inside it.

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
