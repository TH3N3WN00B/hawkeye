package gg.moonrise.hawkeye.config.message;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import lombok.Getter;

/**
 * The {@code actions} section of {@code messages.yml}: the {@code <action>} placeholder of a result
 * line. Placeholders in every entry: {@code <target>} (block, item or entity name, with a hover
 * showing the full state), {@code <amount>}, {@code <text>} (chat, commands, sign lines).
 */
@Getter
@Configuration
public final class ActionMessages {

    private String blockBreak = "<red>broke</red> <white><target></white>";
    private String blockPlace = "<green>placed</green> <white><target></white>";
    private String blockChange = "<yellow>changed</yellow> <white><target></white>";
    private String signEdit = "<yellow>wrote</yellow> <white><text></white>";
    private String interact = "<gray>used</gray> <white><target></white>";
    private String containerDeposit = "<green>added</green> <white><amount>x <target></white>";
    private String containerWithdraw = "<red>removed</red> <white><amount>x <target></white>";
    private String itemDrop = "<gray>dropped</gray> <white><amount>x <target></white>";
    private String itemPickup = "<gray>picked up</gray> <white><amount>x <target></white>";
    private String entityKill = "<red>killed</red> <white><target></white>";
    private String chat = "<gray>said</gray> <white><text></white>";
    private String command = "<gray>ran</gray> <white><text></white>";
    private String join = "<green>joined</green>";
    private String quit = "<red>left</red>";

    @Comment("<action> for an action this version does not know (written by a newer Hawkeye).")
    private String unknown = "<gray>did something</gray>";
}
