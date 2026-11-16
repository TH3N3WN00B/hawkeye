package gg.moonrise.hawkeye.core.service.rollback;

import org.jetbrains.annotations.NotNull;

/**
 * Where an operation was issued from: chunks nearest to it are changed first, so the issuer sees the
 * result around them before the far edges.
 *
 * @param world the world name
 * @param x     block x
 * @param z     block z
 */
public record RollbackOrigin(@NotNull String world, int x, int z) {
}
