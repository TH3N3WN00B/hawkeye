package gg.moonrise.hawkeye.core.service.query;

import java.util.Locale;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/** A {@code #flag} in a parameter list. */
public enum QueryFlag {

    /** {@code #preview}: show a rollback or restore to the sender only, then apply or cancel. */
    PREVIEW,
    /** {@code #count}: report how many events match instead of listing them. */
    COUNT;

    /**
     * The token that sets this flag.
     *
     * @return e.g. {@code #preview}
     */
    public @NotNull String token() {
        return "#" + name().toLowerCase(Locale.ROOT);
    }

    /**
     * The flag a token sets.
     *
     * @param token e.g. {@code #preview}, any case
     * @return the flag, or empty when the token is not a flag
     */
    public static @NotNull Optional<QueryFlag> of(@NotNull String token) {
        for (QueryFlag flag : values()) {
            if (flag.token().equalsIgnoreCase(token)) {
                return Optional.of(flag);
            }
        }
        return Optional.empty();
    }
}
