package gg.moonrise.hawkeye.core.service.rollback;

import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Who runs an operation. The key identifies them for "one operation at a time", their preview and
 * {@code /hawkeye undo}: a player's UUID, {@code #console} for the console. A network job handler can
 * build one with its own key (for example {@code job:<id>}) and the console as the sender.
 *
 * @param key    the issuer's identity
 * @param name   a display name for logs
 * @param sender where replies go
 * @param origin where it was issued from, or {@code null} without a position
 */
public record RollbackIssuer(@NotNull String key, @NotNull String name, @NotNull CommandSender sender,
        @Nullable RollbackOrigin origin) {

    /** The key of the server console. */
    public static final String CONSOLE = "#console";

    /**
     * The issuer for a command sender; call on the sender's own thread (it reads their position).
     *
     * @param sender the sender
     * @return the issuer
     */
    public static @NotNull RollbackIssuer of(@NotNull CommandSender sender) {
        RollbackOrigin origin = null;
        if (sender instanceof Entity entity) {
            Location location = entity.getLocation();
            origin = new RollbackOrigin(location.getWorld().getName(), location.getBlockX(), location.getBlockZ());
        }
        String key = sender instanceof Player player ? player.getUniqueId().toString() : CONSOLE;
        return new RollbackIssuer(key, sender.getName(), sender, origin);
    }

    /**
     * The player behind this issuer.
     *
     * @return the player, or empty for the console and other senders
     */
    public @NotNull Optional<Player> player() {
        return sender instanceof Player player ? Optional.of(player) : Optional.empty();
    }

    /**
     * Where it was issued from.
     *
     * @return the origin, or empty without a position
     */
    public @NotNull Optional<RollbackOrigin> originValue() {
        return Optional.ofNullable(origin);
    }
}
