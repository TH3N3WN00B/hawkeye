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
| Row size | `time int` (overflows 2038), separate x/y/z, `rowid`, 3+ B-tree indexes per table | One 64-bit id carries time, server and sequence; one 64-bit packed position; two secondary indexes |
| Old data | The fork packs cold SQLite data into segments; MySQL and the other engines keep full rows | Every engine archives events older than a day into deflated per-region column segments: about 12 bytes per block event on SQLite against about 80 for CoreProtect's relational layout (`StorageFootprintTest`) |
| Storage engines | Four engines and four query builders (DuckDB, ClickHouse, SQLite segments, MySQL) | One schema, one query builder, three dialects (SQLite, MySQL/MariaDB, PostgreSQL) behind a small `Dialect` seam |
| Multi-server | One writer per database; id allocation is an in-memory counter that races under several writers | Ids are generated locally and never collide; dictionaries are insert-or-get against unique keys; any number of servers share one database |
| Sharding | None | Each server writes to its home shard; network lookups fan out to every shard and merge by id |
| Network operations | None | Durable network jobs (rollback/restore on other servers) with a Redis or database-polling bus |
| Freshness | Lookups miss events still in the 500 ms queue | Every lookup first flushes the local queue (read-your-writes) |
| Durability | Queue lost on crash or database outage | Events stay queued through outages with retry and back-off, and are spooled to disk on shutdown when the database is unreachable |

## Storage format

### Event id (`util.EventIds`)

Every event has a 64-bit id, generated on the server that captured it, without a database round
trip:

```
 63      62 ............................ 22   21 ........ 12   11 ........ 0
 [0]     [ milliseconds since 2025-01-01Z ]   [ node id    ]   [ sequence  ]
          41 bits (until 2094)                 10 bits          12 bits
```

- The id is the primary key and the time column at once: a time window is an id range
  (`EventIds.floor(t)` .. `EventIds.ceiling(t)`), and `ORDER BY id DESC` is newest-first.
- The node id is the server's registration number in its shard (`hawkeye_server`, 1..1023), so two
  servers never produce the same id, and ids from all servers interleave by time.
- Up to 4,096 ids per millisecond per server; a burst beyond that borrows from the next millisecond
  (the logical clock runs ahead of the wall clock briefly). A wall clock that steps backwards never
  produces a smaller id.

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
| `segment` | `id`, `world`, `region`, `first_id`, `last_id`, `events`, `body` | PK `id`, index `(world, region, last_id)`, index `(last_id)` |
| `segment_actor` | `actor`, `segment` | PK `(actor, segment)` |

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
- **`flags`.** Bit 0: rolled back.
- **`data`.** An optional payload, tagged by its first byte (`util.codec.Payloads`): text (chat,
  commands), sign sides (before and after), an item (Paper's version-safe item bytes, only when the
  item has components), an inventory snapshot (a broken container), or an entity (Paper's
  version-safe entity bytes, for kill rollback). Large text payloads are deflated.

### Archive

The `event` table is the hot tier: every row individually indexed, cheap to insert and to flag.
Events older than `archive.after-hours` move to the cold tier, `segment`, in passes of
`archive.batch-events` (one transaction each, under the `archive` lease so one server per shard does
it). A pass groups its events by world and region (8 x 8 chunks) and packs each group into one
segment (`core.repository.SegmentCodec`): sorted by id, stored column by column (time deltas,
sequences, x/z/y deltas inside the region, indexes into per-segment palettes of actors and types,
actions, amounts, flags, payloads) and deflated. `segment_actor` lists the actors of each segment.

Reads merge both tiers (`core.storage.Archive`): candidate segments are found by region range (the
same chunk-column trick as positions), world, actor and id range, decoded, and filtered with
`EventFilter.matches`, the Java twin of the SQL predicate (`EventFilterTest` checks they agree). A
newest-first page stops reading segments once none can beat the page's worst event. Rolling back an
archived event rewrites its segment; a purge drops whole segments or rewrites their newer part.

### Indexes and query shapes

| Query | Plan |
|---|---|
| Inspector (one block) | `world = ? AND pos = ?` on `(world, pos)`, newest first |
| Radius / rollback area | one `(world = ? AND pos BETWEEN ? AND ?)` term per chunk column, OR-ed; rows outside the exact radius are filtered in Java |
| User | `actor IN (...)` on `(actor)` plus the id range |
| Time only | primary key range |
| Purge | primary key range delete, in batches |

InnoDB and SQLite append the primary key to secondary indexes, so `(world, pos)` and `(actor)` are
already ordered by id within a key; PostgreSQL declares `id` explicitly.

## Threading

| Work | Thread |
|---|---|
| Capturing an event (block state strings, item bytes) | The event's own thread (the owning region on Folia); never blocks |
| Queueing | Lock-free `EventQueue` (bounded; overflow is counted and reported, never blocks the game) |
| Writing | One `Hawkeye-Writer` thread: drains the queue every `flush-interval` or at `batch-size`, resolves dictionaries, writes one transaction per batch, retries with back-off |
| Lookups, rollback planning, purge, jobs | The bounded `Hawkeye-Query` executor |
| Applying a rollback/restore/preview | The region that owns each chunk (`core.scheduler.ServerThreads`), a bounded number of blocks per tick |
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
