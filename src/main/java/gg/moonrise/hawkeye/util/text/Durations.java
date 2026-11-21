package gg.moonrise.hawkeye.util.text;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * Parses and formats compact durations such as {@code 2w3d}, {@code 1.5h} or {@code 90s}. Units:
 * {@code w} weeks, {@code d} days, {@code h} hours, {@code m} minutes, {@code s} seconds. A bare
 * number is seconds.
 */
public final class Durations {

    private Durations() {
    }

    /**
     * Parses a compact duration.
     *
     * @param input the text, e.g. {@code 2d12h}
     * @return the duration, or empty when the text is not a positive duration
     */
    public static @NotNull Optional<Duration> parse(@NotNull String input) {
        String text = input.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal totalSeconds = BigDecimal.ZERO;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c) || c == '.') {
                continue;
            }
            Optional<BigDecimal> part = part(text.substring(start, i), c);
            if (part.isEmpty()) {
                return Optional.empty();
            }
            totalSeconds = totalSeconds.add(part.get());
            start = i + 1;
        }
        if (start < text.length()) {
            Optional<BigDecimal> rest = part(text.substring(start), 's');
            if (rest.isEmpty()) {
                return Optional.empty();
            }
            totalSeconds = totalSeconds.add(rest.get());
        }
        if (totalSeconds.signum() <= 0) {
            return Optional.empty();
        }
        long millis = totalSeconds.movePointRight(3).longValue();
        return millis <= 0 ? Optional.empty() : Optional.of(Duration.ofMillis(millis));
    }

    /**
     * Formats a duration compactly with its two largest units, e.g. {@code 3d4h} or {@code 12m5s}.
     *
     * @param duration the duration
     * @return the text; {@code 0s} for anything under a second
     */
    public static @NotNull String format(@NotNull Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long[] amounts = {seconds / 604_800, seconds / 86_400 % 7, seconds / 3_600 % 24, seconds / 60 % 60,
                seconds % 60};
        char[] units = {'w', 'd', 'h', 'm', 's'};
        StringBuilder out = new StringBuilder();
        int written = 0;
        for (int i = 0; i < amounts.length && written < 2; i++) {
            if (amounts[i] > 0) {
                out.append(amounts[i]).append(units[i]);
                written++;
            } else if (written > 0) {
                break;
            }
        }
        return out.isEmpty() ? "0s" : out.toString();
    }

    private static @NotNull Optional<BigDecimal> part(@NotNull String number, char unit) {
        if (number.isEmpty() || number.chars().filter(c -> c == '.').count() > 1) {
            return Optional.empty();
        }
        long factor = switch (unit) {
            case 'w' -> 604_800;
            case 'd' -> 86_400;
            case 'h' -> 3_600;
            case 'm' -> 60;
            case 's' -> 1;
            default -> -1;
        };
        if (factor < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(number).multiply(BigDecimal.valueOf(factor)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
