package gg.moonrise.hawkeye.core.service.rollback;

import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.core.service.rollback.apply.WorldWriter;
import gg.moonrise.hawkeye.core.service.rollback.plan.ChunkPlan;
import gg.moonrise.hawkeye.core.service.rollback.plan.PlannedChange;
import gg.moonrise.hawkeye.core.service.rollback.plan.RollbackPlan;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.moss.spring.Disableable;
import gg.moonrise.moss.spring.SpringComponent;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * The previews players are looking at. A preview sends the planned blocks to its player only (the
 * world does not change) and keeps the plan until {@code /hawkeye apply}, {@code cancel}, expiry or the
 * player leaving; cancelling or expiring sends the real blocks back, read on each chunk's own thread.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class RollbackPreviews implements Disableable {

    private final @NotNull JavaPlugin plugin;
    private final @NotNull ServerThreads threads;
    private final @NotNull WorldWriter writer;
    private final @NotNull HawkeyeExecutors executors;
    private final @NotNull Clock clock;
    private final Map<UUID, Preview> previews = new ConcurrentHashMap<>();

    /**
     * A preview on show.
     *
     * @param request         the operation it previews
     * @param plan            its plan
     * @param player          who sees it
     * @param expiresAtMillis when it expires, Unix milliseconds
     */
    public record Preview(@NotNull RollbackRequest request, @NotNull RollbackPlan plan, @NotNull Player player,
            long expiresAtMillis) {
    }

    /**
     * Shows a plan to a player, replacing their previous preview.
     *
     * @param request the operation
     * @param plan    its plan
     * @param player  who sees it
     * @param minutes until it expires
     * @return the preview
     */
    public @NotNull Preview show(@NotNull RollbackRequest request, @NotNull RollbackPlan plan, @NotNull Player player,
            int minutes) {
        Preview preview = new Preview(request, plan, player, clock.millis() + TimeUnit.MINUTES.toMillis(minutes));
        Preview previous = previews.put(player.getUniqueId(), preview);
        if (previous != null) {
            revert(previous);
        }
        send(preview);
        try {
            executors.maintenance().schedule(() -> expire(player.getUniqueId(), preview), minutes, TimeUnit.MINUTES);
        } catch (RejectedExecutionException e) {
            log.debug("Not scheduling the preview expiry: the plugin is shutting down");
        }
        return preview;
    }

    /**
     * Takes a player's preview to apply it; the player keeps seeing the planned blocks, which the
     * applied change then makes real.
     *
     * @param player the player's UUID
     * @return the preview, or empty when they have none (or it expired)
     */
    public @NotNull Optional<Preview> take(@NotNull UUID player) {
        Preview preview = previews.remove(player);
        if (preview == null) {
            return Optional.empty();
        }
        if (preview.expiresAtMillis() <= clock.millis()) {
            expired(preview);
            return Optional.empty();
        }
        return Optional.of(preview);
    }

    /**
     * Drops a player's preview and sends the real blocks back.
     *
     * @param player the player's UUID
     * @return whether they had one
     */
    public boolean cancel(@NotNull UUID player) {
        Preview preview = previews.remove(player);
        if (preview == null) {
            return false;
        }
        revert(preview);
        return true;
    }

    /**
     * Drops a player's preview without sending blocks back, for a player who left: their client
     * dropped the fake blocks with the world, and after a reconnect it shows the real ones.
     *
     * @param player the player's UUID
     * @return whether they had one
     */
    public boolean discard(@NotNull UUID player) {
        return previews.remove(player) != null;
    }

    /**
     * Whether a player has a preview on show.
     *
     * @param player the player's UUID
     * @return {@code true} when they have one
     */
    public boolean has(@NotNull UUID player) {
        return previews.containsKey(player);
    }

    /**
     * Expires every preview due by a time (run by the scheduled expiry, and by tests).
     *
     * @param nowMillis the time, Unix milliseconds
     * @return how many expired
     */
    public int expireDue(long nowMillis) {
        int expired = 0;
        for (Map.Entry<UUID, Preview> entry : previews.entrySet()) {
            Preview preview = entry.getValue();
            if (preview.expiresAtMillis() <= nowMillis && previews.remove(entry.getKey(), preview)) {
                expired(preview);
                expired++;
            }
        }
        return expired;
    }

    /** Forgets every preview; the players' clients drop the fake blocks when they reconnect. */
    @Override
    public void onDisable() {
        previews.clear();
    }

    private void expire(@NotNull UUID player, @NotNull Preview preview) {
        if (previews.remove(player, preview)) {
            expired(preview);
        }
    }

    private void expired(@NotNull Preview preview) {
        revert(preview);
        try {
            preview.request().listener().previewExpired(preview.request().mode());
        } catch (RuntimeException e) {
            log.warn("A preview listener failed", e);
        }
    }

    private void send(@NotNull Preview preview) {
        Player player = preview.player();
        for (ChunkPlan chunk : preview.plan().chunks()) {
            List<PlannedChange.SetBlock> blocks = blocks(chunk);
            if (blocks.isEmpty()) {
                continue;
            }
            threads.runFor(player, () -> {
                World world = player.getWorld();
                if (!world.getName().equals(chunk.world())) {
                    return;
                }
                for (PlannedChange.SetBlock block : blocks) {
                    writer.blockData(block.state()).ifPresent(data ->
                            player.sendBlockChange(new Location(world, block.x(), block.y(), block.z()), data));
                }
            });
        }
    }

    private void revert(@NotNull Preview preview) {
        Player player = preview.player();
        for (ChunkPlan chunk : preview.plan().chunks()) {
            List<PlannedChange> blocks = List.copyOf(blocks(chunk));
            World world = plugin.getServer().getWorld(chunk.world());
            if (blocks.isEmpty() || world == null) {
                continue;
            }
            try {
                threads.runAtChunk(world, chunk.chunkX(), chunk.chunkZ(), () -> {
                    Map<Location, BlockData> real = writer.current(world, blocks);
                    threads.runFor(player, () -> {
                        if (player.getWorld().equals(world)) {
                            real.forEach(player::sendBlockChange);
                        }
                    });
                });
            } catch (RuntimeException e) {
                log.debug("Could not send the real blocks of a preview back to {}", player.getName(), e);
            }
        }
    }

    private static @NotNull List<PlannedChange.SetBlock> blocks(@NotNull ChunkPlan chunk) {
        return chunk.changes().stream()
                .filter(PlannedChange.SetBlock.class::isInstance)
                .map(PlannedChange.SetBlock.class::cast)
                .toList();
    }
}
