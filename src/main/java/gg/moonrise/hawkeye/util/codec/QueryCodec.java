package gg.moonrise.hawkeye.util.codec;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.entity.query.ServerScope;
import gg.moonrise.hawkeye.entity.query.TimeWindow;
import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

/**
 * A text form of a {@link LookupQuery} for a network job row: one {@code key=value} per line, values
 * URL-encoded. Absolute times are kept, so the target applies exactly the window the issuer asked for.
 * The server scope is not stored: a job always runs on its target server. Versioned ({@code v=1}).
 */
public final class QueryCodec {

    private static final String VERSION = "1";

    private QueryCodec() {
    }

    /**
     * Encodes a query.
     *
     * @param query the query
     * @return the text
     */
    public static @NotNull String encode(@NotNull LookupQuery query) {
        StringJoiner out = new StringJoiner("\n");
        out.add("v=" + VERSION);
        out.add("from=" + query.time().fromMillis());
        out.add("to=" + query.time().toMillis());
        out.add("area=" + area(query.area()));
        out.add("u=" + list(query.actors()));
        out.add("eu=" + list(query.excludedActors()));
        out.add("a=" + query.actions().stream().map(action -> Integer.toString(action.code()))
                .sorted().collect(Collectors.joining(",")));
        out.add("i=" + list(query.includedTypes()));
        out.add("ei=" + list(query.excludedTypes()));
        out.add("state=" + query.rollbackState().name());
        return out.toString();
    }

    /**
     * Decodes what {@link #encode} wrote, as a query on the local server.
     *
     * @param text the text
     * @return the query
     * @throws CodecException when the text is not a valid encoded query
     */
    public static @NotNull LookupQuery decode(@NotNull String text) {
        Map<String, String> fields = new HashMap<>();
        for (String line : text.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                fields.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        if (!VERSION.equals(fields.get("v"))) {
            throw new CodecException("unsupported query version " + fields.get("v"));
        }
        try {
            TimeWindow time = new TimeWindow(Long.parseLong(require(fields, "from")),
                    Long.parseLong(require(fields, "to")));
            Set<Action> actions = Arrays.stream(require(fields, "a").split(","))
                    .filter(code -> !code.isEmpty())
                    .map(code -> Action.byCode(Integer.parseInt(code))
                            .orElseThrow(() -> new CodecException("unknown action " + code)))
                    .collect(Collectors.toSet());
            return new LookupQuery(new ServerScope.Local(), area(require(fields, "area")), time,
                    set(require(fields, "u")), set(require(fields, "eu")), actions, set(require(fields, "i")),
                    set(require(fields, "ei")), RollbackState.valueOf(require(fields, "state")));
        } catch (IllegalArgumentException e) {
            throw new CodecException("corrupt query: " + e.getMessage(), e);
        }
    }

    private static @NotNull String area(@NotNull Area area) {
        return switch (area) {
            case Area.Everywhere everywhere -> "everywhere";
            case Area.World world -> "world:" + escape(world.world());
            case Area.Box box -> "box:" + escape(box.world()) + ":" + box.minX() + ":" + box.minY() + ":" + box.minZ()
                    + ":" + box.maxX() + ":" + box.maxY() + ":" + box.maxZ();
            case Area.Block block -> "block:" + escape(block.world()) + ":" + block.x() + ":" + block.y() + ":"
                    + block.z();
        };
    }

    private static @NotNull Area area(@NotNull String text) {
        String[] parts = text.split(":");
        return switch (parts[0]) {
            case "everywhere" -> new Area.Everywhere();
            case "world" -> new Area.World(unescape(parts, 1));
            case "box" -> new Area.Box(unescape(parts, 1), integer(parts, 2), integer(parts, 3), integer(parts, 4),
                    integer(parts, 5), integer(parts, 6), integer(parts, 7));
            case "block" -> new Area.Block(unescape(parts, 1), integer(parts, 2), integer(parts, 3), integer(parts, 4));
            default -> throw new CodecException("unknown area " + parts[0]);
        };
    }

    private static @NotNull String list(@NotNull Collection<String> values) {
        return values.stream().sorted().map(QueryCodec::escape).collect(Collectors.joining(","));
    }

    private static @NotNull Set<String> set(@NotNull String text) {
        return Arrays.stream(text.split(",")).filter(value -> !value.isEmpty())
                .map(value -> URLDecoder.decode(value, StandardCharsets.UTF_8)).collect(Collectors.toSet());
    }

    private static @NotNull String escape(@NotNull String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static @NotNull String unescape(@NotNull String[] parts, int index) {
        if (index >= parts.length) {
            throw new CodecException("area is missing part " + index);
        }
        return URLDecoder.decode(parts[index], StandardCharsets.UTF_8);
    }

    private static int integer(@NotNull String[] parts, int index) {
        if (index >= parts.length) {
            throw new CodecException("area is missing part " + index);
        }
        return Integer.parseInt(parts[index]);
    }

    private static @NotNull String require(@NotNull Map<String, String> fields, @NotNull String key) {
        String value = fields.get(key);
        if (value == null) {
            throw new CodecException("query is missing " + key);
        }
        return value;
    }
}
