package com.digitalbluebird.once4k

import redis.clients.jedis.UnifiedJedis
import redis.clients.jedis.params.SetParams

/**
 * A [RedisCommands] backed by a Jedis [UnifiedJedis] (a `JedisPooled`, `JedisCluster`, and so on).
 *
 * Jedis is a `compileOnly` dependency of once4k, so it is not forced on callers who use a different
 * store; add `redis.clients:jedis` to your build to use this adapter with [RedisStore].
 */
public class JedisRedisCommands(private val jedis: UnifiedJedis) : RedisCommands {
    override fun setIfAbsent(key: String, value: String, ttlMillis: Long): Boolean {
        val params = SetParams().nx()
        if (ttlMillis > 0) params.px(ttlMillis)
        return jedis.set(key, value, params) != null // "OK" when set, null when NX blocked it
    }

    override fun set(key: String, value: String, ttlMillis: Long) {
        val params = SetParams()
        if (ttlMillis > 0) params.px(ttlMillis)
        jedis.set(key, value, params)
    }

    override fun get(key: String): String? = jedis.get(key)

    override fun delete(key: String) {
        jedis.del(key)
    }

    override fun compareAndSet(key: String, expected: String, value: String, ttlMillis: Long): Boolean =
        jedis.eval(CAS_SCRIPT, 1, key, expected, value, ttlMillis.toString()) == 1L

    override fun compareAndDelete(key: String, expected: String): Boolean =
        jedis.eval(CAD_SCRIPT, 1, key, expected) == 1L

    private companion object {
        // Set only if the current value still matches our claim; honour PX when ttl > 0. One atomic EVAL.
        const val CAS_SCRIPT =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                "if tonumber(ARGV[3]) > 0 then redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]) " +
                "else redis.call('SET', KEYS[1], ARGV[2]) end return 1 else return 0 end"

        // Delete only if the current value still matches our claim.
        const val CAD_SCRIPT =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('DEL', KEYS[1]) return 1 else return 0 end"
    }
}
