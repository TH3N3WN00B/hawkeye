package gg.moonrise.hawkeye.config.message;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import lombok.Getter;

/** The {@code inspect} section of {@code messages.yml}. */
@Getter
@Configuration
public final class InspectMessages {

    @Comment("Reply when the inspector is turned on.")
    private String enabled = "<#7dd3fc>Inspector on:</#7dd3fc> <gray>left-click a block for its history, right-click "
            + "a container for its transactions, place against a block to see that spot.";

    @Comment("Reply when the inspector is turned off.")
    private String disabled = "<gray>Inspector off.";

    @Comment("First line of an inspector reply. Placeholders: <x>, <y>, <z>, <world>, <page>, <pages>, <total>.")
    private String header = "<#7dd3fc>----- Hawkeye</#7dd3fc> <gray><x> <y> <z></gray> <#7dd3fc>-----";

    @Comment("Reply when a block has no recorded history.")
    private String noHistory = "<gray>No history for this block.";
}
