package gg.moonrise.hawkeye.listener.player;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.event.inventory.ItemCraftedEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrowableProjectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Items players make, buy and use up, recorded where the player stands: crafted results they take
 * ({@link LogCategory#CRAFTING}), villager and wandering trader purchases ({@link LogCategory#TRADES}),
 * and ({@link LogCategory#ITEM_USE}) items they throw or shoot and tools that wear out.
 */
@SpringComponent
@RequiredArgsConstructor
public final class ItemUseListener implements Listener {

    private final @NotNull EventRecorder recorder;

    /**
     * Records a crafted item the player takes from the result slot, with the crafted count.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(@NotNull ItemCraftedEvent event) {
        Player player = event.getPlayer();
        if (recorder.enabled(LogCategory.CRAFTING, player.getWorld())) {
            recorder.item(Action.ITEM_CRAFT, Actors.of(player), player.getLocation(), event.getCraftedItem());
        }
    }

    /**
     * Records the result of a trade, with its count.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(@NotNull PlayerTradeEvent event) {
        Player player = event.getPlayer();
        if (recorder.enabled(LogCategory.TRADES, player.getWorld())) {
            recorder.item(Action.ITEM_TRADE, Actors.of(player), player.getLocation(), event.getTrade().getResult());
        }
    }

    /**
     * Records a tool, weapon or armour piece that broke.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(@NotNull PlayerItemBreakEvent event) {
        Player player = event.getPlayer();
        if (recorder.enabled(LogCategory.ITEM_USE, player.getWorld())) {
            recorder.item(Action.ITEM_BREAK, Actors.of(player), player.getLocation(), event.getBrokenItem().asOne());
        }
    }

    /**
     * Records the item a player throws or shoots (snowball, egg, pearl, potion, arrow, trident).
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(@NotNull ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player)
                || !recorder.enabled(LogCategory.ITEM_USE, player.getWorld())) {
            return;
        }
        carried(projectile).ifPresent(item -> recorder.item(Action.ITEM_THROW, Actors.of(player),
                player.getLocation(), item.asOne()));
    }

    private static @NotNull Optional<ItemStack> carried(@NotNull Projectile projectile) {
        return switch (projectile) {
            // Every elytra boost launches one; they would drown the throws.
            case Firework _ -> Optional.empty();
            case AbstractArrow arrow -> Optional.of(arrow.getItemStack());
            case ThrowableProjectile thrown -> Optional.of(thrown.getItem());
            default -> Optional.empty();
        };
    }
}
