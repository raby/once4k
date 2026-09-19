# once4k

**At-most-once execution for JVM services**, keyed by an idempotency key, written in Kotlin.

Wrap a side-effecting operation in `execute(key) { … }` and it runs **once** per key: a retry (the
classic double-tap on a slow network) returns the first result instead of doing the work again, and
concurrent callers with the same key collapse to a single execution.

> **Status: early / work in progress.** The core executor, an in-memory store (with TTL expiry), a
> JDBC store and a Redis store are in, with concurrency tests. A Spring integration is next. Not yet
> published to Maven Central.

## Why

Idempotency is easy to describe and subtle to get right: two requests race, an in-flight request has
to be de-duplicated, a result has to be replayed, and a failure must not poison the key forever.
Services hand-roll this badly. `once4k` is a small, correct, well-tested core with a pluggable store.

## Example (target API)

```kotlin
val once = Once(InMemoryStore())

val charge = once.execute("charge:order-42") {
    paymentGateway.charge(order)   // runs exactly once for this key
}
// a second call with "charge:order-42" returns the first charge, and never charges again
```

## Guarantees

- **At-most-once execution** per key: a completed key replays its stored result; the action does not
  run again.
- **Concurrent de-duplication**: many threads calling with the same key run the action once; the
  rest wait for and share that result.
- **Failure is retryable**: if the action throws, the key is released (not cached), so a later call
  can try again, and a concurrent caller takes over rather than all failing.
- **Keys expire**: a completed key replays its result for a configurable TTL (default 24h), then
  re-runs; expired entries are reclaimed so the in-memory store does not grow without bound.

For true exactly-once with an external side effect, record the result in the same transaction as the
effect (an advanced pattern the store SPI is designed to allow).

## Stores

- **`InMemoryStore`** — a `ConcurrentHashMap` of per-key futures; correct within one process, with
  TTL expiry.
- **`JdbcStore`** — idempotency state in one table, shared across processes. The atomic claim is an
  `INSERT` guarded by the primary key, so concurrent callers on a fresh key resolve to one runner;
  results are stored via a codec (`String` and `null` by default, or supply your own for richer
  types). Call `initSchema()` at startup, and pass a pooled `DataSource`.
- **`RedisStore`** — idempotency state in Redis, shared across processes and instances. The atomic
  claim is `SET NX`, and TTL is delegated to Redis. It runs against a small `RedisCommands` port; a
  `JedisRedisCommands` adapter provides the binding (Jedis is `compileOnly`, so add it only if you
  use this store).

A store only has to implement the small `IdempotencyStore` SPI (`begin` / `succeed` / `abandon` /
`await`), so a distributed cache or a bespoke backend slots in the same way.

## Benchmark

`./gradlew benchmark` runs an indicative harness (JIT warmup, best of five rounds, results kept
live). A **cache hit** — an idempotency check when the key already exists, the common case for a
retry or a de-duplicated request — costs about **30 ns** (~33M/sec) with the in-memory store, so the
guarantee is essentially free on the hot path. (Figures are indicative and machine-dependent.)

## Building

```bash
./gradlew build
```

Requires a JDK; the Gradle wrapper fetches the rest.

## Licence

[MIT](LICENSE).
