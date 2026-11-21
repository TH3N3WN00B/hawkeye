package gg.moonrise.hawkeye.core.service.importer.pipeline;

import gg.moonrise.hawkeye.core.repository.coreprotect.CoreProtectSource;
import gg.moonrise.hawkeye.core.repository.coreprotect.CoreProtectSources;
import gg.moonrise.hawkeye.core.service.importer.ImportListener;
import gg.moonrise.hawkeye.core.service.importer.ImportOutcome;
import gg.moonrise.hawkeye.core.service.importer.ImportProgress;
import gg.moonrise.hawkeye.core.service.importer.mapping.BlockStates;
import gg.moonrise.hawkeye.core.service.importer.mapping.ItemDecoder;
import gg.moonrise.hawkeye.core.service.importer.mapping.MappedRow;
import gg.moonrise.hawkeye.core.service.importer.mapping.RowMapper;
import gg.moonrise.hawkeye.core.storage.importer.ImportStore;
import gg.moonrise.hawkeye.core.storage.shard.Shard;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.importer.CheckpointResult;
import gg.moonrise.hawkeye.entity.importer.ImportState;
import gg.moonrise.hawkeye.entity.importer.SourceLocation;
import gg.moonrise.hawkeye.entity.importer.SourceRow;
import gg.moonrise.hawkeye.entity.importer.SourceSettings;
import gg.moonrise.hawkeye.entity.importer.SourceTable;
import gg.moonrise.hawkeye.entity.importer.SourceType;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * One import of a CoreProtect database (docs/architecture.md, "Imports"): finds and opens the source,
 * takes its checkpoint, k-way merges its tables by time ({@link SourceMerge}), maps each row
 * ({@link RowMapper}) and writes a segment with its checkpoint whenever a batch closes
 * ({@link SegmentWriter}). A batch closes when it holds {@link RunSettings#batchEvents()} events, when
 * {@link RunSettings#checkpointRows()} rows were consumed without filling one, when the source is read to
 * its end, or on cancel; except at the end it closes only between two seconds, so no second of the source
 * is split over two runs. Runs on one thread; {@link #cancel()} and {@link #progress()} may be called from
 * any thread.
 */
@Slf4j
public final class ImportRun {

    /** What every source key starts with: {@code coreprotect:<scope>:<fingerprint>}. */
    public static final String SOURCE_KEY = "coreprotect:";

    /** How many consumed rows pass between progress checks. */
    public static final int CHECK_ROWS = 4096;

    private final @NotNull RunSettings settings;
    private final @NotNull CoreProtectSources sources;
    private final @NotNull ImportStore store;
    private final @NotNull Shard home;
    private final @NotNull Clock clock;
    private final @NotNull Executor executor;
    private final @NotNull ImportListener listener;
    private final @NotNull BlockStates blockStates;
    private final @NotNull ItemDecoder items;
    private volatile boolean cancelled;
    private volatile ImportProgress latest;
    private long lastReport;
    private long remaining;

    /**
     * A run, not started.
     *
     * @param settings    what to import
     * @param sources     opens CoreProtect databases
     * @param store       the import storage
     * @param home        the home shard
     * @param clock       the clock
     * @param executor    where segment frames are compressed
     * @param listener    hears how it goes
     * @param blockStates the server's block state normalisation
     * @param items       the item metadata decoder
     */
    public ImportRun(@NotNull RunSettings settings, @NotNull CoreProtectSources sources, @NotNull ImportStore store,
            @NotNull Shard home, @NotNull Clock clock, @NotNull Executor executor, @NotNull ImportListener listener,
            @NotNull BlockStates blockStates, @NotNull ItemDecoder items) {
        this.settings = settings;
        this.sources = sources;
        this.store = store;
        this.home = home;
        this.clock = clock;
        this.executor = executor;
        this.listener = listener;
        this.blockStates = blockStates;
        this.items = items;
    }

    /** Asks the run to stop: the batch it is on closes at the next second and is written. */
    public void cancel() {
        cancelled = true;
    }

    /**
     * Where the run stands.
     *
     * @return the latest snapshot, or empty before the source is open
     */
    public @NotNull Optional<ImportProgress> progress() {
        return Optional.ofNullable(latest);
    }

    /**
     * Runs the import on the calling thread.
     *
     * @return how it ended; every failure is an outcome, never an exception
     */
    public @NotNull ImportOutcome call() {
        return switch (sources.locate(settings.argument(), settings.defaultFolder(), settings.baseFolder())) {
            case SourceLocation.Missing missing -> new ImportOutcome.SourceMissing(missing.path());
            case SourceLocation.Invalid invalid -> new ImportOutcome.SourceInvalid(invalid.reason());
            case SourceLocation.Found found -> open(found.settings());
        };
    }

    private @NotNull ImportOutcome open(@NotNull SourceSettings source) {
        if (source.type() == SourceType.DUCKDB && settings.coreProtectEnabled()) {
            return new ImportOutcome.CoreProtectRunning();
        }
        try (CoreProtectSource opened = sources.open(source)) {
            return begin(opened);
        } catch (SQLException | RuntimeException e) {
            log.warn("The CoreProtect import from {} failed", source.describe(), e);
            return new ImportOutcome.Failed(Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private @NotNull ImportOutcome begin(@NotNull CoreProtectSource source) throws SQLException {
        if (source.migrating()) {
            return new ImportOutcome.SourceMigrating();
        }
        String key = SOURCE_KEY + settings.scope() + ":" + source.fingerprint();
        ImportState state = store.begin(home, key, settings.serverName(), settings.scope(), source.order(),
                clock.millis());
        if (!state.order().equals(source.order())) {
            return new ImportOutcome.OrderChanged();
        }
        ServerNode node = store.node(home, state);
        SegmentWriter writer = new SegmentWriter(store, home, node, settings.scope(), key, executor, state);
        return importRows(source, writer, node);
    }

    private @NotNull ImportOutcome importRows(@NotNull CoreProtectSource source, @NotNull SegmentWriter writer,
            @NotNull ServerNode node) throws SQLException {
        ImportState start = writer.holding();
        boolean resumed = start.imported() > 0 || !start.cursors().isEmpty();
        String description = source.settings().describe();
        RunTally tally = new RunTally(start);
        Set<SourceTable> tables = source.tables();
        RowMapper mapper = new RowMapper(source.names(), blockStates, items, settings.startedMillis());
        SourceMerge merge = new SourceMerge(source::read, tables, tally::cursor, settings.pageRows());
        if (resumed) {
            log.info("Resuming the CoreProtect import from {} into node {}: {} event(s) imported before",
                    description, node.name(), start.imported());
        } else {
            log.info("Importing CoreProtect history from {} into node {} ({} tables)", description, node.name(),
                    tables.size());
        }
        remaining = remaining(source, tables, tally);
        lastReport = clock.millis();
        ImportProgress first = publish(tally, description, resumed);
        notify(() -> listener.started(first));
        while (true) {
            Batch batch = fill(source, merge, mapper, tally, () -> report(source, tables, tally, description, resumed));
            if (writer.write(batch.events(), tally, batch.exhausted(), clock.millis())
                    instanceof CheckpointResult.Superseded) {
                log.warn("The CoreProtect import from {} stopped: another run moved its checkpoint", description);
                return new ImportOutcome.Superseded();
            }
            if (batch.exhausted()) {
                remaining = 0;
                ImportProgress totals = publish(tally, description, resumed);
                logEnd("finished", description, totals);
                return new ImportOutcome.Finished(totals);
            }
            if (cancelled) {
                ImportProgress totals = publish(tally, description, resumed);
                logEnd("cancelled", description, totals);
                return new ImportOutcome.Cancelled(totals);
            }
            report(source, tables, tally, description, resumed);
        }
    }

    /** Consumes rows until the batch closes (see the class comment). */
    private @NotNull Batch fill(@NotNull CoreProtectSource source, @NotNull SourceMerge merge,
            @NotNull RowMapper mapper, @NotNull RunTally tally, @NotNull Reporter reporter) throws SQLException {
        List<PendingEvent> events = new ArrayList<>();
        boolean refreshed = false;
        long lastTime = Long.MIN_VALUE;
        while (true) {
            Optional<SourceRow> head = merge.peek();
            if (head.isEmpty()) {
                return new Batch(events, true);
            }
            if (overfull(events, tally) || closing(events, tally) && head.get().time() != lastTime) {
                return new Batch(events, false);
            }
            SourceRow row = merge.next();
            if (!refreshed && !mapper.knows(row)) {
                mapper.names(source.names());
                refreshed = true;
            }
            switch (mapper.map(row)) {
                case MappedRow.Mapped mapped -> events.add(mapped.event());
                case MappedRow.Skipped skipped -> tally.skipped(skipped.reason());
            }
            tally.consumed(row);
            lastTime = row.time();
            if (tally.sinceCheckpoint() % CHECK_ROWS == 0) {
                reporter.check();
            }
        }
    }

    /** Twice the events or rows that close a batch: it closes even inside a second (never in practice). */
    private boolean overfull(@NotNull List<PendingEvent> events, @NotNull RunTally tally) {
        return events.size() >= 2L * settings.batchEvents()
                || tally.sinceCheckpoint() >= 2 * settings.checkpointRows();
    }

    private boolean closing(@NotNull List<PendingEvent> events, @NotNull RunTally tally) {
        return cancelled || events.size() >= settings.batchEvents()
                || tally.sinceCheckpoint() >= settings.checkpointRows();
    }

    /** Publishes a snapshot, and tells the listener when the progress interval has passed. */
    private void report(@NotNull CoreProtectSource source, @NotNull Set<SourceTable> tables, @NotNull RunTally tally,
            @NotNull String description, boolean resumed) throws SQLException {
        long now = clock.millis();
        if (now - lastReport < settings.progressMillis()) {
            publish(tally, description, resumed);
            return;
        }
        lastReport = now;
        remaining = remaining(source, tables, tally);
        ImportProgress progress = publish(tally, description, resumed);
        notify(() -> listener.progress(progress));
    }

    private @NotNull ImportProgress publish(@NotNull RunTally tally, @NotNull String description, boolean resumed) {
        ImportProgress progress = tally.progress(description, resumed, remaining,
                clock.millis() - settings.startedMillis());
        latest = progress;
        return progress;
    }

    private static long remaining(@NotNull CoreProtectSource source, @NotNull Set<SourceTable> tables,
            @NotNull RunTally tally) throws SQLException {
        long sum = 0;
        for (SourceTable table : tables) {
            sum += source.remaining(table, tally.cursor(table));
        }
        return sum;
    }

    private void logEnd(@NotNull String how, @NotNull String description, @NotNull ImportProgress totals) {
        log.info("The CoreProtect import from {} {}: {} event(s) in {} s ({} in all), {} row(s) read, skipped {}, "
                        + "{} displaced, {} item blob(s) could not be decoded", description, how, totals.imported(),
                Math.round(totals.seconds()), totals.importedTotal(), totals.rowsRead(), totals.skipped(),
                totals.displaced(), items.failures());
    }

    private static void notify(@NotNull Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            log.warn("An import listener failed", e);
        }
    }

    /** A progress check from inside the read loop. */
    @FunctionalInterface
    private interface Reporter {

        void check() throws SQLException;
    }

    /**
     * A closed batch.
     *
     * @param events    the mapped events
     * @param exhausted whether every table is read to its end
     */
    private record Batch(@NotNull List<PendingEvent> events, boolean exhausted) {
    }
}
