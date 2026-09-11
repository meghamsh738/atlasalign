package org.atlasalign.application;

import java.util.Objects;
import org.atlasalign.core.SourceImageSnapshot;

/**
 * Creates a preview and then proves that the source's pixels and scientific
 * metadata still match their pre-operation state.
 */
public final class SafeImageIntakeService {

    public SafePreviewResult preparePreview(final ReadOnlySourceImage source,
            final RegistrationInput input, final int maximumDimension) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(input, "input");
        final SourceImageSnapshot before = source.snapshot();
        input.validateAgainst(before.metadata());
        if (maximumDimension < 64) {
            throw new IllegalArgumentException("Maximum preview dimension must be at least 64 pixels");
        }
        final RegistrationPreview preview = source.createPreview(input, maximumDimension);
        final SourceImageSnapshot after = source.snapshot();
        if (!before.equals(after)) {
            throw new SourceVerificationException("Source image changed while preparing the registration preview");
        }
        if (!RegistrationInput.from(preview).equals(input)) {
            throw new SourceVerificationException("Preview did not use the requested registration plane");
        }
        return new SafePreviewResult(preview, after);
    }

    public SafePreviewResult preparePreview(
            final ReadOnlySourceImage source,
            final int oneBasedChannel,
            final int maximumDimension) {
        Objects.requireNonNull(source, "source");
        final SourceImageSnapshot before = source.snapshot();
        if (oneBasedChannel < 1 || oneBasedChannel > before.metadata().channels()) {
            throw new IllegalArgumentException(
                    "Registration channel must be between 1 and " + before.metadata().channels());
        }
        if (maximumDimension < 64) {
            throw new IllegalArgumentException("Maximum preview dimension must be at least 64 pixels");
        }

        final RegistrationPreview preview =
                source.createPreview(oneBasedChannel, maximumDimension);
        final SourceImageSnapshot after = source.snapshot();
        if (!before.equals(after)) {
            throw new SourceVerificationException(
                    "Source image changed while preparing the registration preview");
        }
        return new SafePreviewResult(preview, after);
    }
}
