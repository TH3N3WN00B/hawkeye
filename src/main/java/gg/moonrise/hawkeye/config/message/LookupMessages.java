package gg.moonrise.hawkeye.config.message;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import lombok.Getter;

/** The {@code lookup} section of {@code messages.yml}. */
@Getter
@Configuration
public final class LookupMessages {

    @Comment("First line of a results page. Placeholders: <page>, <pages>, <total>.")
    private String header = "<#7dd3fc>----- Hawkeye</#7dd3fc> <gray>results</gray> <#7dd3fc>-----";

    @Comment({"One result. Placeholders: <time> (e.g. 3h12m), <date> (hover), <actor>, <action> (from the actions",
            "section), <location> (clickable), <server>, <world>, <x>, <y>, <z>, <rolled-back> (the marker below",
            "or nothing)."})
    private String line = "<gray><hover:show_text:'<date>'><time></hover></gray> <rolled-back><white><actor></white> "
            + "<action> <dark_gray><location></dark_gray>";

    @Comment("<location> in a line. Clicking it teleports (permission hawkeye.teleport). Placeholders: <world>, <x>,"
            + " <y>, <z>, <server>.")
    private String location = "<click:run_command:'/hawkeye tp <world> <x> <y> <z>'><hover:show_text:'<gray>Teleport"
            + "</gray>'>(<x>/<y>/<z>/<world>)</hover></click>";

    @Comment("<location> for an event on another server. Placeholders: <world>, <x>, <y>, <z>, <server>.")
    private String remoteLocation = "(<x>/<y>/<z>/<world>@<server>)";

    @Comment("<rolled-back> on a line whose change was rolled back.")
    private String rolledBackMarker = "<strikethrough>";

    @Comment("Last line of a page. Placeholders: <page>, <pages>, <previous>, <next> (clickable when they exist).")
    private String footer = "<gray>Page <white><page></white>/<white><pages></white></gray> <previous> <next>";

    @Comment("<previous> when there is a previous page.")
    private String previous = "<click:run_command:'/hawkeye page <page>'><#7dd3fc>[< prev]</#7dd3fc></click>";

    @Comment("<next> when there is a next page.")
    private String next = "<click:run_command:'/hawkeye page <page>'><#7dd3fc>[next >]</#7dd3fc></click>";

    @Comment("Reply when nothing matches.")
    private String noResults = "<gray>No results found.";

    @Comment("Reply to a #count lookup. Placeholder: <total>.")
    private String count = "<gray>Matching events: <white><total></white>";

    @Comment("Reply to /hawkeye page without a recent lookup.")
    private String noSession = "<red>You have no recent lookup to page through.";

    @Comment("Reply to /hawkeye page with a page beyond the last. Placeholder: <pages>.")
    private String pageOutOfRange = "<red>There are only <pages> page(s).";

    @Comment("Reply when a lookup runs past lookup.timeout-seconds.")
    private String timedOut = "<red>The lookup took too long; narrow it with t:, r: or u:.";

    @Comment("Reply when a network lookup could not read a shard. Placeholders: <shard>, <reason>.")
    private String shardUnavailable = "<yellow>Shard <shard> could not be read: <reason>";
}
