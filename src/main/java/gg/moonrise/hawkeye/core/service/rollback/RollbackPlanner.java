package gg.moonrise.hawkeye.core.service.rollback;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.action.ActionFamily;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * Plans rollbacks and restores from events, without touching the server or the database: which
 * events an operation reads, and what each block, container and killed entity becomes
 * ({@link PlanBuilder}).
 */
@SpringComponent
public final class RollbackPlanner {

    /**
     * The query an operation reads: the issuer's filter narrowed to the actions it reverses (only
     * reversible ones; containers and kills only when configured; never kills for a restore) and to
     * the events the mode undoes or redoes.
     *
     * @param query   the issuer's query
     * @param mode    rollback or restore
     * @param options what to include
     * @return the query, or empty when no action it names can be reversed
     */
    public @NotNull Optional<LookupQuery> query(@NotNull LookupQuery query, @NotNull RollbackMode mode,
            @NotNull PlanOptions options) {
        Set<Action> reversed = EnumSet.noneOf(Action.class);
        for (Action action : Action.values()) {
            if (reverses(action, mode, options)) {
                reversed.add(action);
            }
        }
        if (!query.actions().isEmpty()) {
            reversed.retainAll(query.actions());
        }
        if (reversed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(query.withActions(reversed).withRollbackState(mode.reads()));
    }

    /**
     * A builder to feed events into page by page.
     *
     * @param mode    rollback or restore
     * @param options what to include and where to start
     * @return an empty builder
     */
    public @NotNull PlanBuilder builder(@NotNull RollbackMode mode, @NotNull PlanOptions options) {
        return new PlanBuilder(mode, options);
    }

    /**
     * Plans an operation from all its events at once.
     *
     * @param mode    rollback or restore
     * @param events  the matching events, in any order
     * @param options what to include and where to start
     * @return the plan
     */
    public @NotNull RollbackPlan plan(@NotNull RollbackMode mode, @NotNull Collection<StoredEvent> events,
            @NotNull PlanOptions options) {
        PlanBuilder builder = builder(mode, options);
        events.forEach(builder::add);
        return builder.build();
    }

    private static boolean reverses(@NotNull Action action, @NotNull RollbackMode mode,
            @NotNull PlanOptions options) {
        if (!action.reversible()) {
            return false;
        }
        ActionFamily family = action.family();
        if (family == ActionFamily.CONTAINER) {
            return options.restoreContainers();
        }
        if (family == ActionFamily.ENTITY) {
            return mode == RollbackMode.ROLLBACK && options.restoreEntities();
        }
        return true;
    }
}
