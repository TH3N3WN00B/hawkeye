package gg.moonrise.hawkeye.command;

import static org.junit.jupiter.api.Assertions.fail;

import gg.moonrise.hawkeye.command.support.QueryInput;
import gg.moonrise.hawkeye.support.TestCommandManager;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.jetbrains.annotations.NotNull;
import org.mockbukkit.mockbukkit.ServerMock;

/** Helpers for the rollback and purge command tests. */
public final class RollbackTestSupport {

    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(20);

    private RollbackTestSupport() {
    }

    /**
     * Registers every command bean on a platform-free manager. The parameter-list suggestion provider
     * is declared by the lookup command; it is registered here first so the rollback and purge commands
     * register on their own too.
     *
     * @return the manager
     */
    public static @NotNull TestCommandManager commands() {
        TestCommandManager manager = new TestCommandManager();
        manager.parserRegistry().registerSuggestionProvider(QueryInput.SUGGESTIONS, SuggestionProvider.noSuggestions());
        TestPlugins.bean(HawkeyeCommandRegistry.class).register(manager);
        return manager;
    }

    /**
     * Ticks the server until a sender received a message containing some text.
     *
     * @param server   the mocked server
     * @param sender   the sender
     * @param fragment the text
     * @return every message received meanwhile
     */
    public static @NotNull List<String> tickUntilReply(@NotNull ServerMock server, @NotNull CommandSender sender,
            @NotNull String fragment) {
        List<String> seen = new ArrayList<>();
        tickUntil(server, () -> {
            seen.addAll(TestPlugins.drain(sender));
            return seen.stream().anyMatch(line -> line.contains(fragment));
        }, () -> "no reply containing '" + fragment + "' in " + seen);
        return seen;
    }

    /**
     * Ticks the server until a condition holds.
     *
     * @param server    the mocked server
     * @param condition the condition
     * @param message   the failure message
     */
    public static void tickUntil(@NotNull ServerMock server, @NotNull BooleanSupplier condition,
            @NotNull Supplier<String> message) {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            server.getScheduler().performOneTick();
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
        fail(message.get());
    }
}
