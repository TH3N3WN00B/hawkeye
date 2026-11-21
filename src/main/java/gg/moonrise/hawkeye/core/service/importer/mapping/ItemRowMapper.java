package gg.moonrise.hawkeye.core.service.importer.mapping;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.importer.SkipReason;
import gg.moonrise.hawkeye.entity.importer.SourceRow;
import gg.moonrise.hawkeye.entity.payload.Payload;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Maps CoreProtect's {@code container}, {@code entity_container} and {@code item} rows: items moved into
 * and out of containers, dropped, picked up, thrown, broken, crafted and traded. The item's components
 * come along when its metadata blob decodes ({@link ItemDecoder}); the material and count always do.
 */
public final class ItemRowMapper {

    /** {@code container.action} of items taken out. */
    public static final int REMOVED = 0;
    /** {@code container.action} of items put in. */
    public static final int ADDED = 1;

    private final @NotNull SourceNameResolver names;
    private final @NotNull ItemDecoder items;

    /**
     * A mapper.
     *
     * @param names the source's names
     * @param items the item metadata decoder
     */
    public ItemRowMapper(@NotNull SourceNameResolver names, @NotNull ItemDecoder items) {
        this.names = names;
        this.items = items;
    }

    /**
     * The Hawkeye action of an {@code item} row.
     *
     * @param action {@code item.action}
     * @return the action, or empty for one Hawkeye has no event for (9 ingredients used, 11 given to a
     *         villager, unknown codes)
     */
    public static @NotNull Optional<Action> itemAction(int action) {
        return Optional.ofNullable(switch (action) {
            case 2 -> Action.ITEM_DROP;
            case 3 -> Action.ITEM_PICKUP;
            case 4 -> Action.CONTAINER_WITHDRAW;
            case 5 -> Action.CONTAINER_DEPOSIT;
            case 6, 7 -> Action.ITEM_THROW;
            case 8 -> Action.ITEM_BREAK;
            case 10 -> Action.ITEM_CRAFT;
            case 12 -> Action.ITEM_TRADE;
            default -> null;
        });
    }

    /**
     * Maps a container row whose time, world and position are already accepted.
     *
     * @param row  the row
     * @param base what its event shares with every other
     * @return the event, or why there is none
     */
    public @NotNull MappedRow map(@NotNull SourceRow.Container row, @NotNull RowBase base) {
        Optional<Action> action = switch (row.action()) {
            case REMOVED -> Optional.of(Action.CONTAINER_WITHDRAW);
            case ADDED -> Optional.of(Action.CONTAINER_DEPOSIT);
            default -> Optional.empty();
        };
        boolean rolledBack = (row.rolledBack() & 1) != 0;
        return action.map(known -> item(base, known, row.type(), row.amount(), row.metadata(), rolledBack))
                .orElseGet(ItemRowMapper::noEquivalent);
    }

    /**
     * Maps an item row whose time, world and position are already accepted.
     *
     * @param row  the row
     * @param base what its event shares with every other
     * @return the event, or why there is none
     */
    public @NotNull MappedRow map(@NotNull SourceRow.Item row, @NotNull RowBase base) {
        return itemAction(row.action())
                .map(known -> item(base, known, row.type(), row.amount(), row.metadata(), false))
                .orElseGet(ItemRowMapper::noEquivalent);
    }

    private @NotNull MappedRow item(@NotNull RowBase base, @NotNull Action action, int type, int amount,
            byte @Nullable [] metadata, boolean rolledBack) {
        Optional<String> material = names.material(type);
        if (material.isEmpty()) {
            return new MappedRow.Skipped(SkipReason.UNKNOWN_REFERENCE);
        }
        Payload payload = metadata == null || metadata.length == 0 ? null
                : items.decode(material.get(), metadata).map(Payload.Item::new).orElse(null);
        return base.event(action, material.get(), null, Math.max(0, amount), payload, rolledBack);
    }

    private static @NotNull MappedRow noEquivalent() {
        return new MappedRow.Skipped(SkipReason.NO_EQUIVALENT);
    }
}
