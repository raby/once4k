package com.digitalbluebird.once4k.benchmark

import com.digitalbluebird.once4k.InMemoryStore
import com.digitalbluebird.once4k.Once

/**
 * An indicative microbenchmark for the hot path: what an idempotency check costs when the key already
 * exists (the common case for a retried or de-duplicated request). Not JMH, but careful about the
 * usual traps: it warms the JIT, keeps results live through a volatile [sink], and reports the best of
 * several rounds. Run with `./gradlew benchmark`.
 */
private const val WARMUP = 200_000
private const val ITERATIONS = 2_000_000
private const val ROUNDS = 5

@Volatile
private var sink: Any? = null

private fun blackhole(value: Any?) {
    sink = value
}

private inline fun best(block: () -> Unit): Long {
    var min = Long.MAX_VALUE
    repeat(ROUNDS) {
        val start = System.nanoTime()
        block()
        min = minOf(min, System.nanoTime() - start)
    }
    return min
}

private fun report(label: String, totalNs: Long) {
    val perOp = totalNs.toDouble() / ITERATIONS
    println("%-24s %8.1f ns/op   %,13.0f ops/sec".format(label, perOp, 1_000_000_000.0 / perOp))
}

fun main() {
    val once = Once(InMemoryStore())
    once.execute("hot") { "cached-result" } // prime the key so every measured call replays

    repeat(WARMUP) { blackhole(once.execute<String>("hot") { error("should not run") }) }

    val ns = best {
        var acc = 0
        repeat(ITERATIONS) { acc += once.execute<String>("hot") { error("should not run") }.length }
        blackhole(acc)
    }

    println("once4k benchmark — cached-key replay (InMemoryStore)")
    println("iterations per round: %,d   rounds: %d   (best shown)".format(ITERATIONS, ROUNDS))
    println()
    report("execute (cache hit)", ns)
}
