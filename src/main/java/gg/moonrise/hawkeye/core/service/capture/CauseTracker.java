package gg.moonrise.hawkeye.core.service.capture;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.moss.spring.SpringComponent;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.jetbrains.annotations.NotNull;

/**
 * Short-lived memory of who caused what at a block position, for changes the server reports without a
 * cause: a torch falling off the wall a player broke, water flowing from a bucket a player poured, TNT
 * lit by redstone that a player placed, a bed exploding in the nether after a player clicked it.
 * Every map is bounded and expires on its own; a miss simply means the generic source is recorded.
 * Thread-safe (events arrive on every region thread on Folia).
 */
@SpringComponent
public final class CauseTracker {

    /** How long a removed block's neighbours remember who removed it (cascades take a few ticks). */
    public static final Duration NEIGHBOUR_TTL = Duration.ofSeconds(5);

    /** How long a poured liquid and its flow remember who poured it. */
    public static final Duration FLUID_TTL = Duration.ofMinutes(30);

    /** How long a placed TNT block remembers who placed it. */
    public static final Duration TNT_TTL = Duration.ofHours(6);

    /** How long a recorded position is remembered to avoid recording the same change twice. */
    public static final Duration RECORDED_TTL = Duration.ofSeconds(2);

    /** How many blocks above a removed block inherit its cause (sugar cane, cactus, bamboo, scaffolding). */
    public static final int COLUMN = 24;

    private final Cache<Key, Actor> neighbours = Caffeine.newBuilder().maximumSize(200_000)
            .expireAfterWrite(NEIGHBOUR_TTL).build();
    private final Cache<Key, Actor> fluids = Caffeine.newBuilder().maximumSize(200_000)
            .expireAfterWrite(FLUID_TTL).build();
    private final Cache<Key, Actor> tnt = Caffeine.newBuilder().maximumSize(50_000)
            .expireAfterWrite(TNT_TTL).build();
    private final Cache<Key, Boolean> recorded = Caffeine.newBuilder().maximumSize(200_000)
            .expireAfterWrite(RECORDED_TTL).build();

    /**
     * Remembers that an actor changed a block, for the blocks that may fall off because of it: the block
     * itself, its six neighbours and the column above it.
     *
     * @param block the changed block
     * @param actor who changed it
     */
    public void rememberAround(@NotNull Block block, @NotNull Actor actor) {
        put(neighbours, block, actor);
        for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
                BlockFace.DOWN}) {
            put(neighbours, block.getRelative(face), actor);
        }
        for (int up = 1; up <= COLUMN && block.getY() + up <= BlockPositions.MAX_Y; up++) {
            put(neighbours, block.getRelative(0, up, 0), actor);
        }
    }

    /**
     * Who recently changed a block or its support.
     *
     * @param block the block
     * @return the actor, or empty when nobody is remembered
     */
    public @NotNull Optional<Actor> recentCause(@NotNull Block block) {
        return get(neighbours, block);
    }

    /**
     * Remembers who a single block's next change belongs to (a clicked bed, a used spawn egg).
     *
     * @param block the block
     * @param actor the actor
     */
    public void remember(@NotNull Block block, @NotNull Actor actor) {
        put(neighbours, block, actor);
    }

    /**
     * Remembers who poured the liquid at a block.
     *
     * @param block the liquid block
     * @param actor who poured it (or who poured the liquid it flowed from)
     */
    public void rememberFluid(@NotNull Block block, @NotNull Actor actor) {
        put(fluids, block, actor);
    }

    /**
     * Who poured the liquid at a block.
     *
     * @param block the liquid block
     * @return the actor, or empty when the liquid is natural or forgotten
     */
    public @NotNull Optional<Actor> fluidCause(@NotNull Block block) {
        return get(fluids, block);
    }

    /**
     * Remembers who placed a TNT block.
     *
     * @param block the TNT block
     * @param actor who placed it
     */
    public void rememberTnt(@NotNull Block block, @NotNull Actor actor) {
        put(tnt, block, actor);
    }

    /**
     * Who placed a TNT block, forgetting it (the block becomes primed TNT once).
     *
     * @param block the TNT block
     * @return the actor, or empty when unknown
     */
    public @NotNull Optional<Actor> takeTnt(@NotNull Block block) {
        Key key = key(block);
        Actor actor = tnt.getIfPresent(key);
        tnt.invalidate(key);
        return Optional.ofNullable(actor);
    }

    /**
     * Marks a block position as recorded just now.
     *
     * @param world the world
     * @param x     block x
     * @param y     block y
     * @param z     block z
     */
    public void markRecorded(@NotNull World world, int x, int y, int z) {
        recorded.put(new Key(world.getUID(), BlockPositions.pack(x, y, z)), Boolean.TRUE);
    }

    /**
     * Whether a change at a block was recorded in the last {@link #RECORDED_TTL}, so a second event for
     * the same change (a piston break the server also reports as a destroyed block) is skipped.
     *
     * @param block the block
     * @return {@code true} when recently recorded
     */
    public boolean recentlyRecorded(@NotNull Block block) {
        return recorded.getIfPresent(key(block)) != null;
    }

    private static void put(@NotNull Cache<Key, Actor> cache, @NotNull Block block, @NotNull Actor actor) {
        if (inRange(block)) {
            cache.put(key(block), actor);
        }
    }

    private static @NotNull Optional<Actor> get(@NotNull Cache<Key, Actor> cache, @NotNull Block block) {
        return inRange(block) ? Optional.ofNullable(cache.getIfPresent(key(block))) : Optional.empty();
    }

    private static boolean inRange(@NotNull Block block) {
        return block.getY() >= BlockPositions.MIN_Y && block.getY() <= BlockPositions.MAX_Y
                && block.getX() >= BlockPositions.MIN_HORIZONTAL && block.getX() <= BlockPositions.MAX_HORIZONTAL
                && block.getZ() >= BlockPositions.MIN_HORIZONTAL && block.getZ() <= BlockPositions.MAX_HORIZONTAL;
    }

    private static @NotNull Key key(@NotNull Block block) {
        return new Key(block.getWorld().getUID(), BlockPositions.pack(block.getX(), block.getY(), block.getZ()));
    }

    private record Key(@NotNull UUID world, long position) {
    }
}
