package gg.moonrise.hawkeye.core.service.thread;

import gg.moonrise.engine.paper.scheduler.Scheduler;
import gg.moonrise.moss.spring.SpringComponent;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

/** {@link ServerThreads} on the plugin-engine's region-aware {@link Scheduler} (Paper and Folia). */
@SpringComponent
public final class EngineServerThreads implements ServerThreads {

    @Override
    public void runAtChunk(@NotNull World world, int chunkX, int chunkZ, @NotNull Runnable task) {
        Scheduler.location().executeChunk(world, chunkX, chunkZ, task);
    }

    @Override
    public void runFor(@NotNull CommandSender sender, @NotNull Runnable task) {
        if (sender instanceof Entity entity) {
            // The entity scheduler needs a delay of at least one tick; a removed entity drops the task.
            Scheduler.entity(entity).execute(task, 1L);
        } else {
            Scheduler.sync().execute(task);
        }
    }

    @Override
    public void runGlobal(@NotNull Runnable task) {
        Scheduler.sync().execute(task);
    }
}
