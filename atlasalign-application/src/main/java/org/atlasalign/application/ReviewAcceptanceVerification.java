package org.atlasalign.application;

import java.util.Objects;
import org.atlasalign.core.SourceImageSnapshot;

/**
 * Evidence produced by live source and atlas verification.
 */
public record ReviewAcceptanceVerification(
        SourceImageSnapshot currentSourceSnapshot,
        AtlasReviewProvenance currentAtlas) {

    public ReviewAcceptanceVerification {
        currentSourceSnapshot = Objects.requireNonNull(
                currentSourceSnapshot, "currentSourceSnapshot");
        currentAtlas = Objects.requireNonNull(
                currentAtlas, "currentAtlas");
    }
}
