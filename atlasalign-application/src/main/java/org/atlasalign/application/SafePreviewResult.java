package org.atlasalign.application;

import java.util.Objects;
import org.atlasalign.core.SourceImageSnapshot;

public record SafePreviewResult(
        RegistrationPreview preview,
        SourceImageSnapshot verifiedSource) {

    public SafePreviewResult {
        preview = Objects.requireNonNull(preview, "preview");
        verifiedSource = Objects.requireNonNull(verifiedSource, "verifiedSource");
    }
}
