package org.atlasalign.deepslice;

/**
 * Fail-closed installation or worker protocol failure.
 */
public final class DeepSliceAdapterException extends RuntimeException {

    public DeepSliceAdapterException(final String message) {
        super(message);
    }

    public DeepSliceAdapterException(
            final String message,
            final Throwable cause) {
        super(message, cause);
    }
}
