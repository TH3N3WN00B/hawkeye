package gg.moonrise.hawkeye.core.storage.segment;

/**
 * A slice of a segment body.
 *
 * @param offset the first byte
 * @param length how many bytes
 */
public record ByteRange(int offset, int length) {

    /**
     * The byte after the range.
     *
     * @return {@code offset + length}
     */
    public int end() {
        return offset + length;
    }
}
