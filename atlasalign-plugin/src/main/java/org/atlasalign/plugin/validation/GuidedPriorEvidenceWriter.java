package org.atlasalign.plugin.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.atlasalign.application.guided.AnatomicalSearchPriorV1;

/** Create-only writer for one provider's locked, pre-output G01 prior. */
public final class GuidedPriorEvidenceWriter {

    private GuidedPriorEvidenceWriter() {
    }

    public static void writeCreateOnly(
            final Path output,
            final AnatomicalSearchPriorV1 prior,
            final double entrySeconds,
            final int preResultRevisionCount) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(prior, "prior");
        if (!Double.isFinite(entrySeconds) || entrySeconds < 0
                || preResultRevisionCount < 0) {
            throw new IllegalArgumentException(
                    "Prior timing and revision evidence is invalid");
        }
        final String payload = String.format(Locale.ROOT, """
                {"ap_end_index":%d,"ap_end_um":%d,"ap_start_index":%d,"ap_start_um":%d,"atlas_identity_sha256":"%s","captured_at":"%s","captured_before_automatic_display":true,"entry_seconds":%.9f,"locked_at":"%s","pre_result_revision_count":%d,"prior_sha256":"%s","provider_id":"%s","revision_parent_sha256":%s,"source_sha256":"%s","tissue_class":"%s"}
                """,
                prior.inclusiveEnd().zeroBasedAnteriorPosteriorIndex(),
                prior.endMicrometersFromAnteriorOrigin(),
                prior.inclusiveStart().zeroBasedAnteriorPosteriorIndex(),
                prior.startMicrometersFromAnteriorOrigin(),
                prior.atlasIdentitySha256(),
                prior.capturedAt(),
                entrySeconds,
                prior.lockedAt(),
                preResultRevisionCount,
                prior.priorSha256(),
                prior.providerId(),
                prior.revisionParentSha256()
                        .map(value -> "\"" + value + "\"")
                        .orElse("null"),
                prior.sourceSha256(),
                prior.tissueClass().name());
        try {
            final Path absolute = output.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            Files.writeString(
                    absolute,
                    payload,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(absolute, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ));
            } catch (UnsupportedOperationException ignored) {
                absolute.toFile().setReadOnly();
            }
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Could not create locked G01 prior evidence", error);
        }
    }
}
