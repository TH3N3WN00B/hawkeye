package gg.moonrise.hawkeye.config.file;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import java.util.List;
import java.util.Locale;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code logging} section: one switch per {@link LogCategory}, plus exclusions.
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Configuration
public final class LoggingSettings {

    private boolean blockBreak = true;

    private boolean blockPlace = true;

    @Comment("TNT, creepers, beds, respawn anchors and other explosions.")
    private boolean explosions = true;

    @Comment("Fire burning blocks and blocks being lit.")
    private boolean fire = true;

    @Comment("Water and lava flow (the busiest category; the cause is #water or #lava).")
    private boolean fluids = true;

    private boolean pistons = true;

    @Comment("Leaf decay, tree and crop growth, snow and ice forming, melting, grass and sculk spreading.")
    private boolean natural = true;

    private boolean buckets = true;

    private boolean signs = true;

    @Comment("Doors, trapdoors, gates, levers, buttons, repeaters, comparators, note blocks.")
    private boolean interactions = true;

    @Comment({"Endermen, falling blocks, trampled farmland, sheep eating grass, wither and dragon damage, turtle",
            "eggs cracked by players and mobs."})
    private boolean entityChanges = true;

    @Comment("Players putting items into and taking them out of containers.")
    private boolean containers = true;

    @Comment("Hoppers, droppers and hopper minecarts moving items (high volume).")
    private boolean hoppers = false;

    private boolean itemDrops = true;

    private boolean itemPickups = true;

    private boolean kills = true;

    private boolean chat = true;

    private boolean commands = true;

    private boolean sessions = true;

    @Comment({"Crops and plants growing. Recorded compactly: a ripe crop and a newly grown block (melon, pumpkin,",
            "sugar cane, cactus, vines) each make one event under #growth, and rollbacks never change them."})
    private boolean cropGrowth = true;

    @Comment("Also record every intermediate growth stage of crops (several events per crop; off keeps it compact).")
    private boolean cropGrowthStages = false;

    @Comment("Blocks and liquids placed or picked up by dispensers (#dispenser).")
    private boolean dispensers = true;

    @Comment({"Entities placed, spawned or destroyed: armour stands, boats, minecarts, paintings, item frames,",
            "end crystals, spawn eggs, built golems and withers, mobs caught in buckets."})
    private boolean entities = true;

    @Comment("Players shearing, naming, leashing, taming and dyeing entities.")
    private boolean entityInteractions = true;

    private boolean crafting = true;

    @Comment("Items thrown or shot (snowballs, eggs, pearls, tridents, potions) and tools that broke.")
    private boolean itemUse = true;

    private boolean trades = true;

    @Comment("Books written and signed (the text is stored).")
    private boolean books = true;

    private boolean gamemodes = true;

    @Comment("Commands run from the server console (not command blocks).")
    private boolean consoleCommands = true;

    @Comment({"Blame flowing water and lava on the player who poured it (and on its flow), instead of #water or",
            "#lava, so /hawkeye rollback u:<player> also removes the flow."})
    private boolean liquidTracking = true;

    @Comment({"Record blocks that fall off when their support is removed (torches, signs, rails, flowers, sugar",
            "cane, ...) under whoever removed the support."})
    private boolean attachedBlocks = true;

    @Comment("World names where nothing is recorded.")
    private List<String> disabledWorlds = List.of();

    @Comment("Commands recorded without their arguments, so passwords never reach the database.")
    private List<String> redactedCommands = List.of("login", "l", "register", "reg", "changepassword", "changepass",
            "unregister", "2fa", "totp");

    /**
     * Whether a category is recorded.
     *
     * @param category the category
     * @return its switch
     */
    public boolean enabled(@NotNull LogCategory category) {
        return switch (category) {
            case BLOCK_BREAK -> blockBreak;
            case BLOCK_PLACE -> blockPlace;
            case EXPLOSIONS -> explosions;
            case FIRE -> fire;
            case FLUIDS -> fluids;
            case PISTONS -> pistons;
            case NATURAL -> natural;
            case BUCKETS -> buckets;
            case SIGNS -> signs;
            case INTERACTIONS -> interactions;
            case ENTITY_CHANGES -> entityChanges;
            case CONTAINERS -> containers;
            case HOPPERS -> hoppers;
            case ITEM_DROPS -> itemDrops;
            case ITEM_PICKUPS -> itemPickups;
            case KILLS -> kills;
            case CHAT -> chat;
            case COMMANDS -> commands;
            case SESSIONS -> sessions;
            case CROP_GROWTH -> cropGrowth;
            case DISPENSERS -> dispensers;
            case ENTITIES -> entities;
            case ENTITY_INTERACTIONS -> entityInteractions;
            case CRAFTING -> crafting;
            case ITEM_USE -> itemUse;
            case TRADES -> trades;
            case BOOKS -> books;
            case GAMEMODES -> gamemodes;
            case CONSOLE_COMMANDS -> consoleCommands;
        };
    }

    /**
     * Whether a world is excluded.
     *
     * @param world the world name
     * @return {@code true} when nothing is recorded there
     */
    public boolean worldDisabled(@NotNull String world) {
        return disabledWorlds.contains(world);
    }

    /**
     * Whether a command's arguments are hidden.
     *
     * @param label the command label without the slash, any case, possibly namespaced
     * @return {@code true} when only the label is recorded
     */
    public boolean redacts(@NotNull String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        String name = colon >= 0 ? lower.substring(colon + 1) : lower;
        return redactedCommands.stream().anyMatch(command -> command.equalsIgnoreCase(name));
    }
}
