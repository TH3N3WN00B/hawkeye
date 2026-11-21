package gg.moonrise.hawkeye.util.redact;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * Keeps credentials out of exceptions that are logged or shown. A driver's message can name the user
 * ({@code Access denied for user 'hawkeye'@'10.0.0.5'}) or echo a connection property; such an exception,
 * anywhere in its graph (causes, suppressed exceptions, {@link SQLException#getNextException()}), is
 * rebuilt as a chain of plain {@link SQLException}s with scrubbed messages, the same SQL states, vendor
 * codes and stack traces, and no reference to an original.
 */
public final class RedactedErrors {

    /** How deep a graph is followed; deeper parts are dropped from a rebuilt one. */
    private static final int MAX_DEPTH = 32;

    private RedactedErrors() {
    }

    /**
     * The exception to throw or log in place of {@code error}.
     *
     * @param error    what was thrown
     * @param redactor what must not be shown
     * @return {@code error} itself when nothing in its graph reveals a hidden value, otherwise a scrubbed copy
     */
    public static @NotNull Throwable safe(@NotNull Throwable error, @NotNull Redactor redactor) {
        return reveals(error, redactor) ? rebuild(error, redactor) : error;
    }

    /**
     * The SQL exception to throw in place of {@code error}.
     *
     * @param error    what the driver threw
     * @param redactor what must not be shown
     * @return {@code error} itself when nothing in its graph reveals a hidden value, otherwise a scrubbed copy
     */
    public static @NotNull SQLException safe(@NotNull SQLException error, @NotNull Redactor redactor) {
        return reveals(error, redactor) ? rebuild(error, redactor) : error;
    }

    /**
     * Whether any message in the graph of {@code error} contains a hidden value.
     *
     * @param error    the exception
     * @param redactor what must not be shown
     * @return {@code true} when a message, a cause's, a suppressed or a next exception's does
     */
    public static boolean reveals(@NotNull Throwable error, @NotNull Redactor redactor) {
        if (!redactor.hidesAnything()) {
            return false;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> pending = new ArrayList<>(List.of(error));
        while (!pending.isEmpty() && seen.size() < MAX_DEPTH * MAX_DEPTH) {
            Throwable next = pending.removeLast();
            if (!seen.add(next)) {
                continue;
            }
            if (redactor.reveals(next.getMessage()) || redactor.reveals(next.getLocalizedMessage())) {
                return true;
            }
            pending.addAll(linked(next));
        }
        return false;
    }

    /**
     * A scrubbed copy of the graph of {@code error}: an {@link SQLException} keeps its message, SQL state and
     * vendor code; any other exception becomes one whose message starts with the original class name, as
     * {@link Throwable#toString()} does.
     *
     * @param error    the exception
     * @param redactor what must not be shown
     * @return the copy
     */
    public static @NotNull SQLException rebuild(@NotNull Throwable error, @NotNull Redactor redactor) {
        return copy(error, redactor, new IdentityHashMap<>(), 0);
    }

    private static @NotNull SQLException copy(@NotNull Throwable error, @NotNull Redactor redactor,
            @NotNull Map<Throwable, SQLException> copies, int depth) {
        SQLException known = copies.get(error);
        if (known != null) {
            return known;
        }
        SQLException copy = error instanceof SQLException sql
                ? new SQLException(sql.getMessage() == null ? null : redactor.scrub(sql.getMessage()),
                        sql.getSQLState(), sql.getErrorCode())
                : new SQLException(error.getClass().getName() + (error.getMessage() == null ? ""
                        : ": " + redactor.scrub(error.getMessage())));
        copy.setStackTrace(error.getStackTrace());
        copies.put(error, copy);
        if (depth >= MAX_DEPTH) {
            return copy;
        }
        Throwable cause = error.getCause();
        if (cause != null && cause != error && !copies.containsKey(cause)) {
            copy.initCause(copy(cause, redactor, copies, depth + 1));
        }
        for (Throwable suppressed : error.getSuppressed()) {
            if (!copies.containsKey(suppressed)) {
                copy.addSuppressed(copy(suppressed, redactor, copies, depth + 1));
            }
        }
        if (error instanceof SQLException sql && sql.getNextException() != null
                && !copies.containsKey(sql.getNextException())) {
            copy.setNextException(copy(sql.getNextException(), redactor, copies, depth + 1));
        }
        return copy;
    }

    private static @NotNull List<Throwable> linked(@NotNull Throwable error) {
        List<Throwable> linked = new ArrayList<>(List.of(error.getSuppressed()));
        if (error.getCause() != null) {
            linked.add(error.getCause());
        }
        if (error instanceof SQLException sql && sql.getNextException() != null) {
            linked.add(sql.getNextException());
        }
        return linked;
    }
}
