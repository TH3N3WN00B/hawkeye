package gg.moonrise.hawkeye.config.message;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import lombok.Getter;

/** The {@code importing} section of {@code messages.yml}: {@code /hawkeye import}. */
@Getter
@Configuration
public final class ImportMessages {

    @Comment("Reply when an import of a CoreProtect database begins. Placeholder: <source>.")
    private String started = "<gray>Importing CoreProtect history from <white><source></white>. Use "
            + "<white>/he import status</white> to follow it.";

    @Comment("Reply when an import continues an earlier one of the same database. Placeholders: <source>, <imported>.")
    private String resumed = "<gray>Continuing the CoreProtect import from <white><source></white>: "
            + "<white><imported></white> event(s) were imported before.";

    @Comment({"Sent about every 30 seconds while an import runs. Placeholders: <events> (this run), <total> (this",
            "database in all), <percent> (of the database read), <rate> (events per second), <duration>."})
    private String progress = "<gray>Import: <white><events></white> event(s), <white><percent>%</white> of the "
            + "database read, <white><rate></white> events/s.";

    @Comment({"Reply to /hawkeye import status while an import runs. Placeholders: <source>, <events>, <total>,",
            "<percent>, <rate>, <duration>."})
    private String status = "<gray>Importing from <white><source></white> for <white><duration></white>: "
            + "<white><events></white> event(s) (<total> in all), <white><percent>%</white> read, "
            + "<white><rate></white> events/s.";

    @Comment("Reply when an import has read the whole database. Placeholders: <source>, <events>, <total>, <duration>.")
    private String finished = "<green>Import done:</green> <gray><white><events></white> event(s) imported from "
            + "<source> in <duration> (<total> from this database in all).";

    @Comment({"Sent after an import that skipped rows (only then). Placeholders: <skipped> (all), <old> (before",
            "2025-01-01, or stamped in the future), <no-equivalent>, <folded> (sign snapshots another event",
            "carries), <unknown> (an unknown world, material, entity or position)."})
    private String skipped = "<gray>Skipped <white><skipped></white> row(s): <white><old></white> older than "
            + "2025-01-01, which Hawkeye cannot store; <white><no-equivalent></white> with no Hawkeye equivalent; "
            + "<white><folded></white> sign snapshots kept in other events; <white><unknown></white> with an unknown "
            + "world, material, entity or position.";

    @Comment("Sent after an import that stored events later than they happened (only then). Placeholder: <displaced>.")
    private String displaced = "<gray><white><displaced></white> event(s) were older than history already imported "
            + "and are stored up to a few seconds later than they happened.";

    @Comment("Reply when a cancelled import has stopped. Placeholders: <events>, <total>, <duration>.")
    private String cancelled = "<yellow>Import stopped after <white><events></white> event(s).</yellow> <gray>Run "
            + "<white>/he import coreprotect</white> again to continue where it stopped.";

    @Comment("Reply to /hawkeye import cancel while an import runs.")
    private String cancelling = "<gray>Stopping the import after the current batch...";

    @Comment("Reply when an import is started while another runs.")
    private String alreadyRunning = "<red>An import is already running; see <white>/he import status</white>.";

    @Comment("Reply to /hawkeye import status and cancel when no import runs.")
    private String notRunning = "<gray>No import is running.";

    @Comment("Reply when no CoreProtect database is where the command points. Placeholder: <path>.")
    private String sourceMissing = "<red>No CoreProtect database found at <white><path></white>.";

    @Comment("Reply when CoreProtect's config.yml cannot be used. Placeholder: <reason>.")
    private String sourceInvalid = "<red>That CoreProtect configuration cannot be used: <reason>";

    @Comment("Reply when the database is a DuckDB file and CoreProtect runs on this server.")
    private String coreProtectRunning = "<red>CoreProtect is running on this server and keeps its DuckDB file open. "
            + "Remove or disable CoreProtect, restart, then import again.";

    @Comment("Reply when CoreProtect has not finished migrating the database.")
    private String sourceMigrating = "<red>CoreProtect has not finished migrating this database. Let it finish, "
            + "then import again.";

    @Comment("Reply when the database was imported before in another scan order (it moved to or from ClickHouse).")
    private String orderChanged = "<red>This database was imported before from another engine (it moved to or from "
            + "ClickHouse), so the import cannot continue where it stopped.";

    @Comment("Reply when another server is importing the same database.")
    private String superseded = "<red>Another server is importing this CoreProtect database; this import stopped. "
            + "Nothing was imported twice.";
}
