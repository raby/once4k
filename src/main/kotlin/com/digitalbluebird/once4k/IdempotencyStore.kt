package com.digitalbluebird.once4k

/** The lifecycle state of an idempotency key, as reported by [IdempotencyStore.begin]. */
public sealed interface KeyState {
    /**
     * The key was free and is now claimed by this caller, which must run the action and then call
     * [IdempotencyStore.succeed] or [IdempotencyStore.abandon].
     */
    public data object New : KeyState

    /** Another caller is running the action for this key right now; wait via [IdempotencyStore.await]. */
    public data object InProgress : KeyState

    /** The action already completed for this key; [result] is its stored value. */
    public data class Done(val result: Any?) : KeyState
}

/** The outcome of awaiting an in-flight key via [IdempotencyStore.await]. */
public sealed interface AwaitOutcome {
    /** The runner finished successfully; [result] is its value. */
    public data class Ready(val result: Any?) : AwaitOutcome

    /** The runner released the key (its action failed, or the key expired); the caller should retry. */
    public data object Retry : AwaitOutcome
}

/**
 * Pluggable storage for idempotency keys: where a key's in-flight and completed state lives.
 *
 * [begin] must be **atomic**: of two callers racing on the same free key, at most one may receive
 * [KeyState.New]. Implementations back this with in-memory concurrency, a database row and a unique
 * constraint, a Redis `SET NX`, and so on. The [Once] executor drives the lifecycle; a store only has
 * to make each operation correct.
 */
public interface IdempotencyStore {
    /** Atomically claim [key] for this caller, or report its current [KeyState]. */
    public fun begin(key: String): KeyState

    /** Record a successful [result] for a key this caller claimed with [KeyState.New]. */
    public fun succeed(key: String, result: Any?)

    /** Release a key this caller claimed but whose action failed, so it can be retried. */
    public fun abandon(key: String)

    /** Wait for the in-flight [key] (claimed by another caller) to finish, then report the outcome. */
    public fun await(key: String): AwaitOutcome
}
