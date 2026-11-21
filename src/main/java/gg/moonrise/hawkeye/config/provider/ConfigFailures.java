package gg.moonrise.hawkeye.config.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

/**
 * Turns a failure to read a settings file into a reason an operator can be shown. ConfigLib 4.8.1 quotes
 * the value it could not read, and the value of every section around it: a wrong type anywhere in
 * {@code storage} would print the whole section, password included. The reason instead names the key
 * path and the type found ({@code storage.pool-size: cannot read this value (String): ...}), adds the innermost detail (an enum's valid values, a number's range) unless
 * the key is a credential, and never quotes a section.
 */
public final class ConfigFailures {

    /** Keys whose values are credentials or may contain them. */
    private static final Set<String> SECRET_KEYS = Set.of("username", "password", "parameters", "redis-uri");

    /** ConfigLib's message for a value it could not convert: the value, its type, the field. */
    private static final Pattern FIELD = Pattern.compile(
            "with type '(?:class |interface )?([^']*)' for field '[^']*?\\.([A-Za-z0-9_$]+)' failed\\.");

    private static final String TEMPLATE_START = "Deserialization of value '";

    private ConfigFailures() {
    }

    /**
     * The reason for a failed read.
     *
     * @param error what reading threw
     * @return a reason without the value of any credential
     */
    public static @NotNull String reason(@NotNull Throwable error) {
        List<Throwable> chain = chain(error);
        List<String> path = new ArrayList<>();
        String found = null;
        int last = -1;
        for (int i = 0; i < chain.size(); i++) {
            String message = Objects.requireNonNullElse(chain.get(i).getMessage(), "");
            if (!message.startsWith(TEMPLATE_START)) {
                continue;
            }
            last = i;
            Optional<Matcher> field = lastMatch(message);
            if (field.isPresent()) {
                path.add(kebab(field.get().group(2)));
                found = field.get().group(1);
            }
        }
        if (last < 0) {
            return Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName());
        }
        String key = path.isEmpty() ? "a value" : String.join(".", path);
        boolean secret = path.stream().anyMatch(SECRET_KEYS::contains);
        StringBuilder reason = new StringBuilder(key).append(": cannot read this value");
        if (found != null) {
            reason.append(" (").append(found.substring(found.lastIndexOf('.') + 1)).append(')');
        }
        if (last + 1 < chain.size()) {
            Throwable detail = chain.get(last + 1);
            // A cast failure names only classes; any other detail may quote the value.
            if (detail instanceof ClassCastException || !secret) {
                Optional.ofNullable(detail.getMessage()).filter(message -> !message.startsWith(TEMPLATE_START))
                        .ifPresent(message -> reason.append(": ").append(message));
            }
        }
        return reason.toString();
    }

    private static @NotNull List<Throwable> chain(@NotNull Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> chain = new ArrayList<>();
        for (Throwable next = error; next != null && seen.add(next); next = next.getCause()) {
            chain.add(next);
        }
        return chain;
    }

    /** The last match: a quoted value could itself contain the pattern's text. */
    private static @NotNull Optional<Matcher> lastMatch(@NotNull String message) {
        Matcher matcher = FIELD.matcher(message);
        Optional<Matcher> last = Optional.empty();
        while (matcher.find()) {
            Matcher copy = FIELD.matcher(message);
            copy.find(matcher.start());
            last = Optional.of(copy);
        }
        return last;
    }

    /** {@code poolSize} as the file spells it: {@code pool-size}. */
    private static @NotNull String kebab(@NotNull String field) {
        return field.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}
