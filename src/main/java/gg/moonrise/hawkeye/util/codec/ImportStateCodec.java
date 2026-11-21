package gg.moonrise.hawkeye.util.codec;

import gg.moonrise.hawkeye.entity.importer.ImportState;
import gg.moonrise.hawkeye.entity.importer.SkipReason;
import gg.moonrise.hawkeye.entity.importer.source.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.source.SourceTable;
import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.jetbrains.annotations.NotNull;

/**
 * The text form of an {@link ImportState} for the {@code state} column of {@code import_state}: one
 * {@code key=value} per line, readable in any SQL client. The revision and the update time live in their
 * own columns and are not part of the text.
 *
 * <pre>
 * v=1
 * node=survival-coreprotect
 * order=rowid
 * cursor.block=1735689600:812
 * newest-slot=31622400
 * imported=812
 * displaced=0
 * skipped.no_equivalent=3
 * complete=false
 * started=1767225600000
 * </pre>
 *
 * <p>Unknown keys, and cursors or counts of tables and reasons this build does not know, are ignored, so
 * a later build can add fields without changing the format version; only an incompatible change raises
 * {@code v}. Strings are URL-encoded.
 */
public final class ImportStateCodec {

    private static final String VERSION = "1";
    private static final String CURSOR = "cursor.";
    private static final String SKIPPED = "skipped.";

    private ImportStateCodec() {
    }

    /**
     * Encodes a state, without its revision and update time.
     *
     * @param state the state
     * @return the text
     */
    public static @NotNull String encode(@NotNull ImportState state) {
        StringJoiner out = new StringJoiner("\n");
        out.add("v=" + VERSION);
        out.add("node=" + escape(state.node()));
        out.add("order=" + escape(state.order()));
        for (SourceTable table : SourceTable.values()) {
            SourceCursor cursor = state.cursors().get(table);
            if (cursor != null) {
                out.add(CURSOR + key(table) + "=" + cursor.time() + ":" + cursor.rowId());
            }
        }
        out.add("newest-slot=" + state.newestSlot());
        out.add("imported=" + state.imported());
        out.add("displaced=" + state.displaced());
        for (SkipReason reason : SkipReason.values()) {
            long count = state.skipped(reason);
            if (count != 0) {
                out.add(SKIPPED + key(reason) + "=" + count);
            }
        }
        out.add("complete=" + state.complete());
        out.add("started=" + state.startedMillis());
        return out.toString();
    }

    /**
     * Decodes what {@link #encode} wrote.
     *
     * @param text          the text
     * @param revision      the row's revision
     * @param updatedMillis the row's update time
     * @return the state
     * @throws CodecException when the version is unknown or a required value is missing or malformed
     */
    public static @NotNull ImportState decode(@NotNull String text, long revision, long updatedMillis) {
        Map<String, String> fields = new HashMap<>();
        for (String line : text.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                fields.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        if (!VERSION.equals(fields.get("v"))) {
            throw new CodecException("unsupported import state version " + fields.get("v"));
        }
        Map<SourceTable, SourceCursor> cursors = new EnumMap<>(SourceTable.class);
        Map<SkipReason, Long> skipped = new EnumMap<>(SkipReason.class);
        try {
            fields.forEach((key, value) -> {
                if (key.startsWith(CURSOR)) {
                    table(key.substring(CURSOR.length())).ifPresent(table -> cursors.put(table, cursor(value)));
                } else if (key.startsWith(SKIPPED)) {
                    reason(key.substring(SKIPPED.length())).ifPresent(reason -> skipped.put(reason,
                            Long.parseLong(value)));
                }
            });
            return new ImportState(revision, unescape(require(fields, "node")), unescape(require(fields, "order")),
                    cursors, Long.parseLong(require(fields, "newest-slot")), Long.parseLong(require(fields, "imported")),
                    Long.parseLong(require(fields, "displaced")), skipped,
                    Boolean.parseBoolean(require(fields, "complete")), Long.parseLong(require(fields, "started")),
                    updatedMillis);
        } catch (IllegalArgumentException e) {
            throw new CodecException("corrupt import state: " + e.getMessage(), e);
        }
    }

    private static @NotNull SourceCursor cursor(@NotNull String value) {
        int colon = value.indexOf(':');
        if (colon < 0) {
            throw new CodecException("cursor without a rowid: " + value);
        }
        return new SourceCursor(Long.parseLong(value.substring(0, colon)), Long.parseLong(value.substring(colon + 1)));
    }

    private static @NotNull String key(@NotNull Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }

    private static @NotNull Optional<SourceTable> table(@NotNull String key) {
        for (SourceTable table : SourceTable.values()) {
            if (key(table).equals(key)) {
                return Optional.of(table);
            }
        }
        return Optional.empty();
    }

    private static @NotNull Optional<SkipReason> reason(@NotNull String key) {
        for (SkipReason reason : SkipReason.values()) {
            if (key(reason).equals(key)) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }

    private static @NotNull String escape(@NotNull String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static @NotNull String unescape(@NotNull String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static @NotNull String require(@NotNull Map<String, String> fields, @NotNull String key) {
        String value = fields.get(key);
        if (value == null) {
            throw new CodecException("import state is missing " + key);
        }
        return value;
    }
}
