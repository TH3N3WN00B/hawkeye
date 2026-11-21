package gg.moonrise.hawkeye.core.storage.segment;

import gg.moonrise.hawkeye.core.repository.event.EventFilter;
import gg.moonrise.hawkeye.core.repository.event.EventRow;
import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import gg.moonrise.hawkeye.util.codec.binary.Compression;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.jetbrains.annotations.NotNull;

/**
 * Archive format 2: up to about a million events of any worlds and servers in one segment, stored
 * column by column in stored order (unplaced rows by id, then placed rows by world order, position
 * and id) and cut into units of {@link #UNIT_ROWS} rows, so a lookup about one place decodes one or two
 * units. Ranks are not stored when they follow the writer's numbering. {@link SegmentHeader} describes
 * the header, {@link SharedColumns} and {@link UnitColumns} the body's frames. A selective read
 * matches a filter's column rules first ({@link #match}) and needs ids only for what matched
 * ({@link SegmentMatch}).
 *
 * <p>Input flags are not stored: only {@link EventRow#ROLLED_BACK} is reported, as ordinals in
 * {@link PackedSegment#rolledBack()}, and decoding sets it on the ordinals it is given.
 */
public final class Segments {

    /** Rows per unit (the last unit of each group may hold fewer). */
    public static final int UNIT_ROWS = 16_384;

    private static final Executor DIRECT = Runnable::run;

    private Segments() {
    }

    /**
     * Encodes events on the calling thread.
     *
     * @param rows       the events, in any order, with distinct ids
     * @param worldOrder the world order; every row's world must be in it
     * @return the segment
     * @throws IllegalArgumentException when the rows are empty, an id repeats or a world is missing
     */
    public static @NotNull PackedSegment encode(@NotNull List<EventRow> rows, @NotNull List<Integer> worldOrder) {
        return encode(rows, worldOrder, DIRECT);
    }

    /**
     * Encodes events, compressing the frames on an executor. The bytes equal the two-argument form's.
     *
     * @param rows       the events, in any order, with distinct ids
     * @param worldOrder the world order; every row's world must be in it
     * @param executor   where frames are compressed
     * @return the segment
     * @throws IllegalArgumentException when the rows are empty, an id repeats or a world is missing
     */
    public static @NotNull PackedSegment encode(@NotNull List<EventRow> rows, @NotNull List<Integer> worldOrder,
            @NotNull Executor executor) {
        return encode(rows, worldOrder, executor, Compression.zstdAvailable() ? Compression.ZSTD : Compression.DEFLATE);
    }

    /**
     * Encodes events with a given frame codec (tests and benchmarks force deflate with it).
     *
     * @param rows       the events
     * @param worldOrder the world order
     * @param executor   where frames are compressed
     * @param codec      {@link Compression#ZSTD} or {@link Compression#DEFLATE}
     * @return the segment
     */
    public static @NotNull PackedSegment encode(@NotNull List<EventRow> rows, @NotNull List<Integer> worldOrder,
            @NotNull Executor executor, int codec) {
        return SegmentEncoder.encode(rows, worldOrder, codec, executor);
    }

    /**
     * Decodes every row.
     *
     * @param header     the parsed header
     * @param body       the whole body
     * @param rolledBack ascending ordinals to flag
     * @return the rows, sorted by id
     * @throws CodecException when the body is corrupt
     */
    public static @NotNull List<EventRow> decode(@NotNull SegmentHeader header, byte @NotNull [] body,
            int @NotNull [] rolledBack) {
        if (body.length != header.bodyLength()) {
            throw new CodecException("body is " + body.length + " bytes, the header says " + header.bodyLength());
        }
        checkOrdinals(header, rolledBack);
        SharedColumns shared = SharedColumns.decode(header, body, 0, header.shared().length());
        List<EventRow> rows = new ArrayList<>(header.events());
        for (int u = 0; u < header.unitCount(); u++) {
            ByteRange range = header.unit(u);
            UnitDecoder.decode(header, shared, u, body, range.offset(), range.length(), rolledBack, rows);
        }
        rows.sort(Comparator.comparingLong(EventRow::id));
        return rows;
    }

    /**
     * Decodes the rows of some units.
     *
     * @param header     the parsed header
     * @param shared     the bytes of {@code header.shared()}
     * @param units      the unit numbers
     * @param unitFrames {@code unitFrames.get(i)} is the bytes of {@code header.unit(units[i])}
     * @param rolledBack ascending ordinals to flag
     * @return the rows, unit by unit in the order given, each in stored order
     * @throws CodecException when the frames are corrupt
     */
    public static @NotNull List<EventRow> decodeUnits(@NotNull SegmentHeader header, byte @NotNull [] shared,
            int @NotNull [] units, @NotNull List<byte[]> unitFrames, int @NotNull [] rolledBack) {
        return decodeUnits(header, SharedColumns.decode(header, shared, 0, shared.length), units, unitFrames,
                rolledBack);
    }

    /**
     * Decodes the rows of some units with shared frames decoded already ({@link #shared}).
     *
     * @param header     the parsed header
     * @param shared     the decoded shared frames
     * @param units      the unit numbers
     * @param unitFrames {@code unitFrames.get(i)} is the bytes of {@code header.unit(units[i])}
     * @param rolledBack ascending ordinals to flag
     * @return the rows, unit by unit in the order given, each in stored order
     * @throws CodecException when the frames are corrupt
     */
    public static @NotNull List<EventRow> decodeUnits(@NotNull SegmentHeader header, @NotNull SharedColumns shared,
            int @NotNull [] units, @NotNull List<byte[]> unitFrames, int @NotNull [] rolledBack) {
        if (unitFrames.size() != units.length) {
            throw new IllegalArgumentException(units.length + " units but " + unitFrames.size() + " frames");
        }
        checkOrdinals(header, rolledBack);
        List<EventRow> rows = new ArrayList<>();
        for (int i = 0; i < units.length; i++) {
            if (units[i] < 0 || units[i] >= header.unitCount()) {
                throw new IllegalArgumentException("unit " + units[i] + " out of " + header.unitCount());
            }
            byte[] frames = unitFrames.get(i);
            UnitDecoder.decode(header, shared, units[i], frames, 0, frames.length, rolledBack, rows);
        }
        return rows;
    }

    /**
     * Matches the column rules of a filter against some units, without ids, rows or payloads: the
     * first step of a selective read. Every unit {@link SegmentHeader#units} selects for the filter must
     * be given, or matches are lost.
     *
     * @param header     the parsed header
     * @param filter     the filter; only its column rules are applied ({@link EventFilter#matchesColumns})
     * @param palettes   the segment's palettes; required when {@link ColumnRules#needsPalettes} says so
     * @param units      the unit numbers
     * @param unitFrames {@code unitFrames.get(i)} is the bytes of {@code header.unit(units[i])}
     * @param rolledBack ascending ordinals of the rolled-back rows
     * @return the matching rows; {@link SegmentMatch#rows} builds them once ids are known
     * @throws CodecException when the frames are corrupt
     */
    public static @NotNull SegmentMatch match(@NotNull SegmentHeader header, @NotNull EventFilter filter,
            @NotNull Optional<Palettes> palettes, int @NotNull [] units, @NotNull List<byte[]> unitFrames,
            int @NotNull [] rolledBack) {
        if (unitFrames.size() != units.length) {
            throw new IllegalArgumentException(units.length + " units but " + unitFrames.size() + " frames");
        }
        checkOrdinals(header, rolledBack);
        if (units.length == 0) {
            // Nothing was selected, so nothing was fetched: not the palettes either.
            return new SegmentMatch(List.of(), List.of(), rolledBack);
        }
        ColumnRules rules = new ColumnRules(header, filter, palettes);
        List<UnitScan> scans = new ArrayList<>();
        List<int[]> matches = new ArrayList<>();
        for (int i = 0; i < units.length; i++) {
            if (units[i] < 0 || units[i] >= header.unitCount()) {
                throw new IllegalArgumentException("unit " + units[i] + " out of " + header.unitCount());
            }
            byte[] frames = unitFrames.get(i);
            UnitScan scan = new UnitScan(header, units[i], frames, 0, frames.length);
            int[] rows = rules.match(scan, rolledBack);
            if (rows.length > 0) {
                scans.add(scan);
                matches.add(rows);
            }
        }
        return new SegmentMatch(scans, matches, rolledBack);
    }

    /**
     * Decodes the palette frame alone.
     *
     * @param header   the parsed header
     * @param palettes the bytes of {@code header.palettes()}
     * @return the palettes
     * @throws CodecException when the frame is corrupt
     */
    public static @NotNull Palettes palettes(@NotNull SegmentHeader header, byte @NotNull [] palettes) {
        return Palettes.decode(header, palettes, 0, palettes.length);
    }

    /**
     * Decodes the shared frames once, for several {@link #decodeUnits} calls.
     *
     * @param header the parsed header
     * @param shared the bytes of {@code header.shared()}
     * @return the decoded shared frames
     * @throws CodecException when they are corrupt
     */
    public static @NotNull SharedColumns shared(@NotNull SegmentHeader header, byte @NotNull [] shared) {
        return SharedColumns.decode(header, shared, 0, shared.length);
    }

    /**
     * The id of every row, by ordinal; decodes only the shared frames.
     *
     * @param header the parsed header
     * @param shared the bytes of {@code header.shared()}
     * @return the ids
     * @throws CodecException when the frames are corrupt
     */
    public static long @NotNull [] ids(@NotNull SegmentHeader header, byte @NotNull [] shared) {
        return shared(header, shared).ids().clone();
    }

    private static void checkOrdinals(@NotNull SegmentHeader header, int @NotNull [] rolledBack) {
        for (int i = 0; i < rolledBack.length; i++) {
            if (rolledBack[i] < 0 || rolledBack[i] >= header.events() || i > 0 && rolledBack[i] <= rolledBack[i - 1]) {
                throw new IllegalArgumentException("rolled-back ordinals must be ascending and below "
                        + header.events());
            }
        }
    }
}
