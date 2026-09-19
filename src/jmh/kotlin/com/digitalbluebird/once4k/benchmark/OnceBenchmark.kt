package com.digitalbluebird.once4k.benchmark

import com.digitalbluebird.once4k.InMemoryStore
import com.digitalbluebird.once4k.Once
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.util.concurrent.TimeUnit

/**
 * JMH microbenchmark for the hot path: what an idempotency check costs when the key already exists —
 * the common case for a retried or de-duplicated request, where `execute` replays the stored result
 * without re-running the action. JMH returns the value to its Blackhole, so the call is not optimised
 * away. Run with `./gradlew jmh`.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public open class OnceBenchmark {

    private val once = Once(InMemoryStore())

    @Setup
    public fun prime() {
        once.execute("hot") { "cached-result" } // prime the key once, so every measured call replays
    }

    @Benchmark
    public fun cacheHit(): String = once.execute<String>("hot") { error("a primed key must not run the action") }
}
