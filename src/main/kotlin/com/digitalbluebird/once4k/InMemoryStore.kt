package com.digitalbluebird.once4k

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/**
 * An in-memory [IdempotencyStore] backed by a [ConcurrentHashMap] of per-key futures. Correct within
 * a single process (a sensible default, and the basis for the concurrency tests); a distributed
 * deployment wants a shared store (JDBC, Redis).
 *
 * Each key holds a [CompletableFuture]. The caller that atomically creates the entry is the runner
 * and completes the future; concurrent callers await it. A failed action removes the entry and
 * completes its future exceptionally, waking waiters to retry.
 *
 * Completed entries persist so their result can replay, so the map grows with the number of distinct
 * successful keys; a size bound or per-entry TTL is a later addition.
 */
public class InMemoryStore : IdempotencyStore {
    private val slots = ConcurrentHashMap<String, CompletableFuture<Any?>>()

    override fun begin(key: String): KeyState {
        while (true) {
            val fresh = CompletableFuture<Any?>()
            val existing = slots.putIfAbsent(key, fresh) ?: return KeyState.New
            if (!existing.isDone) return KeyState.InProgress
            try {
                return KeyState.Done(existing.join())
            } catch (e: CompletionException) {
                // The runner abandoned this key; drop the dead entry and loop to reclaim it.
                slots.remove(key, existing)
            }
        }
    }

    override fun succeed(key: String, result: Any?) {
        slots[key]?.complete(result)
    }

    override fun abandon(key: String) {
        slots.remove(key)?.completeExceptionally(AbandonedException())
    }

    override fun await(key: String): AwaitOutcome {
        val slot = slots[key] ?: return AwaitOutcome.Retry // already gone (abandoned or expired)
        return try {
            AwaitOutcome.Ready(slot.join())
        } catch (e: CompletionException) {
            AwaitOutcome.Retry
        }
    }
}

/** Completes an abandoned key's future exceptionally, so awaiting callers retry rather than replay. */
internal class AbandonedException : RuntimeException()
