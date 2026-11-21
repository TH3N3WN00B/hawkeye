package gg.moonrise.hawkeye.util.text;

import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/** Human-readable byte counts for status output: {@code 0 B}, {@code 512 B}, {@code 1.5 KB}, {@code 3.2 GB}. */
public final class ByteSizes {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB", "EB"};

    private ByteSizes() {
    }

    /**
     * Formats a byte count with binary multiples (1 KB = 1024 B) and one decimal above bytes.
     *
     * @param bytes the count, not negative
     * @return the text
     */
    public static @NotNull String format(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        int unit = 0;
        double value = bytes;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }
}
