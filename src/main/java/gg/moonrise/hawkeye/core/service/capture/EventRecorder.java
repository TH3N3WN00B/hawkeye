package gg.moonrise.hawkeye.core.service.capture;

import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.config.file.LoggingSettings;
import gg.moonrise.hawkeye.core.service.capture.container.InventoryDiff;
import gg.moonrise.hawkeye.core.service.capture.container.PlayerContents;
import gg.moonrise.hawkeye.core.queue.event.EventQueue;
import gg.moonrise.hawkeye.core.time.EventClock;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.action.ActionFamily;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import gg.moonrise.hawkeye.util.serialize.EntityBytes;
import gg.moonrise.hawkeye.util.serialize.ItemBytes;
import gg.moonrise.hawkeye.util.codec.PayloadCodec;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The one entry point listeners record through. Every method runs on the event's own thread, does
 * only cheap in-memory work (state strings, item and entity NBT) and hands the event to the
 * lock-free queue; nothing here touches the database or blocks. The {@code enabled} checks let a
 * listener skip building an event that would be thrown away.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class EventRecorder {

    /** The block state left behind when a block is removed. */
    public static final String AIR = "minecraft:air";

    private final @NotNull EventClock clock;
    private final @NotNull EventQueue queue;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull CauseTracker causes;
    private final @NotNull EntityBytes entityBytes;
    private final LongAdder skipped = new LongAdder();

    /**
     * Whether a category is recorded in a world.
     *
     * @param category the category
     * @param world    the world
     * @return {@code true} when the category is on and the world is not excluded
     */
    public boolean enabled(@NotNull LogCategory category, @NotNull World world) {
        LoggingSettings logging = configProvider.get().logging();
        return logging.enabled(category) && !logging.worldDisabled(world.getName());
    }

    /**
     * Records a block change with explicit states.
     *
     * @param action    a block-family action
     * @param actor     who or what did it
     * @param block     where
     * @param subject   the subject state (broken block for a break, placed block for a place)
     * @param secondary the other side (what a break left, what a place replaced)
     * @param payload   optional extra data (container contents, sign text)
     */
    public void block(@NotNull Action action, @NotNull Actor actor, @NotNull Block block, @NotNull BlockData subject,
            @NotNull BlockData secondary, @Nullable Payload payload) {
        requireFamily(action, ActionFamily.BLOCK);
        record(action, actor, block.getWorld(), block.getX(), block.getY(), block.getZ(), subject.getAsString(),
                secondary.getAsString(), 0, payload);
    }

    /**
     * Records a block removed by an actor: the subject is the block's state before, the secondary
     * what it became.
     *
     * @param actor  who or what removed it
     * @param before the block's state before the removal
     * @param after  the state left behind (air, or water for a waterlogged block)
     * @param payload optional contents (container items, sign text) for a rollback to restore
     */
    public void blockBreak(@NotNull Actor actor, @NotNull BlockState before, @NotNull BlockData after,
            @Nullable Payload payload) {
        record(Action.BLOCK_BREAK, actor, before.getWorld(), before.getX(), before.getY(), before.getZ(),
                before.getBlockData().getAsString(), after.getAsString(), 0, payload);
    }

    /**
     * Records a block placed by an actor: the subject is the placed state, the secondary what was
     * there before.
     *
     * @param actor    who or what placed it
     * @param replaced the block's state before the placement
     * @param placed   the placed state
     */
    public void blockPlace(@NotNull Actor actor, @NotNull BlockState replaced, @NotNull BlockData placed) {
        record(Action.BLOCK_PLACE, actor, replaced.getWorld(), replaced.getX(), replaced.getY(), replaced.getZ(),
                placed.getAsString(), replaced.getBlockData().getAsString(), 0, null);
    }

    /**
     * Records an item moved into or out of a container.
     *
     * @param action    {@link Action#CONTAINER_DEPOSIT} or {@link Action#CONTAINER_WITHDRAW}
     * @param actor     who moved it
     * @param container the container's location
     * @param item      the item (its amount is ignored)
     * @param amount    how many moved
     */
    public void container(@NotNull Action action, @NotNull Actor actor, @NotNull Location container,
            @NotNull ItemStack item, int amount) {
        if (action != Action.CONTAINER_DEPOSIT && action != Action.CONTAINER_WITHDRAW) {
            throw new IllegalArgumentException(action + " is not a container block action");
        }
        recordItem(action, actor, container, item, amount);
    }

    /**
     * Records the net changes of a container's contents: a deposit for every kind of item there is more
     * of, a withdrawal for every kind there is less of.
     *
     * @param actor     who changed the contents
     * @param container the container's location
     * @param deltas    the changes, as {@link InventoryDiff#diff} returns them
     */
    public void containerChanges(@NotNull Actor actor, @NotNull Location container,
            @NotNull List<InventoryDiff.Delta> deltas) {
        for (InventoryDiff.Delta delta : deltas) {
            Action action = delta.amount() > 0 ? Action.CONTAINER_DEPOSIT : Action.CONTAINER_WITHDRAW;
            container(action, actor, container, delta.item(), Math.abs(delta.amount()));
        }
    }

    /**
     * Records an item moved into or out of a virtual container (an ender chest, a plugin's inventory,
     * another player's inventory), where the player stands. The payload names the inventory and holds the
     * item's NBT when the item has components (empty for a plain stack).
     *
     * @param action    {@link Action#VIRTUAL_DEPOSIT} or {@link Action#VIRTUAL_WITHDRAW}
     * @param actor     who moved it
     * @param at        where they stood
     * @param container the inventory's label
     * @param item      the item (its amount is ignored)
     * @param amount    how many moved
     */
    public void virtual(@NotNull Action action, @NotNull Actor actor, @NotNull Location at,
            @NotNull String container, @NotNull ItemStack item, int amount) {
        if (action != Action.VIRTUAL_DEPOSIT && action != Action.VIRTUAL_WITHDRAW) {
            throw new IllegalArgumentException(action + " is not a virtual container action");
        }
        if (item.isEmpty() || amount <= 0) {
            return;
        }
        byte[] bytes = item.hasItemMeta() ? ItemBytes.serialize(item.asOne()) : new byte[0];
        record(action, actor, at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                item.getType().getKey().toString(), null, amount, new Payload.VirtualItem(container, bytes));
    }

    /**
     * Records a viewer's moves in a virtual container: a deposit for every positive delta, a withdrawal
     * for every negative one.
     *
     * @param actor     who moved the items
     * @param at        where they stood
     * @param container the inventory's label
     * @param moves     the moves, as {@code VirtualTransfers#match} returns them
     */
    public void virtualChanges(@NotNull Actor actor, @NotNull Location at, @NotNull String container,
            @NotNull List<InventoryDiff.Delta> moves) {
        for (InventoryDiff.Delta move : moves) {
            Action action = move.amount() > 0 ? Action.VIRTUAL_DEPOSIT : Action.VIRTUAL_WITHDRAW;
            virtual(action, actor, at, container, move.item(), Math.abs(move.amount()));
        }
    }

    /**
     * Records an item dropped or picked up.
     *
     * @param action   {@link Action#ITEM_DROP} or {@link Action#ITEM_PICKUP}
     * @param actor    who did it
     * @param location where
     * @param item     the item stack
     */
    public void item(@NotNull Action action, @NotNull Actor actor, @NotNull Location location, @NotNull ItemStack item) {
        requireFamily(action, ActionFamily.ITEM);
        recordItem(action, actor, location, item, item.getAmount());
    }

    /**
     * Records a kill. With {@code rollback.restore-entities} on, the entity is serialized so a rollback
     * can respawn it.
     *
     * @param killer who or what killed it
     * @param victim the entity, still in the world (call from the death event)
     */
    public void kill(@NotNull Actor killer, @NotNull Entity victim) {
        Payload payload = null;
        if (configProvider.get().rollback().restoreEntities() && !(victim instanceof Player)) {
            try {
                payload = new Payload.Entity(entityBytes.serialize(victim));
            } catch (RuntimeException e) {
                log.debug("Could not serialize a killed {}; it is logged without rollback data", victim.getType(), e);
            }
        }
        Location location = victim.getLocation();
        record(Action.ENTITY_KILL, killer, location.getWorld(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ(), victim.getType().getKey().toString(), null, 0, payload);
    }

    /**
     * Records an entity placed or spawned by an actor, with a reference so a rollback can remove it.
     *
     * @param actor  who placed or spawned it
     * @param entity the entity, already in the world or about to be added
     */
    public void entitySpawn(@NotNull Actor actor, @NotNull Entity entity) {
        entity(Action.ENTITY_SPAWN, actor, entity, new Payload.EntityRef(entity.getUniqueId()));
    }

    /**
     * Records a non-living entity destroyed or taken (painting, item frame, vehicle, a mob caught in a
     * bucket). With {@code rollback.restore-entities} on, the entity is serialized so a rollback can put
     * it back.
     *
     * @param actor  who destroyed or took it
     * @param entity the entity, still in the world
     */
    public void entityRemove(@NotNull Actor actor, @NotNull Entity entity) {
        Payload payload = null;
        if (configProvider.get().rollback().restoreEntities()) {
            try {
                payload = new Payload.Entity(entityBytes.serialize(entity));
            } catch (RuntimeException e) {
                log.debug("Could not serialize a removed {}; it is logged without rollback data", entity.getType(),
                        e);
            }
        }
        entity(Action.ENTITY_REMOVE, actor, entity, payload);
    }

    /**
     * Records an entity-family action at an entity's position.
     *
     * @param action  an entity-family action
     * @param actor   who did it
     * @param entity  the entity
     * @param payload optional extra data (a new name, a colour, an entity reference)
     */
    public void entity(@NotNull Action action, @NotNull Actor actor, @NotNull Entity entity,
            @Nullable Payload payload) {
        requireFamily(action, ActionFamily.ENTITY);
        Location location = entity.getLocation();
        record(action, actor, location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                entity.getType().getKey().toString(), null, 0, payload);
    }

    /**
     * Records a chat message or command.
     *
     * @param action   {@link Action#CHAT} or {@link Action#COMMAND}
     * @param actor    who sent it
     * @param location where they stood
     * @param text     the message or command line
     */
    public void message(@NotNull Action action, @NotNull Actor actor, @NotNull Location location,
            @NotNull String text) {
        requireFamily(action, ActionFamily.MESSAGE);
        record(action, actor, location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                "", null, 0, new Payload.Text(text));
    }

    /**
     * Records a join or quit.
     *
     * @param action   {@link Action#JOIN} or {@link Action#QUIT}
     * @param actor    the player
     * @param location where they were
     */
    public void session(@NotNull Action action, @NotNull Actor actor, @NotNull Location location) {
        requireFamily(action, ActionFamily.SESSION);
        record(action, actor, location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                "", null, 0, null);
    }

    /**
     * Records a copy of a player's whole inventory (armour, off hand and the cursor stack included).
     * An empty inventory is recorded too, so the moment is kept.
     *
     * @param action a snapshot-family action
     * @param player the player, whose inventory is read now
     * @param at     where the moment happened (for a teleport, where the player left from)
     * @param detail what the moment was (where a teleport went, the death message), or empty
     */
    public void snapshot(@NotNull Action action, @NotNull Player player, @NotNull Location at,
            @NotNull String detail) {
        requireFamily(action, ActionFamily.SNAPSHOT);
        PlayerContents contents = PlayerContents.of(player);
        record(action, Actors.of(player), at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), "", null,
                contents.amount(), new Payload.Snapshot(detail, contents.slots()));
    }

    /**
     * Records an event from its parts; every other method ends here.
     *
     * @param action    what happened
     * @param actor     who or what did it
     * @param world     the world
     * @param x         block x
     * @param y         block y
     * @param z         block z
     * @param subject   the subject value, or empty
     * @param secondary the secondary block state, or {@code null}
     * @param amount    the item count, or 0
     * @param payload   optional extra data
     */
    public void record(@NotNull Action action, @NotNull Actor actor, @Nullable World world, int x, int y, int z,
            @NotNull String subject, @Nullable String secondary, int amount, @Nullable Payload payload) {
        if (world == null || !BlockPositions.contains(x, y, z)) {
            skipped.increment();
            return;
        }
        if (configProvider.get().logging().worldDisabled(world.getName())) {
            return;
        }
        if (action.family() == ActionFamily.BLOCK) {
            // Lets a second report of the same change (a piston break the server also reports as a
            // destroyed block) be recognised and skipped.
            causes.markRecorded(world, x, y, z);
        }
        byte[] data = payload == null ? null : PayloadCodec.encode(payload);
        queue.offer(new PendingEvent(clock.next(), world.getName(), BlockPositions.pack(x, y, z), actor, action,
                subject, secondary, amount, data));
    }

    /**
     * Events refused because they had no world or lay outside the packable range.
     *
     * @return the count since start
     */
    public long skipped() {
        return skipped.sum();
    }

    private void recordItem(@NotNull Action action, @NotNull Actor actor, @NotNull Location location,
            @NotNull ItemStack item, int amount) {
        if (item.isEmpty() || amount <= 0) {
            return;
        }
        Payload payload = item.hasItemMeta() ? new Payload.Item(ItemBytes.serialize(item.asOne())) : null;
        record(action, actor, location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                item.getType().getKey().toString(), null, amount, payload);
    }

    private static void requireFamily(@NotNull Action action, @NotNull ActionFamily family) {
        if (action.family() != family) {
            throw new IllegalArgumentException(action + " is not a " + family + " action");
        }
    }
}
