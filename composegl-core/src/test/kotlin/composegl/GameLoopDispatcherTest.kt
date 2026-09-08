package composegl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class GameLoopDispatcherTest {

    private val dispatcher = GameLoopDispatcher()
    private val log = mutableListOf<String>()

    private fun post(name: String, body: () -> Unit = {}) =
        dispatcher.dispatch(dispatcher) { log += name; body() }

    @Test
    fun `nothing runs until drain`() {
        post("a")
        assertEquals(emptyList<String>(), log)
        assertTrue(dispatcher.hasPendingWork)
        dispatcher.drain()
        assertEquals(listOf("a"), log)
        assertFalse(dispatcher.hasPendingWork)
    }

    @Test
    fun `tasks run in the order they were queued`() {
        post("a"); post("b"); post("c")
        dispatcher.drain()
        assertEquals(listOf("a", "b", "c"), log)
    }

    @Test
    fun `a task queued during a drain runs in that same drain`() {
        post("outer") { post("inner") }
        dispatcher.drain()
        assertEquals(listOf("outer", "inner"), log)
    }

    @Test
    fun `queueing keeps going for as many levels as it takes`() {
        fun chain(depth: Int) {
            dispatcher.dispatch(dispatcher) {
                log += "level$depth"
                if (depth < 5) chain(depth + 1)
            }
        }
        chain(1)
        dispatcher.drain()
        assertEquals((1..5).map { "level$it" }, log)
    }

    @Test
    fun `a re-entrant drain does not run anything twice`() {
        post("outer") {
            post("inner")
            dispatcher.drain() // must be a no-op; the outer drain owns the queue
            log += "after-reentrant-drain"
        }
        dispatcher.drain()
        assertEquals(listOf("outer", "after-reentrant-drain", "inner"), log)
    }

    @Test
    fun `a throwing task does not swallow the rest of the queue`() {
        post("a") { error("boom") }
        post("b")
        val thrown = assertThrows<IllegalStateException> { dispatcher.drain() }
        assertEquals("boom", thrown.message)
        assertEquals(listOf("a", "b"), log)
        assertFalse(dispatcher.hasPendingWork)
    }

    @Test
    fun `dispatch is safe from another thread`() {
        val started = CountDownLatch(1)
        val t = thread {
            started.countDown()
            repeat(500) { i -> dispatcher.dispatch(dispatcher) { log += "t$i" } }
        }
        started.await()
        t.join()
        dispatcher.drain()
        assertEquals(500, log.size)
        assertEquals((0 until 500).map { "t$it" }, log)
    }

    @Test
    fun `isDispatchNeeded is always true so nothing runs inline`() {
        assertTrue(dispatcher.isDispatchNeeded(dispatcher))
        var ran = false
        val scope = CoroutineScope(dispatcher)
        scope.launch { ran = true }
        assertFalse(ran, "launch must not run inline")
        dispatcher.drain()
        assertTrue(ran)
    }

    @Test
    fun `delay resumes on the draining thread`() {
        val scope = CoroutineScope(dispatcher)
        var resumedOn: Thread? = null
        val gameThread = Thread.currentThread()
        scope.launch {
            log += "before"
            delay(20)
            resumedOn = Thread.currentThread()
            log += "after"
        }
        dispatcher.drain()
        assertEquals(listOf("before"), log)

        val deadline = System.nanoTime() + 5_000_000_000L
        while (resumedOn == null && System.nanoTime() < deadline) {
            dispatcher.drain()
            Thread.sleep(1)
        }
        assertEquals(listOf("before", "after"), log)
        assertEquals(gameThread, resumedOn, "delay() must resume back on the thread that drains")
    }
}
