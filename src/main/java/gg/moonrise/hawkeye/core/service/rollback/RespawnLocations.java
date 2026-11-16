package gg.moonrise.hawkeye.core.service.rollback;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Painting;
import org.jetbrains.annotations.NotNull;

/**
 * Where a killed or removed entity is spawned again. Its recorded bytes carry its exact position and
 * rotation, which is used while it lies in the recorded block; otherwise the block's centre.
 *
 * <p>{@code Entity#spawnAt} moves a block-attached entity (painting, item frame) to the block that
 * contains the given location and attaches it there. A painting of even width or height is centred on
 * a block edge, counter-clockwise of its facing and above its anchor block, so its centre lies in the
 * neighbouring block; it is spawned at a point inside its anchor block instead.
 */
public final class RespawnLocations {

    private static final double HALF = 0.5;

    private RespawnLocations() {
    }

    /**
     * The location to spawn a deserialized entity at.
     *
     * @param entity the entity, not yet in the world
     * @param world  the world
     * @param x      the recorded block x
     * @param y      the recorded block y
     * @param z      the recorded block z
     * @return the location
     */
    public static @NotNull Location of(@NotNull Entity entity, @NotNull World world, int x, int y, int z) {
        Location own = entity.getLocation();
        if (own.getBlockX() != x || own.getBlockY() != y || own.getBlockZ() != z) {
            return new Location(world, x + HALF, y, z + HALF);
        }
        own.setWorld(world);
        if (entity instanceof Painting painting) {
            return anchor(own, painting.getFacing(), painting.getArt().getBlockWidth(),
                    painting.getArt().getBlockHeight());
        }
        return own;
    }

    /**
     * A point inside a painting's anchor block.
     *
     * @param centre the painting's centre
     * @param facing the direction it faces
     * @param width  its width in blocks
     * @param height its height in blocks
     * @return the point, a new location
     */
    public static @NotNull Location anchor(@NotNull Location centre, @NotNull BlockFace facing, int width,
            int height) {
        double along = width % 2 == 0 ? HALF : 0;
        double up = height % 2 == 0 ? HALF : 0;
        // Counter-clockwise of a horizontal facing (x, z) is (z, -x).
        return centre.clone().subtract(facing.getModZ() * along, up, -facing.getModX() * along);
    }
}
