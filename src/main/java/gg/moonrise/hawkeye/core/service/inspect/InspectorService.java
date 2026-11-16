package gg.moonrise.hawkeye.core.service.inspect;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.moss.spring.SpringComponent;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;

/**
 * Who has the inspector on, and the queries its clicks run. A player's inspector stays on until they
 * toggle it off or leave. Safe from any thread (players live on different regions on Folia).
 */
@SpringComponent
@RequiredArgsConstructor
public final class InspectorService {

    /** A second click on the same block within this many milliseconds runs no new lookup. */
    public static final long REPEAT_MILLIS = 1_000;

    private static final Set<Action> CONTAINER_ACTIONS = EnumSet.of(Action.CONTAINER_DEPOSIT,
            Action.CONTAINER_WITHDRAW);

    private final @NotNull Clock clock;
    private final Set<UUID> inspecting = ConcurrentHashMap.newKeySet();
    private final Map<UUID, LastLookup> lastLookups = new ConcurrentHashMap<>();

    /**
     * Turns a player's inspector on or off.
     *
     * @param player the player's UUID
     * @return {@code true} when it is now on
     */
    public boolean toggle(@NotNull UUID player) {
        if (inspecting.remove(player)) {
            lastLookups.remove(player);
            return false;
        }
        inspecting.add(player);
        return true;
    }

    /**
     * Whether a player's inspector is on.
     *
     * @param player the player's UUID
     * @return {@code true} when on
     */
    public boolean isInspecting(@NotNull UUID player) {
        return inspecting.contains(player);
    }

    /**
     * Turns a player's inspector off (they left).
     *
     * @param player the player's UUID
     */
    public void clear(@NotNull UUID player) {
        inspecting.remove(player);
        lastLookups.remove(player);
    }

    /**
     * Whether a click should run its lookup: not when the same player ran the same lookup a moment ago
     * (holding the mouse button repeats the click).
     *
     * @param player the player's UUID
     * @param query  the lookup the click would run
     * @return {@code true} to run the lookup
     */
    public boolean shouldLookUp(@NotNull UUID player, @NotNull LookupQuery query) {
        long now = clock.millis();
        LastLookup previous = lastLookups.put(player, new LastLookup(query, now));
        return previous == null || !previous.query().equals(query) || now - previous.millis() >= REPEAT_MILLIS;
    }

    /**
     * Everything that happened at a block.
     *
     * @param block the block
     * @return the query, on this server, over all time
     */
    public @NotNull LookupQuery history(@NotNull Block block) {
        return LookupQuery.at(area(block));
    }

    /**
     * The items put into and taken out of a container.
     *
     * @param block the container block
     * @return the query, on this server, over all time
     */
    public @NotNull LookupQuery transactions(@NotNull Block block) {
        return LookupQuery.at(area(block)).withActions(CONTAINER_ACTIONS);
    }

    private static @NotNull Area.Block area(@NotNull Block block) {
        return new Area.Block(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private record LastLookup(@NotNull LookupQuery query, long millis) {
    }
}
