# once4k

**At-most-once execution for JVM services**, keyed by an idempotency key, written in Kotlin.

Wrap a side-effecting operation in `execute(key) { … }` and it runs **once** per key: a retry (the
classic double-tap on a slow network) returns the first result instead of doing the work again, and
concurrent callers with the same key collapse to a single execution.

> **Status: early / work in progress.** The core executor and an in-memory store (with TTL expiry)
> are in, with concurrency tests. Pluggable stores (JDBC, Redis) and a Spring integration are next.
> Not yet published.

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

## Building

```bash
./gradlew build
```

Requires a JDK; the Gradle wrapper fetches the rest.

## Licence

[MIT](LICENSE).
