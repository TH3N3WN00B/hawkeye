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

    @Comment("<pages> and <total> when the matching events could not be counted.")
    private String unknownTotal = "?";

    @Comment({"<pages> and <total> of a large result when they are lower bounds: opening a lookup counts at most",
            "16 archive segments; #count gives the exact number. Placeholder: <value>."})
    private String atLeast = "<value>+";

    @Comment({"<contents> of an inventory snapshot. Placeholders: <stacks> (occupied slots), <amount> (items in",
            "all of them). Its hover lists the items (snapshot-item, one line each)."})
    private String snapshotContents = "<white><amount> items</white> <#7dd3fc>[<stacks> stacks]</#7dd3fc>";

    @Comment({"One line of a snapshot's hover. Placeholders: <amount>, <target> (the item), <slot> (0-35 storage,",
            "36-39 armour, 40 off hand, 41 cursor)."})
    private String snapshotItem = "<white><amount>x <target></white>";

    @Comment("The hover of a snapshot without items.")
    private String snapshotEmpty = "<gray>empty";

    @Comment({"The hover of <target> on an item with a name, enchantments or a book lists the item's id, then",
            "these lines. The item's custom name. Placeholder: <name>."})
    private String itemName = "<gray>Name:</gray> <white><name></white>";

    @Comment("One enchantment (stored enchantments of an enchanted book too). Placeholders: <enchantment>, <level>.")
    private String itemEnchantment = "<gray><enchantment> <level>";

    @Comment("A written book. Placeholders: <title>, <author>, <pages>.")
    private String itemBook = "<white><title></white> <gray>by <author>, <pages> pages";

    @Comment("A book and quill. Placeholder: <pages>.")
    private String itemWritableBook = "<gray>Book and quill, <pages> pages";

    @Comment("Last line of the hover of a book with pages: clicking <target> opens the book.")
    private String itemReadHint = "<#7dd3fc>Click to read";
}
