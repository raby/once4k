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
}
