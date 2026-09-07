package org.atlasalign.plugin.export;

/** Explicit opt-in convenience outputs for one source-space export. */
public record SourceSpaceExportOptions(
        boolean includeCombinedUnion,
        boolean includeFullSourceMask,
        boolean includeMaskedSourceCrop) {

    public static SourceSpaceExportOptions canonicalOnly(
            final boolean includeCombinedUnion) {
        return new SourceSpaceExportOptions(
                includeCombinedUnion, false, false);
    }
}
