package gg.moonrise.hawkeye.util.codec;

import org.jetbrains.annotations.NotNull;

/** Encoded bytes that cannot be decoded: truncated, an unknown tag, or a corrupt compressed body. */
public final class CodecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * An exception with a reason.
     *
     * @param message what is wrong with the input
     */
    public CodecException(@NotNull String message) {
        super(message);
    }

    /**
     * An exception with a reason and a cause.
     *
     * @param message what is wrong with the input
     * @param cause   the underlying failure
     */
    public CodecException(@NotNull String message, @NotNull Throwable cause) {
        super(message, cause);
    }
}
