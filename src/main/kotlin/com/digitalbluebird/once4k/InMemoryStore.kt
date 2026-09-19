package com.digitalbluebird.once4k

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * An in-memory [IdempotencyStore] backed by a [ConcurrentHashMap] of per-key futures. Correct within
 * a single process (a sensible default, and the basis for the concurrency tests); a distributed
 * deployment wants a shared store (JDBC, Redis).
 *
 * Each key holds a [CompletableFuture]. The caller that atomically creates the entry is the runner
 * and completes the future; concurrent callers await it. A failed action removes the entry and
 * completes its future exceptionally, waking waiters to retry.
 *
 * A completed key replays its result only for [ttl]; after that it re-runs. Expired entries are
 * reclaimed lazily when a key is next seen, and swept opportunistically as keys are begun, so the map
 * does not grow without bound. An in-flight entry never expires while its action is running. [clock]
 * (epoch millis) is injectable for testing; it defaults to the wall clock.
 */
public class InMemoryStore(
    private val ttl: Duration = 24.hours,
    private val clock: () -> Long = System::currentTimeMillis,
) : IdempotencyStore {

    private class Slot(val token: Long) {
        val future = CompletableFuture<Any?>()

        /** When the completed result stops replaying; [Long.MAX_VALUE] while in-flight or never-expiring. */
        @Volatile
        var expiresAt: Long = Long.MAX_VALUE
    }

    private val slots = ConcurrentHashMap<String, Slot>()
    private val opsSinceSweep = AtomicInteger(0)
    private val tokenSeq = AtomicLong(0) // mints a unique claim token per Slot

    override fun begin(key: String): KeyState {
        maybeSweep()
        while (true) {
            val fresh = Slot(tokenSeq.incrementAndGet())
            val existing = slots.putIfAbsent(key, fresh) ?: return KeyState.New(FenceToken(fresh.token))
            if (!existing.future.isDone) return KeyState.InProgress
            if (isExpired(existing)) {
                slots.remove(key, existing) // stale result: drop it and reclaim the key
                continue
            }
            try {
                return KeyState.Done(existing.future.join())
            } catch (e: CompletionException) {
                slots.remove(key, existing) // the runner abandoned this key
            }
        }
    }

    override fun succeed(key: String, token: FenceToken, result: Any?) {
        val slot = slots[key] ?: return
        if (slot.token != token.value) return // the claim was taken over; this write is fenced out
        slot.expiresAt = expiryFrom(clock()) // set before completing, so a racing reader sees it
        slot.future.complete(result)
    }

    override fun abandon(key: String, token: FenceToken) {
        val slot = slots[key] ?: return
        if (slot.token != token.value) return // the claim was taken over; do not disturb it
        if (slots.remove(key, slot)) slot.future.completeExceptionally(AbandonedException())
    }

    override fun await(key: String): AwaitOutcome {
        val slot = slots[key] ?: return AwaitOutcome.Retry // already gone (abandoned or expired)
        return try {
            AwaitOutcome.Ready(slot.future.join())
        } catch (e: CompletionException) {
            AwaitOutcome.Retry
        }
    }

    /** Number of keys currently tracked (in-flight or completed-and-unexpired). Useful for metrics. */
    public val size: Int
        get() = slots.size

    /** Remove every completed entry whose [ttl] has elapsed. Returns how many were removed. */
    public fun purgeExpired(): Int {
        var removed = 0
        for ((key, slot) in slots) {
            if (slot.future.isDone && isExpired(slot) && slots.remove(key, slot)) removed++
        }
        return removed
    }

    private fun isExpired(slot: Slot): Boolean = clock() >= slot.expiresAt

    private fun expiryFrom(now: Long): Long =
        if (ttl == Duration.INFINITE) Long.MAX_VALUE else now + ttl.inWholeMilliseconds

    /** Amortised, thread-free cleanup: sweep expired entries once every [SWEEP_INTERVAL] begins. */
    private fun maybeSweep() {
        if (opsSinceSweep.incrementAndGet() >= SWEEP_INTERVAL) {
            opsSinceSweep.set(0)
            purgeExpired()
        }
    }

    private companion object {
        const val SWEEP_INTERVAL = 512
    }
}

/** Completes an abandoned key's future exceptionally, so awaiting callers retry rather than replay. */
internal class AbandonedException : RuntimeException()
