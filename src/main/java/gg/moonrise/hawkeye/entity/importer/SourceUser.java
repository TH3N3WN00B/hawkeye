package gg.moonrise.hawkeye.entity.importer;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A row of CoreProtect's {@code user} table: a player, or a {@code #cause} without a UUID.
 *
 * @param name the name
 * @param uuid the player's UUID, or {@code null} for a cause (or a blank or unreadable UUID)
 */
public record SourceUser(@NotNull String name, @Nullable UUID uuid) {
}
