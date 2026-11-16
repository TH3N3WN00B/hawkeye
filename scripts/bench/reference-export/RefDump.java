import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import com.github.luben.zstd.*;
import net.coreprotect.database.ColdSegmentCodec;

/**
 * Exports a packed CoreProtect database into neutral row files: one zstd stream per day and table,
 * rows sorted by (time, row id), every column tagged (0 null, 1 integer, 2 blob, 3 text, 4 real), and
 * the stored entity attached to each kill. Compiled and run by export.sh next to the fork's own
 * segment codec; see README.md. The reader is ReferenceDump in Hawkeye's test sources.
 */
public class RefDump {
    static final String[] TABLES = {"block", "container", "entity_container", "entity_interaction", "item", "sign", "chat", "command", "session"};
    static String db;
    static final ThreadLocal<Connection> CONN = ThreadLocal.withInitial(() -> {
        try { return DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro&immutable=1"); } catch (SQLException e) { throw new RuntimeException(e); }
    });
    static final Map<Integer, byte[]> DICTS = new ConcurrentHashMap<>();
    static final ThreadLocal<Map<Integer, ZstdDictDecompress>> DDICT = ThreadLocal.withInitial(HashMap::new);
    static final ThreadLocal<LinkedHashMap<Long, byte[]>> GROUPS = ThreadLocal.withInitial(() -> new LinkedHashMap<>(512, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, byte[]> e) { return size() > 400; }
    });

    static byte[] dec(byte[] data, int size, int dict) throws Exception {
        if (data == null) return new byte[0];
        boolean z = data.length >= 4 && (data[0] & 0xFF) == 0x28 && (data[1] & 0xFF) == 0xB5 && (data[2] & 0xFF) == 0x2F && (data[3] & 0xFF) == 0xFD;
        if (size == data.length && !z) return data;
        if (dict <= 0) return Zstd.decompress(data, size);
        ZstdDictDecompress d = DDICT.get().get(dict);
        if (d == null) {
            byte[] raw = DICTS.get(dict);
            if (raw == null) {
                try (PreparedStatement s = CONN.get().prepareStatement("select data from co_segment_dict where dict_id=?")) {
                    s.setInt(1, dict); ResultSet r = s.executeQuery(); r.next(); raw = r.getBytes(1); DICTS.put(dict, raw);
                }
            }
            d = new ZstdDictDecompress(raw); DDICT.get().put(dict, d);
        }
        return Zstd.decompress(data, d, size);
    }

    static final java.lang.reflect.Method STARTS;
    static {
        try { STARTS = ColdSegmentCodec.class.getDeclaredMethod("unitPayloadStarts", byte[].class); STARTS.setAccessible(true); } catch (Exception e) { throw new RuntimeException(e); }
    }

    static ColdSegmentCodec.Rows read(long segId) throws Exception {
        Connection c = CONN.get();
        byte[] scalars, payloadFrame; int payloadSize, dictId;
        try (PreparedStatement s = c.prepareStatement("select scalars,scalars_size,payload,payload_size,dict_id from co_segment where id=?")) {
            s.setLong(1, segId); ResultSet r = s.executeQuery(); r.next();
            scalars = dec(r.getBytes(1), r.getInt(2), 0); payloadFrame = r.getBytes(3); payloadSize = r.getInt(4); dictId = r.getInt(5);
        }
        List<byte[]> units = new ArrayList<>(), shares = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("select unit,body,payload from co_segment_unit where segment_id=? order by unit")) {
            s.setLong(1, segId); ResultSet r = s.executeQuery();
            while (r.next()) { units.add(r.getBytes(2)); shares.add(r.getBytes(3)); }
        }
        byte[] payload;
        if (payloadFrame == null && payloadSize > 0) {
            int[] starts = (int[]) STARTS.invoke(null, (Object) scalars);
            payload = new byte[payloadSize];
            for (int u = 0; u < shares.size(); u++) {
                int end = u + 1 < starts.length ? starts[u + 1] : payloadSize;
                byte[] share = dec(shares.get(u), end - starts[u], dictId);
                System.arraycopy(share, 0, payload, starts[u], share.length);
            }
        } else payload = dec(payloadFrame, payloadSize, dictId);
        return ColdSegmentCodec.decode(scalars, units.isEmpty() ? null : units.toArray(new byte[0][]), payload, null);
    }

    static byte[] entityBlob(long rowId) throws Exception {
        long first = (rowId / 64) * 64;
        LinkedHashMap<Long, byte[]> cache = GROUPS.get();
        byte[] frame = cache.get(first);
        if (frame == null) {
            try (PreparedStatement s = CONN.get().prepareStatement("select sizes,data,raw_size,dict_id from co_blob_group where table_id=0 and first_rowid=?")) {
                s.setLong(1, first); ResultSet r = s.executeQuery();
                if (!r.next()) return null;
                byte[] sizes = r.getBytes(1);
                if (sizes != null && sizes.length > 0) throw new IllegalStateException("separate sizes column");
                frame = dec(r.getBytes(2), r.getInt(3), r.getInt(4));
            }
            cache.put(first, frame);
        }
        int wanted = (int) (rowId - first), pos = 0, offset = 0, length = 0;
        for (int i = 0; i < 64; i++) {
            int v = 0, shift = 0, b;
            do { b = frame[pos++] & 0xFF; v |= (b & 0x7F) << shift; shift += 7; } while ((b & 0x80) != 0);
            if (i < wanted) offset += v; else if (i == wanted) length = v;
        }
        if (length == 0 || pos + offset + length > frame.length) return null;
        return Arrays.copyOfRange(frame, pos + offset, pos + offset + length);
    }

    static final class Out {
        byte[] b = new byte[1 << 20]; int n;
        void need(int k) { if (n + k > b.length) b = Arrays.copyOf(b, Math.max(b.length * 2, n + k)); }
        void u8(int v) { need(1); b[n++] = (byte) v; }
        void var(long v) { need(10); while ((v & ~0x7FL) != 0) { b[n++] = (byte) ((v & 0x7F) | 0x80); v >>>= 7; } b[n++] = (byte) v; }
        void zz(long v) { var((v << 1) ^ (v >> 63)); }
        void bytes(byte[] x) { var(x.length); need(x.length); System.arraycopy(x, 0, b, n, x.length); n += x.length; }
        void value(Object o) {
            if (o == null) u8(0);
            else if (o instanceof byte[] x) { u8(2); bytes(x); }
            else if (o instanceof String s) { u8(3); bytes(s.getBytes(StandardCharsets.UTF_8)); }
            else if (o instanceof Double || o instanceof Float) { u8(4); need(8); long l = Double.doubleToLongBits(((Number) o).doubleValue()); for (int i = 0; i < 8; i++) b[n++] = (byte) (l >>> (i * 8)); }
            else { u8(1); zz(((Number) o).longValue()); }
        }
    }

    record Part(byte[] bytes, int length, long[] keys, int[] offsets, int rows, long kills, long killBlobs) {}

    static Part encode(int table, ColdSegmentCodec.Rows rows, int columns) throws Exception {
        Out out = new Out();
        int n = rows.size();
        long[] keys = new long[n]; int[] offs = new int[n];
        long kills = 0, blobs = 0;
        for (int i = 0; i < n; i++) {
            Object[] v = rows.getValues(i);
            if (v.length != columns) throw new IllegalStateException("table " + table + " has " + columns + " columns, segment row has " + v.length);
            long rowId = rows.getRowId(i);
            long time = ((Number) v[0]).longValue();
            keys[i] = (time << 32) | rowId;
            offs[i] = out.n;
            out.var(rowId);
            for (Object o : v) out.value(o);
            if (table == 0) {
                byte[] blob = null;
                if (v[10] != null && ((Number) v[10]).intValue() == 3 && v[7] != null) {
                    kills++;
                    blob = entityBlob(((Number) v[7]).longValue());
                    if (blob != null) blobs++;
                }
                out.value(blob);
            }
        }
        return new Part(out.b, out.n, keys, offs, n, kills, blobs);
    }

    static void sort(long[] k, long[] v, int lo, int hi) {
        while (lo < hi) {
            if (hi - lo < 24) {
                for (int i = lo + 1; i <= hi; i++) { long kk = k[i], vv = v[i]; int j = i - 1; while (j >= lo && k[j] > kk) { k[j + 1] = k[j]; v[j + 1] = v[j]; j--; } k[j + 1] = kk; v[j + 1] = vv; }
                return;
            }
            long a = k[lo], b = k[(lo + hi) >>> 1], c = k[hi];
            long p = Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
            int i = lo, j = hi;
            while (i <= j) {
                while (k[i] < p) i++;
                while (k[j] > p) j--;
                if (i <= j) { long t = k[i]; k[i] = k[j]; k[j] = t; t = v[i]; v[i] = v[j]; v[j] = t; i++; j--; }
            }
            if (j - lo < hi - i) { sort(k, v, lo, j); lo = i; } else { sort(k, v, i, hi); hi = j; }
        }
    }

    public static void main(String[] a) throws Exception {
        db = a[0];
        Path out = Paths.get(a[1]);
        int fromDay = a.length > 2 ? Integer.parseInt(a[2]) : 0, toDay = a.length > 3 ? Integer.parseInt(a[3]) : Integer.MAX_VALUE;
        int threads = a.length > 4 ? Integer.parseInt(a[4]) : 5;
        Files.createDirectories(out.resolve("meta"));
        Connection c = CONN.get();
        String[][] maps = {{"users", "select id, user, uuid from co_user order by id"}, {"worlds", "select id, world from co_world order by id"}, {"materials", "select id, material from co_material_map order by id"},
            {"blockdata", "select id, data from co_blockdata_map order by id"}, {"entities", "select id, entity from co_entity_map order by id"}, {"art", "select id, art from co_art_map order by id"},
            {"spawns", "select id, uuid, wid, time from co_entity_spawn order by id"}};
        for (String[] m : maps) {
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("meta").resolve(m[0] + ".tsv"))); Statement s = c.createStatement(); ResultSet r = s.executeQuery(m[1])) {
                int cols = r.getMetaData().getColumnCount();
                while (r.next()) { StringBuilder b = new StringBuilder(); for (int i = 1; i <= cols; i++) { if (i > 1) b.append('\t'); String v = r.getString(i); b.append(v == null ? "" : v.replace('\t', ' ').replace('\n', ' ')); } w.println(b); }
            }
        }
        String[][] columns = new String[TABLES.length][];
        for (int t = 0; t < TABLES.length; t++) {
            List<String> names = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("pragma table_info(co_" + TABLES[t] + ")")) { while (r.next()) names.add(r.getString(2)); }
            if (t == 0) names.add("entity");
            columns[t] = names.toArray(new String[0]);
        }
        List<Integer> days = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("select distinct day from co_segment order by day")) { while (r.next()) days.add(r.getInt(1)); }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long total = 0, started = System.nanoTime();
        Path spoolPath = out.resolve("spool.tmp");
        for (int day : days) {
            if (day < fromDay || day > toDay) continue;
            Path dir = out.resolve("days").resolve(String.valueOf(day));
            Files.createDirectories(dir);
            for (int t = 0; t < TABLES.length; t++) {
                Path file = dir.resolve(TABLES[t] + ".rows.zst");
                if (Files.exists(file)) continue;
                List<Long> segs = new ArrayList<>(); long expect = 0;
                try (PreparedStatement s = c.prepareStatement("select id,row_count from co_segment where day=? and table_id=? order by id")) {
                    s.setInt(1, day); s.setInt(2, t); ResultSet r = s.executeQuery();
                    while (r.next()) { segs.add(r.getLong(1)); expect += r.getLong(2); }
                }
                if (segs.isEmpty()) continue;
                long[] keys = new long[Math.toIntExact(expect)], offs = new long[Math.toIntExact(expect)];
                int n = 0; long spoolSize = 0, kills = 0, blobs = 0;
                final int table = t, columnCount = columns[t].length - (t == 0 ? 1 : 0);
                try (FileChannel spool = FileChannel.open(spoolPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    Deque<Future<Part>> window = new ArrayDeque<>();
                    int next = 0;
                    while (next < segs.size() || !window.isEmpty()) {
                        while (next < segs.size() && window.size() < threads * 2) { long id = segs.get(next++); window.add(pool.submit(() -> encode(table, read(id), columnCount))); }
                        Part p = window.poll().get();
                        for (int i = 0; i < p.rows; i++) { keys[n] = p.keys[i]; offs[n] = spoolSize + p.offsets[i]; n++; }
                        ByteBuffer buf = ByteBuffer.wrap(p.bytes, 0, p.length);
                        while (buf.hasRemaining()) spool.write(buf);
                        spoolSize += p.length; kills += p.kills; blobs += p.killBlobs;
                    }
                }
                if (n != expect) throw new IllegalStateException("day " + day + " table " + t + ": " + n + " rows, expected " + expect);
                // each row's end is the start of the row that follows it in spool order
                long[] ends = new long[n];
                { long[] sortedOffs = offs.clone(); Arrays.sort(sortedOffs); long[] tmp = new long[n];
                  for (int i = 0; i < n; i++) { int at = Arrays.binarySearch(sortedOffs, offs[i]); ends[i] = at + 1 < n ? sortedOffs[at + 1] : spoolSize; }
                }
                // sort a permutation by key
                long[] perm = new long[n]; for (int i = 0; i < n; i++) perm[i] = i;
                sort(keys, perm, 0, n - 1);
                Path tmpFile = dir.resolve(TABLES[t] + ".rows.zst.tmp");
                try (FileChannel spool = FileChannel.open(spoolPath, StandardOpenOption.READ);
                     ZstdOutputStream z = new ZstdOutputStream(new BufferedOutputStream(Files.newOutputStream(tmpFile), 1 << 20), 3)) {
                    List<MappedByteBuffer> maps2 = new ArrayList<>();
                    long slice = 1L << 30;
                    for (long at = 0; at < spoolSize; at += slice) maps2.add(spool.map(FileChannel.MapMode.READ_ONLY, at, Math.min(slice, spoolSize - at)));
                    DataOutputStream head = new DataOutputStream(z);
                    head.writeBytes("HKD1"); head.writeInt(columns[t].length); for (String name : columns[t]) head.writeUTF(name); head.writeLong(n); head.flush();
                    byte[] row = new byte[1 << 16];
                    for (int i = 0; i < n; i++) {
                        int idx = (int) perm[i]; long from = offs[idx]; int len = (int) (ends[idx] - from);
                        if (len > row.length) row = new byte[len * 2];
                        int done = 0;
                        while (done < len) {
                            long at = from + done; MappedByteBuffer m = maps2.get((int) (at / slice)); int in = (int) (at % slice); int take = Math.min(len - done, m.capacity() - in);
                            m.get(in, row, done, take); done += take;
                        }
                        z.write(row, 0, len);
                    }
                }
                Files.move(tmpFile, file, StandardCopyOption.ATOMIC_MOVE);
                total += n;
                System.out.printf("day %d %-18s rows %10d spool %6.1f MB file %6.1f MB kills %d blobs %d total %d (%.0f rows/s)%n", day, TABLES[t], n, spoolSize / 1e6, Files.size(file) / 1e6, kills, blobs, total, total / ((System.nanoTime() - started) / 1e9));
            }
        }
        Files.deleteIfExists(spoolPath);
        pool.shutdown();
    }
}
