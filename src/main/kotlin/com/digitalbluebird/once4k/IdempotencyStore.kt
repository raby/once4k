package com.digitalbluebird.once4k

/**
 * A fence token identifying one claim of a key. [IdempotencyStore.begin] mints a fresh token with every
 * [KeyState.New], and [IdempotencyStore.succeed] / [IdempotencyStore.abandon] must present it: a store
 * applies the write only while the token is still the key's current claim. So a runner whose claim was
 * taken over (its lease lapsed) cannot clobber the key — its write is silently ignored.
 *
 * The token is opaque and store-minted; it need only be **unique per claim**, since the store arbitrates
 * by equality. (It is deliberately not a monotonic, client-side fencing token for an external resource —
 * here the store itself is the single arbiter, so uniqueness is sufficient.)
 */
@JvmInline
public value class FenceToken(public val value: Long)

/** The lifecycle state of an idempotency key, as reported by [IdempotencyStore.begin]. */
public sealed interface KeyState {
    /**
     * The key was free and is now claimed by this caller, which must run the action and then call
     * [IdempotencyStore.succeed] or [IdempotencyStore.abandon] presenting this claim's [token].
     */
    public data class New(val token: FenceToken) : KeyState

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
 * [KeyState.New], and it mints a [FenceToken] with that claim. [succeed] and [abandon] are **fenced** —
 * the store applies each only while [token] is still the key's current claim, so a runner that was taken
 * over after its lease lapsed cannot overwrite the new runner's result or delete its live claim.
 *
 * Implementations back this with in-memory concurrency, a database row and a unique constraint, a Redis
 * `SET NX`, and so on. The [Once] executor drives the lifecycle; a store only has to make each operation
 * correct.
 */
public interface IdempotencyStore {
    /** Atomically claim [key] for this caller (minting a [FenceToken]), or report its current [KeyState]. */
    public fun begin(key: String): KeyState

    /** Record a successful [result] for a key claimed with [token]; a no-op if that claim was superseded. */
    public fun succeed(key: String, token: FenceToken, result: Any?)

    /** Release a key claimed with [token] so it can be retried; a no-op if that claim was superseded. */
    public fun abandon(key: String, token: FenceToken)

    /** Wait for the in-flight [key] (claimed by another caller) to finish, then report the outcome. */
    public fun await(key: String): AwaitOutcome
}
