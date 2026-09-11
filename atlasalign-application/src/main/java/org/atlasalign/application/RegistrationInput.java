package org.atlasalign.application;

import java.util.Objects;
import org.atlasalign.core.SourceImageMetadata;

/** Immutable one-based source plane used to establish one review's geometry. */
public record RegistrationInput(int channel, int slice, int frame) {
    public RegistrationInput {
        if (channel < 1 || slice < 1 || frame < 1) {
            throw new IllegalArgumentException("Registration C/Z/T indices must be positive and one-based");
        }
    }

    public void validateAgainst(final SourceImageMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (channel > metadata.channels() || slice > metadata.slices()
                || frame > metadata.frames()) {
            throw new IllegalArgumentException("Registration C/Z/T is outside the source image");
        }
    }

    public static RegistrationInput from(final RegistrationPreview preview) {
        return new RegistrationInput(preview.channel(), preview.sourceSlice(), preview.sourceFrame());
    }
}
