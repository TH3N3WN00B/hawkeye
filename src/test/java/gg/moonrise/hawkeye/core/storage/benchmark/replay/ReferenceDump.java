package gg.moonrise.hawkeye.core.storage.benchmark.replay;

import com.github.luben.zstd.ZstdInputStream;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;

/**
 * Reads an exported CoreProtect database (the export is made by {@code scripts/bench/RefDump.java}):
 * {@code meta/<name>.tsv} holds the dictionaries and {@code days/<day>/<table>.rows.zst} the rows of
 * one table and UTC day, sorted by time and row id, as a zstd stream of
 * <pre>
 *   "HKD1", int column count, the column names (modified UTF-8), long row count, then per row:
 *   varint row id, and per column a tag byte and a value:
 *   0 null | 1 zig-zag varint integer | 2 varint length + bytes | 3 varint length + UTF-8 | 4 double (LE)
 * </pre>
 */
public final class ReferenceDump {

    /** The exported tables, in CoreProtect's segment table order. */
    public static final List<String> TABLES = List.of("block", "container", "entity_container", "entity_interaction",
            "item", "sign", "chat", "command", "session");

    private final Path root;

    /**
     * Opens an export.
     *
     * @param root the export folder
     */
    public ReferenceDump(@NotNull Path root) {
        this.root = root;
    }

    /**
     * The exported days.
     *
     * @return UTC day numbers (Unix time / 86,400), ascending
     * @throws IOException when the folder cannot be listed
     */
    public @NotNull List<Integer> days() throws IOException {
        try (Stream<Path> folders = Files.list(root.resolve("days"))) {
            return folders.map(folder -> folder.getFileName().toString()).filter(name -> name.matches("\\d+"))
                    .map(Integer::parseInt).sorted().toList();
        }
    }

    /**
     * A dictionary file.
     *
     * @param name the dictionary ({@code users}, {@code worlds}, {@code materials}, {@code blockdata},
     *             {@code entities}, {@code spawns})
     * @return its lines, split at tabs; the first field is the id
     * @throws IOException when the file cannot be read
     */
    public @NotNull List<String[]> dictionary(@NotNull String name) throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(root.resolve("meta").resolve(name + ".tsv"), StandardCharsets.UTF_8)) {
            if (!line.isEmpty()) {
                rows.add(line.split("\t", -1));
            }
        }
        return rows;
    }

    /**
     * Opens the rows of one table and day.
     *
     * @param day   the UTC day number
     * @param table the table name
     * @return the rows, or empty when the day has none of that table
     * @throws IOException when the file cannot be opened
     */
    public @NotNull Optional<Rows> open(int day, @NotNull String table) throws IOException {
        Path file = root.resolve("days").resolve(String.valueOf(day)).resolve(table + ".rows.zst");
        return Files.exists(file) ? Optional.of(new Rows(file)) : Optional.empty();
    }

    /** A cursor over the rows of one file. Values are read by column index; see {@link #column(String)}. */
    public static final class Rows implements Closeable {

        private static final int NULL = 0;
        private static final int INTEGER = 1;
        private static final int BLOB = 2;
        private static final int TEXT = 3;

        private final DataInputStream in;
        private final String[] columns;
        private final byte[] kinds;
        private final long[] integers;
        private final byte[][] bytes;
        private long remaining;
        private long rowId;

        private Rows(@NotNull Path file) throws IOException {
            this.in = new DataInputStream(new BufferedInputStream(new ZstdInputStream(
                    new BufferedInputStream(Files.newInputStream(file), 1 << 20)), 1 << 20));
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!"HKD1".equals(new String(magic, StandardCharsets.US_ASCII))) {
                throw new IOException(file + " is not a row export");
            }
            this.columns = new String[in.readInt()];
            for (int column = 0; column < columns.length; column++) {
                columns[column] = in.readUTF();
            }
            this.remaining = in.readLong();
            this.kinds = new byte[columns.length];
            this.integers = new long[columns.length];
            this.bytes = new byte[columns.length][];
        }

        /**
         * The index of a column.
         *
         * @param name the column name
         * @return its index
         */
        public int column(@NotNull String name) {
            for (int column = 0; column < columns.length; column++) {
                if (columns[column].equals(name)) {
                    return column;
                }
            }
            throw new IllegalArgumentException("no column " + name);
        }

        /**
         * Moves to the next row.
         *
         * @return {@code false} after the last row
         * @throws IOException when the file is truncated or corrupt
         */
        public boolean next() throws IOException {
            if (remaining == 0) {
                return false;
            }
            remaining--;
            rowId = varint();
            for (int column = 0; column < columns.length; column++) {
                int kind = in.readUnsignedByte();
                kinds[column] = (byte) kind;
                bytes[column] = null;
                switch (kind) {
                    case NULL -> integers[column] = 0;
                    case INTEGER -> {
                        long value = varint();
                        integers[column] = (value >>> 1) ^ -(value & 1);
                    }
                    case BLOB, TEXT -> {
                        byte[] value = new byte[Math.toIntExact(varint())];
                        in.readFully(value);
                        bytes[column] = value;
                    }
                    default -> integers[column] = Long.reverseBytes(in.readLong());
                }
            }
            return true;
        }

        /**
         * The current row's id.
         *
         * @return the row id
         */
        public long rowId() {
            return rowId;
        }

        /**
         * An integer column of the current row.
         *
         * @param column the column index
         * @return the value, 0 for null
         */
        public long integer(int column) {
            return integers[column];
        }

        /**
         * A blob or text column of the current row.
         *
         * @param column the column index
         * @return the bytes (UTF-8 for text), or empty for null and for other kinds
         */
        public @NotNull Optional<byte[]> bytes(int column) {
            return Optional.ofNullable(bytes[column]);
        }

        /**
         * A text or blob column as a string.
         *
         * @param column the column index
         * @return the text, empty for null
         */
        public @NotNull String text(int column) {
            return bytes[column] == null ? "" : new String(bytes[column], StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }

        private long varint() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                int next = in.readUnsignedByte();
                value |= (long) (next & 0x7F) << shift;
                if ((next & 0x80) == 0) {
                    return value;
                }
            }
            throw new EOFException("varint too long");
        }
    }
}
