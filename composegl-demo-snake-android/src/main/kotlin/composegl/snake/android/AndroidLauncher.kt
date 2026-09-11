package composegl.snake.android

import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration

/**
 * The activity. Everything below it is the same code the desktop runs.
 *
 * @see SnakeGdxApp
 */
class AndroidLauncher : AndroidApplication() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen, asked for in code rather than by the old `Theme.…Fullscreen`. The theme
        // version is sent no insets at all, so nothing can find out what the keyboard is covering;
        // this version hides the same bars and still reports. `BY_SWIPE` brings them back with a
        // swipe from the edge and hides them again by itself, which is what a game wants.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val configuration = AndroidApplicationConfiguration().apply {
            // The interface has translucent panels over a board, so it needs an alpha channel and
            // nothing else: no depth buffer, no stencil, no multisampling.
            r = 8
            g = 8
            b = 8
            a = 8
            depth = 0
            stencil = 0
            numSamples = 0
            useAccelerometer = false
            useCompass = false
            useGyroscope = false
        }

        initialize(SnakeGdxApp(), configuration)
    }
}
