package gg.moonrise.hawkeye.entity.action;

import java.util.Arrays;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * Everything Hawkeye records. The {@link #code()} is stored in every event row: it is a data
 * contract, so codes are never reused or renumbered, and new actions take new codes.
 */
public enum Action {

    /** A block was removed; subject is the broken block, secondary what was left (usually air). */
    BLOCK_BREAK(1, ActionFamily.BLOCK, true),
    /** A block was placed; subject is the placed block, secondary the block it replaced. */
    BLOCK_PLACE(2, ActionFamily.BLOCK, true),
    /** A block changed in place (stripped, tilled, faded, grew); subject is the new state, secondary the old. */
    BLOCK_CHANGE(3, ActionFamily.BLOCK, true),
    /** A sign's text was edited; the payload holds the sides before and after. */
    SIGN_EDIT(4, ActionFamily.BLOCK, true),
    /** A block was used (door, lever, button, ...); subject is the new state, secondary the old. */
    INTERACT(5, ActionFamily.BLOCK, false),

    /** Items were put into a container; subject is the item material, amount the count. */
    CONTAINER_DEPOSIT(10, ActionFamily.CONTAINER, true),
    /** Items were taken out of a container; subject is the item material, amount the count. */
    CONTAINER_WITHDRAW(11, ActionFamily.CONTAINER, true),

    /** Items were dropped; subject is the item material, amount the count. */
    ITEM_DROP(20, ActionFamily.ITEM, false),
    /** Items were picked up; subject is the item material, amount the count. */
    ITEM_PICKUP(21, ActionFamily.ITEM, false),

    /** An entity was killed; subject is the entity type, the payload the entity for rollback. */
    ENTITY_KILL(30, ActionFamily.ENTITY, true),

    /** A chat message; the payload is the text. */
    CHAT(40, ActionFamily.MESSAGE, false),
    /** A command; the payload is the command line. */
    COMMAND(41, ActionFamily.MESSAGE, false),

    /** A player joined. */
    JOIN(50, ActionFamily.SESSION, false),
    /** A player left. */
    QUIT(51, ActionFamily.SESSION, false);

    private static final Action[] BY_CODE = new Action[64];

    static {
        for (Action action : values()) {
            if (BY_CODE[action.code] != null) {
                throw new IllegalStateException("duplicate action code " + action.code);
            }
            BY_CODE[action.code] = action;
        }
    }

    private final int code;
    private final ActionFamily family;
    private final boolean reversible;

    Action(int code, @NotNull ActionFamily family, boolean reversible) {
        this.code = code;
        this.family = family;
        this.reversible = reversible;
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
     * The family, which says what {@code subject} refers to.
     *
     * @return the family
     */
    public @NotNull ActionFamily family() {
        return family;
    }

    /**
     * Whether rollback and restore change the world for this action.
     *
     * @return {@code true} for block, container and kill actions
     */
    public boolean reversible() {
        return reversible;
    }

    /**
     * The action stored under a code.
     *
     * @param code a stored code
     * @return the action, or empty for an unknown code (a newer Hawkeye wrote it)
     */
    public static @NotNull Optional<Action> byCode(int code) {
        return code >= 0 && code < BY_CODE.length ? Optional.ofNullable(BY_CODE[code]) : Optional.empty();
    }

    /**
     * Every action of a family.
     *
     * @param family the family
     * @return the actions, in declaration order
     */
    public static @NotNull Action @NotNull [] of(@NotNull ActionFamily family) {
        return Arrays.stream(values()).filter(action -> action.family == family).toArray(Action[]::new);
    }
}
