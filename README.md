<img src="assets/icon.svg" width="64" height="64" alt="">

# Hawkeye

Block and action logging for Paper and Folia 26.3, with lookups, an inspector, rollbacks and
restores, on one server or across a network of servers. A ground-up alternative to CoreProtect
focused on clean code, compact storage, multi-server operation and fast reads.

## Why Hawkeye

| | CoreProtect | Hawkeye |
|---|---|---|
| Old data | Full rows (the fork packs SQLite only) | Every engine packs settled events into immutable column segments of 262,144 events, read by byte range |
| Storage per block event (SQLite, measured) | about 80 bytes (`co_block` and its three indexes) | about 73 hot, **about 6 archived** on random worst-case data; about 1.2 bytes per event on replayed real history |
| Time | 32-bit seconds (overflows in 2038) | 64-bit ids carry the second until 2094, the server and the event's rank in that second |
| Several servers on one database | One writer; ids from an in-memory counter that races | Any number of writers; ids never collide; dictionaries are insert-or-get |
| Sharding | No | Each server writes its home shard; network lookups fan out to every shard and merge |
| Network rollbacks | No | `s:<server>` / `s:#network` rollbacks run as durable jobs on each target server |
| Sync | None | Redis pub/sub wake-ups with database polling as the fallback (the database is the source of truth) |
| Freshness | Lookups miss the last 0.5 s of events | Every lookup first flushes the write queue (read-your-writes) |
| Database outage | Queued events are lost on a crash or restart | Events stay queued with retry and back-off and are spooled to disk on shutdown |
| Area lookups | Index on `(world, x, z, time)`; y is never indexed | Chunk-major packed positions: a radius is a few index range scans, a block one seek |
| Code | Static state, classes of 1,000-3,900 lines, raw threads | Spring beans, layered packages checked by ArchUnit, Cloud 2 commands, owned executors |

The storage figures come from `StorageFootprintTest`: 60,000 block events with random positions,
types and timing (the worst case for compression), written in each layout on SQLite.

## Requirements

- Paper or Folia 26.3 (`api-version: '26.3'`), Java 25.
- Nothing else for a single server: the default database is an SQLite file in the plugin folder.
- MySQL 8+, MariaDB 10.6+ or PostgreSQL 13+ to share a database between servers; Redis (optional)
  for instant network jobs.
- On first start the server downloads Hawkeye's libraries (Spring, Moss, Cloud, ConfigLib, HikariCP,
  the JDBC drivers, Caffeine, Lettuce) through Paper's library loader; nothing is shaded into the jar.

## Commands

The root command is `/hawkeye` with the alias `/he`.

| Command | Permission | What it does |
|---|---|---|
| `/he`, `/he help` | `hawkeye.help` | Version and command overview |
| `/he inspect` (`i`) | `hawkeye.inspect` | Toggles the inspector |
| `/he lookup <parameters>` (`l`) | `hawkeye.lookup` | Searches the history, newest first |
| `/he near [radius]` | `hawkeye.lookup` | What happened around you |
| `/he page <page>` | `hawkeye.lookup` | Another page of the last lookup or inspection |
| `/he tp <world> <x> <y> <z>` | `hawkeye.teleport` | Teleports to a result (clicking a location runs it) |
| `/he rollback <parameters>` (`rb`) | `hawkeye.rollback` | Undoes changes; with `#preview` shows them to you first |
| `/he restore <parameters>` (`rs`) | `hawkeye.rollback` | Redoes rolled-back changes |
| `/he apply`, `/he cancel` | `hawkeye.rollback` | Applies or cancels your preview |
| `/he undo` | `hawkeye.rollback` | Reverts your last rollback or restore |
| `/he purge t:<age> [r:#world]` | `hawkeye.purge` | Deletes data older than the age |
| `/he status` | `hawkeye.admin` | Queue, writer and database health |
| `/he network` | `hawkeye.network` | Every server of every shard and its heartbeat |
| `/he reload` | `hawkeye.admin` | Reloads `config.yml` and `messages.yml` |

All permissions default to operators; `hawkeye.*` grants them all. `hawkeye.network` is also
needed for `s:` in any parameter list. Commands, permission nodes and configuration keys are
contracts with server operators and only change as an explicit breaking change in the changelog.

## Parameters

Lookups, rollbacks, restores and purges take the same parameters as CoreProtect, so staff can switch
without relearning:

| Parameter | Meaning | Examples |
|---|---|---|
| `u:` | Players, or sources with `#` | `u:Alex,Steve`, `u:#tnt` |
| `t:` | How far back, or between two ages | `t:2h`, `t:3d12h`, `t:1h-2d` |
| `r:` | Radius around you, a whole world, or everywhere | `r:10`, `r:10x5` (5 up and down), `r:#world_nether`, `r:#global` |
| `a:` | Actions | `block`, `+block`, `-block`, `change`, `sign`, `click`, `grow`, `container`, `+container`, `-container`, `item`, `+item`, `-item`, `craft`, `throw`, `trade`, `kill`, `entity`, `+entity`, `-entity`, `spawn`, `chat`, `command`, `book`, `gamemode`, `session`, `+session`, `-session` |
| `i:` | Only these blocks, items or entities | `i:diamond_ore,chest` |
| `e:` | Except these types or players | `e:stone,Steve` |
| `s:` | Other servers | `s:creative`, `s:#network` |
| `#preview` | Show a rollback or restore to you only | |
| `#count` | Count matches instead of listing them (always the exact number) | |

A lookup without `r:` searches every world of this server. Rollbacks and restores need a `t:` (a
safety net against undoing a server's whole history by accident); without `r:` they use
`rollback.default-radius` around you, and the console must give `r:#world` or `r:#global`. A purge
takes exactly one `t:<age>` and optionally `r:#world`; in game it may only delete data older than
`purge.player-minimum-days`. TNT lit by a player and arrows they shot are recorded under that
player, so `/he rollback u:Griefer t:1h` also undoes their TNT damage.

`/he page <n>` keeps working for `lookup.session-minutes` after a lookup or an inspection; page 1
stays pinned to the events that were newest when you looked, so new events never shift the pages
you are reading. Opening a lookup or an inspection counts at most the 16 newest archive segments
that hold matches, so the first page never waits for months of history: the total and page count of
a large result are lower bounds, shown as `160+` and `16+` (`lookup.at-least` in `messages.yml`).
Paging past them keeps going until the events end, where the total becomes exact; `#count` gives the
exact number. Locations in results on this server are clickable (`/he tp`), results from other
servers show their server name.

## Inspector

With `/he inspect` on, left-clicking a block shows its history (the block is not broken),
right-clicking a container shows its transactions, right-clicking any other block shows the block
in front of the clicked face, and placing a block shows that spot (the placement is cancelled). The
inspector turns itself off if you lose `hawkeye.inspect`.

## What is recorded

Every category has a switch in `config.yml` (`logging`); everything but hoppers is on by default.
Changes the server reports without a cause are blamed on whoever caused them where Hawkeye can tell:
TNT lit by redstone counts for the player who placed it, a wither for the player who built it, an
end crystal for the player who hit it, flowing lava for the player who poured it, and a torch that
falls off a wall for the player who broke the wall.

| Category | Details |
|---|---|
| Blocks | Breaks (with container contents and sign text, both halves of doors, beds and tall plants), places, multi-block places, buckets (including waterlogging) |
| Attached blocks | Torches, signs, rails, redstone, flowers, sugar cane and anything else that falls off when its support goes, under the remover of the support (else `#physics`) |
| Explosions | Every destroyed block, the TNT block itself, and an exploding bed or respawn anchor, under the player who lit, placed, clicked or hit the cause, else the entity (`#creeper`) or `#explosion` |
| Fire, fluids, pistons | Burning (`#fire`), lighting, flow (under the player who poured it, else `#water`/`#lava`), stone and obsidian forming, sponges, pistons (moves as a break and a place, destroyed blocks as breaks) |
| Natural | Leaf decay, melting, snow and ice forming, grass/mycelium/vine/sculk spread, tree and mushroom growth (under the player when bone-mealed), bone meal, portals forming, cauldrons filling and drying |
| Crop growth | A ripe crop or a newly grown block (melon, pumpkin, sugar cane, cactus, vines) as one compact `#growth` event; `crop-growth-stages` records every stage. Rollbacks never change growth |
| Signs | Edits with the text before and after, per side |
| Interactions | Doors, trapdoors, gates, levers, buttons, repeaters, comparators, note blocks, daylight detectors (a tick later, only when the state changed); berries picked, pumpkins and beehives sheared, flower pots, dragon eggs |
| Containers | Net change per kind of item while a container is open; jukeboxes, chiseled bookshelves, decorated pots, shelves and lecterns; items in item frames and on armour stands |
| Dispensers and hoppers | Blocks and liquids a dispenser places or picks up (`#dispenser`); hopper transfers and ground pickups (off by default, high volume) |
| Entities | Placed or spawned: armour stands, boats, minecarts, paintings, item frames, end crystals, spawn eggs, built golems and withers (with the consumed blocks), mobs released from buckets. Destroyed or taken: paintings, item frames, vehicles, end crystals, mobs caught in buckets. Kills |
| Entity interactions | Shearing, naming (with the name), leashing, unleashing, taming, dyeing |
| Items | Drops (and death drops), pickups (and arrows), crafting, thrown and shot items, worn-out tools, villager trades |
| Chat, commands, books | Chat, commands, sign-click commands (`[sign]`), console commands (`#console`); `logging.redacted-commands` are stored without their arguments; written and signed books |
| Sessions and modes | Joins, quits (with the kick reason), game mode changes |

A rollback reverses blocks, container and item-frame/armour-stand items, kills and destroyed
entities (respawned) and placed or spawned entities (removed while they are still in the world);
interactions, growth, items, chat and sessions are history only.

## Configuration

`plugins/Hawkeye/config.yml` and `messages.yml` are written with every default and a comment on
first start; keys missing from an older file are added. `/he reload` applies the logging, lookup,
rollback, purge and archive sections and every message; `server`, `storage`, `writer` and `network`
need a restart (the reply says which changed). A file that fails to load or validate keeps the
previous configuration.

| Section | Highlights |
|---|---|
| `server` | `name` (unique per database), `world-scope` (shared by servers that share world files, e.g. MultiPaper) |
| `storage` | `type` (`SQLITE`, `MYSQL`, `MARIADB`, `POSTGRESQL`), connection, `table-prefix`, `pool-size` |
| `writer` | `flush-interval-millis` (500), `batch-size`, `queue-capacity`, `shutdown-timeout-seconds` |
| `logging` | One switch per category, `crop-growth-stages`, `liquid-tracking`, `attached-blocks`, `disabled-worlds`, `redacted-commands` (logged without arguments) |
| `lookup` | `page-size`, `near-radius`, `max-radius`, session and timeout limits |
| `rollback` | `default-radius`, `max-radius`, `blocks-per-tick`, `concurrent-chunks`, `max-events`, preview expiry |
| `purge` | `retention-days` (automatic purge, 0 = keep everything), `player-minimum-days` |
| `network` | `bus` (`NONE` or `REDIS`), `redis-uri`, `poll-interval-seconds`, `shards` |
| `archive` | `enabled`, `segment-events` (262144 events per segment, 16384 to 1048576), `settle-seconds` (60: newer events stay hot), `after-hours` (24: older events are packed even when fewer than a segment are waiting), `interval-minutes` (1) |

Every player-facing text is a MiniMessage template in `messages.yml`. `lookup.unknown-total` (`?`)
stands in for `<total>` and `<pages>` when a lookup could not be counted and `lookup.at-least`
(`<value>+`) when they are lower bounds.

## Running several servers

1. **One shared database.** Point every server's `storage` at the same MySQL, MariaDB or
   PostgreSQL database with the same `table-prefix`, and give each a unique `server.name`. Each
   server registers itself and gets a node id; ids, worlds and dictionaries never collide, and every
   server sees the others' events within one flush interval. `/he lookup s:#network u:Alex` searches
   every server; `/he rollback s:#network u:Alex t:1h r:#global` rolls Alex back everywhere.
2. **Shards.** A large network can split servers over several databases. Each server writes only to
   its own `storage` and lists the others under `network.shards`; network lookups read every shard in
   parallel and merge the results, and network jobs are written into the target server's shard.
3. **Redis (optional).** With `network.bus: REDIS` a network rollback starts on its targets at once
   and its result comes back at once. Without it, servers find their jobs by polling the database
   every `network.poll-interval-seconds`. Either way the database is the source of truth.
4. **MultiPaper and shared worlds.** Servers that share world files set the same
   `server.world-scope`, so their worlds are one.

Maintenance that should run once per database (the automatic purge and archiving) takes a database
lease, so any number of servers can be configured identically.

## Storage

Events are written by one background thread in batches (`writer.batch-size`, at least every
`writer.flush-interval-millis`), so capturing an event never touches the database. The hot `event`
table holds recent events with an index on `(world, packed position)` and one on the actor. As soon as
`archive.segment-events` events are older than `archive.settle-seconds` they are packed into one
archive segment: immutable, stored column by column and compressed with zstd, about 1.2 bytes per
event on real history. A lookup about one place reads only the segment's header and one or two small
parts of it. Lookups, the inspector, rollbacks, restores and purges read both tiers transparently. `docs/architecture.md` describes the format, the threading model and the multi-server
protocol in detail.

## API for other plugins

```java
HawkeyeApi hawkeye = Bukkit.getServicesManager().load(HawkeyeApi.class);
hawkeye.logPlacement(Actor.player(player.getUniqueId(), player.getName()), replacedState, placedData);
if (hawkeye.parse("u:Alex t:2h r:10", player.getLocation()) instanceof HawkeyeApi.QueryParse.Parsed parsed) {
    hawkeye.lookup(parsed.query(), 0, 20).thenAccept(events -> { /* newest first */ });
}
```

`HawkeyeApi` records placements, removals, container moves and messages on behalf of an actor (a
player, or a source such as `Actor.source("myplugin")`), parses parameter lists, and looks up or
counts events without blocking the caller. `HawkeyeApi.VERSION` changes on incompatible changes.

## Building

```bash
./gradlew build                          # compile, tests, ArchitectureTest, verifyPluginJar
./gradlew test --tests '<TestClass>'     # one test class
```

The plugin jar is `build/libs/Hawkeye.jar`. Storage tests run on real SQLite files. To run the same
scenarios on server engines, point `HAWKEYE_TEST_MYSQL` and/or `HAWKEYE_TEST_POSTGRES` at an empty
database (`mariadb://user:password@host:3306/db`, `postgresql://user:password@host:5432/db`); the
tests are skipped without them.

`scripts/smoke/run.py <server-dir> scripts/smoke/tnt-rollback.txt` boots a real Paper server with the
jar and runs a console scenario: TNT damage is recorded, looked up, rolled back, restored and undone,
with the world checked by `/execute if block` after each step.

## Documentation

- `docs/architecture.md`: storage format, query shapes, threading, multi-server design.
- `docs/knowledge/README.md`: verified facts and owner preferences for contributors.
- `AGENTS.md`: the rules every change follows.
- `CHANGELOG.md`: changes per release.
