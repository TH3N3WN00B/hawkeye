package gg.moonrise.hawkeye.core.service.rollback;

import gg.moonrise.hawkeye.config.file.RollbackSettings;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a plan includes besides blocks, and where it starts.
 *
 * @param restoreContainers reverse container deposits and withdrawals (item frames and armour stands too)
 * @param restoreEntities   on rollback, respawn killed and removed entities and remove placed and spawned
 *                          ones
 * @param origin            change the chunks nearest to here first, or {@code null} for a fixed order
 */
public record PlanOptions(boolean restoreContainers, boolean restoreEntities, @Nullable RollbackOrigin origin) {

    /**
     * The options from the configuration.
     *
     * @param settings the {@code rollback} section
     * @param origin   where the operation was issued from, or {@code null}
     * @return the options
     */
    public static @NotNull PlanOptions of(@NotNull RollbackSettings settings, @Nullable RollbackOrigin origin) {
        return new PlanOptions(settings.restoreContainers(), settings.restoreEntities(), origin);
    }

    /**
     * Where the plan starts.
     *
     * @return the origin, or empty for a fixed order
     */
    public @NotNull Optional<RollbackOrigin> originValue() {
        return Optional.ofNullable(origin);
    }
}
