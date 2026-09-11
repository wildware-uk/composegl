package composegl.snake.android

import android.os.Bundle
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
