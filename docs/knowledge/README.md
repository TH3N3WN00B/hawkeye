# Knowledge base

Read this before working in this repository. It records the owner's preferences and verified,
project-specific know-how that the code does not make obvious. Keep entries short and factual, and
update them in the same change when you learn something durable or an entry stops being true.

## Source of truth

- `AGENTS.md` holds the rules; this file holds the facts behind them.
- The build files are the executable authority: `gradle/libs.versions.toml` for versions,
  `settings.gradle.kts` for repositories, `build.gradle.kts` for packaging and the jar check.
- `src/main/resources/paper-plugin.yml` is hand-written; `PaperPluginYmlTest` and `verifyPluginJar`
  keep it in step with the classes.

## Hawkeye facts (verified 2026-11-16)

- The design contract (storage format, threading, multi-server protocol) is `docs/architecture.md`;
  change it in the same commit as the code.
- ConfigLib 4.8.1 fills a record component that is missing from the file with `null`/`0`, not with
  the record's no-argument defaults, so a key added in a later version would break every existing
  file. Settings are `@Configuration` classes with field initializers, read through Lombok
  `@Getter @Accessors(fluent = true)` (`config.storage().type()`), plus `@EqualsAndHashCode` so a
  reload can tell which restart-only sections changed.
- PostgreSQL identities and InnoDB auto-increments consume an id even for an insert that conflicts
  (`ON CONFLICT DO NOTHING`, `ON DUPLICATE KEY UPDATE`). Dictionary interning and server registration
  therefore select first and insert only what is missing: node ids end at 1023.
- MySQL's `>>` is a logical 64-bit shift and SQLite/PostgreSQL's is arithmetic, so SQL on packed
  positions only extracts low bits (`(pos >> n) & mask`), which agree on all three.
- SQLite creates its database file but not missing folders; `DatabaseFactory` creates them.
- SQLite query plans for the lookup shapes are asserted in `EventSqlPlanTest` (area: index range scans
  on `(world, pos)`; one block: one seek; whole worlds: the primary key, because `(world + 0)` keeps
  the position index out).
- Storage footprint on SQLite (`StorageFootprintTest`, 60,000 random block events): hot rows about
  73 bytes per event, archived about 12, CoreProtect's relational `co_block` layout about 80. Random
  positions and types are the worst case for the archive; real builds compress better.
- This Linux build host has no JDK 25 and no Docker. `settings.gradle.kts` applies the foojay toolchain
  resolver, which downloaded Temurin 25 into `~/.gradle/jdks`. SQLite runs for real in every storage
  test.
- `ServerDatabaseIntegrationTest` runs the storage scenarios on real servers when
  `HAWKEYE_TEST_MYSQL` / `HAWKEYE_TEST_POSTGRES` hold `scheme://user:password@host:port/database`;
  otherwise it is skipped. Without Docker, real servers come from Maven Central binaries: PostgreSQL
  from `io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64` (a `.txz` with `initdb` and
  `pg_ctl`) and MariaDB from `ch.vorburger.mariaDB4j:mariaDB4j-db-linux64` (`scripts/mariadb-install-db`
  and `bin/mariadbd`), unpacked under `~/.cache` and started on local ports. Verified 2026-11-16 on
  MariaDB 11.4.5 and PostgreSQL 18.6.
- `scripts/smoke/run.py <server-dir> <scenario>` boots a real Paper server from the console, runs a
  scenario (`command => expected output`) and fails on a missing reply or a Hawkeye error in the log.
  `scripts/smoke/tnt-rollback.txt` records TNT damage, then looks it up, rolls it back, restores and
  undoes it, checking the world with vanilla `/execute if block`; it needs a flat overworld. The
  server folder holds `paper.jar` (from `https://fill.papermc.io/v3/projects/paper/versions/26.3/builds`),
  `eula.txt` and `plugins/Hawkeye.jar`. Verified 2026-11-16 on Paper 26.3 build 253: the loader
  resolved every library, Cloud registered all command groups through Brigadier, and the scenario
  passed.
- MariaDB Connector/J reports matched rows, not inserted rows, for `ON DUPLICATE KEY UPDATE`, so an
  insert's update count is not "rows inserted" on MySQL; nothing relies on it.
- Heavy builds on this host go through `rtk proxy t3-build ./gradlew ...` (shared build slots).
- Cloud 2.0.0 recurses until `StackOverflowError` on an empty greedy `@Default("")`. Optional greedy
  parameter lists are `[parameters]` with a `@Nullable` argument read as `""`; an optional `[arg]`
  without `@Default` arrives as `null`.
- Cloud resolves a named suggestion provider while it parses each command that references it, so the
  bean declaring `@Suggestions(QueryInput.SUGGESTIONS)` (`LookupCommand`) is registered first
  (`@Order(Ordered.HIGHEST_PRECEDENCE)`). A `@Suggestions` method can take a `CommandInput`, whose
  `remainingInput()` is the whole greedy argument.
- `Placeholder.parsed` values work inside MiniMessage tag arguments (click commands);
  `Placeholder.unparsed` values do not.
- CompletableFuture runs a dependent stage's `execute` on the thread that completes the source; an
  executor that rejects throws there. `EventWriter` completes flush requests one by one inside a
  guard so a caller's stage can never stop the writer thread.
- MockBukkit 4.120: `Entity#teleportAsync` throws `UnimplementedOperationException`;
  `PlayerMock#sendBlockChange` does nothing (subclass it to record what was sent);
  `BlockMock#setBlockData` does not replace the block's state object, so restored container contents
  and sign text cannot be checked there; region and entity tasks run on `performOneTick`. Its data
  folder is predictable (`<tmp>/TestHawkeyePlugin-0.0.0`), so tests write a `config.yml` before
  loading for start-only settings.
- `PlayerInteractEvent#isCancelled` is deprecated in Paper 26.3; use `useInteractedBlock()`.

## Owner preferences

- Commit after every logical, working unit and push right after. Merge `origin/main`, never rebase or
  force-push published history.
- No AI attribution anywhere: commits, pull requests, release notes, code review comments, docs.
- Conventional commit prefixes (`feat:`, `fix:`, `build:`, `ci:`, `docs:`, `test:`, `chore:`).
- Strict Java style from the `java-development` skill: explicit visibility, `@NotNull`/`@Nullable`
  on parameters, no `return null`, records and sealed interfaces, SLF4J, constructor injection,
  layered packages, files under 1000 lines.
- Moonrise house build: Gradle Kotlin DSL, version catalog with camelCase aliases, the shadow jar
  named after the plugin in `build/libs`, the plain `jar` task disabled, Java 25.
- Every player-facing message is configurable (MiniMessage); commands, permissions and config keys
  are contracts with operators.
- Folia support by default (`folia-supported: true`): schedule through the engine `Scheduler` only.
- Repositories are private and carry no license file unless the owner adds one.

## Environment facts (verified 2026-11-12)

- Checkouts under `~/Documents` live in an iCloud Drive file-provider domain. iCloud can write
  conflict copies of build outputs (`Foo 2.class`, `index 3.html`); a `Foo 2.class` in
  `build/classes/java/test` fails the test task with "wrong name". Fix: delete them
  (`find . -name '* [0-9]*' -not -path './.git/*' -prune -exec rm -rf {} +`, after checking no source
  file matches) and exclude the checkout, or at least its `build` and `.gradle` folders, from sync with
  `xattr -w 'com.apple.fileprovider.ignore#P' 1 <folder>` (macOS 14 or later; local, not in git).
- Local JDKs on the owner's Mac: Temurin 25 at `/Library/Java/JavaVirtualMachines/temurin-25.jdk`;
  the default `java` is OpenJDK 26. Gradle 9.9.0 runs on JDK 26 and compiles with the Temurin 25
  toolchain. Its "restricted method System::load" and `sun.misc.Unsafe` warnings (Gradle's
  native-platform, Lombok, ByteBuddy in MockBukkit) are harmless.
- `https://repo.moonrise.gg/repository/maven-releases/` and `.../maven-snapshots/` allow anonymous
  read: no credentials are needed to build. Publishing needs `nexusUsername`/`nexusPassword`
  (CI: the `NEXUS_USERNAME`/`NEXUS_PASSWORD` repository secrets; there are no org-level secrets).
- GitHub Actions in `moonrise-studios` refused to start any job on 2026-11-12 ("recent account
  payments have failed or your spending limit needs to be increased"); the run shows as failed with
  no steps. That is billing, not the workflow: build locally or on Jenkins until it is fixed.
- MockBukkit 4.119 loads a plugin through a ByteBuddy subclass in the unnamed package, and Moss scans
  `getClass().getPackageName()`, which would then be the whole class path. Tests load
  `support.TestHawkeyePlugin`, which repeats `MossPaper.onLoad` with the real base package. In tests
  the engine is not relocated, so its own beans (`PlayerArgument`, `Cooldowns`, ...) are not scanned.
- MockBukkit cannot build Cloud's `PaperCommandManager`: `HawkeyeCommandRegistry` logs the failure and
  the plugin still enables. Tests register the command beans on `support.TestCommandManager`
  (`TestPlugins.commands()`).
- `MockBukkit.load(Class)` gives the plugin a synthetic descriptor (version `0.0.0`): compare against
  `getPluginMeta().getVersion()`, never a literal. MockBukkit does not register the permissions of a
  `paper-plugin.yml`.
- MockBukkit builds a Bukkit `LibraryLoader` on every `registerEvents`, whose Maven resolver calls
  `InetAddress.getLocalHost()`. On macOS a host name missing from `/etc/hosts` waits 5 s for mDNS per
  call, so the test JVM resolves names from `src/test/hosts` (localhost only).
- MockBukkit 4.119 throws `UnimplementedOperationException` from, among others,
  `ScheduledTask.cancel()` for region and entity tasks; JUnit reports that as a skipped test.

## Build facts (verified 2026-11-12)

- Pinned and resolved: Gradle 9.9.0, Shadow 9.6.1, paper-api `26.3.build.246-stable`, plugin-engine
  `1.10.1`, moss-paper `1.2.2`, spring-context `6.2.13`, jakarta.annotation-api `3.0.0`, cloud-paper
  `2.0.1`, cloud-annotations `2.0.0`, configlib-yaml `4.8.1`, Lombok `1.18.48`, JetBrains annotations
  `26.1.0`, JUnit `6.1.3` with platform launcher `6.1.3`, MockBukkit `mockbukkit-v26.3` `4.120.0`,
  ArchUnit `1.5.1`.
- The engine jar is a fat jar whose POM declares no dependencies. Moss, Spring, Jakarta annotations,
  Cloud and ConfigLib are `compileOnly` (and on the test class path) because the engine
  `PaperPluginLoader` downloads exactly these versions at server start. When the engine version
  changes, read the new loader's defaults and move the catalog with it; Dependabot ignores these
  five for that reason.
- Engine 1.10.1 `PaperPluginLoader.classloader(builder)` calls `onClassLoaderInit(builder)`, then reads
  `libraries()` (`group:artifact:version` strings; an entry with the same `group:artifact` as an engine
  default replaces it), then `addLibraries(resolver)`. It resolves from the Maven Central mirror plus
  the Moonrise releases and snapshots repositories.
- The shadow jar bundles our classes and the engine only (an include filter), relocates
  `gg.moonrise.engine` to `gg.moonrise.hawkeye.libs.engine` and excludes the engine's
  `PaperCommandRegistry`, which runs handlers on Cloud's async coordinator; `HawkeyeCommandRegistry`
  uses `simpleCoordinator` instead. `verifyPluginJar` checks all of it, plus the package directory
  entry Spring needs to list classes.
- Moss 1.2.2 `MossPaper.disableComponents` ends with `Bukkit.getScheduler().cancelTasks(this)`, which
  throws on Folia and skips the Spring context close; `HawkeyePlugin` overrides it with the global
  region and async schedulers.
- Moss lifecycle: `Loadable` (after the context refresh in `onLoad`), `Enableable`, `Disableable` and
  the engine's `Reloadable` (run by `PaperPlugin.reload()`); `@PostConstruct` works. Every `Listener`
  bean is registered at enable.
- `processResources` expands `${version}` in `paper-plugin.yml`; CI passes `-Pversion=<tag>` for
  releases.
- CI pins `gradle/actions/setup-gradle@v5` on purpose. v6 (2026-03) moved caching into the closed-source
  `gradle-actions-caching` component; using v6 accepts the Gradle Terms of Use. Dependabot ignores
  `gradle/actions >= 6` until the owner decides to accept them.

## Release facts

- A release is: set `version` in `gradle.properties` to the release version, commit, publish a GitHub
  release tagged `vX.Y.Z`. CI builds that version, attaches `Hawkeye-X.Y.Z.jar` and, when the Nexus
  secrets exist, publishes it. Then set the next `-SNAPSHOT` version.
- Without GitHub Actions: build locally with `./gradlew build -Pversion=X.Y.Z`, rename the jar to
  `Hawkeye-X.Y.Z.jar` and attach it with `gh release create vX.Y.Z --verify-tag`.
