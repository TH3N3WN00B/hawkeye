package gg.moonrise.hawkeye.config;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import lombok.Getter;

/**
 * The {@code config.yml} model. ConfigLib derives every key from the field name in lower kebab case
 * ({@code greetingsEnabled} is {@code greetings-enabled}), so renaming or reordering a field changes the
 * operator's file: treat it as a data migration. Every player-facing text lives here as MiniMessage,
 * never as a literal in code.
 */
@Getter
@Configuration
public final class HawkeyeConfig {

    @Comment("Greets players when they join and allows /hawkeye greet.")
    private boolean greetingsEnabled = true;

    @Comment("Player-facing messages, in MiniMessage format (https://docs.advntr.dev/minimessage/format).")
    private Messages messages = new Messages();

    /** The {@code messages} section. */
    @Getter
    @Configuration
    public static final class Messages {

        @Comment("Sent to a player on their first join. Placeholder: <player>.")
        private String firstJoin = "<green>Welcome to the server, <player>!";

        @Comment("Sent to a returning player. Placeholder: <player>.")
        private String welcomeBack = "<gray>Welcome back, <player>.";

        @Comment("Reply to /hawkeye. Placeholders: <name>, <version>.")
        private String info = "<gray><name> <white><version></white>";

        @Comment("Reply to /hawkeye reload when the file was read.")
        private String reloaded = "<green>Configuration reloaded.";

        @Comment("Reply to /hawkeye reload when the file is broken. Placeholder: <reason>.")
        private String reloadFailed = "<red>The configuration could not be reloaded and the previous one stays "
                + "active: <reason>";

        @Comment("Reply to /hawkeye greet. Placeholder: <player>.")
        private String greeted = "<gray>Greeted <player>.";

        @Comment("Reply to /hawkeye greet while greetings-enabled is false.")
        private String greetingsDisabled = "<gray>Greetings are turned off in config.yml.";

        @Comment("Reply when no online player has the given name. Placeholder: <player>.")
        private String playerNotFound = "<red>No online player is called <player>.";
    }
}
