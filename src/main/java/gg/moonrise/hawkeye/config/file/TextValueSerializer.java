package gg.moonrise.hawkeye.config.file;

import de.exlll.configlib.Serializer;
import org.jetbrains.annotations.NotNull;

/**
 * Reads a credential as text whatever scalar YAML made of it: {@code password: 123456} is the text
 * {@code 123456}, not an error. ConfigLib's own string serializer rejects a number with a message that
 * quotes the value, and a credential must never be quoted in an error.
 */
public final class TextValueSerializer implements Serializer<String, Object> {

    @Override
    public @NotNull Object serialize(@NotNull String element) {
        return element;
    }

    @Override
    public @NotNull String deserialize(@NotNull Object element) {
        if (element instanceof String || element instanceof Number || element instanceof Boolean) {
            return element.toString();
        }
        throw new IllegalArgumentException("expected a single text value, not a list or a section");
    }
}
