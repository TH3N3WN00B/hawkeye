package gg.moonrise.hawkeye.integration.api;

import gg.moonrise.hawkeye.api.HawkeyeApi;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.lookup.LookupOutcome;
import gg.moonrise.hawkeye.core.service.lookup.LookupService;
import gg.moonrise.hawkeye.core.service.query.BuildResult;
import gg.moonrise.hawkeye.core.service.query.syntax.ParseResult;
import gg.moonrise.hawkeye.core.service.query.QueryBuilder;
import gg.moonrise.hawkeye.core.service.query.QueryContext;
import gg.moonrise.hawkeye.core.service.query.syntax.QueryError;
import gg.moonrise.hawkeye.core.service.query.syntax.QuerySyntax;
import gg.moonrise.hawkeye.core.service.status.StatusService;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.moss.spring.Disableable;
import gg.moonrise.moss.spring.Enableable;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link HawkeyeApi} over the services, registered in Bukkit's {@code ServicesManager} at enable and
 * unregistered at disable.
 */
@SpringComponent
@RequiredArgsConstructor
public final class HawkeyeApiService implements HawkeyeApi, Enableable, Disableable {

    private final @NotNull JavaPlugin plugin;
    private final @NotNull EventRecorder recorder;
    private final @NotNull LookupService lookupService;
    private final @NotNull QuerySyntax syntax;
    private final @NotNull QueryBuilder builder;
    private final @NotNull StatusService statusService;

    @Override
    public void onEnable() {
        plugin.getServer().getServicesManager().register(HawkeyeApi.class, this, plugin, ServicePriority.Normal);
    }

    @Override
    public void onDisable() {
        plugin.getServer().getServicesManager().unregister(HawkeyeApi.class, this);
    }

    @Override
    public boolean ready() {
        return statusService.status().health() instanceof StatusService.Health.Ready;
    }

    @Override
    public void logPlacement(@NotNull Actor actor, @NotNull BlockState replaced, @NotNull BlockData placed) {
        recorder.blockPlace(actor, replaced, placed);
    }

    @Override
    public void logRemoval(@NotNull Actor actor, @NotNull BlockState broken) {
        recorder.blockBreak(actor, broken, plugin.getServer().createBlockData(EventRecorder.AIR), null);
    }

    @Override
    public void logContainer(@NotNull Action action, @NotNull Actor actor, @NotNull Location container,
            @NotNull ItemStack item, int amount) {
        recorder.container(action, actor, container, item, amount);
    }

    @Override
    public void logMessage(@NotNull Action action, @NotNull Actor actor, @NotNull Location location,
            @NotNull String text) {
        recorder.message(action, actor, location, text);
    }

    @Override
    public @NotNull QueryParse parse(@NotNull String parameters, @Nullable Location origin) {
        QueryContext.SenderPosition position = origin == null || origin.getWorld() == null ? null
                : new QueryContext.SenderPosition(origin.getWorld().getName(), origin.getBlockX(), origin.getBlockY(),
                        origin.getBlockZ());
        QueryContext context = new QueryContext(position, true, 0, 0,
                world -> plugin.getServer().getWorld(world) != null);
        BuildResult built = switch (syntax.parse(parameters)) {
            case ParseResult.Parsed parsed -> builder.build(parsed.parameters(), context);
            case ParseResult.Invalid invalid -> new BuildResult.Invalid(invalid.error());
        };
        return switch (built) {
            case BuildResult.Built ok -> new QueryParse.Parsed(ok.query());
            case BuildResult.Invalid invalid -> new QueryParse.Invalid(describe(invalid.error()));
        };
    }

    @Override
    public @NotNull CompletableFuture<List<StoredEvent>> lookup(@NotNull LookupQuery query, long cursor, int limit) {
        return lookupService.page(query, cursor, limit).thenApply(outcome -> switch (outcome) {
            case LookupOutcome.Page page -> page.events();
            case LookupOutcome.Count count -> throw new IllegalStateException("unexpected count");
            case LookupOutcome.Problem problem -> throw new IllegalStateException(describe(problem));
        });
    }

    @Override
    public @NotNull CompletableFuture<Long> count(@NotNull LookupQuery query) {
        return lookupService.count(query).thenApply(outcome -> switch (outcome) {
            case LookupOutcome.Count count -> count.total();
            case LookupOutcome.Page page -> throw new IllegalStateException("unexpected page");
            case LookupOutcome.Problem problem -> throw new IllegalStateException(describe(problem));
        });
    }

    private static @NotNull String describe(@NotNull QueryError error) {
        return error.kind().name().toLowerCase(Locale.ROOT) + ": " + error.value();
    }

    private static @NotNull String describe(@NotNull LookupOutcome.Problem problem) {
        return switch (problem) {
            case LookupOutcome.NotReady notReady -> "Hawkeye's database is not ready";
            case LookupOutcome.Failed failed -> failed.reason();
            case LookupOutcome.TimedOut timedOut -> "the lookup timed out";
        };
    }
}
