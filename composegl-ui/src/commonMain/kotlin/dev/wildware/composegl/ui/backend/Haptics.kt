package dev.wildware.composegl.ui.backend

/**
 * A kind of feedback a hand can feel.
 *
 * Named by what happened rather than by how a motor should move, because the three places this
 * ends up disagree about motors: a phone has a tuned actuator with its own vocabulary, iOS has
 * three different generators, and a pad has two rumble motors and a strength. Each backend turns a
 * name into the best thing its hardware has.
 *
 * [durationMillis] and [strength] are the rumble a pad plays, which has no vocabulary of its own.
 * A phone ignores them in favour of the platform's own effect.
 */
enum class Haptic(val durationMillis: Int, val strength: Float) {

    /** A button or a tick box. The one every control gives. */
    LightTap(40, 0.35f),

    /** Something with more weight: picking up an item, confirming a menu. */
    MediumTap(60, 0.6f),

    /** A hit, a landing, a door slamming shut. */
    HeavyTap(90, 1f),

    /** A notch on a slider or a picker. Short enough to feel twenty of in a row. */
    Tick(20, 0.25f),

    /** It worked: saved, bought, unlocked. */
    Success(80, 0.5f),

    /** Something the player should look at before carrying on. */
    Warning(120, 0.7f),

    /** It did not work: not enough gold, a locked door, a wrong password. */
    Failure(180, 1f),
}

/**
 * The phone's vibration, or the pad's rumble.
 *
 * ```kotlin
 * LocalHaptics.current.perform(Haptic.LightTap)
 * ```
 *
 * Fire and forget. Nothing reports back whether anything moved, because nothing can: a phone with
 * vibration turned off in its settings answers the same as a phone that buzzed, and that is the
 * player's decision to make rather than the game's to detect.
 */
interface Haptics {

    fun perform(haptic: Haptic)

    companion object {

        /**
         * Nothing moves.
         *
         * What a control gets on a platform with no motor, and before a game has wired one up, so
         * a button can always ask without knowing where it is running.
         */
        val None: Haptics = object : Haptics {
            override fun perform(haptic: Haptic) = Unit
        }
    }
}

/** For tests. Remembers every request, in order, so a test can check what a player would feel. */
class RecordingHaptics : Haptics {

    private val requests = mutableListOf<Haptic>()

    val performed: List<Haptic> get() = requests.toList()

    override fun perform(haptic: Haptic) {
        requests += haptic
    }

    fun clear() = requests.clear()
}
