package gg.moonrise.hawkeye.config.message;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import lombok.Getter;

/** The {@code purge} section of {@code messages.yml}. */
@Getter
@Configuration
public final class PurgeMessages {

    @Comment("Reply when a purge starts. Placeholder: <age>.")
    private String started = "<gray>Deleting events older than <white><age></white>...";

    @Comment("Reply when a purge is done. Placeholders: <events>, <seconds>.")
    private String finished = "<green>Purge done:</green> <gray><events> event(s) deleted in <seconds>s.";

    @Comment("Reply when a player purges data younger than purge.player-minimum-days. Placeholder: <days>.")
    private String tooRecent = "<red>In game you may only purge data older than <days> days.";

    @Comment("Reply when a purge is already running.")
    private String alreadyRunning = "<red>A purge is already running.";

    @Comment("Reply when a purge is given anything but one t:<age> and optionally r:#<world>.")
    private String unsupportedParameters = "<red>Purge takes one age, e.g. <white>t:30d</white>, and optionally "
            + "<white>r:#<world></white>; nothing else.";
}
