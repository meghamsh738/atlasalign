package org.atlasalign.plugin.manual;

import org.atlasalign.application.AlignmentReviewBasis;
import org.atlasalign.application.manual.SourceImageIdentity;
import org.atlasalign.application.manual.VerifiedAtlasIdentity;

/** Deterministic bindings for in-memory guided-manual input. */
public final class ManualWorkflowIdentities {

    private ManualWorkflowIdentities() {
    }

    public static SourceImageIdentity source(
            final AlignmentReviewBasis basis) {
        final var metadata = basis.sourceSnapshot().metadata();
        final var preview = basis.previewDimensions();
        final String mappingIdentity = "pixel-centre-v1:source="
                + metadata.width() + "x" + metadata.height()
                + ";preview=" + preview.width() + "x" + preview.height();
        return new SourceImageIdentity(
                basis.sourceSnapshot().pixelSha256(),
                metadata, mappingIdentity);
    }

    public static VerifiedAtlasIdentity atlas(
            final AlignmentReviewBasis basis) {
        return new VerifiedAtlasIdentity(
                basis.atlas().atlasId(), basis.atlas().atlasVersion(),
                basis.atlas().identitySha256());
    }
}
