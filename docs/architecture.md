# Hawkeye architecture

Hawkeye records what happens to a Paper/Folia world (blocks, containers, items, kills, signs, chat,
commands, sessions) and lets staff look it up, inspect blocks, roll changes back and restore them,
on one server or across a network of servers. This document is the design contract: the storage
format, the threading model and the multi-server protocol. Code follows it; change it in the same
commit as the code.

## Goals, measured against CoreProtect

| Concern | CoreProtect (PlayPro + the ericlmao fork) | Hawkeye |
|---|---|---|
| Code structure | Static state (`ConfigHandler` has 132 public statics), classes of 1,000-3,900 lines, hand-parsed commands, raw `Thread`s | Spring beans with constructor injection, layered packages checked by ArchUnit, Cloud 2 annotation commands, owned executors, no class near 1,000 lines |
| Row size | `time int` (overflows 2038), separate x/y/z, `rowid`, 3+ B-tree indexes per table | One 64-bit id carries the second, the server and the rank in that second; one 64-bit packed position; two secondary indexes |
| Old data | The fork packs cold SQLite data into segments; MySQL and the other engines keep full rows | Every engine packs settled events into immutable segments of 262,144 events (archive format 2) read by byte range. A real history of 1.40 billion events that takes 3.40 GB in the fork's SQLite database takes 1.52 GB in Hawkeye's: 1.09 bytes per event against 2.43 (`ReplayBenchmark`) |
| Storage engines | Four engines and four query builders (DuckDB, ClickHouse, SQLite segments, MySQL) | One schema, one query builder, three dialects (SQLite, MySQL/MariaDB, PostgreSQL) behind a small `Dialect` seam |
| Multi-server | One writer per database; id allocation is an in-memory counter that races under several writers | Ids are generated locally and never collide; dictionaries are insert-or-get against unique keys; any number of servers share one database |
| Sharding | None | Each server writes to its home shard; network lookups fan out to every shard and merge by id |
| Network operations | None | Durable network jobs (rollback/restore on other servers) with a Redis or database-polling bus |
| Freshness | Lookups miss events still in the 500 ms queue | Every lookup first flushes the local queue (read-your-writes) |
| Durability | Queue lost on crash or database outage | Events stay queued through outages with retry and back-off, and are spooled to disk on shutdown when the database is unreachable |

## Storage format

### Event id (`util.EventIds`)

Every event has a 64-bit id, issued on the server that captured it, without a database round trip:

```
 63      62 ............................ 22   21 ........ 12   11 ........ 0
 [0]     [ milliseconds since 2025-01-01Z ]   [ node id    ]   [ sequence  ]
          41 bits (until 2094)                 10 bits          12 bits
```

- The id is the primary key and the time column at once: a time window is an id range
  (`EventIds.floor(t)` .. `EventIds.ceiling(t)`), and `ORDER BY id DESC` is newest-first.
- The node id is the server's registration number in its shard (`hawkeye_server`, 1..1023), so two
  servers never produce the same id, and ids from all servers interleave by time.
- **The one-second grid.** Capture only stamps events (`[time][sequence]`, capture order). The writer
  (`core.queue.IdAssigner`) buffers them by second (their *slot*) until the second has closed (plus
  250 ms of grace), sorts each slot's events in `util.EventOrder` and numbers them 0, 1, 2, ... (their
  *rank*). The rank fills the sequence bits and then the millisecond bits, so an id is
  `slot * 1000 ms + rank` and is never more than a second off; a slot holds 4,096,000 events.
- **The order inside a second.** Events about a player rather than a place (chat, commands, books,
  game modes, sessions; action codes 40..59) come first, in capture order; every other event follows
  by world name, packed position and capture order. The archive stores rows in exactly this order, so
  it rebuilds every rank from the stored rows and never stores one. Changes to one block keep their
  order, and a conversation reads in order.
- Late events of a slot continue its numbering (the counters of the last 120 slots are kept); the
  writer starts after the newest stored slot on restart, so no id is ever issued twice.

### Packed position (`util.BlockPositions`)

```
 63 ............ 42   41 ............ 20   19 .. 16   15 .. 12   11 ...... 0
 [ chunk x, signed ]  [ chunk z + 2^21 ]   [ x & 15 ]  [ z & 15 ]  [ y + 2048 ]
       22 bits             22 bits           4 bits      4 bits      12 bits
```

- Covers the whole world border (±33.5 million blocks) and y from -2048 to 2047.
- Chunk-major ordering: every block of a chunk column is one contiguous range, and a run of chunks
  with the same chunk x is also one contiguous range. A radius lookup is therefore
  `2 * ceil(r / 16) + 1` index range scans (3 for r = 10, 15 for r = 100), not a scan of the x slab.
- An exact block lookup is one index seek on `(world, pos)`.

### Tables

All tables carry the configured prefix (default `hawkeye_`).

| Table | Columns | Keys |
|---|---|---|
| `schema` | `version` | Migration bookkeeping (`core.sql.Migrations`) |
| `server` | `id SMALLINT`, `name`, `scope`, `last_seen` | PK `id`, unique `name` |
| `world` | `id INT`, `scope`, `name` | unique `(scope, name)` |
| `actor` | `id INT`, `kind`, `key`, `name`, `name_lower` | unique `key`, index `name_lower` |
| `type` | `id INT`, `value` | unique `value` |
| `event` | `id BIGINT`, `world INT`, `pos BIGINT`, `actor INT`, `action SMALLINT`, `subject INT`, `secondary INT NULL`, `amount INT NULL`, `flags SMALLINT`, `data BLOB NULL` | PK `id`, index `(world, pos)`, index `(actor)` |
| `job` | network jobs (see below) | PK `id`, index `(target, status)` |
| `lease` | `name`, `holder`, `expires` | PK `name` |
| `segment` | `id` (auto-increment, never reused), `first_id BIGINT`, `last_id BIGINT`, `events INT`, `header` (small blob), `body` (SQLite `BLOB`, MySQL `LONGBLOB`, PostgreSQL `BYTEA` stored `EXTERNAL`) | PK `id`, indexes `(last_id)` and `(first_id)` |
| `segment_flag` | `segment`, `ordinals` (blob: ascending ordinals of the rolled-back rows, delta varints) | PK `segment` |

- **Dictionaries.** Worlds, actors and types are interned to small integers. `type` holds every
  namespaced value an event refers to: block states (`minecraft:oak_stairs[facing=east,...]`), item
  materials (`minecraft:diamond`) and entity types (`minecraft:zombie`); the action says which.
  Dictionary rows are immutable, so every server caches them forever and no invalidation traffic
  exists. A new value is `INSERT ... ON CONFLICT DO NOTHING` followed by a select, which is safe
  with any number of concurrent writers.
- **World scope.** A world is keyed by `(scope, name)`. The scope defaults to the server name, so
  `world` on `survival` and `world` on `creative` are different worlds. Servers that share world
  files (MultiPaper) configure the same scope.
- **Actors.** Players are keyed by UUID (`kind = PLAYER`) and keep their latest name; non-player
  causes are keyed by a `#name` (`#tnt`, `#creeper`, `#fire`, `#water`, `#piston`, ...).
- **`subject` / `secondary`.** For block actions `subject` is the block the action is about (the
  broken block for a break, the placed block for a place) and `secondary` is the other side of the
  change (the block left behind after a break, the block replaced by a place). Rollback restores
  `subject` for a break and `secondary` for a place; restore does the opposite. For item actions
  `subject` is the material and `amount` the count; for kills it is the entity type.
- **`flags`.** Bit 0: rolled back. Archived events keep it in `segment_flag` instead.
- **`data`.** An optional payload, tagged by its first byte (`util.codec.PayloadCodec`): text (chat,
  commands), sign sides (before and after), an item (only when the item has components), an inventory
  snapshot (a broken container), or an entity (for kill and removal rollback). Items and entities are
  the NBT of Paper's version-safe serialization (it carries the data version, so the server's data
  fixers upgrade old rows), stored uncompressed (`util.ItemBytes`, `util.EntityBytes`) so the archive,
  which compresses many rows together, finds what repeats between snapshots; rows in Paper's own gzip
  form (from earlier builds) read the same. Entity snapshots are normalized first
  (`util.EntitySnapshots`): what a respawn resets, re-rolls or takes from the event row is stripped.
  Large text and sign payloads are deflated; nothing else is compressed per row.

### Archive

The `event` table is the hot tier: every row individually indexed, cheap to insert and to flag. The
cold tier is `segment`: immutable segments of archive format 2 (`core.repository.segment`), written
by `core.storage.Archive` under the `archive` lease (one server per shard).

- **Packing.** Every `archive.interval-minutes` (1) a run packs the `archive.segment-events` (262,144)
  oldest hot events that are older than `archive.settle-seconds` (60) into one segment and deletes
  them from `event`, in one transaction, and repeats until nothing is packed. Fewer events are packed
  only once the oldest is older than `archive.after-hours` (24), so a quiet server is archived too, in
  smaller segments. A segment holds any worlds and servers; segments are time slices whose id ranges
  can overlap (a late event is packed after its neighbours). Packing compresses on the calling thread;
  a bulk import (`EventStore.writeArchived`) compresses on a pool.
- **Stored order.** Unplaced rows by id, then placed rows by (world index in the world order,
  position, id); the world order is the worlds by (scope, name), the order the writer ranks in. Rows
  are numbered 0..n-1 in this order (*ordinals*) and cut into *units* of 16,384 rows; unplaced and
  placed rows never share a unit.
- **Header** (uncompressed, about 1-2 KB for a full segment, cached parsed by segment id): version
  (2), counts, base slot, the world, node and actor lists, an action mask, every distinct chunk of
  the placed rows, every distinct region (32 x 32 chunks, `chunk >> 5`) of the unplaced rows as
  sorted, delta-coded (world index, region x, region z) entries, the lengths of the shared frames
  and, per unit, its row count, first key, frame lengths, actors, action mask and slot range. From it
  a reader decides whether the segment and which units can match an `EventFilter`, and where their
  bytes are: placed units by the chunk list and their first keys, unplaced units by the region list
  (a world or area that reaches none of those regions skips them). The byte layout is in the
  `SegmentHeader` Javadoc.
- **Body.** Frames (`util.codec.Compression`: stored, zstd level 19, or deflate level 9 when zstd's
  native library does not load). First the shared frames: per row the slot delta, the node index (with
  several nodes), the rank difference from the expected rank (omitted when all are 0, the normal case
  for ids the writer issued), and the actor and type palettes. Then per unit a scalar frame (world,
  chunk x/z deltas, local x/z byte, y delta, actor, action, subject, secondary, amount, data length)
  and a payload frame.
- **Reading.** Candidates are found by id range on `last_id` (header and flags, never the body), in
  reading order (`last_id DESC` or `first_id ASC`, keyset paged). Each header selects the units that
  can match; a read then has two steps. (1) One statement fetches the selected units by `substr` on the
  body, touching ranges merged, plus the palette frame (the small last shared frame) when the filter
  names actors or types and the palettes are not cached. Each unit's scalar frame is decompressed and
  only the columns the filter needs are decoded into primitive arrays; the column rules (world, area
  and bounds, actors, actions, types, rolled-back flag: `EventFilter.matchesColumns`) give the
  matching row numbers. No row object is built and no payload decompressed. (2) Only when a row
  matched are the segment's ids obtained (the decoded shared frames, cached by weight, about 32 MB,
  else fetched and decoded), the id rules applied (`EventFilter.matchesId`: id range and nodes, and
  the keyset cursor) and rows built for the survivors; a unit's payload frame is decompressed only
  when a surviving row has data. A segment whose ids all pass the id rules (id range inside the
  filter's, beyond the cursor, its one node listed) skips the per-row id check, and a count of such a
  segment counts column matches without computing ids. `EventFilter.matches` is the Java twin of the
  SQL predicate (`EventFilterTest` checks they agree); both steps use its rule methods. A page stops
  reading segments once none can beat its worst event (by `first_id`/`last_id`).
- **Flags.** A rollback of archived events maps their ids to ordinals (from the shared frames) and
  rewrites the segment's `segment_flag` row under a lock on the segment row; a body is never rewritten
  for a flag.
- **Purging.** A segment entirely older than the bound is deleted with its flags. A segment that
  straddles it, or that holds purged worlds next to others, is decoded with its flags, re-encoded
  without the purged rows as a new segment and the old one deleted. At most 8 segments per batch.

Measured on 2.43 million replayed real events (bytes per event):

| Layout | B/event |
|---|---|
| CoreProtect fork reference (rows by chunk, player, time; zstd 19 per 8,192 rows) | 2.22 |
| Position order, ranks stored | 2.24 |
| Position order, ranks implied, one frame per 262,144 events | 1.18 |
| ... units of 16,384 rows (the format) | 1.22 |
| ... 1,048,576 events per segment | 1.09 |

On SQLite, `StorageFootprintTest` (60,000 uniformly random block events, the worst case) measures 73
bytes per hot event and 6.2 archived, against 80 for CoreProtect's relational layout.

At full scale (`ReplayBenchmark`, 2026-11-16): every packed row of a real CoreProtect database of the
`ericlmao/CoreProtect` fork (1,398,675,414 rows over 59 days, 98% hopper, piston and block activity,
3.40 GB as that fork's SQLite file) replayed as the events Hawkeye would have captured, numbered by
`IdAssigner` and packed with the default settings:

| | Events | SQLite file | Bytes per event |
|---|---|---|---|
| CoreProtect fork | 1,398,675,414 packed + 181,684 waiting | 3,397,459,968 | 2.43 |
| Hawkeye | 1,398,675,414 | 1,517,871,104 | 1.09 |

Hawkeye's file is 5,337 segments (1.49 GB of bodies, 19 MB of headers) and 3 MB of dictionaries. By
period, against the fork's packed segments alone (its entity tables, 0.58 GB, come on top): days 1-16
0.93 against 1.76 bytes per event, days 17-27 0.87 against 1.74, days 28-60 (many kills) 1.39 against
1.92. A kill's entity is a stand-in in the replay (the fork's own stored entity, about 15 bytes
packed); Hawkeye's real snapshots measured 6.8 bytes per kill on a Paper server (docs/knowledge).
A running server adds the hot table on top: the events not yet packed and, as free pages, its
high-water mark (about 22 MB at the default segment size).

Lookups on that database (5,337 segments, warm, a shared host under load): the newest 10 events of a
player 18 ms, a later page at a block 9 ms, a radius of 5 around a busy block 133 ms, everything in
the last hour 106 ms, a block with little history 270-320 ms. The worst case is a block that was never
touched inside a chunk that is active in every segment: one unit per segment has to be read to know,
3.9 s warm and 14 s cold, because SQLite reads a whole segment body to slice it.

### Indexes and query shapes

| Query | Plan |
|---|---|
| Inspector (one block) | `world = ? AND pos = ?` on `(world, pos)`, newest first |
| Radius / rollback area | one `(world = ? AND pos BETWEEN ? AND ?)` term per chunk column, OR-ed; rows outside the exact radius are filtered in Java |
| User | `actor IN (...)` on `(actor)` plus the id range |
| Time only | primary key range |
| Purge | primary key range delete, in batches; archive segments by `first_id`, deleted or rewritten whole |
| Archive candidates | `last_id >= ? AND first_id <= ?` on `(last_id)` (newest first) or `(first_id)` (oldest first); a later page adds `last_id <= ?` so it seeks to where the previous one ended; the flag row by its primary key; the body by `substr` byte ranges |

InnoDB and SQLite append the primary key to secondary indexes, so `(world, pos)` and `(actor)` are
already ordered by id within a key; PostgreSQL declares `id` explicitly.

## Threading

| Work | Thread |
|---|---|
| Capturing an event (block state strings, item bytes) | The event's own thread (the owning region on Folia); never blocks |
| Queueing | Lock-free `EventQueue` (bounded; overflow is counted and reported, never blocks the game) |
| Writing | One `Hawkeye-Writer` thread: drains the queue every `flush-interval` or at `batch-size`, resolves dictionaries, writes one transaction per batch, retries with back-off |
| Lookups, rollback planning, purge, jobs | The bounded `Hawkeye-Query` executor |
| Applying a rollback/restore/preview | The region that owns each chunk (`core.service.thread.ServerThreads`), a bounded number of blocks per tick |
| Replies to a command sender | The sender's own thread |

Every executor is owned by a bean with a `Disableable` shutdown path. Shutdown flushes the queue
(bounded by `shutdown-timeout`) and spools what could not be written.

## Multi-server and sharding

- **One database, many servers.** Point every server at the same MySQL/MariaDB/PostgreSQL. Each
  registers its `server.name` and gets a node id; ids, dictionaries and worlds never collide. Every
  server sees every other server's events within one flush interval.
- **Shards.** A large network splits servers over several databases. A server writes only to its
  home shard (`storage`) and lists the other shards under `network.shards` (read access). Lookups
  with `s:<server>` or `s:#network` run on every shard that holds those servers, in parallel, and
  the newest-first pages are merged by id.
- **Network jobs.** A server can only change its own worlds. `rollback`/`restore` with a server
  filter becomes one `job` row per target server, in the target's shard. The target claims the row
  atomically (`UPDATE ... WHERE status = PENDING`), applies it, and writes the result; the issuer
  reports progress and the result to the sender.
- **Bus.** `network.bus` delivers "job queued" and "job finished" notifications instantly over Redis
  pub/sub. The database stays the source of truth: every server also polls its pending jobs every
  `network.poll-interval`, so a lost message only costs latency, and `bus: none` works with polling
  alone.
- **Leases.** Maintenance that must run once per shard (auto-purge, archiving) takes the `lease`
  row; the holder renews it, and another server takes over when it expires.
