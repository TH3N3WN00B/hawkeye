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
- An archive tier: settled events are packed 262,144 at a time into immutable column segments
  (archive format 2: zstd frames, units of 16,384 rows read by byte range, ranks implied by the
  stored order), about 1.2 bytes per event on replayed real history and about 6 per random block
  event on SQLite, against about 80 for CoreProtect's relational layout; rolled-back flags of
  archived events live beside the segments; every read merges both tiers. An archive read matches a
  lookup's place, actor, action, type and rollback conditions on a unit's columns before it decodes
  ids or builds rows, and block and area lookups skip the chat and command rows of segments whose
  players were elsewhere.
- A segment index (schema version 5: `region_segment`, `block_segment`, `actor_segment`,
  `segment_index`; it replaces version 4's `place_region`, `place_chunk` and `place_state`, and every
  archive is indexed again): per chunk, the archive segments holding rows there, and for chunks active
  in more than 16 segments, per position its newest 64 segments exactly and only older ones in buckets
  of 16 segments. It is kept up to date in the transaction that packs, rewrites or purges a segment,
  and the archiver adds segments written before it existed (newest first, up to 20 s per run). A
  block or area lookup reads only the segments that can hold the place, so a block never touched
  inside a chunk that is active all the time no longer reads every segment, and a block with little
  history inside it reads only the segments that hold it. Per actor, the segments holding its rows: a
  lookup by up to 64 players reads only their segments instead of testing every segment's header,
  and a lookup by player and place reads only the segments both name.
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
- Opening a lookup or an inspection counts at most the 16 newest archive segments with matches (the
  hot table always exactly), so a large result shows lower bounds (`160+`, new message
  `lookup.at-least`, default `<value>+`) instead of making the first page wait seconds for the whole
  history; `/hawkeye page` walks past a lower bound until the events end, and `#count` and
  `HawkeyeApi#count` stay exact.
- The inspector (`/hawkeye inspect`): block history, container transactions, the adjacent block.
- `/hawkeye rollback` and `restore` with chunk-by-chunk application on the owning region (blocks,
  container contents, sign text, container transactions, killed entities), `#preview` with `apply`
  and `cancel`, `undo`, network rollbacks with `s:`, and `/hawkeye purge` plus a lease-guarded
  automatic purge.
- `/hawkeye`, `help`, `status` (queue, writer, archive and network jobs) and `reload` with every
  permission node under `hawkeye.*`.
- `HawkeyeApi` in Bukkit's `ServicesManager` for other plugins.
- Compact crop growth (`BLOCK_GROW`: ripe crops and newly grown blocks under `#growth`, every stage
  with `logging.crop-growth-stages`), bone meal, cauldrons, sponges, portals, berries, sheared
  pumpkins and beehives, flower pots, dispensers, dragon eggs, the TNT block itself and exploding
  beds and respawn anchors.
- Attached blocks (`logging.attached-blocks`): blocks that fall off when their support goes, cascades
  included, under whoever removed the support.
- Cause attribution: TNT lit by redstone or fire counts for its placer, withers for their builder,
  end crystals and creepers for the player who hit or lit them, liquid flows for the player who
  poured them (`logging.liquid-tracking`).
- Entities: placed and spawned (armour stands, boats, minecarts, paintings, item frames, end
  crystals, spawn eggs, built golems and withers, bucket mobs) and destroyed or taken ones, with
  rollback (removal and respawn); item frames and armour stands as containers; shearing, naming,
  leashing, unleashing, taming and dyeing.
- Items: crafting, throwing and shooting, worn-out tools, trades, arrow pickups and death drops; hopper
  ground pickups; jukeboxes, chiseled bookshelves, decorated pots, shelves and lecterns as containers
  (with rollback).
- Books, game mode changes, sign-click and console commands, and kick reasons on quits.
- `config.yml` and `messages.yml` with every default documented; invalid files keep the previous
  configuration, and restart-only changes are reported.
### Changed

- Breaking (before the first release): the archive format and its settings. Schema version 3 drops the
  development archive (`segment` and `segment_actor`); its segments are discarded, not converted, and
  a warning says so. `archive.batch-events` is removed (a file that still has it loads, and the key is
  dropped when the file is written back); `archive.segment-events` (262144) and
  `archive.settle-seconds` (60) are new; `archive.interval-minutes` now defaults to 1 and
  `archive.after-hours` is when fewer than a segment of events are packed anyway.
- Event ids are issued by the writer on a one-second grid: every event of a second gets that second
  and its rank among the server's events of the second (messages and sessions in capture order, then
  block events by world, position and capture order), so the archive never stores a rank.
- `/hawkeye status` shows the archive's stored size (`<archive-size>`).

### Fixed

- A block a piston destroys is recorded as broken, no longer as moved one block on.
- Flowing water and lava are recorded with their flowing level, so a restore never creates sources.
- Killed mobs are stored with their entity again (Paper 26.3 refuses to serialize a dying mob without
  `EntitySerializationFlag.FORCE`), so a rollback respawns them, at full health and at rest.
- Item and entity snapshots are stored as raw NBT instead of Paper's per-item gzip, and entity
  snapshots are normalized (UUID, motion, fire, timers, spawn bookkeeping, combat memory; position and
  rotation for mobs only), so the archive compresses them together: about 7 bytes per kill and under
  2 bytes per repeated item on a real server. Rows written in Paper's gzip form still read.
- Removed entities (paintings, frames, vehicles, bucketed mobs) are serialized only with
  `rollback.restore-entities` on, as kills already were.

### Tests

- MockBukkit, SQLite and opt-in MySQL/PostgreSQL integration tests, an ArchUnit layer check, a
  storage footprint guard, `verifyPluginJar`, and real-server smoke tests (`scripts/smoke`: TNT and
  kill rollbacks).
