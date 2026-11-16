package gg.moonrise.hawkeye.config.message;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import java.util.List;
import lombok.Getter;

/** The {@code status} section of {@code messages.yml}. */
@Getter
@Configuration
public final class StatusMessages {

    @Comment({"Reply to /hawkeye status. Placeholders: <version>, <server>, <node>, <storage>, <state>, <queued>,",
            "<written>, <dropped>, <batches>, <last-flush-ms>, <spooled>, <archived>, <segments>, <jobs-running>,",
            "<jobs-waiting>."})
    private List<String> status = List.of(
            "<#7dd3fc>----- Hawkeye <version> -----",
            "<gray>Server: <white><server></white> (node <node>) on <white><storage></white>: <state>",
            "<gray>Queue: <white><queued></white> waiting, <white><written></white> written in <batches> batch(es), "
                    + "last flush <last-flush-ms> ms",
            "<gray>Dropped: <white><dropped></white>, spooled to disk: <white><spooled></white>",
            "<gray>Archive: <white><archived></white> event(s) in <white><segments></white> segment(s); network jobs: "
                    + "<white><jobs-running></white> running, <white><jobs-waiting></white> awaited");

    @Comment("<state> while the database is connected.")
    private String stateReady = "<green>connected</green>";

    @Comment("<state> while the database is connecting.")
    private String stateConnecting = "<yellow>connecting</yellow>";

    @Comment("<state> while the database is unreachable. Placeholder: <reason>.")
    private String stateFailed = "<red>unavailable (<reason>)</red>";

    @Comment("First line of /hawkeye network.")
    private String networkHeader = "<#7dd3fc>----- Hawkeye network -----";

    @Comment("One server in /hawkeye network. Placeholders: <shard>, <server>, <node>, <seen> (time since its last"
            + " heartbeat), <state>.")
    private String networkServer = "<gray><shard>/<white><server></white> (node <node>): <state> <dark_gray>"
            + "(<seen> ago)";

    @Comment("<state> of a server with a recent heartbeat.")
    private String serverOnline = "<green>online</green>";

    @Comment("<state> of a server without a recent heartbeat.")
    private String serverOffline = "<red>offline</red>";
}
