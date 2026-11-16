package gg.moonrise.hawkeye.ui.lookup;

import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Waits for replies that arrive asynchronously: lookups run on the query pool and reply through a
 * scheduled task on the sender's thread, so the test ticks the mocked scheduler until they arrive.
 */
public final class AwaitReplies {

    private static final long TIMEOUT_SECONDS = 20;

    private AwaitReplies() {
    }

    /**
     * Ticks until the sender received at least one message, and returns everything received.
     *
     * @param server the mocked server
     * @param sender the sender
     * @return the plain text of each message
     */
    public static @NotNull List<String> reply(@NotNull ServerMock server, @NotNull CommandSender sender) {
        return until(server, sender, received -> !received.isEmpty());
    }

    /**
     * Ticks until what the sender received satisfies {@code done}.
     *
     * @param server the mocked server
     * @param sender the sender
     * @param done   when to stop
     * @return the plain text of each message
     */
    public static @NotNull List<String> until(@NotNull ServerMock server, @NotNull CommandSender sender,
            @NotNull Predicate<List<String>> done) {
        List<String> received = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (true) {
            server.getScheduler().performOneTick();
            received.addAll(TestPlugins.drain(sender));
            if (done.test(received)) {
                return received;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no reply within " + TIMEOUT_SECONDS + " s; received " + received);
            }
            pause();
        }
    }

    /**
     * Ticks until a player received at least {@code count} messages, keeping their components.
     *
     * @param server the mocked server
     * @param player the player
     * @param count  how many messages to wait for
     * @return the messages
     */
    public static @NotNull List<Component> components(@NotNull ServerMock server, @NotNull PlayerMock player,
            int count) {
        List<Component> received = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (received.size() < count) {
            server.getScheduler().performOneTick();
            Component message;
            while ((message = player.nextComponentMessage()) != null) {
                received.add(message);
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no reply within " + TIMEOUT_SECONDS + " s; received " + received);
            }
            pause();
        }
        return received;
    }

    /**
     * Ticks for a while and returns whatever arrived (for checks that nothing does).
     *
     * @param server the mocked server
     * @param sender the sender
     * @param millis how long to wait
     * @return the plain text of each message
     */
    public static @NotNull List<String> during(@NotNull ServerMock server, @NotNull CommandSender sender, long millis) {
        List<String> received = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            server.getScheduler().performOneTick();
            received.addAll(TestPlugins.drain(sender));
            pause();
        }
        return received;
    }

    private static void pause() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
