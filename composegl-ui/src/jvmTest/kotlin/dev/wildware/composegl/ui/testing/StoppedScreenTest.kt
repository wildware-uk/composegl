package dev.wildware.composegl.ui.testing

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.Button
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

/**
 * A screen that throws is stopped by Compose for good, and a test driving it has to hear so.
 *
 * On the JVM only. Nothing here catches the error: it still goes wherever the platform sends an
 * uncaught coroutine exception, and on Kotlin/Native that ends the process, test runner and all.
 */
class StoppedScreenTest {

    @Test
    fun `a screen that throws after a click fails the click with what it threw`() {
        uiTest {
            var broken by remember { mutableStateOf(false) }
            check(!broken) { "the screen broke" }
            Button("BREAK", onClick = { broken = true }, modifier = Modifier.testTag("break"))
        }.use { ui ->
            val failure = assertThrows(IllegalStateException::class.java) { ui.click("break") }
            assertEquals("the screen broke", failure.cause?.message, failure.stackTraceToString())
        }
    }

    /**
     * The step that set a failure off can end before the failure lands, when it lands on another
     * thread. The test still cannot end green: closing it says what happened.
     */
    @Test
    fun `a screen that stops on another thread after the last step fails when the test closes`() {
        val go = CountDownLatch(1)
        val ui = uiTest {
            LaunchedEffect(Unit) {
                launch(Dispatchers.IO) {
                    go.await()
                    error("broke off the frame thread")
                }
            }
        }

        go.countDown()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (ui.host.failure == null) {
            check(System.nanoTime() < deadline) { "the failure never reached the host" }
            Thread.sleep(1)
        }

        val failure = assertThrows(IllegalStateException::class.java) { ui.close() }
        assertEquals("broke off the frame thread", failure.cause?.message, failure.stackTraceToString())
    }

    /** A failure the screen was built to survive leaves it running, and the test with it. */
    @Test
    fun `a failure the screen survives does not stop it`() {
        uiTest {
            var presses by remember { mutableStateOf(0) }
            LaunchedEffect(presses) { supervisorScope { launch { error("shielded") } } }
            Button("n=$presses", onClick = { presses++ }, modifier = Modifier.testTag("press"))
        }.use { ui ->
            ui.click("press")
            ui.click("press")

            ui.assertText("press", "n=2")
        }
    }
}
