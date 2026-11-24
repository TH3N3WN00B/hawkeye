package gg.moonrise.hawkeye.entity.action;

/** Groups actions by what their {@code subject} column refers to. */
public enum ActionFamily {

    /** Subject and secondary are block states ({@code minecraft:oak_stairs[facing=east]}). */
    BLOCK,
    /** Subject is an item material at a container; amount is the count. */
    CONTAINER,
    /** Subject is an item material at a location; amount is the count. */
    ITEM,
    /** Subject is an entity type. */
    ENTITY,
    /** No subject; the payload is text. */
    MESSAGE,
    /** No subject. */
    SESSION,
    /** No subject; the payload is the player's inventory and {@code amount} the number of items in it. */
    SNAPSHOT;

    /**
     * Whether the subject column refers to a value in the {@code type} dictionary.
     *
     * @return {@code false} for message, session and snapshot actions
     */
    public boolean hasSubject() {
        return this != MESSAGE && this != SESSION && this != SNAPSHOT;
    }
}
