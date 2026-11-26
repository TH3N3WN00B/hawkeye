package gg.moonrise.hawkeye.core.diagnostic;

import gg.moonrise.hawkeye.util.log.LogMarkers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Writes Hawkeye's warnings, errors and crashes to text files in its data folder, so the console shows each
 * problem once. {@link #report} decides on the calling thread, without blocking or touching a file, whether a
 * problem is new in this run; its own thread writes the files. Two problems are the same when their
 * {@link Problem#fingerprint() fingerprints} match.
 *
 * <p>A problem's first time is written in full (message and stack trace) to the file of that minute, after the
 * server header; every repeat goes into that same file, one line each for the first {@link #REPEAT_LINES}, then
 * one line a minute that counts them. That bounds what a problem repeated every tick writes to about one line a
 * minute. If a file cannot be written the error log stops taking problems, and they are logged as before.
 *
 * <p>Thread-safe: {@link #report} may be called from any thread (Folia region threads included).
 */
@Slf4j
public final class ErrorLog {

    /** Repeats of one problem written one by one before they are counted instead. */
    public static final int REPEAT_LINES = 100;

    /** How often counted repeats are written. */
    public static final Duration SUMMARY_INTERVAL = Duration.ofMinutes(1);

    /** Different problems tracked in one run; any further one is logged as before. */
    public static final int MAX_TRACKED = 1000;

    private static final int QUEUE_CAPACITY = 10_000;
    private static final long NO_THREAD = -1;

    private final @NotNull Path dataFolder;
    private final @NotNull Clock clock;
    private final @NotNull ReportFormat format;
    private final @NotNull Supplier<ServerSnapshot> server;
    private final Map<String, Tracked> tracked = new ConcurrentHashMap<>();
    private final AtomicInteger numbers = new AtomicInteger();
    private final BlockingQueue<Job> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread writer;
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private volatile boolean open;
    private volatile boolean broken;
    private volatile long startupThread = NO_THREAD;

    // Writer thread only.
    private final Set<Path> headed = new HashSet<>();
    private final Set<Tracked> counting = new LinkedHashSet<>();

    /**
     * An error log that is not started yet.
     *
     * @param dataFolder Hawkeye's data folder ({@code plugins/Hawkeye})
     * @param clock      the clock of counted repeats; its zone names the files
     * @param server     the server facts each file starts with, read on the writer thread
     */
    public ErrorLog(@NotNull Path dataFolder, @NotNull Clock clock, @NotNull Supplier<ServerSnapshot> server) {
        this.dataFolder = dataFolder;
        this.clock = clock;
        this.format = new ReportFormat(clock.getZone());
        this.server = server;
        this.writer = new Thread(this::run, "Hawkeye-ErrorLog");
        this.writer.setDaemon(true);
    }

    /** Starts the writer thread; problems are taken from now on. */
    public void start() {
        open = true;
        writer.start();
    }

    /**
     * Files a problem.
     *
     * @param problem the problem, copied out of its logging event
     * @return {@link ReportResult.First} the first time (show it once), {@link ReportResult.Repeat} after that
     *         (stay quiet), {@link ReportResult.Untracked} when the error log does not take it (log it as usual)
     */
    public @NotNull ReportResult report(@NotNull Problem problem) {
        if (broken) {
            return new ReportResult.Untracked("the error files cannot be written");
        }
        if (!open) {
            return new ReportResult.Untracked("the error log is closed");
        }
        String key = problem.fingerprint();
        Tracked known = tracked.get(key);
        if (known == null) {
            if (tracked.size() >= MAX_TRACKED) {
                return new ReportResult.Untracked("more than " + MAX_TRACKED + " different problems");
            }
            // The first job is queued inside computeIfAbsent, so a repeat from another thread (which waits for
            // the mapping) is always queued after it.
            Tracked[] created = new Tracked[1];
            known = tracked.computeIfAbsent(key, ignored -> {
                Tracked fresh = new Tracked(format.file(dataFolder, problem.kind(), problem.timeMillis()),
                        problem.kind(), numbers.incrementAndGet());
                if (!queue.offer(new Job.First(fresh, problem))) {
                    return null;
                }
                created[0] = fresh;
                return fresh;
            });
            if (known == null) {
                dropped.incrementAndGet();
                return new ReportResult.Untracked("the error log is behind");
            }
            if (known == created[0]) {
                return new ReportResult.First(known.file, known.number);
            }
        }
        long occurrence = known.occurrences.incrementAndGet();
        if (!queue.offer(new Job.Repeat(known, problem, occurrence))) {
            dropped.incrementAndGet();
        }
        return new ReportResult.Repeat(known.file, known.number, occurrence);
    }

    /** Marks the calling thread as running Hawkeye's load or enable: what it logs at ERROR is a crash. */
    public void startupBegins() {
        startupThread = Thread.currentThread().threadId();
    }

    /** Ends {@link #startupBegins()}. */
    public void startupEnds() {
        startupThread = NO_THREAD;
    }

    /**
     * Whether a thread is running Hawkeye's load or enable now.
     *
     * @param threadId the thread's id
     * @return {@code true} while it is
     */
    public boolean isStartupThread(long threadId) {
        return threadId != NO_THREAD && threadId == startupThread;
    }

    /**
     * Completes once everything reported so far is written (counted repeats wait for their minute).
     *
     * @return the future; already complete when the error log is closed
     */
    public @NotNull CompletableFuture<Void> flush() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (!open || !queue.offer(new Job.Flush(done))) {
            done.complete(null);
        }
        return done;
    }

    /**
     * Stops taking problems, writes what is queued and the counted repeats, and stops the writer thread.
     *
     * @param wait how long to wait for the writer
     */
    public void close(@NotNull Duration wait) {
        if (!open) {
            return;
        }
        open = false;
        try {
            queue.put(new Job.Stop());
            stopped.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException | ExecutionException e) {
            log.warn(LogMarkers.REPORTED, "The error log did not finish writing within {} s", wait.toSeconds());
        }
    }

    private void run() {
        try {
            boolean stopping = false;
            while (!stopping) {
                Job first = queue.poll(1, TimeUnit.SECONDS);
                List<Job> jobs = new ArrayList<>();
                if (first != null) {
                    jobs.add(first);
                    queue.drainTo(jobs);
                }
                Map<Path, StringBuilder> writes = new LinkedHashMap<>();
                List<CompletableFuture<Void>> flushed = new ArrayList<>();
                for (Job job : jobs) {
                    switch (job) {
                        case Job.First entry -> writeFirst(writes, entry);
                        case Job.Repeat repeat -> writeRepeat(writes, repeat);
                        case Job.Flush flush -> flushed.add(flush.done());
                        case Job.Stop stop -> stopping = true;
                    }
                }
                writeCounted(writes, stopping);
                writeDropped(writes);
                write(writes);
                flushed.forEach(done -> done.complete(null));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            broken = true;
            log.error(LogMarkers.REPORTED, "The error log stopped; problems are logged to the console as before", e);
        } finally {
            open = false;
            queue.forEach(job -> {
                if (job instanceof Job.Flush flush) {
                    flush.done().complete(null);
                }
            });
            stopped.complete(null);
        }
    }

    private void writeFirst(@NotNull Map<Path, StringBuilder> writes, @NotNull Job.First job) {
        Tracked problem = job.tracked();
        StringBuilder text = text(writes, problem.file, problem.kind, job.problem().timeMillis());
        text.append(format.entry(problem.number, job.problem()));
    }

    private void writeRepeat(@NotNull Map<Path, StringBuilder> writes, @NotNull Job.Repeat job) {
        Tracked problem = job.tracked();
        if (problem.written < REPEAT_LINES) {
            problem.written++;
            text(writes, problem.file, problem.kind, job.problem().timeMillis())
                    .append(format.repeat(problem.number, job.occurrence(), job.problem()));
            return;
        }
        if (problem.counted == 0) {
            problem.countedFrom = job.problem().timeMillis();
            counting.add(problem);
        }
        problem.counted++;
        problem.countedTo = job.problem().timeMillis();
        problem.countedTotal = job.occurrence();
        problem.last = job.problem();
    }

    private void writeCounted(@NotNull Map<Path, StringBuilder> writes, boolean all) {
        long now = clock.millis();
        for (var iterator = counting.iterator(); iterator.hasNext(); ) {
            Tracked problem = iterator.next();
            if (!all && now - problem.countedFrom < SUMMARY_INTERVAL.toMillis()) {
                continue;
            }
            Problem last = Objects.requireNonNull(problem.last);
            text(writes, problem.file, problem.kind, now).append(format.summary(problem.number, problem.counted,
                    problem.countedFrom, problem.countedTo, problem.countedTotal, last));
            problem.counted = 0;
            iterator.remove();
        }
    }

    private void writeDropped(@NotNull Map<Path, StringBuilder> writes) {
        long count = dropped.getAndSet(0);
        if (count == 0) {
            return;
        }
        if (writes.isEmpty()) {
            dropped.addAndGet(count);
            return;
        }
        writes.values().iterator().next().append(format.dropped(count, clock.millis()));
    }

    private @NotNull StringBuilder text(@NotNull Map<Path, StringBuilder> writes, @NotNull Path file,
                                        @NotNull ProblemKind kind, long timeMillis) {
        return writes.computeIfAbsent(file, path -> {
            StringBuilder text = new StringBuilder();
            if (headed.add(path)) {
                if (Files.exists(path)) {
                    text.append(System.lineSeparator());
                }
                text.append(header(kind, timeMillis));
            }
            return text;
        });
    }

    private @NotNull String header(@NotNull ProblemKind kind, long timeMillis) {
        try {
            return format.header(kind, server.get(), timeMillis);
        } catch (RuntimeException e) {
            return "Hawkeye " + kind.title() + " (the server facts could not be read: " + e + ")"
                    + System.lineSeparator() + System.lineSeparator();
        }
    }

    private void write(@NotNull Map<Path, StringBuilder> writes) {
        for (Map.Entry<Path, StringBuilder> write : writes.entrySet()) {
            Path file = write.getKey();
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, write.getValue(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                if (!broken) {
                    broken = true;
                    log.error(LogMarkers.REPORTED, "Could not write {}; Hawkeye's problems are logged to the console "
                            + "as before: {}", file, e.toString());
                }
            }
        }
    }

    /** A problem seen in this run. The fields below {@link #occurrences} belong to the writer thread. */
    private static final class Tracked {

        private final @NotNull Path file;
        private final @NotNull ProblemKind kind;
        private final int number;
        private final AtomicLong occurrences = new AtomicLong(1);
        private int written;
        private long counted;
        private long countedFrom;
        private long countedTo;
        private long countedTotal;
        private @Nullable Problem last;

        private Tracked(@NotNull Path file, @NotNull ProblemKind kind, int number) {
            this.file = file;
            this.kind = kind;
            this.number = number;
        }
    }

    /** Work for the writer thread. */
    private sealed interface Job {

        record First(@NotNull Tracked tracked, @NotNull Problem problem) implements Job {
        }

        record Repeat(@NotNull Tracked tracked, @NotNull Problem problem, long occurrence) implements Job {
        }

        record Flush(@NotNull CompletableFuture<Void> done) implements Job {
        }

        record Stop() implements Job {
        }
    }
}
