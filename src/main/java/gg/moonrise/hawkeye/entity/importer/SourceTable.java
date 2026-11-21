package gg.moonrise.hawkeye.entity.importer;

import org.jetbrains.annotations.NotNull;

/**
 * The CoreProtect event tables an import reads, in merge order: of two rows with the same time, the one
 * of the earlier constant is read first.
 */
public enum SourceTable {
    BLOCK("block", false),
    SIGN("sign", false),
    CONTAINER("container", false),
    ENTITY_CONTAINER("entity_container", true),
    ITEM("item", false),
    ENTITY_INTERACTION("entity_interaction", true),
    CHAT("chat", false),
    COMMAND("command", false),
    SESSION("session", false);

    private final String tableName;
    private final boolean optional;

    SourceTable(@NotNull String tableName, boolean optional) {
        this.tableName = tableName;
        this.optional = optional;
    }

    /**
     * The table's name without the prefix.
     *
     * @return the name, e.g. {@code block}
     */
    public @NotNull String tableName() {
        return tableName;
    }

    /**
     * Whether older CoreProtect versions lack the table.
     *
     * @return {@code true} for a table added in a later version
     */
    public boolean optional() {
        return optional;
    }
}
