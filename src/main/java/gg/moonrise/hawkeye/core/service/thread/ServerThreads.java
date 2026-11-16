package gg.moonrise.hawkeye.core.service.thread;

import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * Runs work on the server thread that owns it: the region of a chunk, the thread of a player, or the
 * global region. On Paper these are all the main thread; on Folia they differ, and touching a block
 * or player from the wrong one is an error. Services depend on this interface so they can be tested
 * without a server.
 */
public interface ServerThreads {

    /**
     * Runs a task on the thread that owns a chunk.
     *
     * @param world  the world
     * @param chunkX the chunk x
     * @param chunkZ the chunk z
     * @param task   the task
     */
    void runAtChunk(@NotNull World world, int chunkX, int chunkZ, @NotNull Runnable task);

    /**
     * Runs a task on the thread that owns a command sender (a player's region, or the global region
     * for the console). A player who leaves before it runs drops the task.
     *
     * @param sender the sender
     * @param task   the task
     */
    void runFor(@NotNull CommandSender sender, @NotNull Runnable task);

    /**
     * Runs a task on the global region.
     *
     * @param task the task
     */
    void runGlobal(@NotNull Runnable task);
}
