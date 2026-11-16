package gg.moonrise.hawkeye.entity.actor;

import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/** What an actor is. The code is stored in the {@code actor} table: never renumber. */
public enum ActorKind {

    /** A player, keyed by UUID. */
    PLAYER(0),
    /** A non-player cause, keyed by {@code #name}. */
    SOURCE(1);

    private final int code;

    ActorKind(int code) {
        this.code = code;
    }

    /**
     * The stored code.
     *
     * @return the code
     */
    public int code() {
        return code;
    }

    /**
     * The kind stored under a code.
     *
     * @param code a stored code
     * @return the kind, or empty for an unknown code
     */
    public static @NotNull Optional<ActorKind> byCode(int code) {
        for (ActorKind kind : values()) {
            if (kind.code == code) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
