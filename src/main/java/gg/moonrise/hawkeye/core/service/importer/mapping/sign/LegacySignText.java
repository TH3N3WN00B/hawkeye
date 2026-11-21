package gg.moonrise.hawkeye.core.service.importer.mapping.sign;

import gg.moonrise.hawkeye.core.service.capture.text.SignText;
import gg.moonrise.hawkeye.entity.importer.source.SourceRow;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;

/**
 * Converts CoreProtect's sign lines (legacy text with {@code §} codes, front lines 1-4 and back lines
 * 5-8) to the stored {@link SignSides}: each line as Adventure JSON, a side without any text as no
 * lines, exactly as {@link SignText} stores captured signs.
 */
public final class LegacySignText {

    /** Lines per side. */
    public static final int SIDE_LINES = 4;

    private LegacySignText() {
    }

    /**
     * Both sides of a sign row.
     *
     * @param lines the row's eight lines ({@link SourceRow.Sign#lines()})
     * @return the sides
     */
    public static @NotNull SignSides sides(@NotNull List<String> lines) {
        return new SignSides(side(lines.subList(0, SIDE_LINES)),
                side(lines.subList(SIDE_LINES, Math.min(lines.size(), 2 * SIDE_LINES))));
    }

    /**
     * One side in the stored form.
     *
     * @param lines the side's legacy lines
     * @return one JSON string per line, or no lines when the side has no text
     */
    public static @NotNull List<String> side(@NotNull List<String> lines) {
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
        return SignText.lines(lines.stream().<Component>map(legacy::deserialize).toList());
    }
}
