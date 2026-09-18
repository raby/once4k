package com.digitalbluebird.once4k

/**
 * Runs actions at most once per idempotency key, against a pluggable [store].
 *
 * ```
 * val once = Once(InMemoryStore())
 * val result = once.execute("charge:order-42") { paymentGateway.charge(order) }
 * ```
 *
 * The first caller for a key runs the action and its result is stored; concurrent and later callers
 * with the same key receive that stored result without re-running. A failed action releases the key,
 * so a later call can retry and a concurrent caller can take over rather than all failing.
 *
 * A compiled [Once] is stateless beyond its [store] and safe to share across threads.
 */
public class Once(private val store: IdempotencyStore) {

    /**
     * Return the result of [action] for [key], running it at most once for a successful outcome.
     *
     * A successfully completed key replays its stored result; an in-flight key waits for and shares
     * the running caller's result. If the action throws, the exception propagates to the caller that
     * ran it and the key is released for a later retry.
     *
     * The stored result is cast to [T]; reusing one key for actions with different result types is a
     * misuse that surfaces as a [ClassCastException] at the call site.
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T> execute(key: String, action: () -> T): T {
        while (true) {
            when (val state = store.begin(key)) {
                is KeyState.Done -> return state.result as T
                KeyState.New -> return runClaimed(key, action)
                KeyState.InProgress -> when (val outcome = store.await(key)) {
                    is AwaitOutcome.Ready -> return outcome.result as T
                    AwaitOutcome.Retry -> {} // the runner released the key; loop and try to claim it
                }
            }
        }
    }

    private fun <T> runClaimed(key: String, action: () -> T): T {
        val result = try {
            action()
        } catch (e: Throwable) {
            store.abandon(key)
            throw e
        }
        store.succeed(key, result)
        return result
    }
}
