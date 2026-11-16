package gg.moonrise.hawkeye.support;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.ComponentLike;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A command source for a MockBukkit sender (console or player). */
public record TestCommandSource(@NotNull CommandSender sender) implements CommandSourceStack {

    @Override
    public @NotNull Location getLocation() {
        return sender instanceof Entity entity ? entity.getLocation() : new Location(null, 0, 0, 0);
    }

    @Override
    public @NotNull CommandSender getSender() {
        return sender;
    }

    @Override
    public @Nullable Entity getExecutor() {
        return sender instanceof Entity entity ? entity : null;
    }

    @Override
    public @NotNull Player getPlayerOrThrow() {
        if (sender instanceof Player player) {
            return player;
        }
        throw new IllegalStateException("not a player");
    }

    @Override
    public @NotNull Entity getEntityOrThrow() {
        if (sender instanceof Entity entity) {
            return entity;
        }
        throw new IllegalStateException("not an entity");
    }

    @Override
    public @NotNull CommandSourceStack withLocation(@NotNull Location location) {
        return this;
    }

    @Override
    public @NotNull CommandSourceStack withExecutor(@NotNull Entity executor) {
        return this;
    }

    @Override
    public void sendReply(@NotNull ComponentLike message) {
        sender.sendMessage(message);
    }

    @Override
    public void sendSuccess(@NotNull ComponentLike message, boolean broadcast) {
        sender.sendMessage(message);
    }

    @Override
    public void sendFailure(@NotNull ComponentLike message) {
        sender.sendMessage(message);
    }
}
