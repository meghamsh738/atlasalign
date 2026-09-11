package org.atlasalign.application;

import org.atlasalign.core.SourceImageSnapshot;

/**
 * Boundary around a scientific source image. Implementations must not expose
 * mutable source buffers.
 */
public interface ReadOnlySourceImage {

    SourceImageSnapshot snapshot();

    RegistrationPreview createPreview(int oneBasedChannel, int maximumDimension);

    /** Implementations supporting multidimensional images must address the plane directly. */
    default RegistrationPreview createPreview(final RegistrationInput input,
            final int maximumDimension) {
        final RegistrationPreview preview = createPreview(input.channel(), maximumDimension);
        if (!RegistrationInput.from(preview).equals(input)) {
            throw new IllegalArgumentException("Source adapter cannot read the requested optical plane");
        }
        return preview;
    }
}
