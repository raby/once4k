package com.digitalbluebird.once4k

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class InMemoryStoreTest {

    @Test
    fun `a completed key replays within its TTL`() {
        var now = 0L
        val once = Once(InMemoryStore(ttl = 1000.milliseconds, clock = { now }))
        val runs = AtomicInteger(0)
        once.execute("k") { runs.incrementAndGet(); "v1" }
        now = 999 // still inside the window
        val again = once.execute("k") { runs.incrementAndGet(); "v2" }
        assertThat(again).isEqualTo("v1") // replayed
        assertThat(runs.get()).isEqualTo(1)
    }

    @Test
    fun `a completed key re-runs once its TTL has elapsed`() {
        var now = 0L
        val once = Once(InMemoryStore(ttl = 1000.milliseconds, clock = { now }))
        val runs = AtomicInteger(0)
        once.execute("k") { runs.incrementAndGet(); "v1" }
        now = 1000 // at the expiry boundary
        val again = once.execute("k") { runs.incrementAndGet(); "v2" }
        assertThat(again).isEqualTo("v2") // re-run, not replayed
        assertThat(runs.get()).isEqualTo(2)
    }

    @Test
    fun `purgeExpired frees expired entries`() {
        var now = 0L
        val store = InMemoryStore(ttl = 1000.milliseconds, clock = { now })
        val once = Once(store)
        repeat(10) { i -> once.execute("k$i") { "v$i" } }
        assertThat(store.size).isEqualTo(10)

        now = 1000
        assertThat(store.purgeExpired()).isEqualTo(10)
        assertThat(store.size).isEqualTo(0)
    }

    @Test
    fun `an in-flight key does not expire while its action is still running`() {
        var now = 0L
        val once = Once(InMemoryStore(ttl = 100.milliseconds, clock = { now }))
        val started = CountDownLatch(1)
        val mayFinish = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val runner = pool.submit<String> {
            once.execute("k") { started.countDown(); mayFinish.await(); "done" }
        }
        check(started.await(5, TimeUnit.SECONDS)) { "runner never started" }

        now = 1000 // advance well past the TTL while the action is still in flight
        val waiter = pool.submit<String> { once.execute("k") { "second-run" } }
        Thread.sleep(50) // let the waiter reach await()
        mayFinish.countDown()

        // the waiter must de-duplicate to the in-flight runner, not start a fresh run
        assertThat(runner.get(5, TimeUnit.SECONDS)).isEqualTo("done")
        assertThat(waiter.get(5, TimeUnit.SECONDS)).isEqualTo("done")
        pool.shutdown()
    }

    @Test
    fun `a stale claim token cannot complete a reused key`() {
        var now = 0L
        val store = InMemoryStore(ttl = 1000.milliseconds, clock = { now })

        // A claims and completes; its result replays within the TTL.
        val a = store.begin("k") as KeyState.New
        store.succeed("k", a.token, "A")
        assertThat(store.begin("k")).isEqualTo(KeyState.Done("A"))

        // The TTL elapses and B re-claims the same key with a fresh token.
        now = 1000
        val b = store.begin("k") as KeyState.New
        assertThat(b.token).isNotEqualTo(a.token)

        // A "revives" with its old token: fenced out; B still owns the in-flight claim.
        store.succeed("k", a.token, "A-again")
        assertThat(store.begin("k")).isEqualTo(KeyState.InProgress)

        store.succeed("k", b.token, "B")
        assertThat(store.begin("k")).isEqualTo(KeyState.Done("B"))
    }
}
