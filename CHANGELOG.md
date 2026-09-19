# Changelog

All notable changes to once4k are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `Once.execute(key) { … }`: runs an action at most once per idempotency key, replaying the stored
  result for repeats and de-duplicating concurrent callers.
- `IdempotencyStore` SPI (`begin` / `succeed` / `abandon` / `await`) with a `KeyState` /
  `AwaitOutcome` lifecycle; a failed action releases the key so a later or concurrent caller retries.
- `InMemoryStore`: per-key `CompletableFuture`s for concurrent de-duplication, with per-key TTL
  expiry, opportunistic sweeping, and a `size` / `purgeExpired()` for observability.
- `JdbcStore`: idempotency state in one table, shared across processes; the atomic claim is an
  `INSERT` guarded by the primary key, in-flight callers poll via `await`, results go through a
  pluggable codec, and completed keys expire by TTL.
- `RedisStore`: idempotency state in Redis, shared across processes; the atomic claim is `SET NX`,
  TTL is delegated to Redis, and it runs against a small `RedisCommands` port with a `compileOnly`
  Jedis adapter (`JedisRedisCommands`).
- GitHub Actions CI (build + test).
- Maven Central (Central Portal) publishing configuration — see [PUBLISHING.md](PUBLISHING.md).

[Unreleased]: https://github.com/raby/once4k/commits/main
