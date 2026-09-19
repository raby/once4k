package com.digitalbluebird.once4k

import java.sql.Connection
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.Types
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A JDBC-backed [IdempotencyStore]: idempotency state lives in one table, so many processes and
 * instances share it. The atomic claim in [begin] is an `INSERT` guarded by the table's primary key —
 * of concurrent callers racing on a fresh key, exactly one `INSERT` succeeds and the rest see the
 * constraint violation and read the winner's row.
 *
 * Results are stored as text via [encode] / [decode]; the default codec handles `String` and `null`
 * results, and throws for anything else (supply a codec, e.g. JSON, to cache richer types). Rows are
 * created by [begin]; call [initSchema] once at startup, or create the table yourself.
 *
 * A completed key replays for [ttl] (default 24h) and then re-runs. [await] polls for an in-flight
 * key held by another process, up to [awaitTimeout], before reporting [AwaitOutcome.Retry]. [clock]
 * (epoch millis, for expiry) is injectable for testing.
 */
public class JdbcStore(
    private val dataSource: DataSource,
    private val ttl: Duration = 24.hours,
    private val clock: () -> Long = System::currentTimeMillis,
    private val awaitTimeout: Duration = 10.seconds,
    private val pollInterval: Duration = 25.milliseconds,
    private val encode: (Any?) -> String? = ::defaultEncode,
    private val decode: (String?) -> Any? = { it },
) : IdempotencyStore {

    /** Create the idempotency table if it does not already exist. Safe to call repeatedly. */
    public fun initSchema() {
        connection { c ->
            c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS $TABLE " +
                    "(id VARCHAR(255) PRIMARY KEY, state VARCHAR(16) NOT NULL, result CLOB, expires_at BIGINT)",
            ).use { it.executeUpdate() }
        }
    }

    override fun begin(key: String): KeyState {
        while (true) {
            if (tryClaim(key)) return KeyState.New

            // The row exists: read and interpret it.
            val row = readRow(key) ?: continue // vanished (abandoned) between the INSERT and the read
            when (row.state) {
                IN_PROGRESS -> return KeyState.InProgress
                DONE -> {
                    if (row.isExpired(clock())) {
                        deleteExpired(key, row.expiresAt)
                        continue // reclaim the key on the next loop
                    }
                    return KeyState.Done(decode(row.result))
                }
                else -> error("unexpected idempotency state '${row.state}' for key '$key'")
            }
        }
    }

    override fun succeed(key: String, result: Any?) {
        val expiresAt = if (ttl == Duration.INFINITE) null else clock() + ttl.inWholeMilliseconds
        connection { c ->
            c.prepareStatement("UPDATE $TABLE SET state = '$DONE', result = ?, expires_at = ? WHERE id = ? AND state = '$IN_PROGRESS'").use { ps ->
                ps.setString(1, encode(result))
                if (expiresAt == null) ps.setNull(2, Types.BIGINT) else ps.setLong(2, expiresAt)
                ps.setString(3, key)
                ps.executeUpdate()
            }
        }
    }

    override fun abandon(key: String) {
        connection { c ->
            c.prepareStatement("DELETE FROM $TABLE WHERE id = ? AND state = '$IN_PROGRESS'").use { ps ->
                ps.setString(1, key)
                ps.executeUpdate()
            }
        }
    }

    override fun await(key: String): AwaitOutcome {
        val deadline = System.currentTimeMillis() + awaitTimeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            val row = readRow(key) ?: return AwaitOutcome.Retry // gone (abandoned): reclaim
            when (row.state) {
                DONE -> return AwaitOutcome.Ready(decode(row.result))
                IN_PROGRESS -> Thread.sleep(pollInterval.inWholeMilliseconds)
                else -> error("unexpected idempotency state '${row.state}' for key '$key'")
            }
        }
        return AwaitOutcome.Retry // timed out waiting; let the caller try to reclaim
    }

    /** Delete every completed row whose TTL has elapsed. Returns how many were removed. */
    public fun purgeExpired(): Int = connection { c ->
        c.prepareStatement("DELETE FROM $TABLE WHERE state = '$DONE' AND expires_at IS NOT NULL AND expires_at <= ?").use { ps ->
            ps.setLong(1, clock())
            ps.executeUpdate()
        }
    }

    /** INSERT the claim row; the primary key makes this the atomic gate. Returns whether we won it. */
    private fun tryClaim(key: String): Boolean = try {
        connection { c ->
            c.prepareStatement("INSERT INTO $TABLE (id, state, expires_at) VALUES (?, '$IN_PROGRESS', NULL)").use { ps ->
                ps.setString(1, key)
                ps.executeUpdate()
            }
        }
        true
    } catch (e: SQLIntegrityConstraintViolationException) {
        false // another caller already holds this key
    }

    private class Row(val state: String, val result: String?, val expiresAt: Long?) {
        fun isExpired(now: Long): Boolean = expiresAt != null && now >= expiresAt
    }

    private fun readRow(key: String): Row? = connection { c ->
        c.prepareStatement("SELECT state, result, expires_at FROM $TABLE WHERE id = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    Row(
                        state = rs.getString("state"),
                        result = rs.getString("result"),
                        expiresAt = rs.getLong("expires_at").takeUnless { rs.wasNull() },
                    )
                }
            }
        }
    }

    private fun deleteExpired(key: String, expiresAt: Long?) {
        if (expiresAt == null) return
        connection { c ->
            c.prepareStatement("DELETE FROM $TABLE WHERE id = ? AND state = '$DONE' AND expires_at = ?").use { ps ->
                ps.setString(1, key)
                ps.setLong(2, expiresAt)
                ps.executeUpdate()
            }
        }
    }

    private inline fun <T> connection(block: (Connection) -> T): T = dataSource.connection.use(block)

    private companion object {
        const val TABLE = "idempotency_keys"
        const val IN_PROGRESS = "IN_PROGRESS"
        const val DONE = "DONE"
    }
}

/** The default result codec: stores `String` and `null` verbatim, and refuses anything else. */
private fun defaultEncode(value: Any?): String? = when (value) {
    null -> null
    is String -> value
    else -> throw IllegalArgumentException(
        "JdbcStore's default codec stores only String and null results; " +
            "provide encode/decode to cache ${value::class.simpleName}",
    )
}
