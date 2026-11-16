package gg.moonrise.hawkeye.core.service.capture;

import gg.moonrise.hawkeye.entity.payload.SignSides;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.jetbrains.annotations.NotNull;

/**
 * Converts sign text to the stored {@link SignSides}: every line of a side as Adventure JSON, so
 * colours and formatting survive a rollback, and a side without any text as no lines at all.
 */
public final class SignText {

    private SignText() {
    }

    /**
     * Both sides of a sign.
     *
     * @param sign the sign state
     * @return its text
     */
    public static @NotNull SignSides sides(@NotNull Sign sign) {
        return new SignSides(lines(sign.getSide(Side.FRONT).lines()), lines(sign.getSide(Side.BACK).lines()));
    }

    /**
     * One side's lines in the stored form.
     *
     * @param lines the lines as shown
     * @return one JSON string per line, or no lines when the side has no text
     */
    public static @NotNull List<String> lines(@NotNull List<Component> lines) {
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        if (lines.stream().allMatch(line -> plain.serialize(line).isEmpty())) {
            return List.of();
        }
        GsonComponentSerializer json = GsonComponentSerializer.gson();
        return lines.stream().map(json::serialize).toList();
    }

    /**
     * The sides with one side replaced.
     *
     * @param sides the current sides
     * @param side  the side to replace
     * @param lines its new lines, in the stored form
     * @return the edited sides
     */
    public static @NotNull SignSides with(@NotNull SignSides sides, @NotNull Side side, @NotNull List<String> lines) {
        return switch (side) {
            case FRONT -> new SignSides(lines, sides.back());
            case BACK -> new SignSides(sides.front(), lines);
        };
    }
}
