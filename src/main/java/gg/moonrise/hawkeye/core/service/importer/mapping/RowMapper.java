package gg.moonrise.hawkeye.core.service.importer.mapping;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.importer.SkipReason;
import gg.moonrise.hawkeye.entity.importer.SourceNames;
import gg.moonrise.hawkeye.entity.importer.SourceRow;
import gg.moonrise.hawkeye.entity.importer.SourceTable;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import java.util.Optional;
import java.util.OptionalLong;
import org.jetbrains.annotations.NotNull;

/**
 * Turns CoreProtect rows into Hawkeye events (docs/architecture.md, "Imports"). Every event is stamped at
 * its row's second ({@code EventIds.stamp(time * 1000, 0)}): the import's id assigner breaks ties by the
 * order events are added, which is the source order. A row is skipped, never an error, when it is older
 * than Hawkeye's epoch or later than the run's horizon ({@link SkipReason#BEFORE_EPOCH}; the run only
 * hands over such a row when it is stamped too far ahead to be a row CoreProtect just logged), when
 * its world, material, entity or position cannot be resolved or stored
 * ({@link SkipReason#UNKNOWN_REFERENCE}), when Hawkeye has no event for its action
 * ({@link SkipReason#NO_EQUIVALENT}) or when another event carries it ({@link SkipReason#FOLDED}).
 *
 * <p>Holds sign memory between rows: one mapper per run, used by one thread, fed in source order.
 */
public final class RowMapper {

    /** {@code session.action} of a login. */
    public static final int LOGIN = 1;
    /** {@code session.action} of a logout. */
    public static final int LOGOUT = 0;

    private final @NotNull SourceNameResolver names;
    private final @NotNull BlockRowMapper blocks;
    private final @NotNull SignRowMapper signs;
    private final @NotNull ItemRowMapper items;
    private final long nowMillis;

    /**
     * A mapper for one run.
     *
     * @param names       the source's dictionaries
     * @param blockStates the server's block state normalisation
     * @param itemDecoder the item metadata decoder
     * @param nowMillis   the last millisecond the run imports (its horizon): later rows are skipped
     */
    public RowMapper(@NotNull SourceNames names, @NotNull BlockStates blockStates, @NotNull ItemDecoder itemDecoder,
            long nowMillis) {
        this.names = new SourceNameResolver(names, blockStates);
        SignMemory memory = new SignMemory();
        this.blocks = new BlockRowMapper(this.names, memory);
        this.signs = new SignRowMapper(this.names, memory);
        this.items = new ItemRowMapper(this.names, itemDecoder);
        this.nowMillis = nowMillis;
    }

    /**
     * Replaces the dictionaries (after the run re-read them).
     *
     * @param names the new snapshot
     */
    public void names(@NotNull SourceNames names) {
        this.names.names(names);
    }

    /**
     * Whether the dictionaries know every id a row names.
     *
     * @param row the row
     * @return {@code false} when re-reading them could resolve more
     */
    public boolean knows(@NotNull SourceRow row) {
        return names.complete(row);
    }

    /**
     * Maps one row.
     *
     * @param row the next row in source order
     * @return its event, or why there is none
     */
    public @NotNull MappedRow map(@NotNull SourceRow row) {
        OptionalLong position = position(row);
        if (row instanceof SourceRow.Block block && position.isPresent()) {
            blocks.observe(block, position.getAsLong());
        }
        long millis = row.time() * 1000L;
        if (millis < EventIds.EPOCH_MILLIS || millis > nowMillis) {
            return new MappedRow.Skipped(SkipReason.BEFORE_EPOCH);
        }
        Optional<String> world = names.world(row.world());
        if (world.isEmpty() || position.isEmpty()) {
            return new MappedRow.Skipped(SkipReason.UNKNOWN_REFERENCE);
        }
        RowBase base = new RowBase(EventIds.stamp(millis, 0), world.get(), position.getAsLong(),
                names.actor(row.user()));
        return switch (row) {
            case SourceRow.Block block -> blocks.map(block, base);
            case SourceRow.Sign sign -> signs.map(sign, base);
            case SourceRow.Container container -> items.map(container, base);
            case SourceRow.Item item -> items.map(item, base);
            case SourceRow.Message message -> base.event(message.table() == SourceTable.COMMAND ? Action.COMMAND
                    : Action.CHAT, "", null, 0, new Payload.Text(message.message()), false);
            case SourceRow.Session session -> session(session, base);
            case SourceRow.EntityInteraction interaction -> interaction(interaction, base);
        };
    }

    private static @NotNull MappedRow session(@NotNull SourceRow.Session row, @NotNull RowBase base) {
        return switch (row.action()) {
            case LOGIN -> base.event(Action.JOIN, "", null, 0, null, false);
            case LOGOUT -> base.event(Action.QUIT, "", null, 0, null, false);
            default -> new MappedRow.Skipped(SkipReason.NO_EQUIVALENT);
        };
    }

    private @NotNull MappedRow interaction(@NotNull SourceRow.EntityInteraction row, @NotNull RowBase base) {
        Optional<Action> action = switch (row.action()) {
            case 1 -> Optional.of(Action.ENTITY_SHEAR);
            case 2 -> Optional.of(Action.ENTITY_LEASH);
            case 3 -> Optional.of(Action.ENTITY_UNLEASH);
            default -> Optional.empty();
        };
        if (action.isEmpty()) {
            return new MappedRow.Skipped(SkipReason.NO_EQUIVALENT);
        }
        return names.entity(row.type()).map(entity -> base.event(action.get(), entity, null, 0, null, false))
                .orElseGet(() -> new MappedRow.Skipped(SkipReason.UNKNOWN_REFERENCE));
    }

    private static @NotNull OptionalLong position(@NotNull SourceRow row) {
        if (row.x() < BlockPositions.MIN_HORIZONTAL || row.x() > BlockPositions.MAX_HORIZONTAL
                || row.z() < BlockPositions.MIN_HORIZONTAL || row.z() > BlockPositions.MAX_HORIZONTAL
                || row.y() < BlockPositions.MIN_Y || row.y() > BlockPositions.MAX_Y) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(BlockPositions.pack(row.x(), row.y(), row.z()));
    }
}
