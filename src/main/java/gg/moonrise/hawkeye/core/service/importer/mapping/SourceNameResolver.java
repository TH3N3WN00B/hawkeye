package gg.moonrise.hawkeye.core.service.importer.mapping;

import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.importer.SourceNames;
import gg.moonrise.hawkeye.entity.importer.SourceRow;
import gg.moonrise.hawkeye.entity.importer.SourceUser;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.jetbrains.annotations.NotNull;

/**
 * Turns the ids of a CoreProtect row into Hawkeye's names, from a snapshot of the source's dictionaries
 * ({@link SourceNames}) that the run replaces when a row names an id the snapshot lacks.
 */
public final class SourceNameResolver {

    /** The actor of a row whose user is unknown or unusable. */
    public static final String UNKNOWN_ACTOR = "#unknown";

    /** The namespace a CoreProtect name without one gets. */
    public static final String MINECRAFT = "minecraft:";

    private final @NotNull BlockStates blockStates;
    private final Map<Integer, Actor> actors = new HashMap<>();
    private @NotNull SourceNames names;

    /**
     * A resolver over a snapshot.
     *
     * @param names       the source's dictionaries
     * @param blockStates the server's block state normalisation
     */
    public SourceNameResolver(@NotNull SourceNames names, @NotNull BlockStates blockStates) {
        this.names = names;
        this.blockStates = blockStates;
    }

    /**
     * Replaces the snapshot (after the run re-read the source's dictionaries).
     *
     * @param names the new snapshot
     */
    public void names(@NotNull SourceNames names) {
        this.names = names;
        actors.clear();
    }

    /**
     * Whether the snapshot knows every id a row names: its user, world, material or entity type and
     * block data tokens.
     *
     * @param row the row
     * @return {@code false} when re-reading the source's dictionaries could resolve more
     */
    public boolean complete(@NotNull SourceRow row) {
        if (!names.users().containsKey(row.user())
                || row.world() != SourceRow.NO_WORLD && !names.worlds().containsKey(row.world())) {
            return false;
        }
        return switch (row) {
            case SourceRow.Block block -> block.action() == BlockRowMapper.KILL && block.type() == 0
                    || (BlockRowMapper.namesEntity(block.action()) ? names.entities() : names.materials())
                    .containsKey(block.type()) && blockDataKnown(block.blockData());
            case SourceRow.Container container -> names.materials().containsKey(container.type());
            case SourceRow.Item item -> names.materials().containsKey(item.type());
            case SourceRow.EntityInteraction interaction -> names.entities().containsKey(interaction.type());
            case SourceRow.Sign sign -> true;
            case SourceRow.Message message -> true;
            case SourceRow.Session session -> true;
        };
    }

    /**
     * The actor of a user rowid: a player when the user has a UUID, otherwise a source keyed by its
     * name ({@code #tnt}); {@value #UNKNOWN_ACTOR} for an unknown or unusable user.
     *
     * @param user the {@code user.rowid}
     * @return the actor
     */
    public @NotNull Actor actor(int user) {
        return actors.computeIfAbsent(user, this::resolveActor);
    }

    /**
     * A world's name.
     *
     * @param world the {@code world.id}
     * @return the name, or empty when unknown
     */
    public @NotNull Optional<String> world(int world) {
        return Optional.ofNullable(names.worlds().get(world)).filter(name -> !name.isBlank());
    }

    /**
     * A material's namespaced name.
     *
     * @param type the {@code material_map.id}
     * @return {@code minecraft:stone}, or empty when unknown
     */
    public @NotNull Optional<String> material(int type) {
        return Optional.ofNullable(names.materials().get(type)).filter(name -> !name.isBlank())
                .map(SourceNameResolver::namespaced);
    }

    /**
     * An entity type's namespaced name.
     *
     * @param type the {@code entity_map.id}
     * @return {@code minecraft:zombie}, or empty when unknown
     */
    public @NotNull Optional<String> entity(int type) {
        return Optional.ofNullable(names.entities().get(type)).filter(name -> !name.isBlank())
                .map(SourceNameResolver::namespaced);
    }

    /**
     * A block state from a material and its block data ids, normalised by the server. Ids the snapshot
     * lacks are dropped.
     *
     * @param material  the namespaced material
     * @param blockData {@code blockdata_map} ids
     * @return {@code material[token,token]}, or the bare material when no token resolves
     */
    public @NotNull String blockState(@NotNull String material, int @NotNull [] blockData) {
        StringJoiner tokens = new StringJoiner(",", "[", "]");
        tokens.setEmptyValue("");
        for (int id : blockData) {
            String token = names.blockData().get(id);
            if (token != null && !token.isBlank()) {
                tokens.add(token);
            }
        }
        return normalize(material + tokens);
    }

    /**
     * A block state as the server writes it.
     *
     * @param state a namespaced block state
     * @return the normalised state
     */
    public @NotNull String normalize(@NotNull String state) {
        return blockStates.normalize(state);
    }

    private boolean blockDataKnown(int @NotNull [] blockData) {
        for (int id : blockData) {
            if (!names.blockData().containsKey(id)) {
                return false;
            }
        }
        return true;
    }

    private @NotNull Actor resolveActor(int user) {
        SourceUser source = names.users().get(user);
        if (source == null || source.name().isBlank()) {
            return Actor.source(UNKNOWN_ACTOR);
        }
        try {
            String name = source.name().trim();
            return source.uuid() != null ? Actor.player(source.uuid(), cut(name, Actor.MAX_LENGTH))
                    : Actor.source(cut(name, name.charAt(0) == Actor.SOURCE_PREFIX ? Actor.MAX_LENGTH
                    : Actor.MAX_LENGTH - 1));
        } catch (IllegalArgumentException e) {
            return Actor.source(UNKNOWN_ACTOR);
        }
    }

    private static @NotNull String cut(@NotNull String name, int length) {
        return name.length() <= length ? name : name.substring(0, length);
    }

    private static @NotNull String namespaced(@NotNull String name) {
        return name.indexOf(':') >= 0 ? name : MINECRAFT + name;
    }
}
