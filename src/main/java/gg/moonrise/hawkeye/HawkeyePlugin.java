package gg.moonrise.hawkeye;

import gg.moonrise.engine.paper.PaperPlugin;
import gg.moonrise.moss.spring.Disableable;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.event.HandlerList;

/**
 * Plugin entry point on the Moonrise plugin-engine. Moss builds the Spring context in {@code onLoad}
 * from every {@code @SpringComponent} under this package (the relocated engine included), enables
 * {@code Enableable} beans and registers {@code Listener} beans in {@code onEnable}. Nothing is
 * constructed or registered by hand here.
 *
 * <p>Not {@code final}: MockBukkit loads plugins through a ByteBuddy subclass.
 */
@Slf4j
public class HawkeyePlugin extends PaperPlugin {

    @Override
    public void onEnable() {
        super.onEnable();
        log.info("{} {} enabled", getName(), getPluginMeta().getVersion());
    }

    /**
     * Moss's version ends with {@code Bukkit.getScheduler().cancelTasks(this)}, which throws
     * {@code UnsupportedOperationException} on Folia and so skips the Spring context close that
     * follows in {@code MossPaper.onDisable}. This does the same work with the region-aware
     * schedulers, which exist on Paper and Folia; Paper cancels any legacy scheduler task of a
     * disabled plugin itself.
     */
    @Override
    public void disableComponents() {
        invokeBeans(Disableable.class, Disableable::onDisable, (bean, e) ->
                log.error("Failed to disable {}", bean.getClass().getSimpleName(), e));
        HandlerList.unregisterAll(this);
        getServer().getGlobalRegionScheduler().cancelTasks(this);
        getServer().getAsyncScheduler().cancelTasks(this);
    }
}
