package gg.moonrise.hawkeye.core.service.importer.mapping.block;

import gg.moonrise.hawkeye.core.service.importer.mapping.MappedRow;
import gg.moonrise.hawkeye.core.service.importer.mapping.RowBase;
import gg.moonrise.hawkeye.core.service.importer.mapping.SourceNameResolver;
import gg.moonrise.hawkeye.core.service.importer.mapping.sign.SignMemory;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.importer.SkipReason;
import gg.moonrise.hawkeye.entity.importer.source.SourceRow;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * Maps CoreProtect's {@code block} rows: breaks, places and interactions of blocks (and of the hanging
 * and standing entities CoreProtect logs as blocks), kills and entity spawns.
 */
public final class BlockRowMapper {

    /** {@code block.action} of a break. */
    public static final int BREAK = 0;
    /** {@code block.action} of a place. */
    public static final int PLACE = 1;
    /** {@code block.action} of an interaction. */
    public static final int INTERACT = 2;
    /** {@code block.action} of a kill: {@code type} is an {@code entity_map} id, 0 for a player. */
    public static final int KILL = 3;
    /** {@code block.action} of an entity spawn: {@code type} is an {@code entity_map} id. */
    public static final int SPAWN = 13;

    /** What a break leaves behind and a place replaces, as far as CoreProtect tells. */
    public static final String AIR = "minecraft:air";

    /** The subject of a kill whose victim was a player. */
    public static final String PLAYER = "minecraft:player";

    /** Entities CoreProtect logs as {@code block} breaks and places. */
    public static final Set<String> BLOCK_ENTITIES = Set.of("minecraft:painting", "minecraft:item_frame",
            "minecraft:glow_item_frame", "minecraft:armor_stand", "minecraft:end_crystal");

    private final @NotNull SourceNameResolver names;
    private final @NotNull SignMemory signs;

    /**
     * A mapper.
     *
     * @param names the source's names
     * @param signs the sign memory shared with the sign rows
     */
    public BlockRowMapper(@NotNull SourceNameResolver names, @NotNull SignMemory signs) {
        this.names = names;
        this.signs = signs;
    }

    /**
     * Whether a {@code block} action's {@code type} is an {@code entity_map} id rather than a material.
     *
     * @param action the action code
     * @return {@code true} for kills and spawns
     */
    public static boolean namesEntity(int action) {
        return action == KILL || action == SPAWN;
    }

    /**
     * Remembers the state of a sign block, whether or not its row is imported, so later {@code sign}
     * rows at that position know their block.
     *
     * @param row      the row
     * @param position the packed position
     */
    public void observe(@NotNull SourceRow.Block row, long position) {
        if (namesEntity(row.action())) {
            return;
        }
        names.material(row.type()).filter(material -> material.endsWith("_sign"))
                .ifPresent(material -> signs.state(row.world(), position, names.blockState(material,
                        row.blockData())));
    }

    /**
     * Maps a row whose time, world and position are already accepted.
     *
     * @param row  the row
     * @param base what its event shares with every other
     * @return the event, or why there is none
     */
    public @NotNull MappedRow map(@NotNull SourceRow.Block row, @NotNull RowBase base) {
        boolean rolledBack = (row.rolledBack() & 1) != 0;
        return switch (row.action()) {
            case BREAK, PLACE, INTERACT -> names.material(row.type())
                    .map(material -> block(row, base, material, rolledBack))
                    .orElseGet(() -> skip(SkipReason.UNKNOWN_REFERENCE));
            case KILL -> killed(row).map(subject -> base.event(Action.ENTITY_KILL, subject, null, 0, null,
                    rolledBack)).orElseGet(() -> skip(SkipReason.UNKNOWN_REFERENCE));
            case SPAWN -> names.entity(row.type()).map(subject -> base.event(Action.ENTITY_SPAWN, subject, null, 0,
                    row.entity() == null ? null : new Payload.EntityRef(row.entity()), rolledBack))
                    .orElseGet(() -> skip(SkipReason.UNKNOWN_REFERENCE));
            default -> skip(SkipReason.NO_EQUIVALENT);
        };
    }

    private @NotNull MappedRow block(@NotNull SourceRow.Block row, @NotNull RowBase base, @NotNull String material,
            boolean rolledBack) {
        if (BLOCK_ENTITIES.contains(material)) {
            return switch (row.action()) {
                case BREAK -> base.event(Action.ENTITY_REMOVE, material, null, 0, null, rolledBack);
                case PLACE -> base.event(Action.ENTITY_SPAWN, material, null, 0, null, rolledBack);
                default -> skip(SkipReason.NO_EQUIVALENT);
            };
        }
        String state = names.blockState(material, row.blockData());
        return switch (row.action()) {
            case BREAK -> base.event(Action.BLOCK_BREAK, state, AIR, 0, signText(row, base.position()).orElse(null),
                    rolledBack);
            case PLACE -> base.event(Action.BLOCK_PLACE, state, AIR, 0, null, rolledBack);
            default -> base.event(Action.INTERACT, state, state, 0, null, rolledBack);
        };
    }

    /** The text a {@code sign} break row left for this break, as a broken sign's payload. */
    private @NotNull Optional<Payload> signText(@NotNull SourceRow.Block row, long position) {
        return signs.takeBroken(row.world(), position).filter(sides -> !sides.isEmpty())
                .map(sides -> new Payload.Sign(sides, SignSides.EMPTY));
    }

    private @NotNull Optional<String> killed(@NotNull SourceRow.Block row) {
        return row.type() == 0 ? Optional.of(PLAYER) : names.entity(row.type());
    }

    private static @NotNull MappedRow skip(@NotNull SkipReason reason) {
        return new MappedRow.Skipped(reason);
    }
}
