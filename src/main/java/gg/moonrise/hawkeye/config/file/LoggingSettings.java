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

    @Comment("Endermen, falling blocks, trampled farmland, sheep eating grass, wither and dragon damage.")
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
