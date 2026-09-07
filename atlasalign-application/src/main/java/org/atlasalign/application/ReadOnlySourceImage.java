package org.atlasalign.application;

import org.atlasalign.core.SourceImageSnapshot;

/**
 * Boundary around a scientific source image. Implementations must not expose
 * mutable source buffers.
 */
public interface ReadOnlySourceImage {

    SourceImageSnapshot snapshot();

    RegistrationPreview createPreview(int oneBasedChannel, int maximumDimension);
}
