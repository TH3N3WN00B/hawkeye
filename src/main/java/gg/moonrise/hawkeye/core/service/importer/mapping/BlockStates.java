package gg.moonrise.hawkeye.core.service.importer.mapping;

import org.jetbrains.annotations.NotNull;

/**
 * Brings a block state string into the form the server itself writes, so imported and captured events
 * share dictionary entries. The server's implementation is {@link ServerBlockStates}; tests pass their
 * own.
 */
public interface BlockStates {

    /** Keeps every string as it is. */
    BlockStates UNCHANGED = state -> state;

    /**
     * The server's form of a block state.
     *
     * @param state a namespaced state such as {@code minecraft:oak_stairs[facing=east]}
     * @return the normalised state, or {@code state} itself when the server does not know it
     */
    @NotNull String normalize(@NotNull String state);
}
