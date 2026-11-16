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
    private String blockGrow = "<dark_green>grew</dark_green> <white><target></white>";
    private String containerDeposit = "<green>added</green> <white><amount>x <target></white>";
    private String containerWithdraw = "<red>removed</red> <white><amount>x <target></white>";
    private String itemDrop = "<gray>dropped</gray> <white><amount>x <target></white>";
    private String itemPickup = "<gray>picked up</gray> <white><amount>x <target></white>";
    private String itemCraft = "<green>crafted</green> <white><amount>x <target></white>";
    private String itemThrow = "<gray>threw</gray> <white><target></white>";
    private String itemBreak = "<red>wore out</red> <white><target></white>";
    private String itemTrade = "<yellow>bought</yellow> <white><amount>x <target></white>";
    private String entityKill = "<red>killed</red> <white><target></white>";
    private String entitySpawn = "<green>placed</green> <white><target></white>";
    private String entityRemove = "<red>removed</red> <white><target></white>";
    private String entityShear = "<yellow>sheared</yellow> <white><target></white>";
    private String entityName = "<yellow>named</yellow> <white><target></white> <gray><text></gray>";
    private String entityLeash = "<yellow>leashed</yellow> <white><target></white>";
    private String entityUnleash = "<yellow>unleashed</yellow> <white><target></white>";
    private String entityTame = "<yellow>tamed</yellow> <white><target></white>";
    private String entityDye = "<yellow>dyed</yellow> <white><target></white> <gray><text></gray>";
    private String chat = "<gray>said</gray> <white><text></white>";
    private String command = "<gray>ran</gray> <white><text></white>";
    private String bookEdit = "<gray>wrote a book:</gray> <white><text></white>";
    private String gamemodeChange = "<yellow>switched to</yellow> <white><text></white>";
    private String join = "<green>joined</green>";
    private String quit = "<red>left</red>";

    @Comment("<action> for an action this version does not know (written by a newer Hawkeye).")
    private String unknown = "<gray>did something</gray>";
}
