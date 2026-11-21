package gg.moonrise.hawkeye.core.storage.segment;

import gg.moonrise.hawkeye.core.repository.event.EventFilter;
import gg.moonrise.hawkeye.util.event.EventIds;
import gg.moonrise.hawkeye.util.codec.binary.ByteReader;
import gg.moonrise.hawkeye.util.codec.binary.ByteWriter;
import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The uncompressed header of a format 2 segment: enough to decide which units a query needs and where
 * their bytes are, without touching the body. Varints throughout:
 * <pre>
 *   byte    version (2)
 *   varint  events, unplaced rows, base slot (the smallest slot), slot span (largest slot - base slot)
 *   varint  world count, world ids (in the packer's world order, only those present)
 *   varint  node count, nodes (ascending: the first, then deltas)
 *   varint  actor count, actors (ascending: the first, then deltas)
 *   long    action mask (bit min(code, 63), 8 bytes big-endian)
 *   varint  chunk count, then per chunk of the placed rows, sorted by (world index, chunk x, chunk z):
 *           varint world index delta; zig-zag chunk x (delta when the world is unchanged, else absolute);
 *           zig-zag chunk z (delta when world and chunk x are unchanged, else absolute)
 *   varint  region count, then per region holding an unplaced row (32 x 32 chunks: chunk &gt;&gt; 5),
 *           sorted by (world index, region x, region z), coded like the chunks
 *   varint  byte lengths of the time, node, rank and palette frames (0 for an absent one)
 *   varint  unit count, then per unit:
 *           varint rows; byte placed (0 or 1); placed only: varint world index, long first position;
 *           varint min slot - base slot, varint max slot - min slot;
 *           varint scalar frame length, varint payload frame length;
 *           varint actor count, actor indexes (ascending: the first, then deltas); long action mask
 * </pre>
 * The per-unit and per-segment slot ranges are not required by the format contract; they let an id
 * range skip units and segments, and cost a few bytes per unit. The region list lets a world or area
 * filter skip the unplaced units (chat, commands, sessions), which the chunk list and the unit keys
 * do not describe: a few dozen entries, as those rows come from a few hundred players at most.
 */
public final class SegmentHeader {

    /** The format version byte. */
    public static final int VERSION = 2;

    /** The most events a segment may hold. */
    public static final int MAX_EVENTS = 4 * 1024 * 1024;

    /** The largest slot an id can carry (41 bits of milliseconds). */
    public static final long MAX_SLOT = ((1L << 41) - 1) / EventIds.SLOT_MILLIS;

    /** The packed-position bits below a chunk column's base. */
    public static final long CHUNK_LOCAL_MASK = (1L << 20) - 1;

    /** How many chunk bits a region coordinate drops. */
    public static final int REGION_SHIFT = 5;

    private static final int MAX_CHUNK = (1 << 21) - 1;
    private static final int MAX_REGION = MAX_CHUNK >> REGION_SHIFT;

    private final int events;
    private final int unplaced;
    private final long baseSlot;
    private final long slotSpan;
    private final int[] worlds;
    private final int[] nodes;
    private final int[] actors;
    private final long actionMask;
    private final int[] chunkWorlds;
    private final long[] chunkBases;
    private final Regions regions;
    private final int[] sharedLengths;
    private final List<SegmentUnit> units;
    private final int[] unitStarts;
    private final int[] unitOffsets;
    private final int bodyLength;

    private SegmentHeader(int events, int unplaced, long baseSlot, long slotSpan, int @NotNull [] worlds,
            int @NotNull [] nodes, int @NotNull [] actors, long actionMask, int @NotNull [] chunkWorlds,
            long @NotNull [] chunkBases, @NotNull Regions regions, int @NotNull [] sharedLengths,
            @NotNull List<SegmentUnit> units) {
        this.events = events;
        this.unplaced = unplaced;
        this.baseSlot = baseSlot;
        this.slotSpan = slotSpan;
        this.worlds = worlds;
        this.nodes = nodes;
        this.actors = actors;
        this.actionMask = actionMask;
        this.chunkWorlds = chunkWorlds;
        this.chunkBases = chunkBases;
        this.regions = regions;
        this.sharedLengths = sharedLengths;
        this.units = List.copyOf(units);
        this.unitStarts = new int[units.size()];
        this.unitOffsets = new int[units.size()];
        validate();
        long offset = 0;
        for (int length : sharedLengths) {
            offset += length;
        }
        int start = 0;
        for (int u = 0; u < units.size(); u++) {
            unitStarts[u] = start;
            unitOffsets[u] = (int) offset;
            start += units.get(u).rows();
            offset += units.get(u).length();
            if (offset > Integer.MAX_VALUE) {
                throw new CodecException("segment body above 2 GiB");
            }
        }
        this.bodyLength = (int) offset;
    }

    /**
     * Assembles a header (the encoder's side); validates it like {@link #parse}.
     *
     * @param events        the event count
     * @param unplaced      the unplaced row count
     * @param baseSlot      the smallest slot
     * @param slotSpan      the largest slot minus the smallest
     * @param worlds        the world ids present, in world order
     * @param nodes         the node ids, ascending
     * @param actors        the actor ids, ascending
     * @param actionMask    the segment's action mask
     * @param chunkWorlds   per chunk of the placed rows: its world index
     * @param chunkBases    per chunk: the packed position of its first block (local bits 0), sorted with
     *                      the world index
     * @param regions       the regions of the unplaced rows
     * @param sharedLengths the lengths of the four shared frames ({@link SharedFrame} order)
     * @param units         the units
     * @return the header
     * @throws CodecException when the parts are inconsistent
     */
    public static @NotNull SegmentHeader of(int events, int unplaced, long baseSlot, long slotSpan,
            int @NotNull [] worlds, int @NotNull [] nodes, int @NotNull [] actors, long actionMask,
            int @NotNull [] chunkWorlds, long @NotNull [] chunkBases, @NotNull Regions regions,
            int @NotNull [] sharedLengths, @NotNull List<SegmentUnit> units) {
        return new SegmentHeader(events, unplaced, baseSlot, slotSpan, worlds.clone(), nodes.clone(), actors.clone(),
                actionMask, chunkWorlds.clone(), chunkBases.clone(), regions, sharedLengths.clone(), units);
    }

    /**
     * Reads a header.
     *
     * @param header the header bytes
     * @return the header
     * @throws CodecException when the bytes are corrupt
     */
    public static @NotNull SegmentHeader parse(byte @NotNull [] header) {
        ByteReader in = new ByteReader(header);
        int version = in.readByte();
        if (version != VERSION) {
            throw new CodecException("unknown segment header version " + version);
        }
        int events = in.readVarInt();
        int unplaced = in.readVarInt();
        long baseSlot = in.readVarLong();
        long slotSpan = in.readVarLong();
        int[] worlds = readInts(in, false);
        int[] nodes = readInts(in, true);
        int[] actors = readInts(in, true);
        long actionMask = in.readLong();
        int chunks = count(in, 3);
        int[] chunkWorlds = new int[chunks];
        long[] chunkBases = new long[chunks];
        readChunks(in, chunkWorlds, chunkBases);
        Regions regions = readRegions(in);
        int[] sharedLengths = new int[SharedFrame.values().length];
        for (int i = 0; i < sharedLengths.length; i++) {
            sharedLengths[i] = in.readVarInt();
        }
        int unitCount = count(in, 8);
        List<SegmentUnit> units = new ArrayList<>(unitCount);
        for (int u = 0; u < unitCount; u++) {
            units.add(readUnit(in));
        }
        if (in.hasRemaining()) {
            throw new CodecException(in.remaining() + " unread byte(s) after the segment header");
        }
        return new SegmentHeader(events, unplaced, baseSlot, slotSpan, worlds, nodes, actors, actionMask,
                chunkWorlds, chunkBases, regions, sharedLengths, units);
    }

    /**
     * Writes this header.
     *
     * @return the header bytes
     */
    public byte @NotNull [] toBytes() {
        ByteWriter out = new ByteWriter(256 + units.size() * 32 + chunkBases.length * 3);
        out.writeByte(VERSION).writeVarLong(events).writeVarLong(unplaced).writeVarLong(baseSlot)
                .writeVarLong(slotSpan);
        writeInts(out, worlds, false);
        writeInts(out, nodes, true);
        writeInts(out, actors, true);
        out.writeLong(actionMask);
        writeChunks(out);
        writeRegions(out);
        for (int length : sharedLengths) {
            out.writeVarLong(length);
        }
        out.writeVarLong(units.size());
        for (SegmentUnit unit : units) {
            out.writeVarLong(unit.rows()).writeByte(unit.placed() ? 1 : 0);
            if (unit.placed()) {
                out.writeVarLong(unit.world()).writeLong(unit.position());
            }
            out.writeVarLong(unit.minSlot()).writeVarLong(unit.maxSlot() - unit.minSlot())
                    .writeVarLong(unit.scalarLength()).writeVarLong(unit.payloadLength());
            writeInts(out, unit.actors(), true);
            out.writeLong(unit.actionMask());
        }
        return out.toByteArray();
    }

    /**
     * How many events the segment holds.
     *
     * @return the event count
     */
    public int events() {
        return events;
    }

    /**
     * How many of them are unplaced (they come first in stored order).
     *
     * @return the unplaced row count
     */
    public int unplaced() {
        return unplaced;
    }

    /**
     * How many units the rows are cut into.
     *
     * @return the unit count
     */
    public int unitCount() {
        return units.size();
    }

    /**
     * The smallest slot of the segment.
     *
     * @return the base slot
     */
    public long baseSlot() {
        return baseSlot;
    }

    /**
     * The largest slot minus the smallest.
     *
     * @return the slot span
     */
    public long slotSpan() {
        return slotSpan;
    }

    /**
     * The world ids present, in world order: a row's world index points here.
     *
     * @return the world ids
     */
    public @NotNull List<Integer> worlds() {
        return Arrays.stream(worlds).boxed().toList();
    }

    /**
     * The node ids present, ascending.
     *
     * @return the node ids
     */
    public @NotNull List<Integer> nodes() {
        return Arrays.stream(nodes).boxed().toList();
    }

    /**
     * The actor ids present, ascending.
     *
     * @return the actor ids
     */
    public @NotNull List<Integer> actors() {
        return Arrays.stream(actors).boxed().toList();
    }

    /**
     * The byte length of one shared frame.
     *
     * @param frame the frame
     * @return its length, 0 when absent
     */
    public int frameLength(@NotNull SharedFrame frame) {
        return sharedLengths[frame.ordinal()];
    }

    /**
     * The shared frames: one contiguous range at the start of the body.
     *
     * @return the range
     */
    public @NotNull ByteRange shared() {
        return new ByteRange(0, unitOffsets[0]);
    }

    /**
     * A unit's scalar and payload frames: one contiguous range of the body.
     *
     * @param unit the unit number
     * @return the range
     */
    public @NotNull ByteRange unit(int unit) {
        return new ByteRange(unitOffsets[unit], units.get(unit).length());
    }

    /**
     * What the header records about a unit.
     *
     * @param unit the unit number
     * @return the unit
     */
    public @NotNull SegmentUnit unitInfo(int unit) {
        return units.get(unit);
    }

    /**
     * The ordinal of a unit's first row.
     *
     * @param unit the unit number
     * @return the ordinal
     */
    public int unitStart(int unit) {
        return unitStarts[unit];
    }

    /**
     * The length of the whole body.
     *
     * @return the byte length
     */
    public int bodyLength() {
        return bodyLength;
    }

    /**
     * Whether the segment can hold a row the filter accepts. Never {@code false} for a segment that
     * holds one; may be {@code true} for one that does not.
     *
     * @param filter the filter
     * @return {@code false} when no row can match
     */
    public boolean mayMatch(@NotNull EventFilter filter) {
        return units(filter).length > 0;
    }

    /**
     * The units that can hold a row the filter accepts. Never leaves out a unit that holds one.
     *
     * @param filter the filter
     * @return ascending unit numbers, empty for none
     */
    public int @NotNull [] units(@NotNull EventFilter filter) {
        return new UnitSelector(this, filter).select();
    }

    /**
     * The world ids present, in world order (the array itself: do not modify).
     *
     * @return the world ids
     */
    public int @NotNull [] worldIds() {
        return worlds;
    }

    /**
     * The node ids present, ascending (the array itself: do not modify).
     *
     * @return the node ids
     */
    public int @NotNull [] nodeIds() {
        return nodes;
    }

    /**
     * The actor ids present, ascending (the array itself: do not modify).
     *
     * @return the actor ids
     */
    public int @NotNull [] actorIds() {
        return actors;
    }

    /**
     * The segment's action mask: bit {@code min(code, 63)} for every action code present.
     *
     * @return the mask
     */
    public long actionMask() {
        return actionMask;
    }

    /**
     * Per chunk of the placed rows: its world index (the array itself: do not modify).
     *
     * @return the world indexes, parallel to {@link #chunkBases()}
     */
    public int @NotNull [] chunkWorlds() {
        return chunkWorlds;
    }

    /**
     * Per chunk of the placed rows: the packed position of its first block, sorted with
     * {@link #chunkWorlds()} (the array itself: do not modify).
     *
     * @return the chunk bases
     */
    public long @NotNull [] chunkBases() {
        return chunkBases;
    }

    /**
     * The regions holding an unplaced row (32 x 32 chunks each), sorted by (world index, x, z).
     *
     * @return the regions
     */
    public @NotNull Regions unplacedRegions() {
        return regions;
    }

    /**
     * The byte range of the palette frame, the last shared frame: a reader that needs only the
     * palettes fetches this.
     *
     * @return the range
     */
    public @NotNull ByteRange palettes() {
        int length = frameLength(SharedFrame.PALETTES);
        return new ByteRange(unitOffsets[0] - length, length);
    }

    private void validate() {
        if (events < 1 || events > MAX_EVENTS) {
            throw new CodecException("implausible segment event count " + events);
        }
        if (unplaced < 0 || unplaced > events) {
            throw new CodecException("unplaced count " + unplaced + " outside 0.." + events);
        }
        if (baseSlot < 0 || baseSlot > MAX_SLOT || slotSpan < 0 || slotSpan > MAX_SLOT - baseSlot) {
            throw new CodecException("slot range " + baseSlot + "+" + slotSpan + " out of range");
        }
        if (worlds.length < 1 || worlds.length > events) {
            throw new CodecException("implausible world count " + worlds.length);
        }
        for (int i = 0; i < worlds.length; i++) {
            for (int j = 0; j < i; j++) {
                if (worlds[i] == worlds[j]) {
                    throw new CodecException("world " + worlds[i] + " listed twice");
                }
            }
        }
        checkAscending(nodes, "node");
        if (nodes[nodes.length - 1] > EventIds.MAX_NODE) {
            throw new CodecException("node id " + nodes[nodes.length - 1] + " out of range");
        }
        checkAscending(actors, "actor");
        validateChunks();
        validateRegions();
        validateShared();
        validateUnits();
    }

    private void validateChunks() {
        for (int i = 0; i < chunkWorlds.length; i++) {
            if (chunkWorlds[i] < 0 || chunkWorlds[i] >= worlds.length) {
                throw new CodecException("chunk world index " + chunkWorlds[i] + " out of " + worlds.length);
            }
            if (i > 0 && (chunkWorlds[i] < chunkWorlds[i - 1]
                    || chunkWorlds[i] == chunkWorlds[i - 1] && chunkBases[i] <= chunkBases[i - 1])) {
                throw new CodecException("chunk list not sorted");
            }
        }
    }

    private void validateRegions() {
        int[] regionWorlds = regions.worlds();
        if ((regionWorlds.length == 0) != (unplaced == 0)) {
            throw new CodecException("the region list does not match the " + unplaced + " unplaced rows");
        }
        for (int i = 0; i < regionWorlds.length; i++) {
            if (regionWorlds[i] < 0 || regionWorlds[i] >= worlds.length) {
                throw new CodecException("region world index " + regionWorlds[i] + " out of " + worlds.length);
            }
            if (i > 0 && Regions.compare(regions, i - 1, regions, i) >= 0) {
                throw new CodecException("region list not sorted");
            }
        }
    }

    private void validateShared() {
        if (sharedLengths.length != SharedFrame.values().length) {
            throw new CodecException("expected " + SharedFrame.values().length + " shared frame lengths");
        }
        for (int length : sharedLengths) {
            if (length < 0) {
                throw new CodecException("negative frame length");
            }
        }
        if (frameLength(SharedFrame.TIME) == 0 || frameLength(SharedFrame.PALETTES) == 0) {
            throw new CodecException("the time and palette frames are missing");
        }
        if ((frameLength(SharedFrame.NODE) > 0) != (nodes.length > 1)) {
            throw new CodecException("the node frame does not match the node count " + nodes.length);
        }
    }

    private void validateUnits() {
        int placedRows = events - unplaced;
        int expected = ceilUnits(unplaced) + ceilUnits(placedRows);
        if (units.size() != expected) {
            throw new CodecException("expected " + expected + " units, found " + units.size());
        }
        int unplacedUnits = ceilUnits(unplaced);
        for (int u = 0; u < units.size(); u++) {
            SegmentUnit unit = units.get(u);
            boolean placed = u >= unplacedUnits;
            int groupStart = placed ? unplacedUnits : 0;
            int groupRows = placed ? placedRows : unplaced;
            int rows = Math.min(Segments.UNIT_ROWS, groupRows - (u - groupStart) * Segments.UNIT_ROWS);
            if (unit.rows() != rows || unit.placed() != placed) {
                throw new CodecException("unit " + u + " is not cut as the format requires");
            }
            if (unit.world() < 0 || unit.world() >= worlds.length) {
                throw new CodecException("unit " + u + " world index " + unit.world() + " out of range");
            }
            if (unit.minSlot() < 0 || unit.maxSlot() < unit.minSlot() || unit.maxSlot() > slotSpan) {
                throw new CodecException("unit " + u + " slots outside the segment");
            }
            if (unit.scalarLength() < 1 || unit.payloadLength() < 0) {
                throw new CodecException("unit " + u + " frame lengths are invalid");
            }
            int[] unitActors = unit.actors();
            if (unitActors.length < 1 || unitActors[0] < 0 || unitActors[unitActors.length - 1] >= actors.length) {
                throw new CodecException("unit " + u + " actor indexes out of range");
            }
            checkAscending(unitActors, "unit actor index");
        }
    }

    private static int ceilUnits(int rows) {
        return (rows + Segments.UNIT_ROWS - 1) / Segments.UNIT_ROWS;
    }

    private static void checkAscending(int @NotNull [] values, @NotNull String what) {
        if (values.length == 0) {
            throw new CodecException("empty " + what + " list");
        }
        for (int i = 1; i < values.length; i++) {
            if (values[i] <= values[i - 1]) {
                throw new CodecException(what + " list not ascending");
            }
        }
    }

    private static @NotNull SegmentUnit readUnit(@NotNull ByteReader in) {
        int rows = in.readVarInt();
        int placedByte = in.readByte();
        if (placedByte > 1) {
            throw new CodecException("unit placed byte " + placedByte);
        }
        boolean placed = placedByte == 1;
        int world = placed ? in.readVarInt() : 0;
        long position = placed ? in.readLong() : 0;
        long minSlot = in.readVarLong();
        long slotWidth = in.readVarLong();
        if (minSlot < 0 || slotWidth < 0 || minSlot > MAX_SLOT || slotWidth > MAX_SLOT) {
            throw new CodecException("unit slot range out of range");
        }
        int scalarLength = in.readVarInt();
        int payloadLength = in.readVarInt();
        int[] unitActors = readInts(in, true);
        long mask = in.readLong();
        return new SegmentUnit(rows, placed, world, position, minSlot, minSlot + slotWidth, scalarLength,
                payloadLength, unitActors, mask);
    }

    private static int count(@NotNull ByteReader in, int minBytesEach) {
        int count = in.readVarInt();
        if ((long) count * minBytesEach > in.remaining()) {
            throw new CodecException("count " + count + " exceeds the remaining header bytes");
        }
        return count;
    }

    private static int @NotNull [] readInts(@NotNull ByteReader in, boolean deltas) {
        int count = count(in, 1);
        int[] values = new int[count];
        long previous = 0;
        for (int i = 0; i < count; i++) {
            long value = in.readVarLong() + (deltas && i > 0 ? previous : 0);
            if (value < 0 || value > Integer.MAX_VALUE) {
                throw new CodecException("id " + value + " does not fit an int");
            }
            values[i] = (int) value;
            previous = value;
        }
        return values;
    }

    private static void writeInts(@NotNull ByteWriter out, int @NotNull [] values, boolean deltas) {
        out.writeVarLong(values.length);
        for (int i = 0; i < values.length; i++) {
            out.writeVarLong(deltas && i > 0 ? values[i] - values[i - 1] : values[i]);
        }
    }

    private static void readChunks(@NotNull ByteReader in, int @NotNull [] chunkWorlds, long @NotNull [] chunkBases) {
        long world = 0;
        long chunkX = 0;
        long chunkZ = 0;
        for (int i = 0; i < chunkWorlds.length; i++) {
            long worldDelta = in.readVarLong();
            long x = in.readZigZag();
            long z = in.readZigZag();
            boolean sameWorld = i > 0 && worldDelta == 0;
            world += worldDelta;
            chunkX = sameWorld ? chunkX + x : x;
            chunkZ = sameWorld && x == 0 ? chunkZ + z : z;
            if (world > Integer.MAX_VALUE || chunkX < -MAX_CHUNK - 1L || chunkX > MAX_CHUNK
                    || chunkZ < -MAX_CHUNK - 1L || chunkZ > MAX_CHUNK) {
                throw new CodecException("chunk " + chunkX + ", " + chunkZ + " out of range");
            }
            chunkWorlds[i] = (int) world;
            chunkBases[i] = chunkBase((int) chunkX, (int) chunkZ);
        }
    }

    private void writeChunks(@NotNull ByteWriter out) {
        out.writeVarLong(chunkBases.length);
        int world = 0;
        int chunkX = 0;
        int chunkZ = 0;
        for (int i = 0; i < chunkBases.length; i++) {
            int x = (int) (chunkBases[i] >> 42);
            int z = chunkZOf(chunkBases[i]);
            boolean sameWorld = i > 0 && chunkWorlds[i] == world;
            out.writeVarLong(i == 0 ? chunkWorlds[i] : chunkWorlds[i] - world);
            out.writeZigZag(sameWorld ? (long) x - chunkX : x);
            out.writeZigZag(sameWorld && x == chunkX ? (long) z - chunkZ : z);
            world = chunkWorlds[i];
            chunkX = x;
            chunkZ = z;
        }
    }

    private static @NotNull Regions readRegions(@NotNull ByteReader in) {
        int count = count(in, 3);
        int[] regionWorlds = new int[count];
        int[] xs = new int[count];
        int[] zs = new int[count];
        long world = 0;
        long regionX = 0;
        long regionZ = 0;
        for (int i = 0; i < count; i++) {
            long worldDelta = in.readVarLong();
            long x = in.readZigZag();
            long z = in.readZigZag();
            boolean sameWorld = i > 0 && worldDelta == 0;
            world += worldDelta;
            regionX = sameWorld ? regionX + x : x;
            regionZ = sameWorld && x == 0 ? regionZ + z : z;
            if (world > Integer.MAX_VALUE || regionX < -MAX_REGION - 1L || regionX > MAX_REGION
                    || regionZ < -MAX_REGION - 1L || regionZ > MAX_REGION) {
                throw new CodecException("region " + regionX + ", " + regionZ + " out of range");
            }
            regionWorlds[i] = (int) world;
            xs[i] = (int) regionX;
            zs[i] = (int) regionZ;
        }
        return new Regions(regionWorlds, xs, zs);
    }

    private void writeRegions(@NotNull ByteWriter out) {
        int[] regionWorlds = regions.worlds();
        int[] xs = regions.xs();
        int[] zs = regions.zs();
        out.writeVarLong(regionWorlds.length);
        for (int i = 0; i < regionWorlds.length; i++) {
            boolean sameWorld = i > 0 && regionWorlds[i] == regionWorlds[i - 1];
            out.writeVarLong(i == 0 ? regionWorlds[i] : regionWorlds[i] - regionWorlds[i - 1]);
            out.writeZigZag(sameWorld ? (long) xs[i] - xs[i - 1] : xs[i]);
            out.writeZigZag(sameWorld && xs[i] == xs[i - 1] ? (long) zs[i] - zs[i - 1] : zs[i]);
        }
    }

    /**
     * The packed position of a chunk column's first value.
     *
     * @param chunkX chunk x
     * @param chunkZ chunk z
     * @return the base
     */
    public static long chunkBase(int chunkX, int chunkZ) {
        return ((long) chunkX << 42) | (((long) chunkZ + (1L << 21)) & ((1L << 22) - 1)) << 20;
    }

    private static int chunkZOf(long base) {
        return (int) ((base >>> 20) & ((1L << 22) - 1)) - (1 << 21);
    }
}
