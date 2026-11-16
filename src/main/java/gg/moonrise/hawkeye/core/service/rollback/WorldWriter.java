package gg.moonrise.hawkeye.core.service.rollback;

import gg.moonrise.hawkeye.util.EntityBytes;
import gg.moonrise.hawkeye.util.ItemBytes;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Makes one planned change in the world. Call only on the thread that owns the change's chunk.
 * Blocks are set without physics, so nothing drops, flows or updates its neighbours mid-rollback.
 * Container items go into the block's inventory when it holds items ({@link BlockInventories}: containers,
 * lecterns, chiseled bookshelves, decorated pots, shelves, jukeboxes), else to the item frames and
 * armour stands there ({@link HeldItems}).
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class WorldWriter {

    private static final int SIGN_LINES = 4;

    private final @NotNull JavaPlugin plugin;

    /** What a change did. */
    public enum Effect {
        /** A block state or sign text was written. */
        BLOCK,
        /** Items were put into or taken out of a block's inventory, an item frame or an armour stand. */
        ITEMS,
        /** An entity was spawned or removed. */
        ENTITY,
        /**
         * Nothing: the block no longer fits the change, its data cannot be read, or the entity to remove
         * is gone or owned by another region.
         */
        NONE
    }

    /**
     * Makes a change.
     *
     * @param world  the world
     * @param change the change
     * @return what it did
     */
    public @NotNull Effect apply(@NotNull World world, @NotNull PlannedChange change) {
        Block block = world.getBlockAt(change.x(), change.y(), change.z());
        return switch (change) {
            case PlannedChange.SetBlock set -> setBlock(block, set);
            case PlannedChange.SetSignText text -> writeSign(block, text.sign()) ? Effect.BLOCK : Effect.NONE;
            case PlannedChange.AdjustItems adjust -> adjustItems(block, adjust) ? Effect.ITEMS : Effect.NONE;
            case PlannedChange.SpawnEntity spawn -> spawn(world, change, spawn.entity()) ? Effect.ENTITY : Effect.NONE;
            case PlannedChange.RemoveEntity remove -> remove(world, remove.entity()) ? Effect.ENTITY : Effect.NONE;
        };
    }

    /**
     * Parses a stored block state.
     *
     * @param state e.g. {@code minecraft:oak_stairs[facing=east]}
     * @return the block data, or empty when this server does not know the block or a property
     */
    public @NotNull Optional<BlockData> blockData(@NotNull String state) {
        try {
            return Optional.of(plugin.getServer().createBlockData(state));
        } catch (IllegalArgumentException e) {
            log.debug("Unknown block state {}", state, e);
            return Optional.empty();
        }
    }

    /**
     * The current block data at some positions of one chunk (to undo a preview).
     *
     * @param world   the world
     * @param changes the changes whose positions to read
     * @return location to the real block data
     */
    public @NotNull Map<Location, BlockData> current(@NotNull World world, @NotNull List<PlannedChange> changes) {
        Map<Location, BlockData> blocks = new LinkedHashMap<>();
        for (PlannedChange change : changes) {
            Block block = world.getBlockAt(change.x(), change.y(), change.z());
            blocks.put(block.getLocation(), block.getBlockData());
        }
        return blocks;
    }

    private @NotNull Effect setBlock(@NotNull Block block, @NotNull PlannedChange.SetBlock set) {
        Optional<BlockData> data = blockData(set.state());
        if (data.isEmpty()) {
            return Effect.NONE;
        }
        block.setBlockData(data.get(), false);
        if (!set.sign().isEmpty()) {
            writeSign(block, set.sign());
        }
        if (!set.contents().isEmpty()) {
            BlockInventories.fill(block, set.contents());
        }
        return Effect.BLOCK;
    }

    private static boolean writeSign(@NotNull Block block, @NotNull SignPatch patch) {
        if (!(block.getState() instanceof Sign sign)) {
            return false;
        }
        if (patch.front()) {
            writeSide(sign.getSide(Side.FRONT), patch.sides().front());
        }
        if (patch.back()) {
            writeSide(sign.getSide(Side.BACK), patch.sides().back());
        }
        sign.update(true, false);
        return true;
    }

    private static void writeSide(@NotNull SignSide side, @NotNull List<String> lines) {
        for (int line = 0; line < SIGN_LINES; line++) {
            side.line(line, line < lines.size() ? component(lines.get(line)) : Component.empty());
        }
    }

    private static @NotNull Component component(@NotNull String json) {
        try {
            return GsonComponentSerializer.gson().deserialize(json);
        } catch (RuntimeException e) {
            return Component.text(json);
        }
    }

    private static boolean adjustItems(@NotNull Block block, @NotNull PlannedChange.AdjustItems adjust) {
        if (adjust.delta() == 0) {
            return false;
        }
        Optional<ItemStack> template = item(adjust);
        if (template.isEmpty()) {
            return false;
        }
        int moved = BlockInventories.adjust(block, template.get(), adjust.delta())
                .orElseGet(() -> HeldItems.adjust(block, template.get(), adjust.delta()));
        return moved > 0;
    }

    private static @NotNull Optional<ItemStack> item(@NotNull PlannedChange.AdjustItems adjust) {
        try {
            if (adjust.item().length > 0) {
                return Optional.of(ItemBytes.deserialize(adjust.item()));
            }
            Material material = Material.matchMaterial(adjust.material());
            return material == null || !material.isItem() ? Optional.empty() : Optional.of(new ItemStack(material));
        } catch (RuntimeException e) {
            log.debug("Could not read item {}", adjust.material(), e);
            return Optional.empty();
        }
    }

    private static boolean spawn(@NotNull World world, @NotNull PlannedChange change, byte @NotNull [] bytes) {
        Entity entity = EntityBytes.deserialize(bytes, world);
        if (!entity.spawnAt(RespawnLocations.of(entity, world, change.x(), change.y(), change.z()))) {
            return false;
        }
        settle(entity);
        return true;
    }

    /**
     * Puts a respawned entity at rest: snapshots from earlier builds still carry the moment of the kill
     * (burning, falling, moving, zero health).
     */
    private static void settle(@NotNull Entity entity) {
        entity.setVelocity(new Vector());
        entity.setFallDistance(0);
        entity.setFireTicks(0);
        if (entity instanceof LivingEntity living && living.getHealth() <= 0) {
            AttributeInstance maxHealth = living.getAttribute(Attribute.MAX_HEALTH);
            living.setHealth(maxHealth == null ? 1 : maxHealth.getValue());
        }
    }

    /** Removes an entity owned by the current region; players are never removed. */
    private boolean remove(@NotNull World world, @NotNull UUID uuid) {
        Entity entity = world.getEntity(uuid);
        if (entity == null || entity instanceof Player || !plugin.getServer().isOwnedByCurrentRegion(entity)) {
            return false;
        }
        entity.remove();
        return true;
    }
}
