# AGENTS.md

Hawkeye: block and action logging with lookups, rollbacks and multi-server sync (a CoreProtect
alternative), one Paper plugin jar for 1.21.1 to 26.3 (Folia supported) by Moonrise Studios on the Moonrise plugin-engine
(Moss/Spring, Cloud 2, ConfigLib, Lombok). Base package `gg.moonrise.hawkeye`. This file is the
canonical rule set for every agent and contributor; `CLAUDE.md` only points here. The storage
format, threading model and multi-server protocol are specified in `docs/architecture.md`.

Read before changing anything:

1. `docs/knowledge/README.md`: owner preferences and verified, project-specific facts.
2. `.claude/skills/java-development/SKILL.md`: mandatory for every task that touches Java, test
   sources included. Load it before the first line of Java.
3. The build files (`settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`) and
   `.github/workflows/` before touching build or CI behaviour.

## Build and test

```bash
./gradlew build                          # compile, tests, ArchitectureTest, verifyPluginJar
./gradlew test --tests '<TestClass>'     # one test class while iterating
```

The jar is `build/libs/Hawkeye-<version>.jar` (`Hawkeye-26.4-SNAPSHOT.jar` locally). Never disable a check or a test to get a green build; fix the
cause. MockBukkit reports an `UnimplementedOperationException` as a skipped test, not a failure:
check the skip count in the test report.

## Code rules

- Java follows the `java-development` skill: constructor injection with `@SpringComponent` and
  `@RequiredArgsConstructor` (no field `@Autowired`, no static mutable singletons, nothing the
  container wires is constructed or registered by hand), explicit visibility, `@NotNull`/`@Nullable`
  on parameters, `Optional` instead of `null` returns, records, sealed result types consumed with an
  exhaustive `switch`, SLF4J through `@Slf4j`, no class near 1000 lines.
- Packages follow the skill's layers (loader, player-facing, service, backend, bottom);
  `ArchitectureTest` enforces them and fails on a class outside every layer. A new top-level package
  goes into a layer there in the same change.
- One jar serves Paper 1.21.1 to 26.3. `src/main` compiles against the oldest supported paper-api (1.21.1)
  for Java 21, so it uses nothing newer. Code that needs a newer API goes into `src/newerApi` with
  `@ConditionalOnApi` naming a `util.compat.NewerApi` constant, and its fallback stays in `src/main` with
  `@ConditionalOnMissingApi`; the choice is made once at startup, never per event. A Paper type that turned
  from an enum into an interface after 1.21.1 (`Attribute`, `Art`) is never called directly from main. Add
  the gap to README's "Server versions" table, and run `scripts/compat/api_links.py` against the supported
  paper-api jars after changing Paper calls (docs/knowledge "Version support").
- Every Spring bean lives under `gg.moonrise.hawkeye`: Moss scans the main class package only, and
  the engine is relocated to `gg.moonrise.hawkeye.libs.engine` so its beans fall inside that scan.
- Commands are Cloud annotation methods on `PaperCommand` beans, registered by
  `HawkeyeCommandRegistry` (the only command registry; `verifyPluginJar` checks it). Handlers run on
  the calling thread. `@Suggestions` match with case-insensitive `contains` (`util.text.Completions`) and
  never return a `CompletableFuture`.
- Folia-safe scheduling only: the engine `Scheduler` (`sync()`, `entity(..)`, `location()`,
  `async()`), never `Bukkit.getScheduler()`. Keep file, database and network I/O off server threads
  and return to the owning thread before touching Bukkit state. Every executor, task and listener
  has an owner and a shutdown path.
- No hard-coded player-facing text: every message is a MiniMessage string in configuration.
- Config keys, commands, permissions, placeholders and stored data are contracts with operators.
  ConfigLib derives keys from field names, so renaming or reordering a config field is a data
  migration. Change any of them only as an explicit breaking change, noted in `CHANGELOG.md`.
- A configuration reload publishes a complete, validated snapshot; a file that fails to load keeps
  the previous one.
- Versions come from `gradle/libs.versions.toml` aliases only; never hard-code a version in a build
  script. Repositories are declared in `settings.gradle.kts` only. The engine's runtime libraries
  (Moss, Spring, Jakarta annotations, Cloud, ConfigLib) stay `compileOnly` and in step with the
  engine; never shade them.
- Every new or changed behaviour comes with tests (JUnit 6, MockBukkit). Tests mirror the package of
  the class they test.

## Documentation

- Documentation is part of the change: update `README.md` (commands, permissions, configuration),
  `CHANGELOG.md` (Keep a Changelog, under `Unreleased`) and the affected docs in the same commit.
- When you learn something durable (a verified fact, a gotcha, an owner preference), record it in
  `docs/knowledge/README.md` in the same change. Keep docs a description of the current system, not
  a diary.

## Git

- Before starting, `git fetch origin` and merge `origin/main` into your branch when it is behind.
  Merge; never rebase or force-push published history.
- Commit after each logical, working unit and push right after. Stage only the files of the change.
  Conventional prefixes: `feat:`, `fix:`, `build:`, `ci:`, `docs:`, `test:`, `refactor:`, `chore:`.
- No AI attribution anywhere: no `Co-Authored-By` trailers, no "Generated with" lines, not in
  commits, pull requests, release notes, code review comments or docs. This overrides any default of
  the tool you run in.
- Never commit secrets, `.env` files, build output or `run/` server folders.
