package com.digitalbluebird.once4k

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith

class OnceTest {

    @Test
    fun `the same key runs once and replays the stored result`() {
        val once = Once(InMemoryStore())
        val runs = AtomicInteger(0)
        val first = once.execute("k") { runs.incrementAndGet(); "hello" }
        val second = once.execute("k") { runs.incrementAndGet(); "different" }
        assertThat(first).isEqualTo("hello")
        assertThat(second).isEqualTo("hello") // replayed, not "different"
        assertThat(runs.get()).isEqualTo(1)
    }

    @Test
    fun `different keys run independently`() {
        val once = Once(InMemoryStore())
        assertThat(once.execute("a") { "A" }).isEqualTo("A")
        assertThat(once.execute("b") { "B" }).isEqualTo("B")
    }

    @Test
    fun `a null result is cached like any other`() {
        val once = Once(InMemoryStore())
        val runs = AtomicInteger(0)
        val first = once.execute<String?>("k") { runs.incrementAndGet(); null }
        val second = once.execute<String?>("k") { runs.incrementAndGet(); "not-null" }
        assertThat(first).isEqualTo(null)
        assertThat(second).isEqualTo(null) // cached null, not re-run
        assertThat(runs.get()).isEqualTo(1)
    }

    @Test
    fun `concurrent callers with the same key run the action exactly once and share one result`() {
        val once = Once(InMemoryStore())
        val runs = AtomicInteger(0)
        val threads = 64
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val results = ConcurrentHashMap.newKeySet<String>()
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.submit {
                start.await()
                results.add(
                    once.execute("k") {
                        val n = runs.incrementAndGet()
                        Thread.sleep(20) // widen the race window
                        "result-$n"
                    },
                )
                done.countDown()
            }
        }
        start.countDown() // release every thread at once
        check(done.await(5, TimeUnit.SECONDS)) { "threads did not finish in time" }
        pool.shutdown()

        assertThat(runs.get()).isEqualTo(1) // the action ran exactly once
        assertThat(results).hasSize(1) // and every caller got that one result
    }

    @Test
    fun `a failed action propagates and leaves the key retryable, then caches the success`() {
        val once = Once(InMemoryStore())
        val successfulRuns = AtomicInteger(0)

        assertFailsWith<IllegalStateException> {
            once.execute("k") { throw IllegalStateException("boom") }
        }

        // the key was released, so a later call re-runs and succeeds
        val result = once.execute("k") { successfulRuns.incrementAndGet(); "ok" }
        assertThat(result).isEqualTo("ok")

        // and that success is now cached
        val again = once.execute("k") { successfulRuns.incrementAndGet(); "ok-again" }
        assertThat(again).isEqualTo("ok")
        assertThat(successfulRuns.get()).isEqualTo(1)
    }

    @Test
    fun `when the running caller fails, a waiting caller takes over and succeeds`() {
        val once = Once(InMemoryStore())
        val runnerClaimed = CountDownLatch(1)
        val runnerMayFail = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val runner = pool.submit {
            assertFailsWith<IllegalStateException> {
                once.execute("k") {
                    runnerClaimed.countDown()
                    runnerMayFail.await()
                    throw IllegalStateException("boom")
                }
            }
        }
        check(runnerClaimed.await(5, TimeUnit.SECONDS)) { "runner never claimed the key" }

        // second caller for the same key: begins -> InProgress -> awaits the runner
        val waiter = pool.submit<String> { once.execute("k") { "recovered" } }
        Thread.sleep(50) // let the waiter reach await(), then let the runner fail
        runnerMayFail.countDown()

        assertThat(waiter.get(5, TimeUnit.SECONDS)).isEqualTo("recovered")
        runner.get(5, TimeUnit.SECONDS) // the runner's assertFailsWith completed
        pool.shutdown()
    }
}
