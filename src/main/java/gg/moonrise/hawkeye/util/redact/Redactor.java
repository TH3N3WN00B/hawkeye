package gg.moonrise.hawkeye.util.redact;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hides credentials in text before it reaches a log or a player. A secret (a password, a value read from
 * an environment variable) is replaced wherever it occurs; a name (a user name) only where it stands on
 * its own, not next to a letter, digit or {@code _}, so the user {@code hawkeye} does not mangle the table
 * {@code hawkeye_event}. Empty values are ignored, and where two candidates start at the same place the
 * longer one wins. Immutable; its {@link #toString()} shows only how many values it hides.
 */
public final class Redactor {

    /** What every hidden value is replaced with. */
    public static final String MASK = "<redacted>";

    private static final Redactor NONE = new Redactor(List.of());

    private final @NotNull List<Candidate> candidates;

    private Redactor(@NotNull List<Candidate> candidates) {
        this.candidates = candidates;
    }

    /**
     * A redactor for these values.
     *
     * @param secrets values hidden wherever they occur
     * @param names   values hidden only on identifier boundaries
     * @return the redactor
     */
    public static @NotNull Redactor of(@NotNull Collection<String> secrets, @NotNull Collection<String> names) {
        List<Candidate> candidates = new ArrayList<>();
        secrets.stream().filter(secret -> secret != null && !secret.isEmpty())
                .forEach(secret -> candidates.add(new Candidate(secret, false)));
        names.stream().filter(name -> name != null && !name.isEmpty())
                .forEach(name -> candidates.add(new Candidate(name, true)));
        if (candidates.isEmpty()) {
            return NONE;
        }
        candidates.sort(Comparator.comparingInt((Candidate candidate) -> candidate.text().length()).reversed());
        return new Redactor(List.copyOf(candidates));
    }

    /**
     * A redactor that hides nothing.
     *
     * @return the redactor
     */
    public static @NotNull Redactor none() {
        return NONE;
    }

    /**
     * The text with every hidden value replaced by {@link #MASK}.
     *
     * @param text the text, or {@code null}
     * @return the scrubbed text; empty for {@code null}
     */
    public @NotNull String scrub(@Nullable String text) {
        if (text == null) {
            return "";
        }
        if (candidates.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int length = matchAt(text, i);
            if (length > 0) {
                out.append(MASK);
                i += length;
            } else {
                out.append(text.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Whether a text contains a hidden value.
     *
     * @param text the text, or {@code null}
     * @return {@code true} when {@link #scrub} would change it
     */
    public boolean reveals(@Nullable String text) {
        if (text == null || candidates.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (matchAt(text, i) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this redactor hides anything at all.
     *
     * @return {@code false} for {@link #none()} and for a redactor built from empty values only
     */
    public boolean hidesAnything() {
        return !candidates.isEmpty();
    }

    @Override
    public @NotNull String toString() {
        return "Redactor[" + candidates.size() + " values]";
    }

    /** The length of the longest candidate that matches at {@code at}, or 0. */
    private int matchAt(@NotNull String text, int at) {
        for (Candidate candidate : candidates) {
            String value = candidate.text();
            if (text.startsWith(value, at) && (!candidate.name() || standsAlone(text, at, at + value.length()))) {
                return value.length();
            }
        }
        return 0;
    }

    private static boolean standsAlone(@NotNull String text, int start, int end) {
        return (start == 0 || !identifier(text.charAt(start - 1))) && (end == text.length()
                || !identifier(text.charAt(end)));
    }

    private static boolean identifier(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /**
     * One hidden value.
     *
     * @param text the value
     * @param name whether it is hidden only on identifier boundaries
     */
    private record Candidate(@NotNull String text, boolean name) {

        @Override
        public @NotNull String toString() {
            return name ? "Candidate[name]" : "Candidate[secret]";
        }
    }
}
