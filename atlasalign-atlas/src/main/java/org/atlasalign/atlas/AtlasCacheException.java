package org.atlasalign.atlas;

/**
 * Indicates that an atlas cache cannot be trusted or loaded.
 */
public final class AtlasCacheException extends IllegalStateException {

    public AtlasCacheException(final String message) {
        super(message);
    }

    public AtlasCacheException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
