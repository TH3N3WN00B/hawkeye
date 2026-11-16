package gg.moonrise.hawkeye.core.service.rollback;

import gg.moonrise.hawkeye.entity.query.LookupQuery;
import org.jetbrains.annotations.NotNull;

/**
 * A rollback or restore to run.
 *
 * @param mode     rollback or restore
 * @param query    which events (its rollback-state and the action filter are set by the service)
 * @param issuer   who runs it
 * @param preview  show the change to the issuing player only, until they apply or cancel it
 * @param listener hears progress
 */
public record RollbackRequest(@NotNull RollbackMode mode, @NotNull LookupQuery query, @NotNull RollbackIssuer issuer,
        boolean preview, @NotNull RollbackListener listener) {
}
