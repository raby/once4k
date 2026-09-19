package com.digitalbluebird.once4k

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

/**
 * An in-memory [RedisCommands] that honours the semantics [RedisStore] relies on: atomic `SET NX`
 * (via [ConcurrentHashMap.putIfAbsent]) and `PX` expiry (via an injectable [clock]). It lets the
 * store's logic be tested without a live Redis; the Jedis adapter is the thin real binding.
 */
private class FakeRedisCommands(private val clock: () -> Long = System::currentTimeMillis) : RedisCommands {
    private class Entry(val value: String, val expiresAt: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    override fun setIfAbsent(key: String, value: String, ttlMillis: Long): Boolean {
        expireIfDue(key)
        return map.putIfAbsent(key, Entry(value, expiryAt(ttlMillis))) == null
    }

    override fun set(key: String, value: String, ttlMillis: Long) {
        map[key] = Entry(value, expiryAt(ttlMillis))
    }

    override fun get(key: String): String? {
        expireIfDue(key)
        return map[key]?.value
    }

    override fun delete(key: String) {
        map.remove(key)
    }

    override fun compareAndSet(key: String, expected: String, value: String, ttlMillis: Long): Boolean {
        expireIfDue(key)
        var set = false
        map.compute(key) { _, current -> // atomic per key in ConcurrentHashMap
            if (current != null && current.value == expected) {
                set = true
                Entry(value, expiryAt(ttlMillis))
            } else {
                current
            }
        }
        return set
    }

    override fun compareAndDelete(key: String, expected: String): Boolean {
        expireIfDue(key)
        var deleted = false
        map.compute(key) { _, current ->
            if (current != null && current.value == expected) {
                deleted = true
                null
            } else {
                current
            }
        }
        return deleted
    }

    private fun expiryAt(ttlMillis: Long) = if (ttlMillis > 0) clock() + ttlMillis else Long.MAX_VALUE

    private fun expireIfDue(key: String) {
        val entry = map[key] ?: return
        if (clock() >= entry.expiresAt) map.remove(key, entry)
    }
}

class RedisStoreTest {

    @Test
    fun `the same key runs once and replays the stored result`() {
        val once = Once(RedisStore(FakeRedisCommands()))
        val runs = AtomicInteger(0)
        assertThat(once.execute("k") { runs.incrementAndGet(); "hello" }).isEqualTo("hello")
        assertThat(once.execute("k") { runs.incrementAndGet(); "different" }).isEqualTo("hello")
        assertThat(runs.get()).isEqualTo(1)
    }

    @Test
    fun `different keys run independently`() {
        val once = Once(RedisStore(FakeRedisCommands()))
        assertThat(once.execute("a") { "A" }).isEqualTo("A")
        assertThat(once.execute("b") { "B" }).isEqualTo("B")
    }

    @Test
    fun `a null result is cached via the done-null tag`() {
        val once = Once(RedisStore(FakeRedisCommands()))
        val runs = AtomicInteger(0)
        assertThat(once.execute<String?>("k") { runs.incrementAndGet(); null }).isNull()
        assertThat(once.execute<String?>("k") { runs.incrementAndGet(); "not-null" }).isNull()
        assertThat(runs.get()).isEqualTo(1)
    }

    @Test
    fun `a completed key re-runs once its TTL has elapsed`() {
        var now = 0L
        val once = Once(RedisStore(FakeRedisCommands(clock = { now }), ttl = 1000.milliseconds))
        val runs = AtomicInteger(0)
        once.execute("k") { runs.incrementAndGet(); "v1" }
        now = 1000 // the Redis key expires server-side
        assertThat(once.execute("k") { runs.incrementAndGet(); "v2" }).isEqualTo("v2")
        assertThat(runs.get()).isEqualTo(2)
    }

    @Test
    fun `a failed action releases the key for retry, then caches the success`() {
        val once = Once(RedisStore(FakeRedisCommands()))
        val successes = AtomicInteger(0)
        assertFailsWith<IllegalStateException> {
            once.execute("k") { throw IllegalStateException("boom") }
        }
        assertThat(once.execute("k") { successes.incrementAndGet(); "ok" }).isEqualTo("ok")
        assertThat(once.execute("k") { successes.incrementAndGet(); "again" }).isEqualTo("ok")
        assertThat(successes.get()).isEqualTo(1)
    }

    @Test
    fun `a custom codec caches a non-string result`() {
        val store = RedisStore(FakeRedisCommands(), encode = { (it as Int).toString() }, decode = { it!!.toInt() })
        val once = Once(store)
        val runs = AtomicInteger(0)
        assertThat(once.execute("k") { runs.incrementAndGet(); 42 }).isEqualTo(42)
        assertThat(once.execute("k") { runs.incrementAndGet(); 99 }).isEqualTo(42)
        assertThat(runs.get()).isEqualTo(1)
    }

    @Test
    fun `concurrent callers with the same key run the action exactly once via SET NX`() {
        val once = Once(RedisStore(FakeRedisCommands()))
        val runs = AtomicInteger(0)
        val threads = 32
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
                        Thread.sleep(50)
                        "result-$n"
                    },
                )
                done.countDown()
            }
        }
        start.countDown()
        check(done.await(15, TimeUnit.SECONDS)) { "threads did not finish in time" }
        pool.shutdown()

        assertThat(runs.get()).isEqualTo(1)
        assertThat(results).hasSize(1)
    }

    @Test
    fun `when the running caller fails, a waiting caller takes over`() {
        val once = Once(RedisStore(FakeRedisCommands()))
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

        val waiter = pool.submit<String> { once.execute("k") { "recovered" } }
        Thread.sleep(50)
        runnerMayFail.countDown()

        assertThat(waiter.get(15, TimeUnit.SECONDS)).isEqualTo("recovered")
        runner.get(5, TimeUnit.SECONDS)
        pool.shutdown()
    }

    @Test
    fun `a crashed runner's claim is taken over once its lease lapses`() {
        var now = 0L
        val store = RedisStore(FakeRedisCommands(clock = { now }), lease = 1000.milliseconds)

        // A runner claims the key, then "crashes" without ever succeeding or abandoning.
        check(store.begin("k") is KeyState.New)
        // While the lease still holds, another caller must wait rather than run.
        assertThat(store.begin("k")).isEqualTo(KeyState.InProgress)

        now = 1000 // the lease lapses; Redis drops the in-flight key
        val runs = AtomicInteger(0)
        assertThat(Once(store).execute("k") { runs.incrementAndGet(); "recovered" }).isEqualTo("recovered")
        assertThat(runs.get()).isEqualTo(1) // the next caller took the key over and ran it once
    }

    @Test
    fun `a taken-over runner is fenced out and cannot clobber the new claim`() {
        var now = 0L
        val store = RedisStore(FakeRedisCommands(clock = { now }), lease = 1000.milliseconds)

        val a = store.begin("k") as KeyState.New // runner A claims the key
        now = 1000 // A's lease lapses; Redis drops the claim
        val b = store.begin("k") as KeyState.New // caller B takes it over with a fresh token
        assertThat(b.token).isNotEqualTo(a.token)

        // A "revives" with its stale token: both writes must be fenced out (no-ops).
        store.succeed("k", a.token, "A-result")
        store.abandon("k", a.token)
        assertThat(store.begin("k")).isEqualTo(KeyState.InProgress) // B still owns the in-flight claim

        // B finishes normally; its result is the one everyone sees.
        store.succeed("k", b.token, "B-result")
        assertThat(store.begin("k")).isEqualTo(KeyState.Done("B-result"))
    }
}
