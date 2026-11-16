package gg.moonrise.hawkeye.core.repository.segment;

import gg.moonrise.hawkeye.core.repository.EventRow;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.hawkeye.util.EventOrder;
import gg.moonrise.hawkeye.util.codec.ByteWriter;
import gg.moonrise.hawkeye.util.codec.Compression;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;
import org.jetbrains.annotations.NotNull;

/**
 * Builds a format 2 segment ({@link Segments#encode}). Rows are put in stored order (unplaced by id,
 * then placed by world order, position, id), the shared columns and the units' columns are written
 * raw, and every frame is compressed, on the given executor when there is one.
 */
public final class SegmentEncoder {

    private static final Comparator<Keyed> STORED_ORDER = Comparator.comparingInt(Keyed::group)
            .thenComparingInt(Keyed::worldRank)
            .thenComparingLong(Keyed::position)
            .thenComparingLong(Keyed::id);

    private final int codec;
    private final Executor executor;
    private final EventRow[] rows;
    private final int[] worldIndexes;
    private final int[] worlds;
    private final int unplaced;

    private SegmentEncoder(@NotNull List<EventRow> input, @NotNull List<Integer> worldOrder, int codec,
            @NotNull Executor executor) {
        this.codec = codec;
        this.executor = executor;
        if (input.isEmpty()) {
            throw new IllegalArgumentException("a segment needs at least one event");
        }
        if (input.size() > SegmentHeader.MAX_EVENTS) {
            throw new IllegalArgumentException("a segment holds at most " + SegmentHeader.MAX_EVENTS + " events");
        }
        int[] order = worldOrder.stream().mapToInt(Integer::intValue).toArray();
        int[] sortedWorlds = order.clone();
        Arrays.sort(sortedWorlds);
        int[] rankOfSorted = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            rankOfSorted[Arrays.binarySearch(sortedWorlds, order[i])] = i;
        }
        for (int i = 1; i < sortedWorlds.length; i++) {
            if (sortedWorlds[i] == sortedWorlds[i - 1]) {
                throw new IllegalArgumentException("world " + sortedWorlds[i] + " is twice in the world order");
            }
        }
        List<Keyed> keyed = new ArrayList<>(input.size());
        int unplacedRows = 0;
        for (EventRow row : input) {
            validate(row);
            int found = Arrays.binarySearch(sortedWorlds, row.world());
            if (found < 0) {
                throw new IllegalArgumentException("world " + row.world() + " of event " + row.id()
                        + " is not in the world order");
            }
            boolean placed = EventOrder.byPlace(row.action());
            unplacedRows += placed ? 0 : 1;
            keyed.add(placed ? new Keyed(row, 1, rankOfSorted[found], row.position(), row.id())
                    : new Keyed(row, 0, 0, 0, row.id()));
        }
        keyed.sort(STORED_ORDER);
        this.unplaced = unplacedRows;
        this.rows = new EventRow[keyed.size()];
        boolean[] present = new boolean[order.length];
        int[] worldRanks = new int[rows.length];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = keyed.get(i).row();
            worldRanks[i] = rankOfSorted[Arrays.binarySearch(sortedWorlds, rows[i].world())];
            present[worldRanks[i]] = true;
        }
        int[] headerIndexOfRank = new int[order.length];
        int presentCount = 0;
        for (int rank = 0; rank < order.length; rank++) {
            headerIndexOfRank[rank] = present[rank] ? presentCount++ : -1;
        }
        this.worlds = new int[presentCount];
        for (int rank = 0; rank < order.length; rank++) {
            if (present[rank]) {
                worlds[headerIndexOfRank[rank]] = order[rank];
            }
        }
        this.worldIndexes = new int[rows.length];
        for (int i = 0; i < rows.length; i++) {
            worldIndexes[i] = headerIndexOfRank[worldRanks[i]];
        }
    }

    /**
     * Encodes rows.
     *
     * @param rows       the events, in any order
     * @param worldOrder the world order; every row's world must be in it
     * @param codec      the frame codec ({@link Compression#ZSTD} or {@link Compression#DEFLATE})
     * @param executor   where frames are compressed
     * @return the segment
     */
    public static @NotNull PackedSegment encode(@NotNull List<EventRow> rows, @NotNull List<Integer> worldOrder,
            int codec, @NotNull Executor executor) {
        return new SegmentEncoder(rows, worldOrder, codec, executor).encode();
    }

    private @NotNull PackedSegment encode() {
        int count = rows.length;
        long[] ids = new long[count];
        long baseSlot = Long.MAX_VALUE;
        long lastSlot = Long.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            ids[i] = rows[i].id();
            long slot = EventIds.slot(ids[i]);
            baseSlot = Math.min(baseSlot, slot);
            lastSlot = Math.max(lastSlot, slot);
        }
        long[] sortedIds = ids.clone();
        Arrays.sort(sortedIds);
        for (int i = 1; i < count; i++) {
            if (sortedIds[i] == sortedIds[i - 1]) {
                throw new IllegalArgumentException("event id " + sortedIds[i] + " appears twice");
            }
        }
        int[] nodes = distinct(Arrays.stream(ids).mapToInt(EventIds::node).toArray());
        int[] actors = distinct(Arrays.stream(rows).mapToInt(EventRow::actor).toArray());
        int[] actorPalette = byFrequency(Arrays.stream(rows).mapToInt(EventRow::actor).toArray());
        int[] typePalette = byFrequency(Arrays.stream(rows)
                .flatMapToInt(row -> Arrays.stream(new int[] {row.subject(), row.secondary()}))
                .filter(type -> type != 0).toArray());

        List<CompletableFuture<byte[]>> shared = new ArrayList<>();
        shared.add(compress(timeColumn(ids, baseSlot)));
        shared.add(nodes.length > 1 ? compress(nodeColumn(ids, nodes)) : CompletableFuture.completedFuture(new byte[0]));
        byte[] rankColumn = rankColumn(ids, baseSlot, lastSlot - baseSlot, nodes);
        shared.add(rankColumn.length > 0 ? compress(rankColumn) : CompletableFuture.completedFuture(new byte[0]));
        shared.add(compress(palettes(actorPalette, typePalette)));

        List<UnitParts> units = new ArrayList<>();
        cut(0, unplaced, false, units, actors, actorPalette, typePalette, ids, baseSlot);
        cut(unplaced, count, true, units, actors, actorPalette, typePalette, ids, baseSlot);

        byte[][] sharedFrames = shared.stream().map(SegmentEncoder::join).toArray(byte[][]::new);
        int[] sharedLengths = Arrays.stream(sharedFrames).mapToInt(frame -> frame.length).toArray();
        ByteWriter body = new ByteWriter(64 + count * 2);
        Arrays.stream(sharedFrames).forEach(body::writeRaw);
        List<SegmentUnit> infos = new ArrayList<>(units.size());
        for (UnitParts unit : units) {
            byte[] scalars = join(unit.scalars());
            byte[] payload = join(unit.payload());
            body.writeRaw(scalars).writeRaw(payload);
            SegmentUnit info = unit.info();
            infos.add(new SegmentUnit(info.rows(), info.placed(), info.world(), info.position(), info.minSlot(),
                    info.maxSlot(), scalars.length, payload.length, info.actors(), info.actionMask()));
        }
        long[] chunks = chunkList();
        int[] chunkWorlds = new int[chunks.length / 2];
        long[] chunkBases = new long[chunks.length / 2];
        for (int i = 0; i < chunkWorlds.length; i++) {
            chunkWorlds[i] = (int) chunks[2 * i];
            chunkBases[i] = chunks[2 * i + 1];
        }
        long mask = 0;
        for (SegmentUnit info : infos) {
            mask |= info.actionMask();
        }
        long[] positions = new long[unplaced];
        for (int i = 0; i < unplaced; i++) {
            positions[i] = rows[i].position();
        }
        Regions regions = Regions.of(worldIndexes, positions, 0, unplaced);
        SegmentHeader header = SegmentHeader.of(count, unplaced, baseSlot, lastSlot - baseSlot, worlds, nodes, actors,
                mask, chunkWorlds, chunkBases, regions, sharedLengths, infos);
        return new PackedSegment(sortedIds[0], sortedIds[count - 1], count, header.toBytes(), body.toByteArray(),
                rolledBack());
    }

    private void cut(int from, int to, boolean placed, @NotNull List<UnitParts> units, int @NotNull [] actors,
            int @NotNull [] actorPalette, int @NotNull [] typePalette, long @NotNull [] ids, long baseSlot) {
        PaletteIndex actorIndex = new PaletteIndex(actorPalette);
        PaletteIndex typeIndex = new PaletteIndex(typePalette);
        for (int start = from; start < to; start += Segments.UNIT_ROWS) {
            int end = Math.min(to, start + Segments.UNIT_ROWS);
            units.add(unit(start, end, placed, actors, actorIndex, typeIndex, ids, baseSlot));
        }
    }

    private @NotNull UnitParts unit(int start, int end, boolean placed, int @NotNull [] actors,
            @NotNull PaletteIndex actorIndex, @NotNull PaletteIndex typeIndex, long @NotNull [] ids, long baseSlot) {
        UnitColumns columns = new UnitColumns(end - start);
        long minSlot = Long.MAX_VALUE;
        long maxSlot = Long.MIN_VALUE;
        long mask = 0;
        int[] unitActors = new int[end - start];
        for (int i = start; i < end; i++) {
            EventRow row = rows[i];
            columns.add(worldIndexes[i], row, actorIndex.index(row.actor()),
                    row.subject() == 0 ? 0 : typeIndex.index(row.subject()) + 1,
                    row.secondary() == 0 ? 0 : typeIndex.index(row.secondary()) + 1);
            long slot = EventIds.slot(ids[i]);
            minSlot = Math.min(minSlot, slot);
            maxSlot = Math.max(maxSlot, slot);
            mask |= actionBit(row.action());
            unitActors[i - start] = Arrays.binarySearch(actors, row.actor());
        }
        EventRow first = rows[start];
        SegmentUnit info = new SegmentUnit(end - start, placed, placed ? worldIndexes[start] : 0,
                placed ? first.position() : 0, minSlot - baseSlot, maxSlot - baseSlot, 0, 0, distinct(unitActors),
                mask);
        byte[] payload = columns.payload();
        return new UnitParts(info, compress(columns.scalars()),
                payload.length == 0 ? CompletableFuture.completedFuture(new byte[0]) : compress(payload));
    }

    /**
     * The action mask bit of a code.
     *
     * @param code an action code (not negative)
     * @return bit {@code min(code, 63)}
     */
    public static long actionBit(int code) {
        return 1L << Math.min(code, 63);
    }

    private byte @NotNull [] timeColumn(long @NotNull [] ids, long baseSlot) {
        ByteWriter out = new ByteWriter(ids.length + 16);
        long previous = baseSlot;
        for (long id : ids) {
            long slot = EventIds.slot(id);
            out.writeZigZag(slot - previous);
            previous = slot;
        }
        return out.toByteArray();
    }

    private static byte @NotNull [] nodeColumn(long @NotNull [] ids, int @NotNull [] nodes) {
        ByteWriter out = new ByteWriter(ids.length + 16);
        for (long id : ids) {
            out.writeVarLong(Arrays.binarySearch(nodes, EventIds.node(id)));
        }
        return out.toByteArray();
    }

    /** The rank differences, or an empty array when every one is 0. */
    private static byte @NotNull [] rankColumn(long @NotNull [] ids, long baseSlot, long slotSpan,
            int @NotNull [] nodes) {
        RankExpectation expectation = new RankExpectation(slotSpan, nodes.length, ids.length);
        ByteWriter out = new ByteWriter(ids.length + 16);
        boolean any = false;
        for (long id : ids) {
            long key = (EventIds.slot(id) - baseSlot) * nodes.length
                    + (nodes.length == 1 ? 0 : Arrays.binarySearch(nodes, EventIds.node(id)));
            int rank = EventIds.rank(id);
            int difference = rank - expectation.expected(key);
            expectation.record(key, rank);
            any |= difference != 0;
            out.writeZigZag(difference);
        }
        return any ? out.toByteArray() : new byte[0];
    }

    private static byte @NotNull [] palettes(int @NotNull [] actorPalette, int @NotNull [] typePalette) {
        ByteWriter out = new ByteWriter(16 + (actorPalette.length + typePalette.length) * 3);
        out.writeVarLong(actorPalette.length);
        Arrays.stream(actorPalette).forEach(out::writeVarLong);
        out.writeVarLong(typePalette.length);
        Arrays.stream(typePalette).forEach(out::writeVarLong);
        return out.toByteArray();
    }

    /** Distinct (world index, chunk base) pairs of the placed rows, flattened, in stored order. */
    private long @NotNull [] chunkList() {
        long[] out = new long[16];
        int size = 0;
        for (int i = unplaced; i < rows.length; i++) {
            long world = worldIndexes[i];
            long base = rows[i].position() & ~SegmentHeader.CHUNK_LOCAL_MASK;
            if (size > 0 && out[size - 2] == world && out[size - 1] == base) {
                continue;
            }
            if (size == out.length) {
                out = Arrays.copyOf(out, size * 2);
            }
            out[size++] = world;
            out[size++] = base;
        }
        return Arrays.copyOf(out, size);
    }

    private int @NotNull [] rolledBack() {
        return IntStream.range(0, rows.length).filter(i -> rows[i].rolledBack()).toArray();
    }

    private @NotNull CompletableFuture<byte[]> compress(byte @NotNull [] raw) {
        return CompletableFuture.supplyAsync(() -> Compression.compress(raw, codec), executor);
    }

    private static byte @NotNull [] join(@NotNull CompletableFuture<byte[]> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static void validate(@NotNull EventRow row) {
        if (row.world() < 0 || row.actor() < 0 || row.action() < 0 || row.subject() < 0 || row.secondary() < 0
                || row.amount() < 0 || row.id() < 0) {
            throw new IllegalArgumentException("event " + row.id() + " has a negative field");
        }
        long position = row.position();
        if (BlockPositions.pack(BlockPositions.x(position), BlockPositions.y(position), BlockPositions.z(position))
                != position) {
            throw new IllegalArgumentException("event " + row.id() + " has an invalid packed position");
        }
    }

    /** Sorted distinct values. */
    private static int @NotNull [] distinct(int @NotNull [] values) {
        return Arrays.stream(values).sorted().distinct().toArray();
    }

    /** Distinct values, most frequent first, ties by ascending value. */
    private static int @NotNull [] byFrequency(int @NotNull [] values) {
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        long[] packed = new long[sorted.length];
        int distinct = 0;
        for (int i = 0; i < sorted.length; ) {
            int j = i;
            while (j < sorted.length && sorted[j] == sorted[i]) {
                j++;
            }
            // descending count, then ascending value: sort (-count, value) ascending
            packed[distinct++] = ((long) (Integer.MAX_VALUE - (j - i)) << 32) | (sorted[i] & 0xFFFFFFFFL);
            i = j;
        }
        long[] order = Arrays.copyOf(packed, distinct);
        Arrays.sort(order);
        int[] palette = new int[distinct];
        for (int i = 0; i < distinct; i++) {
            palette[i] = (int) order[i];
        }
        return palette;
    }

    /** A row with its stored-order sort key. */
    private record Keyed(@NotNull EventRow row, int group, int worldRank, long position, long id) {
    }

    /** A unit's header record (frame lengths filled in later) and its frames being compressed. */
    private record UnitParts(@NotNull SegmentUnit info, @NotNull CompletableFuture<byte[]> scalars,
            @NotNull CompletableFuture<byte[]> payload) {
    }

    /** Maps palette values to their palette index without boxing. */
    private static final class PaletteIndex {

        private final int[] sortedValues;
        private final int[] indexes;

        private PaletteIndex(int @NotNull [] palette) {
            long[] pairs = new long[palette.length];
            for (int i = 0; i < palette.length; i++) {
                pairs[i] = ((long) palette[i] << 32) | i;
            }
            Arrays.sort(pairs);
            sortedValues = new int[palette.length];
            indexes = new int[palette.length];
            for (int i = 0; i < pairs.length; i++) {
                sortedValues[i] = (int) (pairs[i] >>> 32);
                indexes[i] = (int) pairs[i];
            }
        }

        private int index(int value) {
            return indexes[Arrays.binarySearch(sortedValues, value)];
        }
    }
}
