package gg.moonrise.hawkeye.util.text;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/** Tab-completion helpers for {@code @Suggestions} providers. */
public final class Completions {

    private Completions() {
    }

    /**
     * The candidates that contain {@code input}, ignoring case, so partial input anywhere in a name
     * matches (never {@code startsWith}).
     *
     * @param candidates every possible suggestion
     * @param input      what the sender typed so far
     * @return the matching candidates in their original order
     */
    public static @NotNull List<String> containing(@NotNull Collection<String> candidates, @NotNull String input) {
        String needle = input.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }
}
