package gg.moonrise.hawkeye.core.service.capture;

import gg.moonrise.hawkeye.entity.action.Action;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;

/**
 * A block change ready to record, in the orientation its action stores: a break keeps the broken
 * state as the subject and what remained as the secondary; a place or change keeps the new state as
 * the subject and the old one as the secondary.
 *
 * @param action    {@link Action#BLOCK_BREAK}, {@link Action#BLOCK_PLACE} or {@link Action#BLOCK_CHANGE}
 * @param subject   the subject state
 * @param secondary the secondary state
 */
public record BlockChange(@NotNull Action action, @NotNull BlockData subject, @NotNull BlockData secondary) {
}
