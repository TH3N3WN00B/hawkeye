package gg.moonrise.hawkeye.core.service.importer.mapping;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.importer.SkipReason;
import gg.moonrise.hawkeye.entity.importer.SourceRow;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import org.jetbrains.annotations.NotNull;

/**
 * Maps CoreProtect's {@code sign} rows. A place or edit becomes a {@link Action#SIGN_EDIT} with the
 * text before (the "before" snapshot CoreProtect wrote a second earlier, if any) and after; the
 * snapshot itself and the text of a broken sign are folded into the events they belong to.
 */
public final class SignRowMapper {

    /** {@code sign.action} of a break, written five seconds before its {@code block} break. */
    public static final int BREAK = 0;
    /** {@code sign.action} of a placed sign's text. */
    public static final int PLACE = 1;
    /** {@code sign.action} of an edit. */
    public static final int EDIT = 2;
    /** {@code sign.action} of the snapshot before an edit, written a second before it. */
    public static final int BEFORE = 3;

    /** The block of a sign row at a position where no sign block was seen. */
    public static final String DEFAULT_SIGN = "minecraft:oak_sign";

    private final @NotNull SourceNameResolver names;
    private final @NotNull SignMemory signs;

    /**
     * A mapper.
     *
     * @param names the source's names
     * @param signs the sign memory shared with the block rows
     */
    public SignRowMapper(@NotNull SourceNameResolver names, @NotNull SignMemory signs) {
        this.names = names;
        this.signs = signs;
    }

    /**
     * Maps a row whose time, world and position are already accepted.
     *
     * @param row  the row
     * @param base what its event shares with every other
     * @return the event, or why there is none
     */
    public @NotNull MappedRow map(@NotNull SourceRow.Sign row, @NotNull RowBase base) {
        SignSides text = LegacySignText.sides(row.lines());
        return switch (row.action()) {
            case BEFORE -> {
                signs.before(row.world(), base.position(), text);
                yield new MappedRow.Skipped(SkipReason.FOLDED);
            }
            case BREAK -> {
                signs.broken(row.world(), base.position(), text);
                yield new MappedRow.Skipped(SkipReason.FOLDED);
            }
            case PLACE, EDIT -> {
                SignSides before = signs.takeBefore(row.world(), base.position()).orElse(SignSides.EMPTY);
                String state = signs.state(row.world(), base.position())
                        .orElseGet(() -> names.normalize(DEFAULT_SIGN));
                yield base.event(Action.SIGN_EDIT, state, state, 0, new Payload.Sign(before, text),
                        row.rolledBack() == 1);
            }
            default -> new MappedRow.Skipped(SkipReason.NO_EQUIVALENT);
        };
    }
}
