package composegl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeadlessRenderingTest {

    @Test
    fun `a Material button is drawn in the theme's primary colour`() {
        HeadlessSurface(200, 100).use { ui ->
            var primary = 0
            ui.setContent {
                primary = MaterialTheme.colorScheme.primary.toArgb()
                Box(Modifier.fillMaxSize()) {
                    // No label: a glyph in the middle would blend with the container colour.
                    Button(onClick = {}, modifier = Modifier.align(Alignment.Center)) {
                        Box(Modifier.size(40.dp))
                    }
                }
            }

            assertEquals(primary, ui.centrePixel(), "the centre of the button is its container colour")
        }
    }

    @Test
    fun `clicking that button fires onClick`() {
        HeadlessSurface(200, 100).use { ui ->
            var clicks = 0
            ui.setContent {
                Box(Modifier.fillMaxSize()) {
                    Button(onClick = { clicks++ }, modifier = Modifier.align(Alignment.Center)) { Text("go") }
                }
            }

            assertTrue(ui.click(100f, 50f))
            assertEquals(1, clicks)
        }
    }

    @Test
    fun `a LaunchedEffect resumes on the game thread after a delay`() {
        HeadlessSurface(32, 32).use { ui ->
            val gameThread = Thread.currentThread()
            var composedOn: Thread? = null
            var resumedOn: Thread? = null
            var done by mutableStateOf(false)

            ui.setContent {
                composedOn = Thread.currentThread()
                LaunchedEffect(Unit) {
                    delay(20)
                    resumedOn = Thread.currentThread()
                    done = true
                }
                Box(Modifier.fillMaxSize())
            }

            ui.frameUntil { done }

            assertSame(gameThread, composedOn, "composition runs on the thread that calls update()")
            assertSame(gameThread, resumedOn, "and so does everything that resumes after a delay")
        }
    }
}
