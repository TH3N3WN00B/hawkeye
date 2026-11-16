package gg.moonrise.hawkeye.api;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hawkeye's API for other plugins, registered in Bukkit's {@code ServicesManager} while Hawkeye is
 * enabled:
 * <pre>{@code
 * HawkeyeApi hawkeye = Bukkit.getServicesManager().load(HawkeyeApi.class);
 * }</pre>
 * Recording methods are cheap and thread-safe (call them from the thread that owns the location);
 * lookups run on Hawkeye's own threads and never block the caller. Types in {@code entity.*} are
 * immutable records and part of the API. Bumped {@link #VERSION} on every incompatible change.
 */
public interface HawkeyeApi {

    /** The API version: bumped on incompatible changes. */
    int VERSION = 1;

    /**
     * Whether the database is connected (lookups fail with a not-ready result before that; recorded
     * events are queued and written once it is).
     *
     * @return {@code true} when ready
     */
    boolean ready();

    /**
     * Records a block placed on behalf of an actor (a plugin building for a player).
     *
     * @param actor    who placed it ({@code Actor.player(uuid, name)} or {@code Actor.source("myplugin")})
     * @param replaced the block's state before
     * @param placed   the placed block data
     */
    void logPlacement(@NotNull Actor actor, @NotNull BlockState replaced, @NotNull BlockData placed);

    /**
     * Records a block removed on behalf of an actor.
     *
     * @param actor  who removed it
     * @param broken the block's state before
     */
    void logRemoval(@NotNull Actor actor, @NotNull BlockState broken);

    /**
     * Records items put into or taken out of a container on behalf of an actor.
     *
     * @param action    {@link Action#CONTAINER_DEPOSIT} or {@link Action#CONTAINER_WITHDRAW}
     * @param actor     who moved them
     * @param container the container's location
     * @param item      the item
     * @param amount    how many
     */
    void logContainer(@NotNull Action action, @NotNull Actor actor, @NotNull Location container,
            @NotNull ItemStack item, int amount);

    /**
     * Records a chat message or command on behalf of an actor.
     *
     * @param action   {@link Action#CHAT} or {@link Action#COMMAND}
     * @param actor    who sent it
     * @param location where
     * @param text     the text
     */
    void logMessage(@NotNull Action action, @NotNull Actor actor, @NotNull Location location, @NotNull String text);

    /**
     * Parses CoreProtect-style parameters ({@code u:Alex t:2h r:10 a:-block}) into a query.
     *
     * @param parameters the parameter list
     * @param origin     the centre of {@code r:}, or {@code null} when the list has no radius
     * @return the query, or a parse failure
     */
    @NotNull QueryParse parse(@NotNull String parameters, @Nullable Location origin);

    /**
     * Reads matching events, newest first.
     *
     * @param query  the query
     * @param cursor the id of the last event of the previous page, or 0 for the first page
     * @param limit  the most events
     * @return the events (empty when nothing matches); fails when the database is unavailable
     */
    @NotNull CompletableFuture<List<StoredEvent>> lookup(@NotNull LookupQuery query, long cursor, int limit);

    /**
     * Counts matching events.
     *
     * @param query the query
     * @return the count; fails when the database is unavailable
     */
    @NotNull CompletableFuture<Long> count(@NotNull LookupQuery query);

    /** Outcome of {@link #parse}. */
    sealed interface QueryParse {

        /**
         * The parameters were valid.
         *
         * @param query the query
         */
        record Parsed(@NotNull LookupQuery query) implements QueryParse {
        }

        /**
         * A parameter is invalid.
         *
         * @param problem the offending token and why
         */
        record Invalid(@NotNull String problem) implements QueryParse {
        }
    }
}
