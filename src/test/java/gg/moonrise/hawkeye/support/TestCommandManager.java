package gg.moonrise.hawkeye.support;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.jetbrains.annotations.NotNull;

/** A Cloud command manager without a platform: commands are parsed and executed in-process. */
public final class TestCommandManager extends CommandManager<CommandSourceStack> {

    public TestCommandManager() {
        super(ExecutionCoordinator.simpleCoordinator(), CommandRegistrationHandler.nullCommandRegistrationHandler());
    }

    @Override
    public boolean hasPermission(@NotNull CommandSourceStack sender, @NotNull String permission) {
        return permission.isEmpty() || sender.getSender().hasPermission(permission);
    }

    /**
     * Runs {@code input} (without the slash) as {@code sender} and waits for it.
     *
     * @param sender who runs the command
     * @param input  the command line
     */
    public void run(@NotNull CommandSender sender, @NotNull String input) {
        commandExecutor().executeCommand(new TestCommandSource(sender), input).join();
    }
}
