# Changelog

All notable changes to this project are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Storage engine for SQLite, MySQL/MariaDB and PostgreSQL with one schema and idempotent, versioned
  migrations; 64-bit event ids (time, server node, sequence) generated without a database round trip;
  chunk-major packed block positions; dictionaries for worlds, actors and block/item/entity types that
  are safe with any number of writers.
- A lock-free capture queue and a single writer thread with batching, retry with back-off, and a disk
  spool for events that could not be written before shutdown.
- An archive tier: events older than `archive.after-hours` move into deflated per-region column
  segments (about 12 bytes per block event on SQLite, against about 80 for CoreProtect's relational
  layout); every read merges both tiers.
- CoreProtect-compatible parameters (`u: t: r: a: i: e: s: #preview #count`) for lookups, rollbacks,
  restores and purges.
- Multi-server operation: shared databases, shards read in parallel for network lookups, network
  rollbacks and restores as durable jobs, a Redis pub/sub bus with database polling as the fallback,
  heartbeats and `/hawkeye network`.
- Capture of block breaks and places, explosions (attributed to the player who lit the TNT), fire,
  fluids, pistons, natural changes, buckets, signs, interactions, entity block changes, kills,
  container transactions, hoppers (off by default), item drops and pickups, chat, commands (with
  redaction) and sessions.
- `/hawkeye lookup`, `near`, `page` and `tp` with clickable results, `#count`, sessions with pinned
  pages, and lookups across shards merged newest first with per-shard warnings.
- The inspector (`/hawkeye inspect`): block history, container transactions, the adjacent block.
- `/hawkeye rollback` and `restore` with chunk-by-chunk application on the owning region (blocks,
  container contents, sign text, container transactions, killed entities), `#preview` with `apply`
  and `cancel`, `undo`, network rollbacks with `s:`, and `/hawkeye purge` plus a lease-guarded
  automatic purge.
- `/hawkeye`, `help`, `status` (queue, writer, archive and network jobs) and `reload` with every
  permission node under `hawkeye.*`.
- `HawkeyeApi` in Bukkit's `ServicesManager` for other plugins.
- `config.yml` and `messages.yml` with every default documented; invalid files keep the previous
  configuration, and restart-only changes are reported.
- MockBukkit, SQLite and opt-in MySQL/PostgreSQL integration tests, an ArchUnit layer check, a
  storage footprint guard, `verifyPluginJar`, and a real-server smoke test (`scripts/smoke`).
