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
| `segment` | `id` (auto-increment, never reused), `first_id BIGINT`, `last_id BIGINT`, `events INT`, `header` (small blob), `body` (SQLite `BLOB`, MySQL `LONGBLOB`, PostgreSQL `BYTEA` stored `EXTERNAL`) | PK `id`, indexes `(last_id, id, first_id)` (reading order, and covering for every segment's id range) and `(first_id)` |
| `segment_flag` | `segment`, `ordinals` (blob: ascending ordinals of the rolled-back rows, delta varints) | PK `segment` |
| `region_segment` | `world INT`, `region BIGINT`, `newest BIGINT`, `body` (a compressed frame) | PK `(world, region)`, index `(newest)` |
| `block_segment` | `world INT`, `chunk BIGINT`, `newest BIGINT`, `body` (a compressed frame) | PK `(world, chunk)`, index `(newest)` |
| `actor_segment` | `actor INT`, `newest BIGINT`, `body` (raw `IdRuns` bytes) | PK `actor`, index `(newest)` |
| `segment_index` | `id` (always 1), `indexed_from BIGINT` | PK `id` |

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
- **Counting.** `Archive.count` takes a segment budget: it reads candidates newest first and counts
  at most that many segment bodies (a candidate whose header selects no unit costs nothing); when the
  budget is spent with a candidate left that needs its body, the count stops and is a lower bound
  (`EventCount.exact` false). The hot table's SQL count is always exact, and a total summed over shards
  is exact only when every shard's is. Opening a lookup or an inspection (`LookupPager.open`) runs the
  first page and a count with a budget of `LookupPager.COUNT_SEGMENTS` (16) side by side, so the reply
  never waits for the whole history; the header and footer then show the total and the page count as
  lower bounds (`lookup.at-least`, "160+"). A session with a lower bound has no page count:
  `/hawkeye page n` walks forward until the rows end (reporting "out of range" only there), and once
  they have ended the session's total is exact. `#count` and `HawkeyeApi#count` count every segment.
  On the 1.40 billion event replay database (2026-11-17, warm, load 6) opening the busy block takes
  81 ms against 3.2 s for its exact count (6.1 million events), radius 5 around it 181 ms against
  7.8 s (16.1 million), a block with little history 51-133 ms against 0.2-1.0 s, a block never
  touched 1.4 ms (exact either way); page 1 is never below what it shows.
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

Lookups on that database are measured under "Segment index" below: with the index a block that was never
touched inside a chunk active in every segment takes about 1-2 ms instead of 3.9 s.

### Segment index

A segment's header settles most lookups about a place without reading the segment, but a chunk that
is active all the time (a hopper line, a farm) is in every segment, and a block inside it would cost
one unit read per segment. The segment index (`core.storage.SegmentIndex`, the one entry point
`Archive` uses) remembers which segments hold which place, so such a lookup reads only the segments
that can hold the block. Its place part is `core.storage.PlaceIndex` (rows in
`core.repository.place`); `SegmentIndex` takes the state lock and calls it. Schema version 5 (version
4 had the place index under the names `place_region`, `place_chunk` and `place_state`; version 5
drops them and every segment is indexed again: an index format change takes new table names, so a
build that does not know the new formats fails on its first index statement instead of misreading
rows).

- **Keys.** A chunk key is a packed position without its 20 in-chunk bits (`pos >> 20`: chunk x above
  chunk z + 2^21); a region key drops 5 more bits of each chunk coordinate (32 x 32 chunks, the
  header's regions). Both order like packed positions.
- **`region_segment`.** One row per region that archived rows are in: per chunk (numbered
  `(x & 31) << 5 | (z & 31)`, delta-coded) the ids of the segments holding rows in it, placed and
  unplaced rows alike (a chat line said at a place is found there), as runs of consecutive ids
  (`IdRuns`: start coded against the previous chunk's first id, gaps and lengths as varints), and
  whether the chunk has a `block_segment` row. `newest` is the largest id in the row.
- **`block_segment`.** Only for a chunk in more than `BUSY_SEGMENTS` (16) segments: every position the
  archived rows touched (in-chunk key delta-coded), each with its newest `PlaceChunk.EXACT_SEGMENTS`
  (64) segment ids exactly and only the older ones as *buckets* (`segment id >> 4`, 16 consecutive
  segments). Most positions are in a handful of segments, so a block's lookup reads exactly the
  segments that hold it; a position touched in more segments stays bounded (the first six pages of a
  block lookup come from exact ids, only deep paging and exact counts read bucket candidates). With
  buckets alone, a block with little history in a busy chunk read every segment of its buckets
  (summed over the full replay database's blocks, 3.34 times the segments that hold them); 64 exact
  ids name 1.75 times at 1.6 times the bytes of buckets alone (a study on that database's rows; every
  id exact would be 2.8 times the bytes). The kept set depends only on the ids added, not their order
  (packing adds ascending, catching up descending). Per position: the key delta, a varint
  `(exact run count << 1) | hasOlder`, the exact runs coded against the previous position's first
  exact id, and when `hasOlder` the bucket run count and runs coded against the previous position's
  first bucket. When the row exists it covers every segment the region row lists for the chunk.
  Bodies are zstd level 9 (`LEVEL`): rows are rewritten on every pack, where level 19 costs more time
  than it saves bytes.
- **Writing.** `Archive.pack` (compaction, bulk import, purge rewrite) updates the index in the
  transaction that inserts the segment. Every writer first locks the `segment_index` row (`FOR UPDATE`
  on MySQL and PostgreSQL), so two writers never rewrite a row from the same old version. The region
  rows of the segment's chunks get its id; a chunk that now crosses `BUSY_SEGMENTS` gets a
  `block_segment` row built from all its segments (their units for the chunk are read and only the
  positions decoded) plus the new rows; an existing row gets the new segment at its positions (the
  smallest exact id of a position that already has 64 moves into its bucket).
  Rows are read and written in batches (one `IN` list or multi-row upsert for many keys; busy rows 500
  at a time, so memory stays bounded). While rewriting a row, ids below the oldest stored segment,
  buckets below its bucket and chunks or positions left with nothing are dropped.
- **Deleting.** A purge that deletes whole segments leaves the rows alone (readers ignore ids that no
  longer exist) and then deletes the rows whose `newest` is below the oldest stored segment, so the
  index shrinks with the data; rows of places still active are trimmed by the next pack that touches
  them. A rewrite (straddling or world purge) removes the old id from the rows of every region its
  header lists and adds the new segment like any pack; the busy rows read for the new segment also
  drop the old id from their exact ids (a bucket cannot drop one id; the region row no longer lists
  it, which keeps it out of lookups).
- **Catching up.** `indexed_from`: every segment at or above it is indexed. Version 5 sets it to one
  more than the largest existing segment id. After its packing passes the archiver spends up to
  `Archiver.CATCH_UP_TIME` (20 s) per run, under its lease, indexing the newest missing segments in
  transactions of `CATCH_UP_BATCH` (64; positions only are decoded) and lowering `indexed_from` in the
  same transaction (`Archive.catchUpIndex`). Emptying the index tables and resetting `indexed_from`
  (`SegmentIndexRepository.reset`) rebuilds the index the same way; the rows equal the ones packing
  wrote.
- **Reading.** A filter with an area of at most `MAX_AREA_CHUNKS` (1,024 chunks, one region's worth:
  at most a few region rows and a bounded number of busy rows, against a cached header test per
  segment for larger areas) reads the region rows of the area's chunks and, for busy chunks the area
  covers only partly, their `block_segment` rows (`IN` lists on the primary keys). A chunk without a
  busy row contributes all its segment ids; a busy one, of the ids its region row lists, those the
  accepted positions (`EventFilter.inPositions`) name exactly and those in their older buckets; no
  accepted position, no segment. Every segment
  below `indexed_from` is added. The id ranges of the candidates are read by primary-key ranges (for a
  set of more than 16 runs, a place active for a long time, from one scan of the covering index
  instead: about 20 bytes a segment where the seeks were thousands), put
  in reading order and filtered by id range and cursor; headers and flags are then fetched page by page
  (4, 8, 16, then 32 ids), with the same early stop as the keyset path. Larger areas and filters
  without an area keep the keyset path over `last_id`/`first_id`.

Measured with the version-4 place index (buckets only, no actor index) on the 1.40 billion event
replay database (5,337 segments, 2026-11-17, SQLite, 4 KB pages):
catching the whole database up takes 607-627 s (about 8.5 segments a second, positions only). The
index is 29,353 region rows (5.0 MB of bodies, 7.2 MB of pages) and 14,699 busy-chunk rows (17.3 MB,
22.6 MB of pages): 29.8 MB, 2.0% of the 1.52 GB file. As `WITHOUT ROWID` tables the same rows took
37.9 MB, because such a row keeps only about a quarter page on its b-tree page and most busy rows
(1.2 KB on average, 48 KB at most) spilled to an overflow page.

| Lookup (whole history, warm "againMillis") | Before | After (load 6) | After (load 9.5) |
|---|---|---|---|
| A block never touched in the busiest chunk | 3,900 (14,000 cold) | 2.0 | 1.1 |
| Newest 10 at a block busy a month before the end | 686 | 31 | 36 |
| Newest 10 at a block with little history, quiet chunk | 270-320 | 24 | 46 |
| The same in the busiest chunk | 270-320 | 48 | 54-173 |
| Radius 5 around the busy block, newest 10 | 133 | 59 | 108-115 |
| A player's newest 10 / everything in the last hour | 18 / 106 | 10 / 100 | 7 / 98 |

The plan itself (region and busy rows read and decoded) costs 0.4-1 ms for each of these. What is
left is reading the segments it names: the busy block reads 2 segments (4 body reads), the
little-history blocks 10-20 (241 and 1,196 candidate segments: those blocks were touched again later),
radius 5 about 10 (3,606 candidates), at roughly 1-2.5 ms per segment on a loaded host (one statement
for the unit, one for the shared frames when a row matched).

### Indexes and query shapes

| Query | Plan |
|---|---|
| Inspector (one block) | `world = ? AND pos = ?` on `(world, pos)`, newest first |
| Radius / rollback area | one `(world = ? AND pos BETWEEN ? AND ?)` term per chunk column, OR-ed; rows outside the exact radius are filtered in Java |
| User | `actor IN (...)` on `(actor)` plus the id range |
| Time only | primary key range |
| Purge | primary key range delete, in batches; archive segments by `first_id`, deleted or rewritten whole |
| Segment index, places | `world = ? AND region IN (...)` / `world = ? AND chunk IN (...)` on the primary keys; trimming `newest < ?` on `(newest)`; candidates `id = ? OR id BETWEEN ? AND ? ...` on the segment primary key, or `last_id >= ?` answered by the covering index alone for a scattered set, then `id IN (...)` on the primary key |
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
