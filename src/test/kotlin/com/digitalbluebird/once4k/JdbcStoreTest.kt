package com.digitalbluebird.once4k

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class JdbcStoreTest {

    // A fresh, isolated in-memory H2 database per test (kept alive for the test by DB_CLOSE_DELAY).
    private fun freshH2(): DataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:once4k-${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        user = "sa"
        password = ""
    }

    private fun store(
        ds: DataSource = freshH2(),
        ttl: kotlin.time.Duration = kotlin.time.Duration.INFINITE,
        clock: () -> Long = System::currentTimeMillis,
        encode: (Any?) -> String? = { it as String? },
        decode: (String?) -> Any? = { it },
    ) = JdbcStore(ds, ttl = ttl, clock = clock, encode = encode, decode = decode).also { it.initSchema() }

    @Test
    fun `the same key runs once and replays the stored result`() {
        val once = Once(store())
        val runs = AtomicInteger(0)
        val first = once.execute("k") { runs.incrementAndGet(); "hello" }
        val second = once.execute("k") { runs.incrementAndGet(); "different" }
        assertThat(first).isEqualTo("hello")
        assertThat(second).isEqualTo("hello")
        assertThat(runs.get()).isEqualTo(1)
    }

    @Test
    fun `different keys run independently`() {
        val once = Once(store())
        assertThat(once.execute("a") { "A" }).isEqualTo("A")
        assertThat(once.execute("b") { "B" }).isEqualTo("B")
    }

    @Test
    fun `a completed key re-runs once its TTL has elapsed`() {
        var now = 0L
        val once = Once(store(ttl = 1000.milliseconds, clock = { now }))
        val runs = AtomicInteger(0)
        once.execute("k") { runs.incrementAndGet(); "v1" }
        now = 1000
        assertThat(once.execute("k") { runs.incrementAndGet(); "v2" }).isEqualTo("v2")
        assertThat(runs.get()).isEqualTo(2)
    }

    @Test
    fun `a failed action releases the key for retry, then caches the success`() {
        val once = Once(store())
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
        val once = Once(store(encode = { (it as Int).toString() }, decode = { it!!.toInt() }))
        val runs = AtomicInteger(0)
        assertThat(once.execute("k") { runs.incrementAndGet(); 42 }).isEqualTo(42)
        assertThat(once.execute("k") { runs.incrementAndGet(); 99 }).isEqualTo(42) // cached via codec
        assertThat(runs.get()).isEqualTo(1)
    }

    @Test
    fun `concurrent callers with the same key run the action exactly once via the INSERT gate`() {
        val once = Once(store())
        val runs = AtomicInteger(0)
        val threads = 16
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
                        Thread.sleep(50) // widen the race window
                        "result-$n"
                    },
                )
                done.countDown()
            }
        }
        start.countDown()
        check(done.await(15, TimeUnit.SECONDS)) { "threads did not finish in time" }
        pool.shutdown()

        assertThat(runs.get()).isEqualTo(1) // the DB's primary key let exactly one caller run
        assertThat(results).hasSize(1) // every caller shared that result
    }

    @Test
    fun `when the running caller fails, a waiting caller takes over across the store`() {
        val once = Once(store())
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
        Thread.sleep(50) // let the waiter reach await()
        runnerMayFail.countDown()

        assertThat(waiter.get(15, TimeUnit.SECONDS)).isEqualTo("recovered")
        runner.get(5, TimeUnit.SECONDS)
        pool.shutdown()
    }
}
