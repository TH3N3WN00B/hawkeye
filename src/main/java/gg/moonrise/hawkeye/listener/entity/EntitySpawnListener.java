package gg.moonrise.hawkeye.listener.entity;

import com.destroystokyo.paper.MaterialTags;
import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Entities placed or spawned by an actor ({@link LogCategory#ENTITIES}), recorded as
 * {@link gg.moonrise.hawkeye.entity.action.Action#ENTITY_SPAWN} with the entity's UUID so a rollback
 * removes it: paintings and item frames ({@link HangingPlaceEvent}), armour stands, boats, minecarts and
 * end crystals ({@link EntityPlaceEvent}), mobs from spawn eggs (blamed on the player who just used an
 * egg at the spawn position, else {@code #spawn_egg}), from dispensed eggs ({@link Actors#DISPENSER}) and
 * released from buckets (the player who emptied the bucket there, else a dispenser). Leash knots are left
 * to the leash interactions: a knot only exists while a mob is tied to it.
 */
@SpringComponent
@RequiredArgsConstructor
public final class EntitySpawnListener implements Listener {

    /** A spawn egg whose user is not known (used on water, or by another plugin). */
    public static final Actor SPAWN_EGG = Actor.source("spawn_egg");

    /** Buckets that pour a fluid only; every other emptied bucket releases a mob. */
    private static final Set<Material> FLUID_BUCKETS = EnumSet.of(Material.BUCKET, Material.WATER_BUCKET,
            Material.LAVA_BUCKET, Material.POWDER_SNOW_BUCKET, Material.MILK_BUCKET);

    /**
     * A mob released from a bucket. Paper names the reason from 1.21.4 (before, such a mob spawns as from a
     * spawn egg), so it is looked up by name: the set is empty on older servers.
     */
    private static final Set<SpawnReason> BUCKET = reasons("BUCKET");

    /** The spawns recorded: everything else (natural, spawners, breeding, built golems) is not, or not here. */
    private static final Set<SpawnReason> RECORDED_REASONS = reasons("SPAWNER_EGG", "DISPENSE_EGG", "BUCKET");

    private final @NotNull EventRecorder recorder;
    private final @NotNull CauseTracker causes;

    /**
     * Records a painting or item frame a player hung up.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(@NotNull HangingPlaceEvent event) {
        placed(event.getPlayer(), event.getEntity());
    }

    /**
     * Records an armour stand, boat, minecart or end crystal a player placed.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(@NotNull EntityPlaceEvent event) {
        placed(event.getPlayer(), event.getEntity());
    }

    /**
     * Remembers who used a spawn egg on a block, for the mob that spawns in the clicked block or the one
     * in front of it.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEggUse(@NotNull PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (clicked == null || event.getAction() != Action.RIGHT_CLICK_BLOCK || !isSpawnEgg(event.getItem())
                || event.useItemInHand() == Event.Result.DENY
                || !recorder.enabled(LogCategory.ENTITIES, clicked.getWorld())) {
            return;
        }
        Actor actor = Actors.of(event.getPlayer());
        causes.remember(clicked, actor);
        causes.remember(clicked.getRelative(event.getBlockFace()), actor);
    }

    /**
     * Remembers who used a spawn egg on a mob, for the baby that spawns at it.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEggUseOnEntity(@NotNull PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        Entity clicked = event.getRightClicked();
        if (isSpawnEgg(player.getInventory().getItem(event.getHand()))
                && recorder.enabled(LogCategory.ENTITIES, clicked.getWorld())) {
            causes.remember(clicked.getLocation().getBlock(), Actors.of(player));
        }
    }

    /**
     * Remembers who emptied a fish, axolotl or tadpole bucket, for the mob that spawns where the water
     * goes.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(@NotNull PlayerBucketEmptyEvent event) {
        Block block = event.getBlock();
        if (!FLUID_BUCKETS.contains(event.getBucket()) && recorder.enabled(LogCategory.ENTITIES, block.getWorld())) {
            causes.remember(block, Actors.of(event.getPlayer()));
        }
    }

    /**
     * Records a mob from a spawn egg, a dispenser or a bucket; natural and other spawns are not
     * recorded.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(@NotNull CreatureSpawnEvent event) {
        Entity entity = event.getEntity();
        SpawnReason reason = event.getSpawnReason();
        if (!RECORDED_REASONS.contains(reason) || !recorder.enabled(LogCategory.ENTITIES, entity.getWorld())) {
            return;
        }
        Block block = entity.getLocation().getBlock();
        // A dispenser empties mob buckets without a player event.
        Actor actor = reason == SpawnReason.DISPENSE_EGG ? Actors.DISPENSER
                : causes.recentCause(block).orElse(BUCKET.contains(reason) ? Actors.DISPENSER : SPAWN_EGG);
        recorder.entitySpawn(actor, entity);
    }

    /** The spawn reasons of these names that this server knows. */
    private static @NotNull Set<SpawnReason> reasons(@NotNull String @NotNull ... names) {
        Set<String> wanted = Set.of(names);
        return Arrays.stream(SpawnReason.values()).filter(reason -> wanted.contains(reason.name()))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(SpawnReason.class)));
    }

    private void placed(@Nullable Player player, @NotNull Entity entity) {
        if (player != null && !(entity instanceof LeashHitch) && recorder.enabled(LogCategory.ENTITIES,
                entity.getWorld())) {
            recorder.entitySpawn(Actors.of(player), entity);
        }
    }

    private static boolean isSpawnEgg(@Nullable ItemStack item) {
        return item != null && MaterialTags.SPAWN_EGGS.isTagged(item.getType());
    }
}
