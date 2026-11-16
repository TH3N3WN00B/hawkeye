package gg.moonrise.hawkeye.config.message;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import lombok.Getter;

/** The {@code rollback} section of {@code messages.yml}. */
@Getter
@Configuration
public final class RollbackMessages {

    @Comment("Reply when a rollback or restore starts. Placeholders: <mode> (rollback/restore), <events>.")
    private String started = "<gray>Starting <mode> of <white><events></white> event(s)...";

    @Comment("Progress, at most every few seconds. Placeholders: <mode>, <done>, <total>, <percent>.")
    private String progress = "<gray><mode>: <white><percent>%</white> (<done>/<total> chunks)";

    @Comment({"Reply when a rollback or restore is done. Placeholders: <mode>, <blocks>, <items>, <entities>,",
            "<seconds>."})
    private String finished = "<green><mode> done:</green> <gray><blocks> block(s), <items> item stack(s), "
            + "<entities> entit(ies) in <seconds>s.";

    @Comment("Reply when nothing matched.")
    private String nothingToDo = "<gray>Nothing to <mode>.";

    @Comment("Reply when a rollback would change more than rollback.max-events. Placeholders: <events>, <max>.")
    private String tooLarge = "<red>That would change <events> events (limit <max>); narrow it with t:, r: or u:.";

    @Comment("Reply when the console runs a rollback without an area.")
    private String areaRequired = "<red>Give an area: r:<radius> needs a player; from the console use r:#<world> or "
            + "r:#global.";

    @Comment("Reply when a preview is shown. Placeholders: <blocks>, <minutes>.")
    private String previewShown = "<gray>Previewing <white><blocks></white> block(s). "
            + "<click:run_command:'/hawkeye apply'><green>[apply]</green></click> "
            + "<click:run_command:'/hawkeye cancel'><red>[cancel]</red></click> <dark_gray>(expires in <minutes>m)";

    @Comment("Reply to /hawkeye apply or cancel without a preview.")
    private String noPreview = "<red>You have no preview to apply or cancel.";

    @Comment("Reply to /hawkeye cancel.")
    private String previewCancelled = "<gray>Preview cancelled.";

    @Comment("Reply to /hawkeye undo without a previous rollback or restore.")
    private String nothingToUndo = "<red>You have no rollback or restore to undo.";

    @Comment("Reply when another rollback by the same sender is still running.")
    private String alreadyRunning = "<red>Your previous rollback or restore is still running.";

    @Comment("Network job progress. Placeholders: <server>, <mode>, <state>.")
    private String networkJob = "<gray><mode> on <white><server></white>: <state>";

    @Comment("Network job finished on a server. Placeholders: <server>, <mode>, <blocks>, <items>, <entities>.")
    private String networkJobFinished = "<green><mode> done on <server>:</green> <gray><blocks> block(s), "
            + "<items> item stack(s), <entities> entit(ies).";

    @Comment("Network job failed on a server. Placeholders: <server>, <mode>, <reason>.")
    private String networkJobFailed = "<red><mode> failed on <server>: <reason>";
}
