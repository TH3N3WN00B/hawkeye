package gg.moonrise.hawkeye.config.message;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import lombok.Getter;

/**
 * The {@code query} section of {@code messages.yml}: why a parameter list was refused. Every entry
 * takes {@code <value>} (the offending token, or the limit involved).
 */
@Getter
@Configuration
public final class QueryMessages {

    private String unknownParameter = "<red>Unknown parameter <white><value></white>. Use u: t: r: a: i: e: s:.";
    private String invalidTime = "<red><white><value></white> is not a time; use e.g. <white>t:2h</white>, "
            + "<white>t:3d12h</white> or <white>t:1h-2d</white>.";
    private String invalidRadius = "<red><white><value></white> is not a radius; use e.g. <white>r:10</white>, "
            + "<white>r:10x5</white>, <white>r:#world</white> or <white>r:#global</white>.";
    private String unknownAction = "<red><white><value></white> is not an action; try block, +block, -block, "
            + "container, item, kill, entity, grow, craft, throw, trade, chat, command, book, session, sign or click.";
    private String unknownType = "<red>There is no block, item or entity called <white><value></white>.";
    private String invalidServer = "<red><white><value></white> is not a server name.";
    private String emptyValue = "<red><white><value></white> needs a value.";
    private String radiusTooLarge = "<red>The largest radius you may use is <white><value></white>.";
    private String radiusNeedsPosition = "<red>A radius needs a position; from the console use r:#<world> or "
            + "r:#global.";
    private String unknownWorld = "<red>This server has no world called <white><value></white>.";
    private String networkNotAllowed = "<red>You may not read or change other servers (s:).";
    private String areaWithServers = "<red>A radius only works on this server; use r:#<world> or r:#global with "
            + "s:.";
}
