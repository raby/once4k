package com.digitalbluebird.once4k

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The few Redis operations [RedisStore] needs, behind a small port so the store can be tested without
 * a live Redis and bound to any client. A `ttlMillis` of zero or less means "no expiry".
 */
public interface RedisCommands {
    /** `SET key value NX PX ttl`: set only if absent. Returns whether it was set (the claim succeeded). */
    public fun setIfAbsent(key: String, value: String, ttlMillis: Long): Boolean

    /** `SET key value PX ttl`: set unconditionally. */
    public fun set(key: String, value: String, ttlMillis: Long)

    /** `GET key`, or null if absent. */
    public fun get(key: String): String?

    /** `DEL key`. */
    public fun delete(key: String)
}

/**
 * A Redis-backed [IdempotencyStore], shared across processes and instances. The atomic claim in
 * [begin] is `SET key NX` — of concurrent callers racing on a fresh key, only one set succeeds. TTL
 * is delegated to Redis (the key carries a `PX` expiry), so keys expire server-side and there is no
 * separate sweep.
 *
 * Each idempotency key becomes a Redis key (under [keyPrefix]) whose value carries a one-character
 * state tag: in-progress, done-with-a-value (through [encode] / [decode]), or done-null. An in-flight
 * claim carries a [lease] so a crashed runner's key eventually frees (size the lease above your
 * slowest action; lease renewal for very long actions is a later addition). [await] polls up to
 * [awaitTimeout] for a key held by another process.
 */
public class RedisStore(
    private val redis: RedisCommands,
    private val ttl: Duration = 24.hours,
    private val lease: Duration = 5.minutes,
    private val keyPrefix: String = "once4k:",
    private val awaitTimeout: Duration = 10.seconds,
    private val pollInterval: Duration = 25.milliseconds,
    private val encode: (Any?) -> String? = { it as String? },
    private val decode: (String?) -> Any? = { it },
) : IdempotencyStore {

    override fun begin(key: String): KeyState {
        val k = keyPrefix + key
        while (true) {
            if (redis.setIfAbsent(k, IN_PROGRESS.toString(), lease.inWholeMilliseconds)) return KeyState.New
            val value = redis.get(k) ?: continue // expired or deleted between the NX and the GET; retry
            return when (value.tag()) {
                IN_PROGRESS -> KeyState.InProgress
                DONE_NULL -> KeyState.Done(null)
                else -> KeyState.Done(decode(value.substring(1)))
            }
        }
    }

    override fun succeed(key: String, result: Any?) {
        val encoded = encode(result)
        val value = if (encoded == null) DONE_NULL.toString() else "$DONE$encoded"
        redis.set(keyPrefix + key, value, ttlMillis(ttl))
    }

    override fun abandon(key: String) {
        redis.delete(keyPrefix + key)
    }

    override fun await(key: String): AwaitOutcome {
        val k = keyPrefix + key
        val deadline = System.currentTimeMillis() + awaitTimeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            val value = redis.get(k) ?: return AwaitOutcome.Retry // gone (abandoned or expired): reclaim
            when (value.tag()) {
                IN_PROGRESS -> Thread.sleep(pollInterval.inWholeMilliseconds)
                DONE_NULL -> return AwaitOutcome.Ready(null)
                else -> return AwaitOutcome.Ready(decode(value.substring(1)))
            }
        }
        return AwaitOutcome.Retry // timed out waiting; let the caller try to reclaim
    }

    private fun String.tag(): Char = firstOrNull() ?: error("corrupt once4k value: empty string")

    private fun ttlMillis(duration: Duration): Long =
        if (duration == Duration.INFINITE) 0L else duration.inWholeMilliseconds

    private companion object {
        const val IN_PROGRESS = 'P' // an in-flight claim
        const val DONE = 'D' // done, with an encoded result following the tag
        const val DONE_NULL = 'N' // done, with a null result
    }
}
